/**
 * MAX Neural Dimension - secure AI backend (Cloudflare Worker).
 *
 * Blueprint Part 11: provider secret keys live here on the server, never in the
 * APK. The Android app calls this Worker with its app token; this service holds
 * GEMINI_API_KEY / OPENAI-compatible keys as Worker secrets.
 *
 * Routes:
 *   GET  /v1/health  -> { ok, ts }                      (auth required)
 *   POST /v1/chat    -> SSE stream of {"t":"chunk"} ... [DONE]
 *      body: { messages: [{role, content}], mode: "fast"|"thinking", system: "..." }
 *
 * Secrets (wrangler secret put ...):
 *   APP_TOKEN        shared token the app must present (required; fail closed when unset)
 *   GEMINI_API_KEY   Gemini key (used when PROVIDER=gemini)
 *   OPENAI_API_KEY   OpenAI-compatible key (used when PROVIDER=openai)
 * Vars (wrangler.toml):
 *   PROVIDER         "gemini" (default) | "openai"
 *   OPENAI_BASE_URL  default https://api.openai.com/v1 (works for Groq: https://api.groq.com/openai/v1)
 *   MODEL_FAST       default gemini-3.6-flash
 *   MODEL_THINKING   default gemini-3.6-flash
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);

    if (url.pathname === "/v1/health" && request.method === "GET") {
      return json({ ok: true, provider: env.PROVIDER || "gemini", ts: Date.now() });
    }
    if (url.pathname === "/v1/chat" && request.method === "POST") {
      return handleChat(request, env);
    }
    return json({ error: "not found" }, 404);
  },
};

function authorized(request, env) {
  // Fail closed: until APP_TOKEN is set, deny everything rather than accept
  // unauthenticated requests during setup.
  if (!env.APP_TOKEN) return false;
  const h = request.headers.get("Authorization") || "";
  return h === `Bearer ${env.APP_TOKEN}`;
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function sseHeaders() {
  return {
    "content-type": "text/event-stream; charset=utf-8",
    "cache-control": "no-cache",
    "x-accel-buffering": "no",
  };
}

async function handleChat(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid JSON body" }, 400);
  }
  const messages = Array.isArray(body.messages) ? body.messages : [];
  if (messages.length === 0) return json({ error: "messages required" }, 400);
  const mode = body.mode === "thinking" ? "thinking" : "fast";
  const system = typeof body.system === "string" ? body.system : "";
  const attErr = validateAttachments(body.attachments);
  if (attErr) return json({ error: attErr }, 400);
  const attachments = Array.isArray(body.attachments) ? body.attachments : [];
  const provider = env.PROVIDER || "gemini";
  try {
    if (provider === "openai") return await chatOpenAI(env, messages, mode, system, attachments);
    return await chatGemini(env, messages, mode, system, attachments);
  } catch (e) {
    return json({ error: "upstream failure: " + (e.message || String(e)) }, 502);
  }
}

// Attachments ride with the last user message. Honest support matrix:
// images + PDFs go to Gemini inline; plain text is inlined as text; the rest
// is rejected here instead of pretending to be understood.
const MAX_ATTACH = 3;
const MAX_ATTACH_B64 = 9500000; // ~7 MB raw per file
function validateAttachments(a) {
  if (a === undefined || a === null) return null;
  if (!Array.isArray(a)) return "attachments must be an array";
  if (a.length > MAX_ATTACH) return `at most ${MAX_ATTACH} attachments per message`;
  for (const it of a) {
    const mime = (it && it.mime) || "";
    const data = (it && it.data) || "";
    const ok = mime.startsWith("image/") || mime === "application/pdf" || mime === "text/plain";
    if (!ok) return `unsupported attachment type: ${mime || "unknown"} (images, PDF and plain text only)`;
    if (typeof data !== "string" || data.length === 0) return "attachment data missing";
    if (data.length > MAX_ATTACH_B64) return "attachment too large (about 7 MB max)";
  }
  return null;
}

function attachmentParts(attachments) {
  const parts = [];
  for (const it of attachments) {
    if (it.mime === "text/plain") {
      let txt = "";
      try { txt = Buffer.from(it.data, "base64").toString("utf8"); } catch { txt = ""; }
      parts.push({ text: `\n\n[Attached file: ${it.name || "file.txt"}]\n${txt.slice(0, 20000)}` });
    } else {
      parts.push({ inline_data: { mime_type: it.mime, data: it.data } });
    }
  }
  return parts;
}

// Parse Retry-After: delta-seconds or HTTP-date. null = absent/unparsable.
function retryAfterMs(h) {
  if (!h) return null;
  const secs = Number(h);
  if (Number.isFinite(secs) && h.trim() !== "") return Math.max(secs, 0) * 1000;
  const t = Date.parse(h);
  if (Number.isFinite(t)) return Math.max(t - Date.now(), 0);
  return null;
}

async function chatGemini(env, messages, mode, system, attachments = []) {
  if (!env.GEMINI_API_KEY) return json({ error: "GEMINI_API_KEY not set on backend" }, 500);
  const model = mode === "thinking"
    ? (env.MODEL_THINKING || "gemini-3.6-flash")
    : (env.MODEL_FAST || "gemini-3.6-flash");
  // No retry layer: exactly one provider call per user message. Transient
  // provider pressure (429/503) is surfaced honestly, with the provider's
  // Retry-After hint when present, for the client to display.
  const upstream = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-goog-api-key": env.GEMINI_API_KEY,
      },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: system }] },
        contents: messages.map((m, i) => ({
          role: m.role === "assistant" ? "model" : "user",
          parts: [
            { text: String(m.content) },
            ...(i === messages.length - 1 && m.role === "user" ? attachmentParts(attachments) : []),
          ],
        })),
      }),
    }
  );
  if (!upstream.ok || !upstream.body) {
    const detail = await upstream.text();
    const ra = upstream.headers.get("retry-after");
    const suffix = ra ? ` | provider Retry-After: ${ra}` : "";
    return json({ error: `gemini ${upstream.status}: ${detail.slice(0, 300)}${suffix}` }, 502);
  }
  return pump(upstream.body, (payload) => {
    try {
      const obj = JSON.parse(payload);
      const parts = obj.candidates?.[0]?.content?.parts || [];
      let out = "";
      for (const p of parts) if (p.text) out += p.text;
      return out;
    } catch {
      return "";
    }
  });
}

async function chatOpenAI(env, messages, mode, system, attachments = []) {
  if (!env.OPENAI_API_KEY) return json({ error: "OPENAI_API_KEY not set on backend" }, 500);
  const base = (env.OPENAI_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
  const model = mode === "thinking"
    ? (env.MODEL_THINKING || "gpt-4o")
    : (env.MODEL_FAST || "gpt-4o-mini");
  const upstream = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${env.OPENAI_API_KEY}`,
    },
    body: JSON.stringify({
      model,
      stream: true,
      messages: [{ role: "system", content: system }, ...messages],
    }),
  });
  if (!upstream.ok || !upstream.body) {
    const detail = await upstream.text();
    return json({ error: `openai ${upstream.status}: ${detail.slice(0, 300)}` }, 502);
  }
  return pump(upstream.body, (payload) => {
    try {
      const obj = JSON.parse(payload);
      return obj.choices?.[0]?.delta?.content || "";
    } catch {
      return "";
    }
  });
}

/** Re-stream an upstream SSE body as data: {"t":"..."} lines + data: [DONE]. */
function pump(upstreamBody, extract) {
  const { readable, writable } = new TransformStream();
  const writer = writable.getWriter();
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();
  (async () => {
    const reader = upstreamBody.getReader();
    let buffer = "";
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let nl;
        while ((nl = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, nl).trim();
          buffer = buffer.slice(nl + 1);
          if (!line.startsWith("data:")) continue;
          const payload = line.slice(5).trim();
          if (!payload || payload === "[DONE]") continue;
          const text = extract(payload);
          if (text) await writer.write(encoder.encode(`data: {"t":${JSON.stringify(text)}}\n\n`));
        }
      }
      await writer.write(encoder.encode("data: [DONE]\n\n"));
    } catch (e) {
      await writer.write(encoder.encode(`data: {"error":${JSON.stringify("stream error: " + (e.message || String(e)))}}\n\n`));
    } finally {
      await writer.close();
    }
  })();
  return new Response(readable, { headers: sseHeaders() });
}

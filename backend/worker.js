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
 *   MODEL_FAST       default gemini-flash-latest
 *   MODEL_THINKING   default gemini-3.8-flash
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
  const provider = env.PROVIDER || "gemini";
  try {
    if (provider === "openai") return await chatOpenAI(env, messages, mode, system);
    return await chatGemini(env, messages, mode, system);
  } catch (e) {
    return json({ error: "upstream failure: " + (e.message || String(e)) }, 502);
  }
}

async function chatGemini(env, messages, mode, system) {
  if (!env.GEMINI_API_KEY) return json({ error: "GEMINI_API_KEY not set on backend" }, 500);
  const model = mode === "thinking"
    ? (env.MODEL_THINKING || "gemini-3.8-flash")
    : (env.MODEL_FAST || "gemini-flash-latest");
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
        contents: messages.map((m) => ({
          role: m.role === "assistant" ? "model" : "user",
          parts: [{ text: String(m.content) }],
        })),
      }),
    }
  );
  if (!upstream.ok || !upstream.body) {
    const detail = await upstream.text();
    return json({ error: `gemini ${upstream.status}: ${detail.slice(0, 300)}` }, 502);
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

async function chatOpenAI(env, messages, mode, system) {
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

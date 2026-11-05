# MAX secure backend

Cloudflare Worker that holds the AI provider keys server-side (blueprint Part 11).
The Android app stores only this backend's URL and an app token.

## Deploy (Cloudflare Workers free tier: 100,000 requests/day)

1. Create a Cloudflare account (free, no card) and install wrangler: `npm i -g wrangler`
2. `wrangler login`
3. In this directory:
   - `wrangler secret put GEMINI_API_KEY` (paste your Gemini key)
   - `wrangler secret put APP_TOKEN` (choose a long random string)
   - `wrangler deploy`
4. Copy the printed `https://max-neural-dimension-backend.<you>.workers.dev` URL.
5. In the app: Settings > Secure Backend -> paste the URL and the same APP_TOKEN -> Test connection.

For an OpenAI-compatible provider instead, set `PROVIDER = "openai"` in wrangler.toml,
optionally `OPENAI_BASE_URL` (Groq: https://api.groq.com/openai/v1) and the models,
then `wrangler secret put OPENAI_API_KEY` and redeploy.

## Free hosting alternatives (as of November 2026)

- **Cloudflare Workers (chosen default):** free tier 100,000 requests/day, 128 MB,
  streaming (SSE) supported, no credit card historically required for the free plan.
  https://developers.cloudflare.com/workers/platform/limits/
- **Render free web services:** free Node hosting, but instances sleep after inactivity
  (cold start delay for the first message). https://render.com/docs/free
- **Vercel hobby / Deno Deploy free:** also workable with small adaptations of worker.js.
- **Google Cloud Run free tier:** requires a billing account with a card - not used.

All of these require an account owned by the user; creating one is the user's decision.

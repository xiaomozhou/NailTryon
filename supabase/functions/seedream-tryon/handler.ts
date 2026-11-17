const ARK_URL = "https://ark.cn-beijing.volces.com/api/v3/images/generations";
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-client-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { ...cors, "Content-Type": "application/json" },
});
// Only inline images: do not let callers make the paid provider fetch arbitrary URLs.
const image = (value: unknown): value is string => typeof value === "string" &&
  value.length <= 12_000_000 && /^data:image\/(jpeg|png);base64,[A-Za-z0-9+/]+={0,2}$/.test(value);

type Config = {apiKey?: string; model: string; demoToken?: string};
export function createHandler(config: Config, transport: typeof fetch = fetch) {
  return async (request: Request): Promise<Response> => {
    if (request.method === "OPTIONS") return new Response("ok", {headers: cors});
    if (request.method !== "POST") return json({error: "method_not_allowed"}, 405);
    if (!config.apiKey) return json({error: "seedream_not_configured"}, 503);
    if (config.demoToken && request.headers.get("x-client-token") !== config.demoToken)
      return json({error: "invalid_demo_token"}, 401);
    // Bound streaming input, including clients with absent/false Content-Length.
    let input: Record<string, unknown>;
    try {
      const reader = request.body?.getReader();
      if (!reader) return json({error: "invalid_json"}, 400);
      const chunks: Uint8Array[] = []; let length = 0;
      while (true) {
        const {done, value} = await reader.read(); if (done) break;
        length += value.length;
        if (length > 28_000_000) {await reader.cancel(); return json({error: "payload_too_large"}, 413);}
        chunks.push(value);
      }
      const bytes = new Uint8Array(length); let offset = 0;
      for (const chunk of chunks) {bytes.set(chunk, offset); offset += chunk.length;}
      input = JSON.parse(new TextDecoder().decode(bytes));
      if (!input || Array.isArray(input) || typeof input !== "object") throw new Error();
    } catch { return json({error: "invalid_json"}, 400); }
    if (!image(input.handImage) || !image(input.nailImage) ||
        (input.guideImage != null && !image(input.guideImage)))
      return json({error: "invalid_images"}, 400);
    if (typeof input.prompt !== "string" || !input.prompt.trim() || input.prompt.length > 6000)
      return json({error: "invalid_prompt"}, 400);
    const requestId = crypto.randomUUID();
    const started = Date.now();
    const report = (event: string, extra: Record<string, unknown> = {}) => {
      console.info(JSON.stringify({ service: "seedream-tryon", requestId, event, elapsedMs: Date.now() - started, ...extra }));
    };
    const fail = (error: string, status: number, extra: Record<string, unknown> = {}) => {
      report(error, extra);
      return json({ error, requestId, ...extra }, status);
    };
    report("generation_started", { inputBytes: input.handImage.length + input.nailImage.length +
        (typeof input.guideImage === "string" ? input.guideImage.length : 0) });
    try {
      const upstream = await transport(ARK_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${config.apiKey}` },
        signal: AbortSignal.timeout(110_000),
        body: JSON.stringify({
          model: config.model,
          prompt: input.prompt,
          image: [input.handImage, input.nailImage, ...(input.guideImage ? [input.guideImage] : [])],
          size: "1.5K",
          output_format: "jpeg",
          response_format: "b64_json",
          watermark: false,
        }),
      });
      const providerRequestId = safeIdentifier(upstream.headers.get("x-request-id") ?? upstream.headers.get("x-tt-logid"));
      report("provider_response", { providerStatus: upstream.status, providerRequestId });
      const payload = await upstream.json().catch(() => null);
      const output = payload?.data?.[0];
      const providerError = payload?.error ?? output?.error;
      if (!upstream.ok || providerError) {
        const providerCode = safeIdentifier(providerError?.code) ?? "unknown";
        const status = upstream.status;
        const error = /sensitive|safety|moderation/i.test(providerCode) ? "seedream_content_rejected"
          : status === 401 || status === 403 ? "seedream_authorization_failed"
          : status === 429 ? "seedream_rate_limited"
          : status === 400 ? "seedream_invalid_input"
          : "seedream_request_failed";
        return fail(error, status === 429 ? 429 : 502, { providerStatus: status, providerCode, providerRequestId });
      }

      const image = output?.b64_json;
      const mime = typeof image === "string" && image.startsWith("/9j/") ? "jpeg"
        : typeof image === "string" && image.startsWith("iVBORw0KGgo") ? "png" : null;
      if (!mime) return fail("seedream_empty_result", 502, { providerRequestId });
      report("generation_complete", { outputBytes: image.length, providerRequestId });
      return json({
        model: payload.model ?? config.model,
        imageDataUrl: `data:image/${mime};base64,${image}`,
        requestId,
      });
    } catch (error) {
      const timeout = error instanceof Error && /Timeout|Abort/.test(error.name);
      return fail(timeout ? "seedream_timeout" : "seedream_unavailable", timeout ? 504 : 502);
    }
  };
}

function safeIdentifier(value: unknown): string | undefined {
  return typeof value === "string" && /^[A-Za-z0-9_.-]{1,128}$/.test(value) ? value : undefined;
}

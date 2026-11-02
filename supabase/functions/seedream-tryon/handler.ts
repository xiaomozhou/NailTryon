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
    try {
      const upstream = await transport(ARK_URL, {
        method: "POST",
        headers: {"Content-Type": "application/json", "Authorization": `Bearer ${config.apiKey}`},
        signal: AbortSignal.timeout(130_000),
        body: JSON.stringify({
          model: config.model,
          prompt: input.prompt,
          image: [input.handImage, input.nailImage, ...(input.guideImage ? [input.guideImage] : [])],
          size: "2K", response_format: "b64_json", watermark: false,
        }),
      });
      const payload = await upstream.json().catch(() => null);
      // Never forward the raw provider payload, which may contain private data.
      if (!upstream.ok) {
        const code = payload?.error?.code;
        // Diagnostic for integration errors only; strip all supplied private material before returning.
        let message = typeof payload?.error?.message === "string" ? payload.error.message : "";
        for (const secret of [config.apiKey, input.handImage, input.nailImage, input.guideImage, input.prompt])
          if (typeof secret === "string" && secret) message = message.replaceAll(secret, "[redacted]");
        message = message.replace(/data:image\/[^\s]+/g,"[image]").slice(0,300);
        return json({error: "seedream_request_failed", providerStatus: upstream.status, providerMessage: message,
          providerCode: typeof code === "string" && /^[A-Za-z0-9_.-]{1,100}$/.test(code) ? code : "unknown"}, upstream.status === 429 ? 429 : 502);
      }
      const output = payload?.data?.[0];
      if (typeof output?.b64_json !== "string" || !output.b64_json ||
          !/^[A-Za-z0-9+/]+={0,2}$/.test(output.b64_json))
        return json({error: "seedream_empty_result"}, 502);
      return json({model: payload.model ?? config.model, imageDataUrl: `data:image/jpeg;base64,${output.b64_json}`});
    } catch(error) {
      return json({error: error instanceof Error && /Timeout|Abort/.test(error.name) ? "seedream_timeout" : "seedream_unavailable"}, 504);
    }
  };
}

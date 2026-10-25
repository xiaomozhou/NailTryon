import { serve } from "https://deno.land/std@0.224.0/http/server.ts";

const ARK_URL = "https://ark.cn-beijing.volces.com/api/v3/images/generations";
const MODEL = Deno.env.get("SEEDREAM_MODEL") ?? "doubao-seedream-4-5-251128";
const API_KEY = Deno.env.get("SEEDREAM_API_KEY");
const DEMO_TOKEN = Deno.env.get("DEMO_ACCESS_TOKEN");

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-client-token",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

function isImageReference(value: unknown): value is string {
  return typeof value === "string" &&
    (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("data:image/"));
}

serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  if (!API_KEY) return json({ error: "seedream_not_configured" }, 503);

  if (DEMO_TOKEN && request.headers.get("x-client-token") !== DEMO_TOKEN) {
    return json({ error: "invalid_demo_token" }, 401);
  }

  let input: { handImage?: unknown; nailImage?: unknown; prompt?: unknown };
  try {
    input = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!isImageReference(input.handImage) || !isImageReference(input.nailImage)) {
    return json({ error: "handImage_and_nailImage_are_required" }, 400);
  }

  const prompt = typeof input.prompt === "string" && input.prompt.trim().length > 0
    ? input.prompt.trim()
    : "在保持手部姿态、肤色、背景、光线和构图不变的前提下，将第二张参考图中的美甲样式自然贴合到第一张手部照片的指甲上。只修改指甲区域，保持五根手指数量和形状不变，边缘清晰，材质真实，输出自然的美甲试戴效果。";

  const upstream = await fetch(ARK_URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${API_KEY}`,
    },
    body: JSON.stringify({
      model: MODEL,
      prompt,
      image: [input.handImage, input.nailImage],
      size: "2K",
      response_format: "b64_json",
      sequential_image_generation: "disabled",
      watermark: false,
    }),
  });

  const payload = await upstream.json().catch(() => null);
  if (!upstream.ok) {
    return json({ error: "seedream_request_failed", details: payload }, upstream.status >= 500 ? 502 : upstream.status);
  }

  const image = payload?.data?.[0];
  if (!image?.b64_json && !image?.url) {
    return json({ error: "seedream_empty_result", details: payload }, 502);
  }

  return json({
    model: payload.model ?? MODEL,
    imageDataUrl: image.b64_json ? `data:image/jpeg;base64,${image.b64_json}` : null,
    imageUrl: image.url ?? null,
    usage: payload.usage ?? null,
  });
});

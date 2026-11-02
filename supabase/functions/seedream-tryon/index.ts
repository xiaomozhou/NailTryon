import { createHandler } from "./handler.ts";

Deno.serve(createHandler({
  apiKey: Deno.env.get("SEEDREAM_API_KEY"),
  model: Deno.env.get("SEEDREAM_MODEL") ?? "doubao-seedream-5-0-pro-260628",
  demoToken: Deno.env.get("DEMO_ACCESS_TOKEN"),
}));

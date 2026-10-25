# Seedream 4.5 integration notes

The Android app keeps the local `FusionEngine` as a deterministic fallback. The cloud path is deliberately server-side:

1. Upload the hand photo and selected nail image to Supabase Storage, or pass short-lived image URLs/data URLs to the Edge Function.
2. Call `seedream-tryon` with the two references and the generated prompt from `SeedreamPrompt`.
3. Decode the returned `imageDataUrl`, save the result to the local history, and fall back to `FusionEngine` when the network or model fails.
4. Persist a copy to Storage if history needs to survive app restarts; Seedream's returned URLs are temporary.

The Android `SeedreamTryOnClient` and request contract are now included. After the API key is available, wire the endpoint into the app configuration, add Storage upload, and enable the cloud feature flag while preserving the local fallback.

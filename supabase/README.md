# Seedream 4.5 integration notes

The Android app keeps the local `FusionEngine` as a deterministic fallback. The cloud path is deliberately server-side:

1. Upload the hand photo and selected nail image to Supabase Storage, or pass short-lived image URLs/data URLs to the Edge Function.
2. Call `seedream-tryon` with the two references and the generated prompt from `SeedreamPrompt`.
3. Decode the returned `imageDataUrl`, save the result to the local history, and fall back to `FusionEngine` when the network or model fails.
4. Persist a copy to Storage if history needs to survive app restarts; Seedream's returned URLs are temporary.

The next implementation step after the API key is available is the Android `SeedreamTryOnClient` plus Storage upload and a feature flag that selects cloud generation while preserving the local fallback.

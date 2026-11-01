# Seedream 5.0 Pro Edge Function

This function keeps the Volcengine API key on the Supabase server and exposes a small app-facing contract:

`POST /functions/v1/seedream-tryon`

```json
{
  "handImage": "https://.../hand.jpg or data:image/jpeg;base64,...",
  "nailImage": "https://.../nail.png or data:image/png;base64,...",
  "prompt": "optional override"
}
```

The response contains `imageDataUrl` (preferred) or `imageUrl`, plus model usage information. `imageUrl` values returned by Seedream are temporary, so the client must save the result to Supabase Storage if history should persist.

## Secrets

```text
supabase secrets set SEEDREAM_API_KEY=... SEEDREAM_MODEL=doubao-seedream-5-0-pro-260628
```

`DEMO_ACCESS_TOKEN` is optional. If set, the Android client must send it as `x-client-token`. Never put `SEEDREAM_API_KEY` in the APK.

## Deployment

From the repository root after Supabase CLI login and project linking:

```text
supabase functions deploy seedream-tryon
```

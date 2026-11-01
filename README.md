# 美甲试戴 AI App

Android Compose demo for the nail try-on experience. The current build includes the nail catalog, photo selection/camera flow, local fallback compositing, result history, and unit tests.

## Project

- Android project: `android/`
- Product requirements: `美甲试妆AI_APP_PRD.md`
- Development plan: `美甲试妆AI_开发计划.md`

## Build

Open `android/` in Android Studio and run the `app` configuration. The debug APK is generated under `android/app/build/outputs/apk/debug/`.

## AI integration

The Seedream 5.0 Pro integration is prepared as a server-side Supabase Edge Function. Keep the Volcengine API key in server secrets; never put it in the Android APK.

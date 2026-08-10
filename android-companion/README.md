# Inkflow BOOX Lab

This is a deliberately narrow Android proof of concept for Inkflow's native BOOX renderer. It answers one question before the production companion is designed: can a normal third-party APK initialize Onyx raw drawing on the BOOX Go 10.3 and match FreeMark's perceived latency?

The app contains a full-screen dotted `SurfaceView` and initializes Onyx `TouchHelper` through the official Pen SDK. It does not request storage, network, overlay, accessibility, or account permissions. It does not yet retain strokes. The visible status must say **Raw ink ready** before the latency test is meaningful.

## Test protocol

1. Install the debug APK on the BOOX Go 10.3.
2. Open **Inkflow BOOX Lab**.
3. Confirm the status says **Raw ink ready**. If it reports an error, capture the exact message.
4. Write several fast loops and short words. Compare the pen-to-line distance with FreeMark.
5. Lift the pen, leave the app, and reopen it once to exercise the SDK lifecycle.

This APK is not the published Obsidian plugin and is not intended for normal note-taking yet.

## Build

The repository workflow provisions Gradle and builds the project because the main Inkflow project itself has no Android toolchain requirement:

```bash
gradle -p android-companion assembleDebug
```

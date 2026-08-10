# Inkflow BOOX Lab

Inkflow BOOX is the native capture companion for the Inkflow Obsidian plugin. It keeps Onyx's zero-latency raw-pen renderer on the hot path while saving the same editable `.ink.json` and portable PNG files used by the plugin.

The app contains a full-screen dotted `SurfaceView` and initializes Onyx `TouchHelper` through the official Pen SDK. Its application bootstrap, hidden-API compatibility layer, supported ABIs, and surface lifecycle mirror Onyx's current pen demo. It requests no storage, network, overlay, accessibility, or account permissions. Android's folder picker grants durable access to the selected vault only.

## Use

1. Install the companion APK on the BOOX Go 10.3.
2. In Obsidian, enable Inkflow's **E-ink mode** and open a Markdown note.
3. Tap Inkflow's ribbon pen. On first use, choose the root of the same Obsidian vault.
4. Write with the pen, erase whole strokes, change among three widths, or undo/redo.
5. Return to Obsidian. The editable ink source and embedded PNG update in the vault.
6. Use the × action to delete disposable handwriting; Obsidian removes the embed and association when it observes the deleted source.

The deep link contains vault-relative paths only. The app cannot access any folder the user did not select, and it has no internet permission.

## Build

The repository workflow provisions Gradle and builds the project because the main Inkflow project itself has no Android toolchain requirement:

```bash
gradle -p android-companion assembleDebug
```

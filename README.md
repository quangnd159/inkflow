# Eink Studio Lab

Eink Studio Lab is a standalone Android proof of concept for a native, professional-feeling drawing app designed around e-ink rather than adapted to it afterward.

The first build targets BOOX devices because Onyx exposes a raw pen pipeline that can draw ahead of Android's normal compositor. The live stroke goes directly from the stylus to Onyx `TouchHelper` and a native `SurfaceView`; document history and saving happen only after pen-up.

## Included in the proof of concept

- BOOX native raw ink with a fountain-style stroke
- Full-screen dotted drawing surface
- Three unambiguous stroke widths
- Pressure-aware committed-stroke rendering
- Stroke eraser, including stylus-eraser callbacks
- Undo and redo with bounded history
- Clear-canvas confirmation
- Crash-safe, atomic local autosave
- Canvas restoration after relaunch
- Monochrome, high-contrast, animation-free interface
- No network, storage, account, overlay, or accessibility permissions

## Performance contract

The raw point-move callback performs no allocations, persistence, UI updates, smoothing, or canvas repainting. Onyx renders the live stroke. Ink Studio receives the completed point list at pen-up, commits it to the model, then schedules autosave on a single background writer.

## Scope

This is deliberately one persistent canvas, not yet a complete illustration product. It proves the interaction architecture before adding a gallery, multiple documents, lasso selection, transforms, layers, export, and vendor-neutral Android rendering.

## Build

```bash
gradle assembleDebug
```

The project requires Java 17, Gradle 8.9, and the Android 35 SDK. It retrieves Onyx Pen SDK 1.5.4 from the BOOX Maven repository.

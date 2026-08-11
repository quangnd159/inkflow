# Inky Sketch

Inky Sketch is a standalone Android proof of concept for a Procreate-like drawing app designed around e-ink. It is separate from the Inkflow Obsidian plugin.

The app targets BOOX devices because Onyx exposes a raw pen pipeline that can draw ahead of Android's normal compositor. Live ink goes directly from the stylus to Onyx `TouchHelper` on a native `SurfaceView`; document changes, UI work, rendering, history, and saving happen only after pen-up.

## Version 0.2.0 POC

- Pressure-aware pen, pencil, and marker brushes
- Four-tone e-ink palette and three brush sizes
- Pixel/segment eraser that splits strokes instead of deleting whole strokes
- Multiple layers with add, select, rename, reorder, visibility, clear, and delete controls
- Undo and redo for marks, erasing, and layer mutations
- Automatic migration of the original v1 flat canvas into an `Imported canvas` layer
- Atomic local autosave with last-good backup recovery
- Monochrome, high-contrast, animation-free interface
- No network, storage, account, overlay, or accessibility permissions

## Performance contract

The raw point-move callbacks perform no allocations, persistence, UI updates, or canvas repainting. Onyx renders the live stroke/eraser preview. Inky Sketch receives the completed point list at pen-up, commits one document operation, and schedules an immutable snapshot on a single background writer.

## Storage

The one local project lives in app-private storage. Writes are flushed to a new file and atomically moved over the active document; the previous valid document is retained as a recovery backup. No storage permission is requested.

## Build

```bash
gradle testDebugUnitTest assembleDebug
```

The isolated GitHub Actions workflow runs only for `codex/eink-studio-poc` and its release tags. The project requires Java 17, Gradle 8.9, and Android SDK 35, and retrieves Onyx Pen SDK 1.5.4 from the BOOX Maven repository.

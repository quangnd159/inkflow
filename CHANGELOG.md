# Changelog

All notable changes to Inkflow are documented here.

## 1.4.0 — 2026-08-14

- Removed the e-ink mode setting: the plugin now uses one optimized drawing path on all devices. Boox setup tips live in the README.
- Faster canvas compositing on all devices; system-drawn ink trail where supported.
- Removed the BOOX companion app in favor of an improved in-app canvas.
- Work around iPadOS Scribble intercepting touchmove events during handwriting.
- Render the predicted stroke tail immediately for lower perceived latency.
- Taper stroke width with pressure and smooth strokes with midpoint quadratics.
- Fix stray spurs left behind at the end of incrementally rendered strokes.
- Remove a duplicate "open browser handwriting canvas" command.

## 1.3.0 — 2026-08-10

- Paint new ink segments directly instead of clearing and compositing the full visible canvas on every pen frame.
- Consume `pointerrawupdate` and coalesced pen samples when the platform supplies them.
- Add an e-ink profile that caps display density, defers PNG encoding until writing pauses, and is automatically selected on standards-compliant slow-update displays.
- Defer eraser redraws until pen-up to prevent repeated full-screen e-ink refreshes.
- Make autosave a true trailing debounce so continuous writing never triggers PNG encoding mid-stroke burst.
- Keep animation and pointer APIs scoped to the canvas window for reliable use in Obsidian pop-out windows.
- Make dotted paper full-bleed so the complete visible pane is writable with no inset sheet or dead margins.

## 1.2.0 — 2026-08-10

- Added a quiet, context-aware trash action for removing handwriting while keeping its Markdown note.
- Move editable ink and PNG snapshots through Obsidian's configured trash instead of permanently deleting them.
- Automatically clean up private Inkflow assets when their parent Markdown note is deleted.
- Protect assets still associated with or embedded by another note.
- Cancel pending saves safely during deletion so discarded handwriting cannot reappear.

## 1.1.1 — 2026-08-10

- Removed competing native and Obsidian hover tooltips from toolbar controls.
- Made active pen and stroke-width states theme-resistant, high-contrast, and persistent through hover and keyboard focus.
- Restyled the three thickness presets as a clear contextual segmented control.

## 1.1.0 — 2026-08-10

- Replaced the multi-option toolbar with a minimal pen, eraser, three-width, undo, and redo interface.
- Made ink and page colors follow Obsidian's light and dark themes automatically.
- Standardized every canvas on unobtrusive dotted paper.
- Reduced snapshot coalescing latency from 650 ms to 120 ms.
- Fixed stale rendered embeds by refreshing Obsidian's versioned image resource URL after every save.
- Removed visible source markers from Markdown; associations now survive renames through plugin data and recover from embeds.
- Added incremental committed-stroke caching to avoid redrawing the full document after ordinary pen strokes.

## 1.0.0 — 2026-08-10

- Initial release.
- Pressure-aware pen input with coalesced pointer samples.
- Whole-stroke eraser and bounded undo/redo history.
- Automatic, debounced `.ink.json` and PNG persistence.
- Rename-safe source markers and portable Obsidian image embeds.
- Blank, ruled, grid, and dotted paper.
- Responsive desktop, tablet, and mobile interface.

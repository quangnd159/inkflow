# Inkflow

Inkflow is a fast handwriting sidecar for Obsidian. Open a Markdown note, tap the pen, and write. Your editable ink is saved automatically and a normal PNG embed stays up to date inside the note.

It is intentionally not a whiteboard or diagramming system. Inkflow does one thing: it makes handwritten marginalia feel immediate on iPad, Boox, and other pen-enabled devices.

## Why it feels different

- **One gesture to ink.** Open Inkflow from the ribbon or command palette and start writing.
- **Made for styluses.** Pressure-sensitive strokes, coalesced pointer samples, palm rejection, and large touch targets.
- **Fast over long sessions.** Completed strokes are cached; only the live stroke is redrawn while writing. Display pixel density is capped to protect e-ink and mobile GPUs.
- **Portable by default.** Every canvas produces an ordinary PNG embedded with Obsidian's `![[...]]` syntax and refreshes visible embeds after each short stroke burst.
- **Editable and recoverable.** A compact `.ink.json` sidecar preserves every point. Rename-safe associations live in plugin data, leaving no bookkeeping text in your notes.
- **Disposable by design.** Delete handwriting in one action: the embed disappears and its private source and image follow your Obsidian trash preference. Deleting the parent note cleans them up automatically.
- **Distraction-free.** Ink automatically follows the theme, paper is always dotted, and the only choices while writing are pen, eraser, and three useful widths.
- **Local and offline.** No accounts, analytics, network calls, native modules, or runtime dependencies.

## Use

1. Open a Markdown note.
2. Select **Open handwriting for current note** from the command palette, or tap the pen ribbon icon.
3. Write. Inkflow creates the sidecar and image after your first stroke.

The canvas follows the active note by default. Turn this off in **Settings → Inkflow** when you want to keep one canvas pinned while reading other notes.

### Controls

| Action | Control |
| --- | --- |
| Pen | Toolbar or `P` while the canvas is focused |
| Stroke eraser | Toolbar or `E` |
| Undo / redo | Toolbar or platform-standard `Ctrl/Cmd+Z` and `Ctrl/Cmd+Shift+Z` |
| Stroke width | Thin, regular, or bold toolbar buttons |
| Delete handwriting | Trash button beside the save status, or **Delete handwriting from current note** in the command palette |
| Zoom | `Ctrl/Cmd` + wheel |

The eraser removes whole strokes. This is deliberate: it is predictable, fast, and makes undo exact.

## Files and privacy

By default Inkflow writes to `Attachments/InkFlow`:

```text
My-note-abc1234.ink.json  # editable source
My-note-abc1234.png       # portable snapshot embedded in Markdown
```

All processing happens on-device. Inkflow does not access the network, collect telemetry, require an account, display ads, or access files outside your vault.

Inkflow assets belong to their note by default. Deleting handwriting keeps the Markdown note, removes its image embed, clears the association, and moves the `.png` and `.ink.json` files to your configured Obsidian trash. Deleting the Markdown note performs the same asset cleanup automatically. If an asset is referenced by another note, Inkflow leaves it in place.

## Installation

### Community plugins

After publication, install **Inkflow** from **Settings → Community plugins → Browse**.

### Manual / beta

Download `main.js`, `manifest.json`, and `styles.css` from a release and place them in:

```text
<vault>/.obsidian/plugins/inkflow/
```

Then reload Obsidian and enable Inkflow.

## Development

Requires Node.js 20.19 or newer.

```bash
npm install
npm run dev
npm run check
```

To test in Obsidian, copy or symlink the repository into a dedicated development vault at `.obsidian/plugins/inkflow`, then enable the plugin. Do not develop against a valuable vault.

## Release checklist

1. Update `CHANGELOG.md`.
2. Run `npm version patch`, `minor`, or `major`.
3. Push the commit and the numeric tag (for example `1.0.1`, without a `v`).
4. GitHub Actions builds and attaches `main.js`, `manifest.json`, and `styles.css` to the release.
5. For the first public version, submit the repository to `obsidianmd/obsidian-releases`.

## License

[MIT](LICENSE)

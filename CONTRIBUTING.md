# Contributing

Thanks for helping Inkflow stay small and excellent. Before proposing a feature, explain how it improves the core loop of handwriting beside a note; broad whiteboard and diagramming features are intentionally out of scope.

For code changes:

1. Use a dedicated development vault.
2. Run `npm install` and `npm run check`.
3. Test pointer input with a mouse and, when relevant, a real stylus on mobile.
4. Keep runtime code free of Node.js, Electron, network access, and new dependencies unless there is a compelling, documented need.
5. Include tests for pure data or geometry behavior.

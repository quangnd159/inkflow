import { describe, expect, it } from "vitest";
import { hashPath, isSafeSourcePath } from "../src/path-utils";
import { removeEmbedLine } from "../src/markdown-utils";

describe("asset identity", () => {
  it("is deterministic and distinguishes same-named notes in different folders", () => {
    expect(hashPath("Books/Thinking.md")).toBe(hashPath("Books/Thinking.md"));
    expect(hashPath("Books/Thinking.md")).not.toBe(hashPath("Projects/Thinking.md"));
  });

  it("rejects markers that could overwrite unrelated vault content", () => {
    expect(isSafeSourcePath("Attachments/InkFlow/note.ink.json")).toBe(true);
    expect(isSafeSourcePath("../note.ink.json")).toBe(false);
    expect(isSafeSourcePath("Attachments/other.json")).toBe(false);
    expect(isSafeSourcePath("/absolute/note.ink.json")).toBe(false);
  });
});

describe("discarding handwriting", () => {
  const embed = "![[Attachments/InkFlow/Thought-123.png]]";

  it("removes only the generated embed and collapses its spacer", () => {
    expect(removeEmbedLine(`# Thought\n\nBody\n\n${embed}\n`, embed)).toBe("# Thought\n\nBody\n");
  });

  it("leaves unrelated content and embeds untouched", () => {
    const content = "# Thought\n\n![[Attachments/photo.png]]\n";
    expect(removeEmbedLine(content, embed)).toBe(content);
  });
});

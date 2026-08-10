import { describe, expect, it } from "vitest";
import { createInkDocument, parseInkDocument } from "../src/model";

describe("InkFlow document format", () => {
  it("round-trips a valid document", () => {
    const document = createInkDocument("dots");
    document.strokes.push({ id: "a", color: "#111111", width: 5, points: [{ x: 1, y: 2, pressure: 0.7, time: 3 }] });
    expect(parseInkDocument(JSON.stringify(document))).toEqual(document);
  });

  it("rejects an unknown document version", () => {
    const document = { ...createInkDocument("blank"), version: 99 };
    expect(() => parseInkDocument(JSON.stringify(document))).toThrow(/unsupported|malformed/i);
  });
});

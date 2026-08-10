import { describe, expect, it } from "vitest";
import { pointToSegmentDistanceSquared, shouldAddPoint, strokeHitsPoint } from "../src/geometry";
import type { InkPoint, InkStroke } from "../src/model";

const point = (x: number, y: number): InkPoint => ({ x, y, pressure: 0.5, time: 0 });

describe("ink geometry", () => {
  it("filters sub-pixel samples without losing intentional movement", () => {
    expect(shouldAddPoint([point(0, 0)], point(0.2, 0.2), 0.7)).toBe(false);
    expect(shouldAddPoint([point(0, 0)], point(1, 0), 0.7)).toBe(true);
  });

  it("measures points against the nearest place on a segment", () => {
    expect(pointToSegmentDistanceSquared(point(5, 4), point(0, 0), point(10, 0))).toBe(16);
    expect(pointToSegmentDistanceSquared(point(-3, 4), point(0, 0), point(10, 0))).toBe(25);
  });

  it("hits both dots and continuous strokes with eraser tolerance", () => {
    const line: InkStroke = { id: "line", color: "#000", width: 4, points: [point(0, 0), point(20, 0)] };
    const dot: InkStroke = { id: "dot", color: "#000", width: 4, points: [point(10, 10)] };
    expect(strokeHitsPoint(line, point(10, 3), 2)).toBe(true);
    expect(strokeHitsPoint(line, point(10, 8), 2)).toBe(false);
    expect(strokeHitsPoint(dot, point(11, 11), 1)).toBe(true);
  });
});

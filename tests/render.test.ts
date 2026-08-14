import { describe, expect, it, vi } from "vitest";
import { pressureWidth, pressureWidthScale, renderPredictedTail, renderStroke, smoothedPressure } from "../src/render";
import type { InkStroke } from "../src/model";

function contextMock(): { context: CanvasRenderingContext2D; spies: Record<string, ReturnType<typeof vi.fn>> } {
  const spies = {
    arc: vi.fn(),
    beginPath: vi.fn(),
    fill: vi.fn(),
    lineTo: vi.fn(),
    moveTo: vi.fn(),
    quadraticCurveTo: vi.fn(),
    restore: vi.fn(),
    save: vi.fn(),
    stroke: vi.fn(),
  };
  return { context: spies as unknown as CanvasRenderingContext2D, spies };
}

const stroke: InkStroke = {
  id: "stroke",
  color: "auto",
  width: 5,
  points: [
    { x: 1, y: 2, pressure: 0.5, time: 0 },
    { x: 3, y: 4, pressure: 0.5, time: 1 },
    { x: 5, y: 6, pressure: 0.5, time: 2 },
  ],
};

describe("stroke rendering", () => {
  it("draws the initial contact once", () => {
    const { context, spies } = contextMock();
    renderStroke(context, { ...stroke, points: [stroke.points[0]!] }, "#000");
    expect(spies.arc).toHaveBeenCalledOnce();
    expect(spies.fill).toHaveBeenCalledOnce();
    expect(spies.stroke).not.toHaveBeenCalled();
  });

  it("draws a straight tail for a two-point stroke", () => {
    const { context, spies } = contextMock();
    const twoPoints: InkStroke = { ...stroke, points: [stroke.points[0]!, stroke.points[1]!] };
    renderStroke(context, twoPoints, "#000");
    expect(spies.quadraticCurveTo).not.toHaveBeenCalled();
    expect(spies.moveTo).toHaveBeenCalledWith(1, 2);
    expect(spies.lineTo).toHaveBeenCalledWith(3, 4);
    expect(spies.stroke).toHaveBeenCalledOnce();
  });

  it("draws a midpoint quadratic curve plus a raw tail once a third point arrives", () => {
    const { context, spies } = contextMock();
    renderStroke(context, stroke, "#000");
    // Curve segment: from the first raw point, through point[1] as control, to the
    // midpoint of point[1] and point[2].
    expect(spies.moveTo).toHaveBeenCalledWith(1, 2);
    expect(spies.quadraticCurveTo).toHaveBeenCalledWith(3, 4, 4, 5);
    // Raw tail from that same midpoint to the latest point.
    expect(spies.moveTo).toHaveBeenCalledWith(4, 5);
    expect(spies.lineTo).toHaveBeenCalledWith(5, 6);
    expect(spies.stroke).toHaveBeenCalledTimes(2);
  });
});

describe("predicted tail rendering", () => {
  it("draws from the last real point through up to two predicted points", () => {
    const { context, spies } = contextMock();
    const predicted = [
      { x: 6, y: 7, pressure: 0.5, time: 3 },
      { x: 7, y: 8, pressure: 0.5, time: 4 },
      { x: 8, y: 9, pressure: 0.5, time: 5 },
    ];
    renderPredictedTail(context, stroke, predicted, "#000");
    expect(spies.moveTo).toHaveBeenCalledWith(5, 6);
    expect(spies.lineTo).toHaveBeenCalledWith(6, 7);
    expect(spies.lineTo).toHaveBeenCalledWith(7, 8);
    expect(spies.lineTo).not.toHaveBeenCalledWith(8, 9);
    expect(spies.stroke).toHaveBeenCalledOnce();
  });

  it("does nothing when there are no predicted points", () => {
    const { context, spies } = contextMock();
    renderPredictedTail(context, stroke, [], "#000");
    expect(spies.moveTo).not.toHaveBeenCalled();
    expect(spies.stroke).not.toHaveBeenCalled();
  });
});

describe("pressure width mapping", () => {
  it("renders default (no-pressure) input at exactly 1.0x", () => {
    expect(pressureWidthScale(0.5)).toBe(1);
    expect(pressureWidth(10, 0.5)).toBe(10);
  });

  it("scales roughly 0.6x..1.5x across the pressure range", () => {
    expect(pressureWidthScale(0)).toBeCloseTo(0.6);
    expect(pressureWidthScale(1)).toBeCloseTo(1.5);
  });

  it("clamps out-of-range pressure", () => {
    expect(pressureWidthScale(-1)).toBe(pressureWidthScale(0));
    expect(pressureWidthScale(2)).toBe(pressureWidthScale(1));
  });

  it("is monotonically increasing", () => {
    expect(pressureWidthScale(0.25)).toBeLessThan(pressureWidthScale(0.5));
    expect(pressureWidthScale(0.5)).toBeLessThan(pressureWidthScale(0.75));
  });
});

describe("pressure smoothing", () => {
  it("averages a trailing window of points", () => {
    const points = [
      { x: 0, y: 0, pressure: 0.2, time: 0 },
      { x: 0, y: 0, pressure: 0.4, time: 1 },
      { x: 0, y: 0, pressure: 0.9, time: 2 },
    ];
    expect(smoothedPressure(points, 2, 3)).toBeCloseTo((0.2 + 0.4 + 0.9) / 3);
  });

  it("smooths out a single spike so width does not jump abruptly", () => {
    const points = [
      { x: 0, y: 0, pressure: 0.5, time: 0 },
      { x: 0, y: 0, pressure: 0.5, time: 1 },
      { x: 0, y: 0, pressure: 1, time: 2 },
    ];
    const smoothed = smoothedPressure(points, 2, 3);
    expect(smoothed).toBeLessThan(1);
    expect(smoothed).toBeGreaterThan(0.5);
  });
});

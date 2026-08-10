import { describe, expect, it, vi } from "vitest";
import { renderStrokeIncrement } from "../src/render";
import type { InkStroke } from "../src/model";

function contextMock(): { context: CanvasRenderingContext2D; spies: Record<string, ReturnType<typeof vi.fn>> } {
  const spies = {
    arc: vi.fn(),
    beginPath: vi.fn(),
    fill: vi.fn(),
    lineTo: vi.fn(),
    moveTo: vi.fn(),
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

describe("incremental stroke rendering", () => {
  it("draws the initial contact once", () => {
    const { context, spies } = contextMock();
    renderStrokeIncrement(context, { ...stroke, points: [stroke.points[0]!] }, "#000", 0);
    expect(spies.arc).toHaveBeenCalledOnce();
    expect(spies.fill).toHaveBeenCalledOnce();
    expect(spies.stroke).not.toHaveBeenCalled();
  });

  it("draws only segments added since the last sample batch", () => {
    const { context, spies } = contextMock();
    renderStrokeIncrement(context, stroke, "#000", 2);
    expect(spies.arc).not.toHaveBeenCalled();
    expect(spies.moveTo).toHaveBeenCalledWith(3, 4);
    expect(spies.lineTo).toHaveBeenCalledWith(5, 6);
    expect(spies.stroke).toHaveBeenCalledOnce();
  });
});

import { clamp } from "./geometry";
import type { InkDocument, InkPoint, InkStroke, PageStyle } from "./model";

const PRESSURE_SMOOTHING_WINDOW = 3;

export interface RenderOptions {
  scale: number;
  offsetX: number;
  offsetY: number;
  background: string;
  guide: string;
  ink: string;
}

export function renderDocument(context: CanvasRenderingContext2D, document: InkDocument, options: RenderOptions): void {
  context.save();
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.clearRect(0, 0, context.canvas.width, context.canvas.height);
  context.translate(options.offsetX, options.offsetY);
  context.scale(options.scale, options.scale);
  renderPage(context, document.width, document.height, document.pageStyle, options.background, options.guide);
  context.beginPath();
  context.rect(0, 0, document.width, document.height);
  context.clip();
  for (const stroke of document.strokes) renderStroke(context, stroke, options.ink);
  context.restore();
}

export function renderPage(
  context: CanvasRenderingContext2D,
  width: number,
  height: number,
  style: PageStyle,
  background: string,
  guide: string,
): void {
  context.fillStyle = background;
  context.fillRect(0, 0, width, height);
  if (style === "blank") return;
  context.save();
  context.strokeStyle = guide;
  context.fillStyle = guide;
  context.lineWidth = 1;
  const spacing = style === "ruled" ? 64 : 48;
  if (style === "dots") {
    for (let y = spacing; y < height; y += spacing) {
      for (let x = spacing; x < width; x += spacing) {
        context.beginPath();
        context.arc(x, y, 1.7, 0, Math.PI * 2);
        context.fill();
      }
    }
  } else {
    for (let y = spacing; y < height; y += spacing) {
      context.beginPath();
      context.moveTo(0, y + 0.5);
      context.lineTo(width, y + 0.5);
      context.stroke();
    }
    if (style === "grid") {
      for (let x = spacing; x < width; x += spacing) {
        context.beginPath();
        context.moveTo(x + 0.5, 0);
        context.lineTo(x + 0.5, height);
        context.stroke();
      }
    }
  }
  context.restore();
}

/**
 * Renders a stroke using midpoint quadratic smoothing: curves run through the
 * midpoints of consecutive points, using the raw points themselves as
 * quadratic control points. The most recent point is always joined to its
 * predecessor's midpoint with a short straight "tail" so the ink tracks the
 * pen tip exactly; that tail gets replaced by a smooth curve once another
 * point arrives.
 */
export function renderStroke(context: CanvasRenderingContext2D, stroke: InkStroke, color = stroke.color): void {
  const points = stroke.points;
  const first = points[0];
  if (first === undefined) return;
  context.save();
  context.fillStyle = color;
  context.strokeStyle = color;
  context.lineCap = "round";
  context.lineJoin = "round";

  context.beginPath();
  context.arc(first.x, first.y, pressureWidth(stroke.width, smoothedPressure(points, 0)) / 2, 0, Math.PI * 2);
  context.fill();

  if (points.length === 2) {
    const end = points[1]!;
    context.beginPath();
    context.lineWidth = pressureWidth(stroke.width, smoothedPressure(points, 1));
    context.moveTo(first.x, first.y);
    context.lineTo(end.x, end.y);
    context.stroke();
    context.restore();
    return;
  }

  if (points.length >= 3) {
    for (let index = 1; index <= points.length - 2; index += 1) {
      const previous = points[index - 1];
      const current = points[index];
      const next = points[index + 1];
      if (previous === undefined || current === undefined || next === undefined) continue;
      const from = index === 1 ? previous : midpoint(previous, current);
      const to = midpoint(current, next);
      context.beginPath();
      context.lineWidth = pressureWidth(stroke.width, smoothedPressure(points, index));
      context.moveTo(from.x, from.y);
      context.quadraticCurveTo(current.x, current.y, to.x, to.y);
      context.stroke();
    }

    const last = points[points.length - 1]!;
    const secondLast = points[points.length - 2]!;
    const tailStart = midpoint(secondLast, last);
    context.beginPath();
    context.lineWidth = pressureWidth(stroke.width, smoothedPressure(points, points.length - 1));
    context.moveTo(tailStart.x, tailStart.y);
    context.lineTo(last.x, last.y);
    context.stroke();
  }

  context.restore();
}

/**
 * Draws a lightweight predicted "tail" ahead of the pen tip, using up to the
 * first two points from `PointerEvent.getPredictedEvents()`. These points are
 * transient render-only data: they are never added to the stroke's saved
 * points, so they get overwritten by the next full redraw of the real stroke.
 */
export function renderPredictedTail(
  context: CanvasRenderingContext2D,
  stroke: InkStroke,
  predicted: readonly InkPoint[],
  color: string,
): void {
  const points = stroke.points;
  const last = points[points.length - 1];
  if (last === undefined || predicted.length === 0) return;
  const pressure = smoothedPressure(points, points.length - 1);
  context.save();
  context.strokeStyle = color;
  context.lineCap = "round";
  context.lineJoin = "round";
  context.lineWidth = pressureWidth(stroke.width, pressure);
  context.beginPath();
  context.moveTo(last.x, last.y);
  for (const point of predicted.slice(0, 2)) {
    context.lineTo(point.x, point.y);
  }
  context.stroke();
  context.restore();
}

function midpoint(a: InkPoint, b: InkPoint): { x: number; y: number } {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

/**
 * Maps pressure 0..1 to a width multiplier of roughly 0.6x..1.5x, piecewise
 * linear so that the default pressure of 0.5 (mice and no-pressure styli)
 * always lands exactly on 1.0x, keeping non-pressure input unchanged.
 */
export function pressureWidthScale(pressure: number): number {
  const p = clamp(pressure, 0, 1);
  return p <= 0.5 ? 0.6 + p * 0.8 : 0.5 + p;
}

export function pressureWidth(width: number, pressure: number): number {
  return width * pressureWidthScale(pressure);
}

/** Smooths pressure over a small trailing window so line width doesn't jitter point-to-point. */
export function smoothedPressure(points: readonly InkPoint[], index: number, window = PRESSURE_SMOOTHING_WINDOW): number {
  const start = Math.max(0, index - window + 1);
  let sum = 0;
  let count = 0;
  for (let i = start; i <= index; i += 1) {
    const point = points[i];
    if (point === undefined) continue;
    sum += point.pressure;
    count += 1;
  }
  return count > 0 ? sum / count : 0.5;
}

export function pointFromPointer(event: PointerEvent, canvas: HTMLCanvasElement, scale: number, offsetX: number, offsetY: number): InkPoint {
  const bounds = canvas.getBoundingClientRect();
  const pressure = event.pressure > 0 ? event.pressure : 0.5;
  return {
    x: (event.clientX - bounds.left - offsetX) / scale,
    y: (event.clientY - bounds.top - offsetY) / scale,
    pressure,
    time: event.timeStamp,
  };
}

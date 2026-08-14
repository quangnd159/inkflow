import { clamp, distanceToSegmentSquaredXY } from "./geometry";
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

export function renderStroke(context: CanvasRenderingContext2D, stroke: InkStroke, color = stroke.color): void {
  renderStrokeIncrement(context, stroke, color, 0);
}

/**
 * Renders a stroke (or the portion of it starting at `firstNewPointIndex`) using
 * midpoint quadratic smoothing: curves run through the midpoints of consecutive
 * points, using the raw points themselves as quadratic control points. The most
 * recent point is always joined to its predecessor's midpoint with a short
 * straight "tail" so the ink tracks the pen tip exactly; that tail gets replaced
 * by a smooth curve once another point arrives. Because each curve segment is
 * fully determined by three consecutive raw points, this can be called
 * incrementally (redrawing only the newly-eligible segments) or as a full
 * redraw (firstNewPointIndex = 0) and produce the same pixels either way.
 *
 * `tail` controls whether that straight tail is drawn at all. On an append-only
 * surface (e-ink) where nothing is ever erased between calls, pass `tail = false`
 * for the per-point incremental calls so no straight spur is left behind; then
 * make one final call with `tail = true` once the pen lifts, to draw the closing
 * curve segment(s) and the tail together so the ink reaches the exact last point.
 */
export function renderStrokeIncrement(
  context: CanvasRenderingContext2D,
  stroke: InkStroke,
  color: string,
  firstNewPointIndex: number,
  tail = true,
): void {
  const points = stroke.points;
  const first = points[0];
  if (first === undefined) return;
  context.save();
  context.fillStyle = color;
  context.strokeStyle = color;
  context.lineCap = "round";
  context.lineJoin = "round";

  if (firstNewPointIndex === 0) {
    context.beginPath();
    context.arc(first.x, first.y, pressureWidth(stroke.width, smoothedPressure(points, 0)) / 2, 0, Math.PI * 2);
    context.fill();
  }

  if (points.length === 2) {
    if (tail) {
      const end = points[1]!;
      context.beginPath();
      context.lineWidth = pressureWidth(stroke.width, smoothedPressure(points, 1));
      context.moveTo(first.x, first.y);
      context.lineTo(end.x, end.y);
      context.stroke();
    }
    context.restore();
    return;
  }

  if (points.length >= 3) {
    const startIndex = Math.max(1, firstNewPointIndex - 1);
    for (let index = startIndex; index <= points.length - 2; index += 1) {
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

    if (tail) {
      const last = points[points.length - 1]!;
      const secondLast = points[points.length - 2]!;
      const tailStart = midpoint(secondLast, last);
      context.beginPath();
      context.lineWidth = pressureWidth(stroke.width, smoothedPressure(points, points.length - 1));
      context.moveTo(tailStart.x, tailStart.y);
      context.lineTo(last.x, last.y);
      context.stroke();
    }
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

export interface DirtyRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * Integer-clamped bounding box (plus 1px pad) of a capsule shape around the
 * segment (x0, y0)-(x1, y1) with the given radius, clamped to [0, maxWidth] x
 * [0, maxHeight]. Used to size the `getImageData`/`putImageData` region for
 * turbo (e-ink) hard-ink rasterization.
 */
export function segmentDirtyRect(x0: number, y0: number, x1: number, y1: number, radius: number, maxWidth: number, maxHeight: number): DirtyRect {
  const pad = 1;
  const minX = clamp(Math.floor(Math.min(x0, x1) - radius - pad), 0, maxWidth);
  const minY = clamp(Math.floor(Math.min(y0, y1) - radius - pad), 0, maxHeight);
  const maxX = clamp(Math.ceil(Math.max(x0, x1) + radius + pad), 0, maxWidth);
  const maxY = clamp(Math.ceil(Math.max(y0, y1) + radius + pad), 0, maxHeight);
  return { x: minX, y: minY, width: Math.max(0, maxX - minX), height: Math.max(0, maxY - minY) };
}

/**
 * Stamps a hard-edged (no antialiasing) capsule around the segment (x0, y0)-(x1, y1)
 * into `image`, in the ImageData's own local pixel space (i.e. x0/y0/x1/y1 are already
 * relative to the ImageData's top-left corner). Every pixel whose center lies within
 * `radius` of the segment becomes fully opaque `rgb`; every other pixel is left
 * untouched. There is no partial coverage: e-ink panels flip pure black/white pixels
 * far faster than antialiased gray ones.
 */
export function rasterizeSegmentHard(image: ImageData, x0: number, y0: number, x1: number, y1: number, radius: number, rgb: readonly [number, number, number]): void {
  const { width, height, data } = image;
  const radiusSquared = radius * radius;
  const [r, g, b] = rgb;
  for (let py = 0; py < height; py += 1) {
    const cy = py + 0.5;
    for (let px = 0; px < width; px += 1) {
      const cx = px + 0.5;
      if (distanceToSegmentSquaredXY(cx, cy, x0, y0, x1, y1) > radiusSquared) continue;
      const index = (py * width + px) * 4;
      data[index] = r;
      data[index + 1] = g;
      data[index + 2] = b;
      data[index + 3] = 255;
    }
  }
}

/** Parses a `#rgb` or `#rrggbb` CSS hex color into 0-255 RGB components; anything else falls back to black. */
export function parseColorToRgb(color: string): [number, number, number] {
  const match = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color.trim());
  if (match === null) return [0, 0, 0];
  const hex = match[1]!;
  if (hex.length === 3) {
    return [parseInt(hex[0]! + hex[0], 16), parseInt(hex[1]! + hex[1], 16), parseInt(hex[2]! + hex[2], 16)];
  }
  return [parseInt(hex.slice(0, 2), 16), parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)];
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

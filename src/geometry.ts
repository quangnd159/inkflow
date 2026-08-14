import type { InkPoint, InkStroke } from "./model";

export function distanceSquared(a: InkPoint, b: InkPoint): number {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return dx * dx + dy * dy;
}

export function shouldAddPoint(points: readonly InkPoint[], point: InkPoint, minimumDistance = 0.7): boolean {
  const previous = points.at(-1);
  return previous === undefined || distanceSquared(previous, point) >= minimumDistance * minimumDistance;
}

export function strokeHitsPoint(stroke: InkStroke, point: InkPoint, radius: number): boolean {
  const threshold = radius + stroke.width / 2;
  const thresholdSquared = threshold * threshold;
  if (stroke.points.length === 1) {
    const only = stroke.points[0];
    return only !== undefined && distanceSquared(only, point) <= thresholdSquared;
  }
  for (let index = 1; index < stroke.points.length; index += 1) {
    const start = stroke.points[index - 1];
    const end = stroke.points[index];
    if (start !== undefined && end !== undefined && pointToSegmentDistanceSquared(point, start, end) <= thresholdSquared) {
      return true;
    }
  }
  return false;
}

export function pointToSegmentDistanceSquared(point: InkPoint, start: InkPoint, end: InkPoint): number {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  if (dx === 0 && dy === 0) return distanceSquared(point, start);
  const projection = Math.max(0, Math.min(1, ((point.x - start.x) * dx + (point.y - start.y) * dy) / (dx * dx + dy * dy)));
  const x = start.x + projection * dx;
  const y = start.y + projection * dy;
  const projected: InkPoint = { x, y, pressure: 0, time: 0 };
  return distanceSquared(point, projected);
}

export function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

/** Squared distance from point (px, py) to the segment (x0, y0)-(x1, y1). Works for zero-length segments (a dot). */
export function distanceToSegmentSquaredXY(px: number, py: number, x0: number, y0: number, x1: number, y1: number): number {
  const dx = x1 - x0;
  const dy = y1 - y0;
  if (dx === 0 && dy === 0) {
    const ddx = px - x0;
    const ddy = py - y0;
    return ddx * ddx + ddy * ddy;
  }
  const t = clamp(((px - x0) * dx + (py - y0) * dy) / (dx * dx + dy * dy), 0, 1);
  const x = x0 + t * dx;
  const y = y0 + t * dy;
  const ddx = px - x;
  const ddy = py - y;
  return ddx * ddx + ddy * ddy;
}

import type { InkDocument, InkPoint, InkStroke, PageStyle } from "./model";

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
  const first = stroke.points[0];
  if (first === undefined) return;
  if (stroke.points.length === 1) {
    context.beginPath();
    context.fillStyle = color;
    context.arc(first.x, first.y, pressureWidth(stroke.width, first.pressure) / 2, 0, Math.PI * 2);
    context.fill();
    return;
  }
  context.save();
  context.strokeStyle = color;
  context.lineCap = "round";
  context.lineJoin = "round";
  for (let index = 1; index < stroke.points.length; index += 1) {
    const start = stroke.points[index - 1];
    const end = stroke.points[index];
    if (start === undefined || end === undefined) continue;
    context.beginPath();
    context.lineWidth = pressureWidth(stroke.width, (start.pressure + end.pressure) / 2);
    context.moveTo(start.x, start.y);
    const next = stroke.points[index + 1];
    if (next === undefined) context.lineTo(end.x, end.y);
    else context.quadraticCurveTo(end.x, end.y, (end.x + next.x) / 2, (end.y + next.y) / 2);
    context.stroke();
  }
  context.restore();
}

function pressureWidth(width: number, pressure: number): number {
  return width * (0.45 + Math.max(0, Math.min(1, pressure)) * 0.9);
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

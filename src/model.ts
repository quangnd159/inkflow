export const DOCUMENT_VERSION = 1;
export const PAGE_WIDTH = 1400;
export const PAGE_HEIGHT = 1800;

export type Tool = "pen" | "eraser";
export type PageStyle = "blank" | "ruled" | "grid" | "dots";

export interface InkPoint {
  x: number;
  y: number;
  pressure: number;
  time: number;
}

export interface InkStroke {
  id: string;
  color: string;
  width: number;
  points: InkPoint[];
}

export interface InkDocument {
  version: typeof DOCUMENT_VERSION;
  width: number;
  height: number;
  pageStyle: PageStyle;
  createdAt: string;
  updatedAt: string;
  strokes: InkStroke[];
}

export function createInkDocument(pageStyle: PageStyle): InkDocument {
  const now = new Date().toISOString();
  return {
    version: DOCUMENT_VERSION,
    width: PAGE_WIDTH,
    height: PAGE_HEIGHT,
    pageStyle,
    createdAt: now,
    updatedAt: now,
    strokes: [],
  };
}

export function parseInkDocument(value: string): InkDocument {
  const parsed: unknown = JSON.parse(value);
  if (!isInkDocument(parsed)) {
    throw new Error("Unsupported or malformed InkFlow document");
  }
  return parsed;
}

function isInkDocument(value: unknown): value is InkDocument {
  if (typeof value !== "object" || value === null) return false;
  const document = value as Partial<InkDocument>;
  return (
    document.version === DOCUMENT_VERSION &&
    typeof document.width === "number" &&
    typeof document.height === "number" &&
    typeof document.createdAt === "string" &&
    typeof document.updatedAt === "string" &&
    isPageStyle(document.pageStyle) &&
    Array.isArray(document.strokes) &&
    document.strokes.every(isInkStroke)
  );
}

function isInkStroke(value: unknown): value is InkStroke {
  if (typeof value !== "object" || value === null) return false;
  const stroke = value as Partial<InkStroke>;
  return (
    typeof stroke.id === "string" &&
    typeof stroke.color === "string" &&
    typeof stroke.width === "number" &&
    Array.isArray(stroke.points) &&
    stroke.points.every(
      (point) =>
        typeof point === "object" &&
        point !== null &&
        typeof (point as Partial<InkPoint>).x === "number" &&
        typeof (point as Partial<InkPoint>).y === "number" &&
        typeof (point as Partial<InkPoint>).pressure === "number" &&
        typeof (point as Partial<InkPoint>).time === "number",
    )
  );
}

export function isPageStyle(value: unknown): value is PageStyle {
  return value === "blank" || value === "ruled" || value === "grid" || value === "dots";
}

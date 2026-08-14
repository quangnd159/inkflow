import { clamp, shouldAddPoint, strokeHitsPoint } from "./geometry";
import {
  parseColorToRgb,
  rasterizeSegmentHard,
  renderDocument,
  renderPredictedTail,
  renderStroke,
  segmentDirtyRect,
  pointFromPointer,
  type DirtyRect,
} from "./render";
import type { InkDocument, InkPoint, InkStroke, Tool } from "./model";

const MAX_DEVICE_PIXEL_RATIO = 2;
const MAX_DISPLAY_PIXELS = 6_000_000;
const MAX_EINK_DISPLAY_PIXELS = 2_000_000;
const MAX_HISTORY = 60;
const ERASER_RADIUS = 24;
const EXPORT_SCALE = 1;
/** How long to wait, after the last stroke started, before replacing turbo hard ink with the smooth version. */
const SETTLE_DELAY_MS = 1500;

export interface InkCanvasOptions {
  getEInkMode: () => boolean;
  getPalmRejection: () => boolean;
  onChange: () => void;
  onToolChange: (tool: Tool) => void;
}

/** Minimal typing for the Chromium-only `navigator.ink` delegated ink trail API (feature-detected; a no-op elsewhere). */
interface InkPresenter {
  updateInkTrailStartPoint(event: PointerEvent, style: { color: string; diameter: number }): void;
}
interface InkPresenterParams {
  presentationArea: Element;
}
interface NavigatorWithInk extends Navigator {
  ink?: {
    requestPresenter(params: InkPresenterParams): Promise<InkPresenter>;
  };
}

export class InkCanvas {
  canvas: HTMLCanvasElement;
  private readonly parent: HTMLElement;
  private readonly cacheCanvas: HTMLCanvasElement;
  private context: CanvasRenderingContext2D;
  private readonly cacheContext: CanvasRenderingContext2D;
  private readonly resizeObserver: ResizeObserver;
  private document: InkDocument | null = null;
  private tool: Tool = "pen";
  private width = 5;
  private currentStroke: InkStroke | null = null;
  /** Transient, render-only predicted points ahead of the pen tip (non-e-ink only). Never saved to the stroke. */
  private predictedPoints: InkPoint[] = [];
  private activePointer: number | null = null;
  private penIsDown = false;
  private eraserChanged = false;
  private zoom = 1;
  private undoStack: InkStroke[][] = [];
  private redoStack: InkStroke[][] = [];
  private frameRequested = false;
  private resizeFrame: number | null = null;
  private cacheDirty = true;
  private viewportWidth = 1;
  private viewportHeight = 1;
  private deviceScale = 1;
  private readonly useRawPointerEvents: boolean;
  /** Whether the visible context was created with `willReadFrequently` (i.e. e-ink mode at last (re)construction). */
  private contextHasFastRead = false;
  /** Accumulated device-pixel bbox of the live turbo (hard-ink) strokes drawn for the current stroke. */
  private turboBounds: DirtyRect | null = null;
  /** Device-pixel regions still showing turbo hard ink, waiting to be replaced by the smooth cache render. */
  private pendingSettleBounds: DirtyRect[] = [];
  private settleTimer: number | null = null;
  private inkPresenter: InkPresenter | null = null;
  private inkPresenterDisabled = false;
  private inkPresenterRequestInFlight = false;

  constructor(parent: HTMLElement, private readonly options: InkCanvasOptions) {
    this.parent = parent;
    this.cacheCanvas = createDetachedCanvas(parent);
    const cacheContext = this.cacheCanvas.getContext("2d");
    if (cacheContext === null) throw new Error("Canvas 2D is unavailable");
    this.cacheContext = cacheContext;
    const created = this.createCanvasElement();
    this.canvas = created.canvas;
    this.context = created.context;
    this.resizeObserver = new ResizeObserver(() => this.scheduleResize());
    this.resizeObserver.observe(parent);
    this.useRawPointerEvents = "onpointerrawupdate" in this.canvas;
    this.bindEvents();
  }

  /**
   * Creates the visible canvas element and its 2D context. Context creation attributes
   * (`willReadFrequently`) are immutable once set, and turbo (e-ink) mode needs fast
   * `getImageData`/`putImageData` while non-e-ink mode does not, so the element is
   * recreated (see `reinitCanvasIfNeeded`) whenever e-ink mode toggles at runtime.
   */
  private createCanvasElement(): { canvas: HTMLCanvasElement; context: CanvasRenderingContext2D } {
    const eInk = this.isEInk();
    const canvas = this.parent.createEl("canvas", { cls: "inkflow-canvas", attr: { tabindex: "0", "aria-label": "Handwriting canvas" } });
    // alpha: false is safe because the document background always fully covers the
    // canvas (renderDocument uses a "cover" transform), and it lets the browser skip
    // compositing against the page behind it.
    const context = canvas.getContext("2d", { desynchronized: true, alpha: false, willReadFrequently: eInk });
    if (context === null) throw new Error("Canvas 2D is unavailable");
    this.contextHasFastRead = eInk;
    return { canvas, context };
  }

  setDocument(document: InkDocument): void {
    document.pageStyle = "dots";
    this.document = document;
    this.currentStroke = null;
    this.undoStack = [];
    this.redoStack = [];
    this.zoom = 1;
    // A full redraw is about to happen (invalidateCache below), so any turbo hard ink
    // still pending settlement is about to be overwritten anyway.
    this.clearPendingSettle();
    this.invalidateCache();
  }

  setTool(tool: Tool): void {
    this.tool = tool;
    this.canvas.dataset.tool = tool;
    this.options.onToolChange(tool);
  }

  setWidth(width: number): void {
    this.width = width;
  }

  refreshTheme(): void {
    this.invalidateCache();
  }

  refreshPerformance(): void {
    this.reinitCanvasIfNeeded();
    this.scheduleResize();
  }

  setZoom(zoom: number): void {
    this.zoom = clamp(zoom, 1, 3);
    this.invalidateCache();
  }

  canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  undo(): void {
    if (this.document === null) return;
    const previous = this.undoStack.pop();
    if (previous === undefined) return;
    this.redoStack.push(this.document.strokes);
    this.document.strokes = previous;
    this.changed();
  }

  redo(): void {
    if (this.document === null) return;
    const next = this.redoStack.pop();
    if (next === undefined) return;
    this.undoStack.push(this.document.strokes);
    this.document.strokes = next;
    this.changed();
  }

  exportPng(): Promise<ArrayBuffer> {
    if (this.document === null) return Promise.reject(new Error("No ink document is open"));
    // Export always renders straight from the saved document (never from the visible or
    // cache canvas), so it is unaffected by turbo hard ink either way; clear the pending
    // settle list simply to avoid a stray settle firing mid-export.
    this.clearPendingSettle();
    const canvas = createDetachedCanvas(this.canvas);
    canvas.width = Math.round(this.document.width * EXPORT_SCALE);
    canvas.height = Math.round(this.document.height * EXPORT_SCALE);
    const context = canvas.getContext("2d", { alpha: false });
    if (context === null) return Promise.reject(new Error("Canvas export is unavailable"));
    const palette = this.getPalette();
    renderDocument(context, this.document, {
      scale: EXPORT_SCALE,
      offsetX: 0,
      offsetY: 0,
      ...palette,
    });
    return new Promise((resolve, reject) => {
      canvas.toBlob((blob) => {
        if (blob === null) reject(new Error("Unable to encode PNG snapshot"));
        else void blob.arrayBuffer().then(resolve, reject);
      }, "image/png");
    });
  }

  destroy(): void {
    this.resizeObserver.disconnect();
    if (this.resizeFrame !== null) this.getWindow().cancelAnimationFrame(this.resizeFrame);
    if (this.settleTimer !== null) this.getWindow().clearTimeout(this.settleTimer);
    this.unbindEvents(this.canvas);
  }

  private bindEvents(): void {
    this.canvas.addEventListener("pointerdown", this.onPointerDown);
    if (this.useRawPointerEvents) this.canvas.addEventListener("pointerrawupdate", this.onPointerRawUpdate);
    else this.canvas.addEventListener("pointermove", this.onPointerMove);
    this.canvas.addEventListener("pointerup", this.onPointerUp);
    this.canvas.addEventListener("pointercancel", this.onPointerUp);
    // iPadOS Scribble intercepts Apple Pencil touch input in WKWebView unless the page
    // actively prevents the default touchmove behavior; touch-action: none (styles.css)
    // alone is not enough to stop it from swallowing strokes.
    this.canvas.addEventListener("touchmove", this.onTouchMove, { passive: false });
    this.canvas.addEventListener("contextmenu", this.onContextMenu);
    this.canvas.addEventListener("keydown", this.onKeyDown);
    this.canvas.addEventListener("wheel", this.onWheel, { passive: false });
  }

  private unbindEvents(canvas: HTMLCanvasElement): void {
    canvas.removeEventListener("pointerdown", this.onPointerDown);
    canvas.removeEventListener("pointerrawupdate", this.onPointerRawUpdate);
    canvas.removeEventListener("pointermove", this.onPointerMove);
    canvas.removeEventListener("pointerup", this.onPointerUp);
    canvas.removeEventListener("pointercancel", this.onPointerUp);
    canvas.removeEventListener("touchmove", this.onTouchMove);
    canvas.removeEventListener("contextmenu", this.onContextMenu);
    canvas.removeEventListener("keydown", this.onKeyDown);
    canvas.removeEventListener("wheel", this.onWheel);
  }

  private readonly onContextMenu = (event: Event): void => {
    event.preventDefault();
  };

  /**
   * Recreates the visible canvas element when e-ink mode has changed since the context
   * was last created, so `willReadFrequently` (needed for fast turbo getImageData/
   * putImageData) stays in sync. Reuses the same append/bind/resize machinery as
   * construction; the old element is simply dropped.
   */
  private reinitCanvasIfNeeded(): void {
    if (this.isEInk() === this.contextHasFastRead) return;
    const activeTool = this.tool;
    const oldCanvas = this.canvas;
    this.unbindEvents(oldCanvas);
    oldCanvas.remove();
    const created = this.createCanvasElement();
    this.canvas = created.canvas;
    this.context = created.context;
    this.canvas.dataset.tool = activeTool;
    this.bindEvents();
    this.clearPendingSettle();
    this.invalidateCache();
  }

  private readonly onPointerDown = (event: PointerEvent): void => {
    if (this.document === null || this.activePointer !== null) return;
    if (this.options.getPalmRejection() && event.pointerType === "touch" && this.penIsDown) return;
    if (event.pointerType === "pen") this.penIsDown = true;
    const point = this.toPoint(event);
    if (!this.isOnPage(point)) return;
    this.activePointer = event.pointerId;
    this.canvas.setPointerCapture(event.pointerId);
    this.canvas.focus({ preventScroll: true });
    this.pushHistory();
    if (this.tool === "pen") {
      this.currentStroke = { id: createStrokeId(), color: "auto", width: this.width, points: [point] };
      this.predictedPoints = [];
      this.turboBounds = null;
      if (this.isEInk()) {
        this.drawTurboIncrement(this.currentStroke, 0);
        this.scheduleSettleTimer();
      } else {
        this.requestFrame();
      }
      if (event.pointerType === "pen") {
        void this.ensureInkPresenter();
        this.updateInkTrail(event);
      }
    } else {
      this.eraserChanged = false;
      // The eraser's own full redraw (on pointerup, via changed()) will overwrite any
      // turbo hard ink anyway; clear eagerly so a mid-drag settle timer never fires.
      this.clearPendingSettle();
      this.erase(point);
    }
    event.preventDefault();
  };

  private readonly onPointerMove = (event: PointerEvent): void => {
    if (event.pointerId !== this.activePointer || this.document === null) return;
    const firstNewPointIndex = this.currentStroke?.points.length ?? 0;
    const coalesced = event.getCoalescedEvents?.();
    const samples = coalesced !== undefined && coalesced.length > 0 ? coalesced : [event];
    for (const sample of samples) {
      const point = this.toPoint(sample);
      if (!this.isOnPage(point)) continue;
      if (this.tool === "pen" && this.currentStroke !== null) {
        if (shouldAddPoint(this.currentStroke.points, point)) this.currentStroke.points.push(point);
      } else if (this.tool === "eraser") {
        this.erase(point);
      }
    }
    if (this.currentStroke !== null && this.currentStroke.points.length > firstNewPointIndex) {
      if (this.isEInk()) {
        this.drawTurboIncrement(this.currentStroke, firstNewPointIndex);
      } else {
        this.predictedPoints = this.getPredictedPoints(event);
        this.requestFrame();
      }
    }
    if (this.tool === "pen" && this.currentStroke !== null && event.pointerType === "pen") this.updateInkTrail(event);
    event.preventDefault();
  };

  private readonly onTouchMove = (event: TouchEvent): void => {
    event.preventDefault();
  };

  private readonly onPointerRawUpdate = (event: Event): void => {
    const PointerEventClass = this.canvas.ownerDocument.defaultView?.PointerEvent;
    if (PointerEventClass !== undefined && event instanceof PointerEventClass) this.onPointerMove(event);
  };

  private readonly onPointerUp = (event: PointerEvent): void => {
    if (event.pointerId !== this.activePointer || this.document === null) return;
    if (event.pointerType === "pen") this.penIsDown = false;
    if (this.currentStroke !== null) {
      const firstNewPointIndex = this.currentStroke.points.length;
      const finalPoint = this.toPoint(event);
      const eInk = this.isEInk();
      if (this.isOnPage(finalPoint) && shouldAddPoint(this.currentStroke.points, finalPoint)) {
        this.currentStroke.points.push(finalPoint);
      }
      // Draw the closing segment(s) in one final call, whether or not a new point was
      // pushed above, so the on-screen turbo ink reaches the exact pen-lift point.
      if (eInk) this.drawTurboIncrement(this.currentStroke, firstNewPointIndex);
      this.predictedPoints = [];
      const committed = this.currentStroke;
      this.document.strokes = [...this.document.strokes, committed];
      this.currentStroke = null;
      // Commit the smooth, antialiased render to the cache exactly as today. The
      // visible canvas keeps showing the hard turbo ink until the settle pass later
      // replaces just this stroke's region with the cache's smooth version.
      this.commitStrokeToCache(committed);
      if (eInk) {
        if (this.turboBounds !== null) this.pendingSettleBounds.push(this.turboBounds);
        this.turboBounds = null;
        this.scheduleSettleTimer();
      } else {
        // Non-e-ink strokes are drawn per-frame (cache blit + full redraw); request one
        // final frame so the settled stroke replaces any lingering predicted tail.
        this.requestFrame();
      }
      this.options.onChange();
    } else if (this.tool === "eraser") {
      if (this.eraserChanged) this.changed();
      else this.undoStack.pop();
    }
    this.activePointer = null;
    if (this.canvas.hasPointerCapture(event.pointerId)) this.canvas.releasePointerCapture(event.pointerId);
    event.preventDefault();
  };

  private readonly onKeyDown = (event: KeyboardEvent): void => {
    const modifier = event.metaKey || event.ctrlKey;
    if (modifier && event.key.toLowerCase() === "z") {
      if (event.shiftKey) this.redo();
      else this.undo();
      event.preventDefault();
    } else if (event.key.toLowerCase() === "p") {
      this.setTool("pen");
    } else if (event.key.toLowerCase() === "e") {
      this.setTool("eraser");
    }
  };

  private readonly onWheel = (event: WheelEvent): void => {
    if (!event.ctrlKey && !event.metaKey) return;
    this.setZoom(this.zoom * Math.exp(-event.deltaY * 0.002));
    event.preventDefault();
  };

  private erase(point: InkPoint): void {
    if (this.document === null) return;
    const remaining = this.document.strokes.filter((stroke) => !strokeHitsPoint(stroke, point, ERASER_RADIUS));
    if (remaining.length !== this.document.strokes.length) {
      this.document.strokes = remaining;
      this.eraserChanged = true;
    }
  }

  private pushHistory(): void {
    if (this.document === null) return;
    this.undoStack.push(this.document.strokes);
    if (this.undoStack.length > MAX_HISTORY) this.undoStack.shift();
    this.redoStack = [];
  }

  private changed(): void {
    // undo/redo and eraser commits all trigger a full redraw below, which will
    // overwrite any turbo hard ink still on screen; no need to blit it first.
    this.clearPendingSettle();
    this.invalidateCache();
    this.options.onChange();
  }

  private resize(): void {
    // A resize forces a full redraw at the new dimensions, which supersedes any
    // pending turbo settle.
    this.clearPendingSettle();
    const bounds = this.canvas.getBoundingClientRect();
    this.viewportWidth = Math.max(1, bounds.width);
    this.viewportHeight = Math.max(1, bounds.height);
    const eInkMode = this.isEInk();
    const pixelLimit = eInkMode ? MAX_EINK_DISPLAY_PIXELS : MAX_DISPLAY_PIXELS;
    const densityLimit = eInkMode ? 1 : MAX_DEVICE_PIXEL_RATIO;
    const areaLimitScale = Math.sqrt(pixelLimit / (this.viewportWidth * this.viewportHeight));
    this.deviceScale = Math.min(densityLimit, this.getWindow().devicePixelRatio || 1, areaLimitScale);
    const width = Math.round(this.viewportWidth * this.deviceScale);
    const height = Math.round(this.viewportHeight * this.deviceScale);
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
      this.cacheCanvas.width = width;
      this.cacheCanvas.height = height;
      this.invalidateCache();
    }
  }

  private scheduleResize(): void {
    if (this.resizeFrame !== null) return;
    this.resizeFrame = this.getWindow().requestAnimationFrame(() => {
      this.resizeFrame = null;
      this.resize();
    });
  }

  private getTransform(): { scale: number; offsetX: number; offsetY: number } {
    if (this.document === null) return { scale: 1, offsetX: 0, offsetY: 0 };
    const cover = Math.max(this.viewportWidth / this.document.width, this.viewportHeight / this.document.height);
    const scale = Math.max(0.01, cover * this.zoom);
    return {
      scale,
      offsetX: (this.viewportWidth - this.document.width * scale) / 2,
      offsetY: (this.viewportHeight - this.document.height * scale) / 2,
    };
  }

  private rebuildCache(): void {
    if (this.document === null) return;
    const transform = this.getTransform();
    const palette = this.getPalette();
    renderDocument(this.cacheContext, this.document, {
      scale: transform.scale * this.deviceScale,
      offsetX: transform.offsetX * this.deviceScale,
      offsetY: transform.offsetY * this.deviceScale,
      ...palette,
    });
    this.cacheDirty = false;
  }

  private drawFrame(): void {
    this.frameRequested = false;
    if (this.document === null) return;
    if (this.cacheDirty) this.rebuildCache();
    this.context.setTransform(1, 0, 0, 1, 0, 0);
    this.context.clearRect(0, 0, this.canvas.width, this.canvas.height);
    this.context.drawImage(this.cacheCanvas, 0, 0);
    if (this.currentStroke !== null) {
      const transform = this.getTransform();
      const ink = this.getPalette().ink;
      this.context.save();
      this.context.translate(transform.offsetX * this.deviceScale, transform.offsetY * this.deviceScale);
      this.context.scale(transform.scale * this.deviceScale, transform.scale * this.deviceScale);
      this.context.beginPath();
      this.context.rect(0, 0, this.document.width, this.document.height);
      this.context.clip();
      renderStroke(this.context, this.currentStroke, ink);
      // Predicted points mask input latency by drawing slightly ahead of the pen. They
      // are transient render-only data, never added to the stroke, and are never used
      // on e-ink: a misprediction there would force an extra, costly panel refresh.
      if (!this.isEInk() && this.predictedPoints.length > 0) {
        renderPredictedTail(this.context, this.currentStroke, this.predictedPoints, ink);
      }
      this.context.restore();
    }
  }

  private requestFrame(): void {
    if (this.frameRequested) return;
    this.frameRequested = true;
    this.getWindow().requestAnimationFrame(() => this.drawFrame());
  }

  private invalidateCache(): void {
    this.cacheDirty = true;
    this.requestFrame();
  }

  private commitStrokeToCache(stroke: InkStroke): void {
    if (this.document === null || this.cacheDirty) {
      this.invalidateCache();
      return;
    }
    const transform = this.getTransform();
    this.cacheContext.save();
    this.cacheContext.translate(transform.offsetX * this.deviceScale, transform.offsetY * this.deviceScale);
    this.cacheContext.scale(transform.scale * this.deviceScale, transform.scale * this.deviceScale);
    this.cacheContext.beginPath();
    this.cacheContext.rect(0, 0, this.document.width, this.document.height);
    this.cacheContext.clip();
    renderStroke(this.cacheContext, stroke, this.getPalette().ink);
    this.cacheContext.restore();
  }

  /**
   * Turbo (e-ink) live drawing: stamps hard-edged, fully opaque capsules directly into
   * the visible canvas's pixels for each newly-added point (constant width, no pressure
   * taper — that returns once the stroke settles to the smooth render). No antialiasing:
   * e-ink panels flip pure black/white pixels far faster than partial-coverage gray ones.
   */
  private drawTurboIncrement(stroke: InkStroke, firstNewPointIndex: number): void {
    if (this.document === null) return;
    const transform = this.getTransform();
    const scale = transform.scale * this.deviceScale;
    const offsetX = transform.offsetX * this.deviceScale;
    const offsetY = transform.offsetY * this.deviceScale;
    const radius = (stroke.width * scale) / 2;
    const rgb = parseColorToRgb(this.getPalette().ink);
    const points = stroke.points;
    const startIndex = Math.max(0, firstNewPointIndex);
    for (let index = startIndex; index < points.length; index += 1) {
      const current = points[index];
      if (current === undefined) continue;
      const previous = index > 0 ? points[index - 1] : current;
      if (previous === undefined) continue;
      const x0 = previous.x * scale + offsetX;
      const y0 = previous.y * scale + offsetY;
      const x1 = current.x * scale + offsetX;
      const y1 = current.y * scale + offsetY;
      const rect = segmentDirtyRect(x0, y0, x1, y1, radius, this.canvas.width, this.canvas.height);
      if (rect.width <= 0 || rect.height <= 0) continue;
      const image = this.context.getImageData(rect.x, rect.y, rect.width, rect.height);
      rasterizeSegmentHard(image, x0 - rect.x, y0 - rect.y, x1 - rect.x, y1 - rect.y, radius, rgb);
      this.context.putImageData(image, rect.x, rect.y);
      this.turboBounds = this.turboBounds === null ? rect : unionRect(this.turboBounds, rect);
    }
  }

  /** Resets the trailing settle timer; fires `runSettle` once no stroke has started (or ended) for `SETTLE_DELAY_MS`. */
  private scheduleSettleTimer(): void {
    if (this.settleTimer !== null) this.getWindow().clearTimeout(this.settleTimer);
    this.settleTimer = this.getWindow().setTimeout(() => this.runSettle(), SETTLE_DELAY_MS);
  }

  /** Replaces turbo hard ink with the smooth, antialiased render for every pending region, one small partial repaint per writing pause. */
  private runSettle(): void {
    this.settleTimer = null;
    if (this.pendingSettleBounds.length === 0) return;
    if (this.cacheDirty) this.rebuildCache();
    for (const rect of this.pendingSettleBounds) {
      this.context.drawImage(this.cacheCanvas, rect.x, rect.y, rect.width, rect.height, rect.x, rect.y, rect.width, rect.height);
    }
    this.pendingSettleBounds = [];
  }

  /** Cancels the settle timer and drops any pending regions, for paths that already trigger their own full redraw. */
  private clearPendingSettle(): void {
    this.pendingSettleBounds = [];
    if (this.settleTimer !== null) {
      this.getWindow().clearTimeout(this.settleTimer);
      this.settleTimer = null;
    }
  }

  /**
   * Lazily requests a Chromium `navigator.ink` presenter for delegated ink trails, which
   * mask latency below the compositor without touching our own canvas pixels. Purely
   * additive and feature-detected: absent on iPadOS/WebKit and any other browser without
   * the API, so it is a no-op everywhere else. Any failure disables it for the session.
   */
  private async ensureInkPresenter(): Promise<InkPresenter | null> {
    if (this.inkPresenterDisabled || this.inkPresenter !== null || this.inkPresenterRequestInFlight) return this.inkPresenter;
    const nav = this.getWindow().navigator as NavigatorWithInk;
    if (nav.ink === undefined) {
      this.inkPresenterDisabled = true;
      return null;
    }
    this.inkPresenterRequestInFlight = true;
    try {
      this.inkPresenter = await nav.ink.requestPresenter({ presentationArea: this.canvas });
    } catch {
      this.inkPresenterDisabled = true;
    } finally {
      this.inkPresenterRequestInFlight = false;
    }
    return this.inkPresenter;
  }

  private updateInkTrail(event: PointerEvent): void {
    if (this.inkPresenter === null) return;
    const transform = this.getTransform();
    try {
      this.inkPresenter.updateInkTrailStartPoint(event, {
        color: this.getPalette().ink,
        diameter: this.width * transform.scale,
      });
    } catch {
      this.inkPresenter = null;
      this.inkPresenterDisabled = true;
    }
  }

  private getPalette(): { background: string; guide: string; ink: string } {
    const dark = this.canvas.ownerDocument.body.classList.contains("theme-dark");
    return dark
      ? { background: "#1e1e1e", guide: "#464646", ink: "#ffffff" }
      : { background: "#ffffff", guide: "#dfe3e8", ink: "#111111" };
  }

  private toPoint(event: PointerEvent): InkPoint {
    const transform = this.getTransform();
    return pointFromPointer(event, this.canvas, transform.scale, transform.offsetX, transform.offsetY);
  }

  private isEInk(): boolean {
    return this.options.getEInkMode();
  }

  private getPredictedPoints(event: PointerEvent): InkPoint[] {
    const predicted = event.getPredictedEvents?.() ?? [];
    if (predicted.length === 0) return [];
    const transform = this.getTransform();
    return predicted
      .slice(0, 2)
      .map((sample) => pointFromPointer(sample, this.canvas, transform.scale, transform.offsetX, transform.offsetY));
  }

  private isOnPage(point: InkPoint): boolean {
    return this.document !== null && point.x >= 0 && point.y >= 0 && point.x <= this.document.width && point.y <= this.document.height;
  }

  private getWindow(): Window {
    return this.canvas.ownerDocument.defaultView ?? window;
  }
}

function createStrokeId(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
}

function createDetachedCanvas(host: HTMLElement): HTMLCanvasElement {
  const canvas = host.createEl("canvas");
  canvas.remove();
  return canvas;
}

function unionRect(a: DirtyRect, b: DirtyRect): DirtyRect {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  const right = Math.max(a.x + a.width, b.x + b.width);
  const bottom = Math.max(a.y + a.height, b.y + b.height);
  return { x, y, width: right - x, height: bottom - y };
}

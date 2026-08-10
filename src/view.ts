import { ItemView, Notice, setIcon, TFile, type WorkspaceLeaf } from "obsidian";
import { InkCanvas } from "./ink-canvas";
import { DeleteInkModal } from "./delete-modal";
import type InkFlowPlugin from "./main";
import type { InkAssetPaths } from "./storage";
import { createInkDocument, type InkDocument, type Tool } from "./model";

export const INKFLOW_VIEW_TYPE = "inkflow-view";
const WIDTHS = [3, 5, 8] as const;
const SAVE_DELAY_MS = 120;

export class InkFlowView extends ItemView {
  private inkCanvas: InkCanvas | null = null;
  private currentNote: TFile | null = null;
  private inkDocument: InkDocument | null = null;
  private paths: InkAssetPaths | null = null;
  private saveTimer: number | null = null;
  private saveChain: Promise<void> = Promise.resolve();
  private dirty = false;
  private loading = false;
  private pendingNote: TFile | null = null;
  private noteTitleEl: HTMLElement | null = null;
  private saveStatusEl: HTMLElement | null = null;
  private undoButton: HTMLButtonElement | null = null;
  private redoButton: HTMLButtonElement | null = null;
  private deleteButton: HTMLButtonElement | null = null;
  private toolButtons = new Map<Tool, HTMLButtonElement>();
  private widthButtons = new Map<number, HTMLButtonElement>();
  private hasSavedAsset = false;

  constructor(leaf: WorkspaceLeaf, private readonly plugin: InkFlowPlugin) {
    super(leaf);
    this.navigation = false;
  }

  getViewType(): string {
    return INKFLOW_VIEW_TYPE;
  }

  getDisplayText(): string {
    return "Inkflow";
  }

  getIcon(): string {
    return "pencil";
  }

  async onOpen(): Promise<void> {
    this.contentEl.addClass("inkflow-view");
    this.buildInterface();
    await this.followActiveNote(true);
  }

  async onClose(): Promise<void> {
    this.clearSaveTimer();
    await this.saveNow();
    this.inkCanvas?.destroy();
  }

  async followActiveNote(force = false): Promise<void> {
    if (!force && !this.plugin.settings.autoFollowActiveNote) return;
    const file = this.app.workspace.getActiveFile();
    if (!(file instanceof TFile) || file.extension !== "md" || file === this.currentNote) return;
    await this.openNote(file);
  }

  undo(): void {
    this.inkCanvas?.undo();
    this.updateControls();
  }

  redo(): void {
    this.inkCanvas?.redo();
    this.updateControls();
  }

  handleThemeChange(): void {
    this.inkCanvas?.refreshTheme();
    if ((this.inkDocument?.strokes.length ?? 0) > 0) this.markChanged();
  }

  requestDelete(): void {
    if (this.currentNote === null || this.paths === null || !this.hasHandwriting()) return;
    const noteName = this.currentNote.basename;
    new DeleteInkModal(this.app, noteName, () => this.deleteHandwriting()).open();
  }

  canDelete(): boolean {
    return this.currentNote !== null && this.paths !== null && this.hasHandwriting();
  }

  async handleDeletedNote(notePath: string): Promise<void> {
    if (this.currentNote?.path !== notePath) return;
    this.clearSaveTimer();
    this.currentNote = null;
    this.paths = null;
    this.dirty = false;
    this.hasSavedAsset = false;
    await this.saveChain;
    const empty = createInkDocument("dots");
    this.inkDocument = empty;
    this.inkCanvas?.setDocument(empty);
    this.noteTitleEl?.setText("Inkflow");
    this.setStatus("Open a note");
    this.updateControls();
  }

  private buildInterface(): void {
    this.contentEl.empty();
    const header = this.contentEl.createDiv({ cls: "inkflow-header" });
    this.noteTitleEl = header.createDiv({ cls: "inkflow-title", text: "Inkflow" });
    const headerActions = header.createDiv({ cls: "inkflow-header-actions" });
    this.saveStatusEl = headerActions.createDiv({ cls: "inkflow-status", text: "Open a note" });
    this.deleteButton = this.createIconButton(headerActions, "trash-2", "Delete handwriting", () => this.requestDelete());
    this.deleteButton.addClass("inkflow-delete-button");

    const toolbar = this.contentEl.createDiv({ cls: "inkflow-toolbar", attr: { role: "toolbar", "aria-label": "Handwriting tools" } });
    const tools = toolbar.createDiv({ cls: "inkflow-tool-group" });
    this.toolButtons.set("pen", this.createIconButton(tools, "pencil", "Pen (P)", () => this.inkCanvas?.setTool("pen")));
    this.toolButtons.set("eraser", this.createIconButton(tools, "eraser", "Stroke eraser (E)", () => this.inkCanvas?.setTool("eraser")));

    const widths = toolbar.createDiv({ cls: "inkflow-tool-group inkflow-widths", attr: { "aria-label": "Stroke width" } });
    for (const [index, width] of WIDTHS.entries()) {
      const labels = ["Thin stroke", "Regular stroke", "Bold stroke"];
      const button = widths.createEl("button", {
        cls: `inkflow-width-button inkflow-width-button-${index + 1}`,
        attr: { type: "button", "aria-label": labels[index] ?? "Stroke width" },
      });
      button.createSpan({ cls: "inkflow-width-sample" });
      button.addEventListener("click", () => this.selectWidth(width));
      this.widthButtons.set(width, button);
    }

    const history = toolbar.createDiv({ cls: "inkflow-tool-group" });
    this.undoButton = this.createIconButton(history, "undo", "Undo", () => this.undo());
    this.redoButton = this.createIconButton(history, "redo", "Redo", () => this.redo());

    const stage = this.contentEl.createDiv({ cls: "inkflow-stage" });
    this.inkCanvas = new InkCanvas(stage, {
      getPalmRejection: () => this.plugin.settings.palmRejection,
      onChange: () => this.markChanged(),
      onToolChange: (tool) => this.updateToolButtons(tool),
    });
    this.inkCanvas.setWidth(this.plugin.settings.defaultWidth);
    this.inkCanvas.setTool("pen");
    this.updateWidthButtons(this.plugin.settings.defaultWidth);
    this.updateControls();
  }

  private async openNote(note: TFile): Promise<void> {
    if (this.loading) {
      this.pendingNote = note;
      return;
    }
    this.loading = true;
    try {
      await this.saveNow();
      if (this.dirty) throw new Error("The current handwriting has not been saved");
      this.setStatus("Loading…");
      const loaded = await this.plugin.storage.loadForNote(note, this.plugin.getAssociation(note.path));
      if (loaded.isNew) await this.plugin.dissociate(note.path);
      else await this.plugin.associate(note.path, loaded.paths.source);
      this.currentNote = note;
      this.inkDocument = loaded.document;
      this.paths = loaded.paths;
      this.hasSavedAsset = !loaded.isNew;
      this.dirty = false;
      this.inkCanvas?.setDocument(loaded.document);
      if (this.noteTitleEl !== null) this.noteTitleEl.setText(note.basename);
      this.setStatus(loaded.isNew ? "Ready" : "Saved");
      this.updateControls();
    } catch (error) {
      console.error("InkFlow: unable to open handwriting", error);
      this.setStatus("Could not open handwriting");
      new Notice("Could not open this note's handwriting.");
    } finally {
      this.loading = false;
      const pending = this.pendingNote;
      this.pendingNote = null;
      if (pending !== null && pending !== this.currentNote) void this.openNote(pending);
    }
  }

  private markChanged(): void {
    if (this.inkDocument === null || this.currentNote === null) return;
    this.dirty = true;
    this.setStatus("Saving");
    this.updateControls();
    if (this.saveTimer !== null) return;
    this.saveTimer = window.setTimeout(() => {
      this.saveTimer = null;
      void this.saveNow();
    }, SAVE_DELAY_MS);
  }

  private async saveNow(): Promise<void> {
    this.clearSaveTimer();
    if (!this.dirty || this.currentNote === null || this.inkDocument === null || this.paths === null || this.inkCanvas === null) {
      await this.saveChain;
      return;
    }
    const note = this.currentNote;
    const document = this.inkDocument;
    const paths = this.paths;
    this.dirty = false;
    this.saveChain = this.saveChain.then(async () => {
      try {
        const snapshot = await this.inkCanvas?.exportPng();
        if (snapshot === undefined) return;
        const image = await this.plugin.storage.save(note, paths, document, snapshot);
        await this.plugin.associate(note.path, paths.source);
        this.hasSavedAsset = true;
        this.refreshVisibleEmbeds(image);
        if (!this.dirty && this.currentNote === note) this.setStatus("Saved");
      } catch (error) {
        if (this.currentNote !== note) return;
        this.dirty = true;
        console.error("InkFlow: unable to save handwriting", error);
        this.setStatus("Retrying");
        this.clearSaveTimer();
        this.saveTimer = window.setTimeout(() => {
          this.saveTimer = null;
          void this.saveNow();
        }, 2000);
      }
    });
    await this.saveChain;
  }

  private createIconButton(parent: HTMLElement, icon: string, label: string, action: () => void): HTMLButtonElement {
    const button = parent.createEl("button", { cls: "inkflow-icon-button", attr: { type: "button", "aria-label": label } });
    setIcon(button, icon);
    button.addEventListener("click", action);
    return button;
  }

  private updateToolButtons(active: Tool): void {
    for (const [tool, button] of this.toolButtons) {
      button.toggleClass("is-active", tool === active);
      button.setAttribute("aria-pressed", String(tool === active));
    }
  }

  private updateControls(): void {
    if (this.undoButton !== null) this.undoButton.disabled = !(this.inkCanvas?.canUndo() ?? false);
    if (this.redoButton !== null) this.redoButton.disabled = !(this.inkCanvas?.canRedo() ?? false);
    if (this.deleteButton !== null) this.deleteButton.disabled = !this.hasHandwriting();
  }

  private selectWidth(width: number): void {
    this.plugin.settings.defaultWidth = width;
    this.inkCanvas?.setWidth(width);
    this.updateWidthButtons(width);
    void this.plugin.saveSettings();
  }

  private updateWidthButtons(activeWidth: number): void {
    for (const [width, button] of this.widthButtons) {
      const active = width === activeWidth;
      button.toggleClass("is-active", active);
      button.setAttribute("aria-pressed", String(active));
    }
  }

  private refreshVisibleEmbeds(image: TFile): void {
    const resource = this.app.vault.getResourcePath(image);
    const resourceBase = stripResourceVersion(resource);
    const documents = new Set<Document>([this.contentEl.ownerDocument]);
    this.app.workspace.iterateAllLeaves((leaf) => documents.add(leaf.view.containerEl.ownerDocument));
    for (const ownerDocument of documents) {
      for (const element of ownerDocument.querySelectorAll<HTMLImageElement>("img")) {
        if (stripResourceVersion(element.src) === resourceBase) element.src = resource;
      }
    }
  }

  private hasHandwriting(): boolean {
    return this.hasSavedAsset || (this.inkDocument?.strokes.length ?? 0) > 0;
  }

  private async deleteHandwriting(): Promise<void> {
    if (this.currentNote === null || this.paths === null) return;
    const note = this.currentNote;
    const paths = this.paths;
    this.clearSaveTimer();
    this.currentNote = null;
    this.dirty = false;
    await this.saveChain;
    this.dirty = false;
    try {
      await this.plugin.discardHandwriting(note, paths);
      this.hasSavedAsset = false;
      await this.openNote(note);
      new Notice("Handwriting moved to trash.");
    } catch (error) {
      console.error("InkFlow: unable to delete handwriting", error);
      this.setStatus("Delete failed");
      new Notice("Could not delete this handwriting.");
    }
  }

  private setStatus(status: string): void {
    this.saveStatusEl?.setText(status);
  }

  private clearSaveTimer(): void {
    if (this.saveTimer !== null) {
      window.clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
  }
}

function stripResourceVersion(resource: string): string {
  return resource.split(/[?#]/, 1)[0] ?? resource;
}

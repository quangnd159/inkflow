import { MarkdownView, Notice, Platform, Plugin, TFile, type WorkspaceLeaf } from "obsidian";
import { InkFlowSettingTab, DEFAULT_SETTINGS, type InkFlowSettings } from "./settings";
import { InkStorage, type InkAssetPaths } from "./storage";
import { INKFLOW_VIEW_TYPE, InkFlowView } from "./view";

export default class InkFlowPlugin extends Plugin {
  settings: InkFlowSettings = DEFAULT_SETTINGS;
  storage = new InkStorage(this.app.vault, () => this.settings.attachmentFolder);

  async onload(): Promise<void> {
    await this.loadSettings();
    this.storage = new InkStorage(this.app.vault, () => this.settings.attachmentFolder);
    this.registerView(INKFLOW_VIEW_TYPE, (leaf) => new InkFlowView(leaf, this));
    this.addRibbonIcon("pencil", "Open handwriting", () => {
      void this.activateView();
    });
    this.addCommand({
      id: "open-handwriting",
      name: "Open handwriting for current note",
      checkCallback: (checking) => {
        const available = this.app.workspace.getActiveViewOfType(MarkdownView) !== null;
        if (available && !checking) void this.activateView();
        return available;
      },
    });
    this.addCommand({
      id: "undo-ink-stroke",
      name: "Undo ink stroke",
      callback: () => this.getVisibleView()?.undo(),
    });
    this.addCommand({
      id: "redo-ink-stroke",
      name: "Redo ink stroke",
      callback: () => this.getVisibleView()?.redo(),
    });
    this.addCommand({
      id: "delete-handwriting",
      name: "Delete handwriting from current note",
      checkCallback: (checking) => {
        const view = this.getVisibleView(false);
        if (view === null || !view.canDelete()) return false;
        if (!checking) view.requestDelete();
        return true;
      },
    });
    this.registerEvent(this.app.workspace.on("file-open", (file) => {
      if (!(file instanceof TFile) || file.extension !== "md") return;
      for (const leaf of this.app.workspace.getLeavesOfType(INKFLOW_VIEW_TYPE)) {
        const view = leaf.view;
        if (view instanceof InkFlowView) void view.followActiveNote();
      }
    }));
    this.registerEvent(this.app.vault.on("rename", (file, oldPath) => {
      if (!(file instanceof TFile) || file.extension !== "md") return;
      const source = this.settings.associations[oldPath];
      if (source === undefined) return;
      delete this.settings.associations[oldPath];
      this.settings.associations[file.path] = source;
      void this.saveSettings();
    }));
    this.registerEvent(this.app.vault.on("delete", (file) => {
      if (file instanceof TFile && file.extension === "md") void this.handleDeletedNote(file.path);
    }));
    this.registerEvent(this.app.workspace.on("css-change", () => {
      for (const leaf of this.app.workspace.getLeavesOfType(INKFLOW_VIEW_TYPE)) {
        const view = leaf.view;
        if (view instanceof InkFlowView) view.handleThemeChange();
      }
    }));
    this.addSettingTab(new InkFlowSettingTab(this.app, this));
  }

  async loadSettings(): Promise<void> {
    const saved = (await this.loadData()) as Partial<InkFlowSettings> | null;
    this.settings = {
      ...DEFAULT_SETTINGS,
      ...(saved ?? {}),
      associations: { ...(saved?.associations ?? {}) },
    };
    this.settings.defaultWidth = closestWidth(this.settings.defaultWidth);
  }

  async saveSettings(): Promise<void> {
    await this.saveData(this.settings);
  }

  getAssociation(notePath: string): string | undefined {
    return this.settings.associations[notePath];
  }

  async associate(notePath: string, sourcePath: string): Promise<void> {
    if (this.settings.associations[notePath] === sourcePath) return;
    this.settings.associations[notePath] = sourcePath;
    await this.saveSettings();
  }

  async dissociate(notePath: string): Promise<void> {
    if (this.settings.associations[notePath] === undefined) return;
    delete this.settings.associations[notePath];
    await this.saveSettings();
  }

  async discardHandwriting(note: TFile, paths: InkAssetPaths): Promise<void> {
    await this.storage.removeEmbed(note, paths);
    await this.dissociate(note.path);
    if (!this.isAssetReferenced(paths, note.path)) {
      await this.storage.trashAssets(paths, this.app.fileManager);
    }
  }

  private async activateView(): Promise<void> {
    let leaf = this.app.workspace.getLeavesOfType(INKFLOW_VIEW_TYPE)[0];
    if (leaf === undefined) {
      leaf = this.chooseLeaf();
      await leaf.setViewState({ type: INKFLOW_VIEW_TYPE, active: true });
    }
    await this.app.workspace.revealLeaf(leaf);
    const view = leaf.view;
    if (view instanceof InkFlowView) await view.followActiveNote(true);
  }

  private chooseLeaf(): WorkspaceLeaf {
    if (Platform.isMobile) return this.app.workspace.getLeaf("tab");
    return this.app.workspace.getRightLeaf(true) ?? this.app.workspace.getLeaf("split", "vertical");
  }

  private getVisibleView(showNotice = true): InkFlowView | null {
    const leaf = this.app.workspace.getLeavesOfType(INKFLOW_VIEW_TYPE)[0];
    if (leaf?.view instanceof InkFlowView) return leaf.view;
    if (showNotice) new Notice("Open the handwriting view first.");
    return null;
  }

  private async cleanupDeletedNote(notePath: string): Promise<void> {
    try {
      const source = this.settings.associations[notePath];
      if (source === undefined) return;
      delete this.settings.associations[notePath];
      await this.saveSettings();
      const paths = this.storage.getPathsFromSource(source);
      if (!this.isAssetReferenced(paths, notePath)) {
        await this.storage.trashAssets(paths, this.app.fileManager);
      }
    } catch (error) {
      console.error("InkFlow: unable to clean up deleted note assets", error);
    }
  }

  private async handleDeletedNote(notePath: string): Promise<void> {
    for (const leaf of this.app.workspace.getLeavesOfType(INKFLOW_VIEW_TYPE)) {
      const view = leaf.view;
      if (view instanceof InkFlowView) await view.handleDeletedNote(notePath);
    }
    await this.cleanupDeletedNote(notePath);
  }

  private isAssetReferenced(paths: InkAssetPaths, excludedNotePath: string): boolean {
    if (Object.entries(this.settings.associations).some(([notePath, source]) => notePath !== excludedNotePath && source === paths.source)) return true;
    return Object.entries(this.app.metadataCache.resolvedLinks).some(
      ([notePath, links]) => notePath !== excludedNotePath && (links[paths.snapshot] ?? 0) > 0,
    );
  }
}

function closestWidth(width: number): number {
  const widths = [3, 5, 8];
  return widths.reduce((closest, candidate) => Math.abs(candidate - width) < Math.abs(closest - width) ? candidate : closest, widths[0] ?? 5);
}

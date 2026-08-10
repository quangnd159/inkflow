import { normalizePath, TFile, type FileManager, type Vault } from "obsidian";
import { createInkDocument, parseInkDocument, type InkDocument } from "./model";
import { hashPath, isSafeSourcePath } from "./path-utils";
import { removeEmbedLine } from "./markdown-utils";

const SOURCE_MARKER = /%%\s*inkflow:source=([^\n%]+)\s*%%/;
const LEGACY_SOURCE_MARKER = /<!--\s*inkflow:source=([^\n>]+)\s*-->/;
const SOURCE_MARKER_LINE = /^[ \t]*%%\s*inkflow:source=[^\n%]+\s*%%[ \t]*\r?\n?/m;
const LEGACY_SOURCE_MARKER_LINE = /^[ \t]*<!--\s*inkflow:source=[^\n>]+\s*-->[ \t]*\r?\n?/m;
const IMAGE_EMBED = /!\[\[([^\]\n]+\.png)(?:\|[^\]]+)?\]\]/g;

export interface InkAssetPaths {
  source: string;
  snapshot: string;
}

export interface LoadedInkAsset {
  document: InkDocument;
  paths: InkAssetPaths;
  isNew: boolean;
}

export class InkStorage {
  constructor(
    private readonly vault: Vault,
    private readonly getFolder: () => string,
  ) {}

  async loadForNote(note: TFile, associatedSource?: string): Promise<LoadedInkAsset> {
    const noteContent = await this.vault.cachedRead(note);
    const markerSource = (SOURCE_MARKER.exec(noteContent)?.[1] ?? LEGACY_SOURCE_MARKER.exec(noteContent)?.[1])?.trim();
    const candidate = associatedSource ?? markerSource ?? this.sourceFromEmbed(noteContent);
    const marker = candidate !== undefined && isSafeSourcePath(candidate) ? candidate : undefined;
    const paths = marker === undefined ? this.createPaths(note) : this.pathsFromSource(marker);
    const source = this.vault.getAbstractFileByPath(paths.source);
    if (source instanceof TFile) {
      const document = parseInkDocument(await this.vault.cachedRead(source));
      document.pageStyle = "dots";
      return { document, paths, isNew: false };
    }
    return { document: createInkDocument("dots"), paths, isNew: true };
  }

  async save(note: TFile, paths: InkAssetPaths, document: InkDocument, snapshot: ArrayBuffer): Promise<TFile> {
    await this.ensureFolder(paths.source);
    document.updatedAt = new Date().toISOString();
    const json = `${JSON.stringify(document)}\n`;
    const source = this.vault.getAbstractFileByPath(paths.source);
    if (source instanceof TFile) await this.vault.process(source, () => json);
    else await this.vault.create(paths.source, json);

    const existingImage = this.vault.getAbstractFileByPath(paths.snapshot);
    let image: TFile;
    if (existingImage instanceof TFile) {
      await this.vault.modifyBinary(existingImage, snapshot);
      image = existingImage;
    } else {
      image = await this.vault.createBinary(paths.snapshot, snapshot);
    }

    await this.ensureEmbed(note, paths);
    return image;
  }

  async removeEmbed(note: TFile, paths: InkAssetPaths): Promise<void> {
    const embed = `![[${paths.snapshot}]]`;
    await this.vault.process(note, (content) => removeEmbedLine(content, embed));
  }

  async trashAssets(paths: InkAssetPaths, fileManager: FileManager): Promise<void> {
    for (const path of [paths.snapshot, paths.source]) {
      const file = this.vault.getAbstractFileByPath(path);
      if (file instanceof TFile) await fileManager.trashFile(file);
    }
  }

  getPathsFromSource(source: string): InkAssetPaths {
    return this.pathsFromSource(source);
  }

  private async ensureEmbed(note: TFile, paths: InkAssetPaths): Promise<void> {
    const embed = `![[${paths.snapshot}]]`;
    await this.vault.process(note, (content) => {
      const cleaned = content.replace(LEGACY_SOURCE_MARKER_LINE, "").replace(SOURCE_MARKER_LINE, "");
      if (cleaned.includes(embed)) return cleaned;
      return `${cleaned.trimEnd()}\n\n${embed}\n`;
    });
  }

  private sourceFromEmbed(content: string): string | undefined {
    const folder = `${normalizePath(this.getFolder())}/`;
    for (const match of content.matchAll(IMAGE_EMBED)) {
      const snapshot = match[1];
      if (snapshot !== undefined && snapshot.startsWith(folder)) {
        const source = `${snapshot.slice(0, -4)}.ink.json`;
        if (isSafeSourcePath(source)) return source;
      }
    }
    return undefined;
  }

  private createPaths(note: TFile): InkAssetPaths {
    const folder = normalizePath(this.getFolder());
    const basename = note.basename.replace(/[^\p{L}\p{N}._-]+/gu, "-").replace(/^-+|-+$/g, "") || "note";
    const suffix = hashPath(note.path);
    return this.pathsFromSource(normalizePath(`${folder}/${basename}-${suffix}.ink.json`));
  }

  private pathsFromSource(source: string): InkAssetPaths {
    const normalized = normalizePath(source);
    return {
      source: normalized,
      snapshot: normalized.endsWith(".ink.json") ? `${normalized.slice(0, -9)}.png` : `${normalized}.png`,
    };
  }

  private async ensureFolder(filePath: string): Promise<void> {
    const parts = normalizePath(filePath).split("/").slice(0, -1);
    let current = "";
    for (const part of parts) {
      current = current === "" ? part : `${current}/${part}`;
      if (this.vault.getAbstractFileByPath(current) === null) {
        await this.vault.createFolder(current);
      }
    }
  }
}

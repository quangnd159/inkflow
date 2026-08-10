import { PluginSettingTab, type App, type SettingDefinitionItem } from "obsidian";
import type InkFlowPlugin from "./main";

export interface InkFlowSettings {
  attachmentFolder: string;
  defaultWidth: number;
  palmRejection: boolean;
  eInkMode: boolean;
  autoFollowActiveNote: boolean;
  associations: Record<string, string>;
}

export const DEFAULT_SETTINGS: InkFlowSettings = {
  attachmentFolder: "Attachments/InkFlow",
  defaultWidth: 5,
  palmRejection: true,
  eInkMode: false,
  autoFollowActiveNote: true,
  associations: {},
};

type VisibleSetting = "attachmentFolder" | "palmRejection" | "eInkMode" | "autoFollowActiveNote";

export class InkFlowSettingTab extends PluginSettingTab {
  constructor(app: App, private readonly plugin: InkFlowPlugin) {
    super(app, plugin);
  }

  getSettingDefinitions(): SettingDefinitionItem<VisibleSetting>[] {
    return [
      {
        name: "Attachment folder",
        desc: "Editable ink and PNG snapshots are stored here.",
        control: {
          type: "folder",
          key: "attachmentFolder",
          defaultValue: DEFAULT_SETTINGS.attachmentFolder,
          placeholder: DEFAULT_SETTINGS.attachmentFolder,
          validate: (value) => value.trim() === "" ? "Choose a folder inside your vault." : undefined,
        },
      },
      {
        name: "Palm rejection",
        desc: "Ignore finger touches while a pen is active. Recommended for Apple Pencil and Boox Pen.",
        control: {
          type: "toggle",
          key: "palmRejection",
          defaultValue: DEFAULT_SETTINGS.palmRejection,
        },
      },
      {
        name: "E-ink mode",
        desc: "Reduce display work and defer snapshots for e-ink devices. Slow-update displays are also detected automatically.",
        control: {
          type: "toggle",
          key: "eInkMode",
          defaultValue: DEFAULT_SETTINGS.eInkMode,
        },
      },
      {
        name: "Follow active note",
        desc: "Switch the handwriting canvas when you open another Markdown note.",
        control: {
          type: "toggle",
          key: "autoFollowActiveNote",
          defaultValue: DEFAULT_SETTINGS.autoFollowActiveNote,
        },
      },
    ];
  }

  getControlValue(key: string): unknown {
    if (key === "attachmentFolder" || key === "palmRejection" || key === "eInkMode" || key === "autoFollowActiveNote") {
      return this.plugin.settings[key];
    }
    return undefined;
  }

  async setControlValue(key: string, value: unknown): Promise<void> {
    if (key === "attachmentFolder" && typeof value === "string") {
      this.plugin.settings.attachmentFolder = value.trim().replace(/^\/+|\/+$/g, "") || DEFAULT_SETTINGS.attachmentFolder;
    } else if (key === "palmRejection" && typeof value === "boolean") {
      this.plugin.settings.palmRejection = value;
    } else if (key === "eInkMode" && typeof value === "boolean") {
      this.plugin.settings.eInkMode = value;
      this.plugin.refreshCanvasPerformance();
    } else if (key === "autoFollowActiveNote" && typeof value === "boolean") {
      this.plugin.settings.autoFollowActiveNote = value;
    } else {
      return;
    }
    await this.plugin.saveSettings();
  }
}

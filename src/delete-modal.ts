import { ButtonComponent, Modal, type App } from "obsidian";

export class DeleteInkModal extends Modal {
  constructor(
    app: App,
    private readonly noteName: string,
    private readonly onConfirm: () => Promise<void>,
  ) {
    super(app);
  }

  onOpen(): void {
    this.titleEl.setText("Delete handwriting?");
    this.contentEl.createEl("p", {
      text: `Remove the handwriting from “${this.noteName}”? The image and editable ink will be moved to your configured trash.`,
    });
    const buttons = this.contentEl.createDiv({ cls: "modal-button-container" });
    new ButtonComponent(buttons).setButtonText("Cancel").onClick(() => this.close());
    const remove = new ButtonComponent(buttons)
      .setButtonText("Delete handwriting")
      .setDestructive()
      .setCta()
      .onClick(() => {
        remove.setDisabled(true);
        void this.confirm();
      });
  }

  private async confirm(): Promise<void> {
    await this.onConfirm();
    this.close();
  }
}

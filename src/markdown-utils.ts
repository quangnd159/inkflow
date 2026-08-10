export function removeEmbedLine(content: string, target: string): string {
  const lines = content.split("\n");
  const index = lines.findIndex((line) => line.trim() === target);
  if (index === -1) return content;
  lines.splice(index, 1);
  if (index > 0 && index < lines.length && lines[index - 1]?.trim() === "" && lines[index]?.trim() === "") {
    lines.splice(index, 1);
  }
  return lines.join("\n");
}

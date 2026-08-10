export function hashPath(path: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < path.length; index += 1) {
    hash ^= path.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(36).padStart(7, "0");
}

export function isSafeSourcePath(path: string): boolean {
  const segments = path.replace(/\\/g, "/").split("/");
  return (
    path.length > 9 &&
    path.length < 1000 &&
    !path.startsWith("/") &&
    path.endsWith(".ink.json") &&
    segments.every((segment) => segment !== "" && segment !== "." && segment !== "..")
  );
}

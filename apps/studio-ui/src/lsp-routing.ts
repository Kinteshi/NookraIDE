export type ServerId = "basedpyright" | "clangd" | "rust-analyzer" | "vtsls";

export function serverForLanguage(language: string): ServerId | null {
  switch (language) {
    case "python":
      return "basedpyright";
    case "c":
    case "cpp":
      return "clangd";
    case "rust":
      return "rust-analyzer";
    case "javascript":
    case "typescript":
      return "vtsls";
    default:
      return null;
  }
}

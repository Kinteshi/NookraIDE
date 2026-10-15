import { create } from "zustand";

export type WorkspaceEntry = {
  name: string;
  path: string;
  is_dir: boolean;
  is_symlink: boolean;
  size: number;
  modified_ts: number;
};

export type EditorDocument = {
  path: string;
  name: string;
  language: string;
  value: string;
  savedValue: string;
  modifiedTs: number;
};

type StudioState = {
  workspaceRoot: string;
  directories: Record<string, WorkspaceEntry[]>;
  expanded: string[];
  tabs: EditorDocument[];
  activePath: string | null;
  splitPath: string | null;
  splitDirection: "horizontal" | "vertical";
  sidebarOpen: boolean;
  setWorkspaceRoot: (path: string) => void;
  setSidebarOpen: (open: boolean) => void;
  setDirectory: (path: string, entries: WorkspaceEntry[]) => void;
  toggleExpanded: (path: string) => void;
  openDocument: (document: EditorDocument) => void;
  setActivePath: (path: string) => void;
  setSplitPath: (path: string | null) => void;
  setSplitDirection: (direction: "horizontal" | "vertical") => void;
  updateDocument: (path: string, value: string) => void;
  markSaved: (path: string, value: string, modifiedTs: number) => void;
  closeDocument: (path: string) => void;
  clearWorkspace: () => void;
};

export const useStudioStore = create<StudioState>((set) => ({
  workspaceRoot: "",
  directories: {},
  expanded: [],
  tabs: [],
  activePath: null,
  splitPath: null,
  splitDirection: "horizontal",
  sidebarOpen: true,
  setWorkspaceRoot: (workspaceRoot) =>
    set((state) => ({
      workspaceRoot,
      directories:
        state.workspaceRoot === workspaceRoot
          ? state.directories
          : Object.fromEntries(
              Object.entries(state.directories).filter(
                ([path]) => path === workspaceRoot || path.startsWith(`${workspaceRoot}/`),
              ),
            ),
      expanded:
        state.workspaceRoot === workspaceRoot
          ? state.expanded
          : state.expanded.filter(
              (path) => path === workspaceRoot || path.startsWith(`${workspaceRoot}/`),
            ),
    })),
  setSidebarOpen: (sidebarOpen) => set({ sidebarOpen }),
  setDirectory: (path, entries) =>
    set((state) => ({ directories: { ...state.directories, [path]: entries } })),
  toggleExpanded: (path) =>
    set((state) => ({
      expanded: state.expanded.includes(path)
        ? state.expanded.filter((item) => item !== path)
        : [...state.expanded, path],
    })),
  openDocument: (document) =>
    set((state) => {
      if (state.tabs.some((tab) => tab.path === document.path)) {
        return { activePath: document.path };
      }
      return { tabs: [...state.tabs, document], activePath: document.path };
    }),
  setActivePath: (activePath) => set({ activePath }),
  setSplitPath: (splitPath) => set({ splitPath }),
  setSplitDirection: (splitDirection) => set({ splitDirection }),
  updateDocument: (path, value) =>
    set((state) => ({
      tabs: state.tabs.map((tab) =>
        tab.path === path ? { ...tab, value } : tab,
      ),
    })),
  markSaved: (path, value, modifiedTs) =>
    set((state) => ({
      tabs: state.tabs.map((tab) =>
        tab.path === path
          ? { ...tab, value, savedValue: value, modifiedTs }
          : tab,
      ),
    })),
  closeDocument: (path) =>
    set((state) => {
      const index = state.tabs.findIndex((tab) => tab.path === path);
      const tabs = state.tabs.filter((tab) => tab.path !== path);
      const activePath =
        state.activePath === path
          ? tabs[Math.min(index, tabs.length - 1)]?.path ?? null
          : state.activePath;
      const splitPath = state.splitPath === path ? null : state.splitPath;
      return { tabs, activePath, splitPath };
    }),
  clearWorkspace: () =>
    set({
      workspaceRoot: "",
      directories: {},
      expanded: [],
      tabs: [],
      activePath: null,
      splitPath: null,
      splitDirection: "horizontal",
      sidebarOpen: true,
    }),
}));

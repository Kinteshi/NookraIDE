package io.github.rosemoe.sora.widget;

import androidx.annotation.Nullable;

import io.github.rosemoe.sora.widget.layout.Layout;

/** Sets sora's package-private layout, so MaterialCode can put its folding layout in. */
public final class EditorLayoutAccess {
    private EditorLayoutAccess() {
    }

    @Nullable
    public static Layout get(CodeEditor editor) {
        return editor.layout;
    }

    public static void set(CodeEditor editor, Layout layout) {
        editor.layout = layout;
    }
}

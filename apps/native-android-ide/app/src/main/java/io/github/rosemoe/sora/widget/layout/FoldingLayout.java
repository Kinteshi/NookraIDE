package io.github.rosemoe.sora.widget.layout;

import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.avfstudio.nativeide.editor.FoldState;

import java.util.NoSuchElementException;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * MaterialCode's code folding for sora-editor 0.23.6, which has none: the line-break layout with
 * the folded lines left out of its rows. The renderer, the cursor, Up/Down and taps all go
 * through the layout, so a hidden line is never drawn or reached; a folded line's position is
 * its fold's first line. It lives in sora's package to build on {@link LineBreakLayout}.
 */
public class FoldingLayout extends LineBreakLayout {
    private final FoldState folds;
    @Nullable
    private final Runnable onFoldsChanged;

    public FoldingLayout(@NonNull CodeEditor editor, @NonNull Content text, @NonNull FoldState folds, @Nullable Runnable onFoldsChanged) {
        super(editor, text);
        this.folds = folds;
        this.onFoldsChanged = onFoldsChanged;
    }

    private int lastRow() {
        return Math.max(0, text.getLineCount() - folds.getHiddenLineCount() - 1);
    }

    private int lineForRow(int row) {
        return Math.min(text.getLineCount() - 1, folds.lineForRow(Math.max(0, Math.min(row, lastRow()))));
    }

    @Override
    public int getRowCount() {
        return lastRow() + 1;
    }

    @Override
    public int getLineNumberForRow(int row) {
        return lineForRow(row);
    }

    @NonNull
    @Override
    public Row getRowAt(int rowIndex) {
        var row = new Row();
        row.lineIndex = lineForRow(rowIndex);
        row.startColumn = 0;
        row.isLeadingRow = true;
        row.endColumn = text.getColumnCount(row.lineIndex);
        return row;
    }

    @NonNull
    @Override
    public RowIterator obtainRowIterator(int initialRow, @Nullable SparseArray<ContentLine> preloadedLines) {
        return new FoldingRowIterator(initialRow, preloadedLines);
    }

    @Override
    public int getRowIndexForPosition(int index) {
        return folds.rowForLine(editor.getText().getIndexer().getCharPosition(index).line);
    }

    @Override
    public int getLayoutHeight() {
        return getRowCount() * editor.getRowHeight();
    }

    @Override
    public long getCharPositionForLayoutOffset(float xOffset, float yOffset) {
        int line = lineForRow((int) (yOffset / editor.getRowHeight()));
        int column = BidiLayout.horizontalIndex(editor, this, text, line, 0, text.getColumnCount(line), xOffset);
        return IntPair.pack(line, column);
    }

    @NonNull
    @Override
    public float[] getCharLayoutOffset(int line, int column, float[] dest) {
        if (dest == null || dest.length < 2) {
            dest = new float[2];
        }
        int shown = line;
        if (folds.isHidden(line)) {
            // A folded line sits at the end of its fold's first line.
            shown = folds.visibleLineAtOrBefore(line);
            column = text.getColumnCount(shown);
        }
        dest[0] = editor.getRowBottom(folds.rowForLine(shown));
        dest[1] = BidiLayout.horizontalOffset(editor, this, text, shown, 0, text.getColumnCount(shown), column);
        return dest;
    }

    @Override
    public long getDownPosition(int line, int column) {
        int row = folds.rowForLine(line);
        if (row >= lastRow()) {
            int last = text.getLineCount() - 1;
            return IntPair.pack(last, text.getColumnCount(last));
        }
        int next = lineForRow(row + 1);
        return IntPair.pack(next, Math.min(column, text.getColumnCount(next)));
    }

    @Override
    public long getUpPosition(int line, int column) {
        int row = folds.rowForLine(line);
        if (row <= 0) {
            return IntPair.pack(0, 0);
        }
        int previous = lineForRow(row - 1);
        return IntPair.pack(previous, Math.min(column, text.getColumnCount(previous)));
    }

    @Override
    public void afterInsert(@NonNull Content content, int startLine, int startColumn, int endLine, int endColumn, @NonNull CharSequence insertedContent) {
        super.afterInsert(content, startLine, startColumn, endLine, endColumn, insertedContent);
        if (folds.afterEdit(startLine, endLine - startLine) && onFoldsChanged != null) {
            onFoldsChanged.run();
        }
    }

    @Override
    public void afterDelete(@NonNull Content content, int startLine, int startColumn, int endLine, int endColumn, @NonNull CharSequence deletedContent) {
        super.afterDelete(content, startLine, startColumn, endLine, endColumn, deletedContent);
        if (folds.afterEdit(startLine, startLine - endLine) && onFoldsChanged != null) {
            onFoldsChanged.run();
        }
    }

    private class FoldingRowIterator implements RowIterator {
        private final Row result = new Row();
        private final int initialRow;
        @Nullable
        private final SparseArray<ContentLine> preloadedLines;
        private int currentRow;

        FoldingRowIterator(int initialRow, @Nullable SparseArray<ContentLine> preloadedLines) {
            this.initialRow = currentRow = initialRow;
            this.preloadedLines = preloadedLines;
            result.isLeadingRow = true;
            result.startColumn = 0;
        }

        @NonNull
        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            result.lineIndex = folds.lineForRow(currentRow);
            var line = preloadedLines != null ? preloadedLines.get(result.lineIndex) : null;
            if (line == null) {
                line = text.getLine(result.lineIndex);
            }
            result.endColumn = line.length();
            currentRow++;
            return result;
        }

        @Override
        public boolean hasNext() {
            return currentRow >= 0 && currentRow <= lastRow() && folds.lineForRow(currentRow) < text.getLineCount();
        }

        @Override
        public void reset() {
            currentRow = initialRow;
        }
    }
}

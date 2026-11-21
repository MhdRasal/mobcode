package com.termux.terminal;

import java.util.ArrayList;

/**
 * Screen buffer maintaining active screen rows and scrollback history.
 */
public final class TerminalBuffer {
    public int mColumns;
    public int mScreenRows;
    public int mTotalRows;
    public final int mMaxHistory;

    private final ArrayList<TerminalRow> mHistory = new ArrayList<>();
    private TerminalRow[] mScreen;
    private long mDefaultStyle;

    public TerminalBuffer(int columns, int screenRows, int maxHistory) {
        mColumns = columns;
        mScreenRows = screenRows;
        mMaxHistory = maxHistory;
        mDefaultStyle = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND, 0);
        mScreen = new TerminalRow[screenRows];
        for (int i = 0; i < screenRows; i++) {
            mScreen[i] = new TerminalRow(columns, mDefaultStyle);
        }
    }

    public synchronized void scrollDownOneLine() {
        if (mScreenRows <= 0) return;
        TerminalRow topRow = mScreen[0];
        if (mMaxHistory > 0) {
            if (mHistory.size() >= mMaxHistory) {
                mHistory.remove(0);
            }
            mHistory.add(topRow);
        }
        for (int i = 0; i < mScreenRows - 1; i++) {
            mScreen[i] = mScreen[i + 1];
        }
        mScreen[mScreenRows - 1] = new TerminalRow(mColumns, mDefaultStyle);
    }

    public synchronized void scrollUpRegion(int top, int bottom, long style) {
        top = clamp(top, 0, mScreenRows - 1);
        bottom = clamp(bottom, 0, mScreenRows - 1);
        if (top >= bottom) {
            mScreen[top].clear(style);
            return;
        }
        for (int i = top; i < bottom; i++) {
            mScreen[i] = mScreen[i + 1];
        }
        mScreen[bottom] = new TerminalRow(mColumns, style);
    }

    public synchronized void scrollDownRegion(int top, int bottom, long style) {
        top = clamp(top, 0, mScreenRows - 1);
        bottom = clamp(bottom, 0, mScreenRows - 1);
        if (top >= bottom) {
            mScreen[top].clear(style);
            return;
        }
        for (int i = bottom; i > top; i--) {
            mScreen[i] = mScreen[i - 1];
        }
        mScreen[top] = new TerminalRow(mColumns, style);
    }

    public synchronized void insertLines(int top, int bottom, int count, long style) {
        top = clamp(top, 0, mScreenRows - 1);
        bottom = clamp(bottom, 0, mScreenRows - 1);
        if (top > bottom || count <= 0) return;
        count = Math.min(count, bottom - top + 1);
        for (int i = bottom; i >= top + count; i--) {
            mScreen[i] = mScreen[i - count];
        }
        for (int i = top; i < top + count; i++) {
            mScreen[i] = new TerminalRow(mColumns, style);
        }
    }

    public synchronized void deleteLines(int top, int bottom, int count, long style) {
        top = clamp(top, 0, mScreenRows - 1);
        bottom = clamp(bottom, 0, mScreenRows - 1);
        if (top > bottom || count <= 0) return;
        count = Math.min(count, bottom - top + 1);
        for (int i = top; i <= bottom - count; i++) {
            mScreen[i] = mScreen[i + count];
        }
        for (int i = bottom - count + 1; i <= bottom; i++) {
            mScreen[i] = new TerminalRow(mColumns, style);
        }
    }

    public synchronized void insertChars(int row, int col, int count, long style) {
        if (row < 0 || row >= mScreenRows || count <= 0) return;
        col = clamp(col, 0, mColumns - 1);
        count = Math.min(count, mColumns - col);
        TerminalRow line = mScreen[row];
        System.arraycopy(line.mText, col, line.mText, col + count, mColumns - col - count);
        System.arraycopy(line.mStyle, col, line.mStyle, col + count, mColumns - col - count);
        for (int c = col; c < col + count; c++) {
            line.setChar(c, ' ', style);
        }
    }

    public synchronized void deleteChars(int row, int col, int count, long style) {
        if (row < 0 || row >= mScreenRows || count <= 0) return;
        col = clamp(col, 0, mColumns - 1);
        count = Math.min(count, mColumns - col);
        TerminalRow line = mScreen[row];
        System.arraycopy(line.mText, col + count, line.mText, col, mColumns - col - count);
        System.arraycopy(line.mStyle, col + count, line.mStyle, col, mColumns - col - count);
        for (int c = mColumns - count; c < mColumns; c++) {
            line.setChar(c, ' ', style);
        }
    }

    public synchronized void clearHistory() {
        mHistory.clear();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public synchronized TerminalRow getScreenRow(int row) {
        if (row >= 0 && row < mScreenRows) {
            return mScreen[row];
        }
        return null;
    }

    public synchronized TerminalRow getRowAt(int topRow, int screenRow) {
        int virtualRow = topRow + screenRow;
        if (virtualRow < 0) {
            int histIdx = mHistory.size() + virtualRow;
            if (histIdx >= 0 && histIdx < mHistory.size()) {
                return mHistory.get(histIdx);
            }
            return null;
        } else if (virtualRow < mScreenRows) {
            return mScreen[virtualRow];
        }
        return null;
    }

    public synchronized TerminalRow getHistoryRow(int historyIndex) {
        if (historyIndex >= 0 && historyIndex < mHistory.size()) {
            return mHistory.get(historyIndex);
        }
        return null;
    }

    public synchronized int getHistorySize() {
        return mHistory.size();
    }

    public synchronized void clear(long defaultStyle) {
        mDefaultStyle = defaultStyle;
        for (int i = 0; i < mScreenRows; i++) {
            mScreen[i].clear(defaultStyle);
        }
    }

    public synchronized void resize(int newColumns, int newRows) {
        if (newColumns == mColumns && newRows == mScreenRows) return;
        TerminalRow[] newScreen = new TerminalRow[newRows];
        for (int i = 0; i < newRows; i++) {
            if (i < mScreenRows) {
                // Copy existing row content up to new columns
                TerminalRow oldRow = mScreen[i];
                TerminalRow newRow = new TerminalRow(newColumns, mDefaultStyle);
                int copyLen = Math.min(oldRow.mColumns, newColumns);
                for (int c = 0; c < copyLen; c++) {
                    newRow.setChar(c, oldRow.getChar(c), oldRow.getStyle(c));
                }
                newScreen[i] = newRow;
            } else {
                newScreen[i] = new TerminalRow(newColumns, mDefaultStyle);
            }
        }
        mColumns = newColumns;
        mScreenRows = newRows;
        mScreen = newScreen;
    }
}

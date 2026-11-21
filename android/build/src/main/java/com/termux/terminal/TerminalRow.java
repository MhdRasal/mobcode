package com.termux.terminal;

import java.util.Arrays;

/**
 * A single row of characters and styles in the terminal grid.
 */
public final class TerminalRow {
    public final char[] mText;
    public final long[] mStyle;
    public final int mColumns;

    public TerminalRow(int columns, long defaultStyle) {
        mColumns = columns;
        mText = new char[columns];
        mStyle = new long[columns];
        clear(defaultStyle);
    }

    public void clear(long style) {
        Arrays.fill(mText, ' ');
        Arrays.fill(mStyle, style);
    }

    public void setChar(int column, char c, long style) {
        if (column >= 0 && column < mColumns) {
            mText[column] = c;
            mStyle[column] = style;
        }
    }

    public char getChar(int column) {
        if (column >= 0 && column < mColumns) {
            return mText[column];
        }
        return ' ';
    }

    public long getStyle(int column) {
        if (column >= 0 && column < mColumns) {
            return mStyle[column];
        }
        return 0;
    }
}

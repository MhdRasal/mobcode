package com.termux.terminal;

/**
 * ANSI / VT100 / Xterm terminal emulator and escape sequence parser
 * supporting UTF-8 and 24-bit TrueColor.
 */
public final class TerminalEmulator {
    public int mColumns;
    public int mRows;
    public int mCursorCol = 0;
    public int mCursorRow = 0;
    public boolean mCursorVisible = true;

    public TerminalBuffer mScreenBuffer;
    public final TerminalColorScheme mColorScheme;

    private TerminalBuffer mPrimaryBuffer;
    private boolean mUsingAltScreen;
    private int mAltSavedCursorCol;
    private int mAltSavedCursorRow;

    private int mCurrentForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
    private int mCurrentBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
    private int mCurrentEffect = 0;

    private int mSavedCursorCol;
    private int mSavedCursorRow;
    private int mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
    private int mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
    private int mSavedEffect = 0;
    private boolean mHasSavedCursor;

    private int mScrollTop;
    private int mScrollBottom;
    private boolean mAutoWrap = true;
    private boolean mMouseTracking = false;
    private boolean mMouseSgr = false;

    private static final int STATE_GROUND = 0;
    private static final int STATE_ESCAPE = 1;
    private static final int STATE_ESCAPE_INTERMEDIATE = 2;
    private static final int STATE_CSI = 3;
    private static final int STATE_OSC = 4;
    private static final int STATE_OSC_ESCAPE = 5;
    private static final int STATE_STRING = 6;
    private static final int STATE_STRING_ESCAPE = 7;

    private int mState = STATE_GROUND;
    private final StringBuilder mParams = new StringBuilder();
    private char mPrivatePrefix;
    private char mIntermediate;

    private int mUtf8Remaining;
    private int mUtf8CodePoint;
    private int mUtf8Min;

    public TerminalEmulator(int columns, int rows, int maxHistory) {
        mColumns = columns;
        mRows = rows;
        mColorScheme = new TerminalColorScheme();
        mScreenBuffer = new TerminalBuffer(columns, rows, maxHistory);
        mScrollTop = 0;
        mScrollBottom = Math.max(0, rows - 1);
    }

    private long getCurrentStyle() {
        return TextStyle.encode(mCurrentForeColor, mCurrentBackColor, mCurrentEffect);
    }

    public synchronized void append(byte[] buffer, int length) {
        if (buffer == null || length <= 0) return;
        int end = Math.min(length, buffer.length);
        for (int i = 0; i < end; i++) {
            decodeUtf8(buffer[i] & 0xFF);
        }
    }

    private void decodeUtf8(int b) {
        if (mUtf8Remaining == 0) {
            if (b < 0x80) {
                processCodePoint(b);
            } else if (b >= 0xC2 && b <= 0xDF) {
                mUtf8CodePoint = b & 0x1F;
                mUtf8Remaining = 1;
                mUtf8Min = 0x80;
            } else if (b >= 0xE0 && b <= 0xEF) {
                mUtf8CodePoint = b & 0x0F;
                mUtf8Remaining = 2;
                mUtf8Min = 0x800;
            } else if (b >= 0xF0 && b <= 0xF4) {
                mUtf8CodePoint = b & 0x07;
                mUtf8Remaining = 3;
                mUtf8Min = 0x10000;
            } else {
                processCodePoint(0xFFFD);
            }
            return;
        }

        if ((b & 0xC0) == 0x80) {
            mUtf8CodePoint = (mUtf8CodePoint << 6) | (b & 0x3F);
            mUtf8Remaining--;
            if (mUtf8Remaining == 0) {
                int cp = mUtf8CodePoint;
                if (cp < mUtf8Min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                    processCodePoint(0xFFFD);
                } else {
                    processCodePoint(cp);
                }
            }
            return;
        }

        mUtf8Remaining = 0;
        processCodePoint(0xFFFD);
        decodeUtf8(b);
    }

    private void processCodePoint(int cp) {
        switch (mState) {
            case STATE_GROUND:
                processGround(cp);
                break;
            case STATE_ESCAPE:
                processEscape(cp);
                break;
            case STATE_ESCAPE_INTERMEDIATE:
                if (cp == 27) {
                    mState = STATE_ESCAPE;
                } else if (cp >= 0x20 && cp <= 0x2F) {
                    break;
                } else {
                    mState = STATE_GROUND;
                }
                break;
            case STATE_CSI:
                processCsi(cp);
                break;
            case STATE_OSC:
                if (cp == 7 || cp == 0x9C) {
                    mState = STATE_GROUND;
                } else if (cp == 27) {
                    mState = STATE_OSC_ESCAPE;
                }
                break;
            case STATE_OSC_ESCAPE:
                if (cp == '\\') {
                    mState = STATE_GROUND;
                } else if (cp != 27) {
                    mState = STATE_OSC;
                }
                break;
            case STATE_STRING:
                if (cp == 7 || cp == 0x9C) {
                    mState = STATE_GROUND;
                } else if (cp == 27) {
                    mState = STATE_STRING_ESCAPE;
                }
                break;
            case STATE_STRING_ESCAPE:
                if (cp == '\\') {
                    mState = STATE_GROUND;
                } else if (cp != 27) {
                    mState = STATE_STRING;
                }
                break;
        }
    }

    private void processGround(int cp) {
        if (cp == 27) {
            mState = STATE_ESCAPE;
        } else if (cp == '\r') {
            mCursorCol = 0;
        } else if (cp == '\n' || cp == 0x0B || cp == 0x0C) {
            lineFeed();
        } else if (cp == '\b') {
            if (mCursorCol > 0) mCursorCol--;
        } else if (cp == '\t') {
            tabForward(1);
        } else if (cp == 7 || cp == 0 || cp == 0x0E || cp == 0x0F) {
            return;
        } else if (cp >= 32 && cp != 0x7F) {
            emitCodePoint(cp);
        }
    }

    private void processEscape(int cp) {
        if (cp == '[') {
            beginCsi();
        } else if (cp == ']') {
            mState = STATE_OSC;
        } else if (cp == 'P' || cp == 'X' || cp == '^' || cp == '_') {
            mState = STATE_STRING;
        } else if (cp >= 0x20 && cp <= 0x2F) {
            mState = STATE_ESCAPE_INTERMEDIATE;
        } else {
            mState = STATE_GROUND;
            if (cp == '7') {
                saveCursor();
            } else if (cp == '8') {
                restoreCursor();
            } else if (cp == 'D') {
                lineFeed();
            } else if (cp == 'E') {
                mCursorCol = 0;
                lineFeed();
            } else if (cp == 'M') {
                reverseIndex();
            } else if (cp == 'c') {
                resetTerminal();
            }
        }
    }

    private void beginCsi() {
        mState = STATE_CSI;
        mParams.setLength(0);
        mPrivatePrefix = 0;
        mIntermediate = 0;
    }

    private void processCsi(int cp) {
        if (cp == 27) {
            mState = STATE_ESCAPE;
        } else if (cp == 0x18 || cp == 0x1A) {
            mState = STATE_GROUND;
        } else if (cp < 0x20) {
            processControl(cp);
        } else if (cp >= 0x40 && cp <= 0x7E) {
            executeCsi((char) cp);
            mState = STATE_GROUND;
        } else if (cp >= 0x20 && cp <= 0x2F) {
            mIntermediate = (char) cp;
        } else if (cp >= 0x30 && cp <= 0x3F) {
            if (mParams.length() == 0 && (cp == '?' || cp == '>' || cp == '=' || cp == '<')) {
                mPrivatePrefix = (char) cp;
            } else if (mParams.length() < 256) {
                mParams.append((char) cp);
            }
        } else if (cp != 0x7F) {
            mState = STATE_GROUND;
            emitCodePoint(cp);
        }
    }

    private void processControl(int cp) {
        if (cp == '\r') {
            mCursorCol = 0;
        } else if (cp == '\n' || cp == 0x0B || cp == 0x0C) {
            lineFeed();
        } else if (cp == '\b') {
            if (mCursorCol > 0) mCursorCol--;
        } else if (cp == '\t') {
            tabForward(1);
        }
    }

    private int[] parseArgs() {
        if (mParams.length() == 0) return new int[0];
        String[] parts = mParams.toString().split(";", -1);
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private void executeCsi(char command) {
        int[] args = parseArgs();
        int arg0 = args.length > 0 ? args[0] : 0;
        int arg1 = args.length > 1 ? args[1] : 0;

        if (mIntermediate != 0) {
            return;
        }

        if (mPrivatePrefix != 0) {
            if (command == 'h' || command == 'l') {
                applyPrivateModes(args, command == 'h');
            }
            return;
        }

        int count = arg0 == 0 ? 1 : arg0;
        switch (command) {
            case 'A':
                setCursor(mCursorRow - count, mCursorCol);
                break;
            case 'B':
                setCursor(mCursorRow + count, mCursorCol);
                break;
            case 'C':
                setCursor(mCursorRow, mCursorCol + count);
                break;
            case 'D':
                setCursor(mCursorRow, mCursorCol - count);
                break;
            case 'E':
                setCursor(mCursorRow + count, 0);
                break;
            case 'F':
                setCursor(mCursorRow - count, 0);
                break;
            case 'G':
                setCursor(mCursorRow, (arg0 == 0 ? 1 : arg0) - 1);
                break;
            case 'H':
            case 'f':
                setCursor((arg0 == 0 ? 1 : arg0) - 1, (arg1 == 0 ? 1 : arg1) - 1);
                break;
            case 'd':
                setCursor((arg0 == 0 ? 1 : arg0) - 1, mCursorCol);
                break;
            case 'J':
                eraseInDisplay(arg0);
                break;
            case 'K':
                eraseInLine(arg0);
                break;
            case 'L':
                insertLines(count);
                break;
            case 'M':
                deleteLines(count);
                break;
            case 'P':
                deleteChars(count);
                break;
            case '@':
                insertChars(count);
                break;
            case 'X':
                eraseChars(count);
                break;
            case 'S':
                scrollUp(count);
                break;
            case 'T':
                scrollDown(count);
                break;
            case 'r':
                setScrollRegion(args);
                break;
            case 'm':
                handleSgr(args);
                break;
            case 's':
                saveCursor();
                break;
            case 'u':
                restoreCursor();
                break;
        }
    }

    private void applyPrivateModes(int[] args, boolean enable) {
        for (int mode : args) {
            if (mode == 25) {
                mCursorVisible = enable;
            } else if (mode == 7) {
                mAutoWrap = enable;
            } else if (mode == 47 || mode == 1047 || mode == 1049) {
                setAltScreen(enable);
            } else if (mode == 1000 || mode == 1002 || mode == 1003) {
                mMouseTracking = enable;
            } else if (mode == 1006) {
                mMouseSgr = enable;
            }
        }
    }

    private void setAltScreen(boolean enable) {
        if (enable == mUsingAltScreen) return;
        if (enable) {
            mPrimaryBuffer = mScreenBuffer;
            mAltSavedCursorCol = mCursorCol;
            mAltSavedCursorRow = mCursorRow;
            mScreenBuffer = new TerminalBuffer(mColumns, mRows, 0);
            mCursorCol = 0;
            mCursorRow = 0;
            mUsingAltScreen = true;
            resetScrollRegion();
        } else {
            if (mPrimaryBuffer == null) return;
            mScreenBuffer = mPrimaryBuffer;
            mPrimaryBuffer = null;
            mUsingAltScreen = false;
            if (mScreenBuffer.mColumns != mColumns || mScreenBuffer.mScreenRows != mRows) {
                mScreenBuffer.resize(mColumns, mRows);
            }
            mCursorCol = clamp(mAltSavedCursorCol, 0, mColumns - 1);
            mCursorRow = clamp(mAltSavedCursorRow, 0, mRows - 1);
            resetScrollRegion();
        }
    }

    private void setCursor(int row, int col) {
        mCursorRow = clamp(row, 0, mRows - 1);
        mCursorCol = clamp(col, 0, mColumns - 1);
    }

    private void saveCursor() {
        mSavedCursorCol = mCursorCol;
        mSavedCursorRow = mCursorRow;
        mSavedForeColor = mCurrentForeColor;
        mSavedBackColor = mCurrentBackColor;
        mSavedEffect = mCurrentEffect;
        mHasSavedCursor = true;
    }

    private void restoreCursor() {
        if (!mHasSavedCursor) return;
        mCursorCol = clamp(mSavedCursorCol, 0, mColumns - 1);
        mCursorRow = clamp(mSavedCursorRow, 0, mRows - 1);
        mCurrentForeColor = mSavedForeColor;
        mCurrentBackColor = mSavedBackColor;
        mCurrentEffect = mSavedEffect;
    }

    private void resetTerminal() {
        if (mUsingAltScreen) {
            setAltScreen(false);
        }
        mScreenBuffer.clear(getCurrentStyle());
        mCursorCol = 0;
        mCursorRow = 0;
        mCurrentForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
        mCurrentBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
        mCurrentEffect = 0;
        mCursorVisible = true;
        mAutoWrap = true;
        mHasSavedCursor = false;
        mState = STATE_GROUND;
        mUtf8Remaining = 0;
        resetScrollRegion();
    }

    private void resetScrollRegion() {
        mScrollTop = 0;
        mScrollBottom = Math.max(0, mRows - 1);
    }

    private void setScrollRegion(int[] args) {
        int top = args.length > 0 && args[0] != 0 ? args[0] - 1 : 0;
        int bottom = args.length > 1 && args[1] != 0 ? args[1] - 1 : mRows - 1;
        top = clamp(top, 0, mRows - 1);
        bottom = clamp(bottom, 0, mRows - 1);
        if (top >= bottom) {
            resetScrollRegion();
            return;
        }
        mScrollTop = top;
        mScrollBottom = bottom;
        setCursor(0, 0);
    }

    private void lineFeed() {
        if (mCursorRow == mScrollBottom) {
            if (mScrollTop == 0 && mScrollBottom == mRows - 1) {
                mScreenBuffer.scrollDownOneLine();
            } else {
                mScreenBuffer.scrollUpRegion(mScrollTop, mScrollBottom, getCurrentStyle());
            }
        } else if (mCursorRow < mRows - 1) {
            mCursorRow++;
        }
    }

    private void reverseIndex() {
        if (mCursorRow == mScrollTop) {
            mScreenBuffer.scrollDownRegion(mScrollTop, mScrollBottom, getCurrentStyle());
        } else if (mCursorRow > 0) {
            mCursorRow--;
        }
    }

    private void scrollUp(int count) {
        for (int i = 0; i < count; i++) {
            if (mScrollTop == 0 && mScrollBottom == mRows - 1) {
                mScreenBuffer.scrollDownOneLine();
            } else {
                mScreenBuffer.scrollUpRegion(mScrollTop, mScrollBottom, getCurrentStyle());
            }
        }
    }

    private void scrollDown(int count) {
        for (int i = 0; i < count; i++) {
            mScreenBuffer.scrollDownRegion(mScrollTop, mScrollBottom, getCurrentStyle());
        }
    }

    private void insertLines(int count) {
        if (mCursorRow < mScrollTop || mCursorRow > mScrollBottom) return;
        mScreenBuffer.insertLines(mCursorRow, mScrollBottom, count, getCurrentStyle());
    }

    private void deleteLines(int count) {
        if (mCursorRow < mScrollTop || mCursorRow > mScrollBottom) return;
        mScreenBuffer.deleteLines(mCursorRow, mScrollBottom, count, getCurrentStyle());
    }

    private void insertChars(int count) {
        mScreenBuffer.insertChars(mCursorRow, mCursorCol, count, getCurrentStyle());
    }

    private void deleteChars(int count) {
        mScreenBuffer.deleteChars(mCursorRow, mCursorCol, count, getCurrentStyle());
    }

    private void eraseChars(int count) {
        TerminalRow row = mScreenBuffer.getScreenRow(mCursorRow);
        if (row == null) return;
        int end = Math.min(mColumns, mCursorCol + count);
        for (int c = mCursorCol; c < end; c++) {
            row.setChar(c, ' ', getCurrentStyle());
        }
    }

    private void eraseInLine(int mode) {
        TerminalRow row = mScreenBuffer.getScreenRow(mCursorRow);
        if (row == null) return;
        if (mode == 0) {
            for (int c = mCursorCol; c < mColumns; c++) row.setChar(c, ' ', getCurrentStyle());
        } else if (mode == 1) {
            for (int c = 0; c <= mCursorCol && c < mColumns; c++) row.setChar(c, ' ', getCurrentStyle());
        } else if (mode == 2) {
            row.clear(getCurrentStyle());
        }
    }

    private void eraseInDisplay(int mode) {
        if (mode == 0) {
            eraseInLine(0);
            for (int r = mCursorRow + 1; r < mRows; r++) {
                TerminalRow row = mScreenBuffer.getScreenRow(r);
                if (row != null) row.clear(getCurrentStyle());
            }
        } else if (mode == 1) {
            for (int r = 0; r < mCursorRow; r++) {
                TerminalRow row = mScreenBuffer.getScreenRow(r);
                if (row != null) row.clear(getCurrentStyle());
            }
            eraseInLine(1);
        } else if (mode == 2) {
            mScreenBuffer.clear(getCurrentStyle());
        } else if (mode == 3) {
            mScreenBuffer.clear(getCurrentStyle());
            mScreenBuffer.clearHistory();
        }
    }

    private void tabForward(int count) {
        for (int i = 0; i < count; i++) {
            mCursorCol = (mCursorCol + 8) & ~7;
            if (mCursorCol >= mColumns) mCursorCol = mColumns - 1;
        }
    }

    private void emitCodePoint(int cp) {
        int width = WcWidth.width(cp);
        if (width <= 0) return;
        if (cp > 0xFFFF) width = 2;
        if (width > mColumns) width = mColumns;

        if (mCursorCol >= mColumns) {
            if (mAutoWrap) {
                mCursorCol = 0;
                lineFeed();
            } else {
                mCursorCol = Math.max(0, mColumns - width);
            }
        }

        if (mCursorCol + width > mColumns) {
            if (mAutoWrap) {
                mCursorCol = 0;
                lineFeed();
            } else {
                mCursorCol = Math.max(0, mColumns - width);
            }
        }

        TerminalRow row = mScreenBuffer.getScreenRow(mCursorRow);
        if (row == null) return;

        if (cp <= 0xFFFF) {
            row.setChar(mCursorCol, (char) cp, getCurrentStyle());
            if (width == 2 && mCursorCol + 1 < mColumns) {
                row.setChar(mCursorCol + 1, ' ', getCurrentStyle());
            }
        } else {
            char[] pair = Character.toChars(cp);
            row.setChar(mCursorCol, pair[0], getCurrentStyle());
            if (mCursorCol + 1 < mColumns) {
                row.setChar(mCursorCol + 1, pair[1], getCurrentStyle());
            }
        }
        mCursorCol += width;
        if (mCursorCol > mColumns) mCursorCol = mColumns;
    }

    private void handleSgr(int[] args) {
        if (args.length == 0) {
            resetStyle();
            return;
        }
        for (int i = 0; i < args.length; i++) {
            int code = args[i];
            if (code == 0) {
                resetStyle();
            } else if (code == 1) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_BOLD;
            } else if (code == 2) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_DIM;
            } else if (code == 3) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_ITALIC;
            } else if (code == 4) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
            } else if (code == 5 || code == 6) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_BLINK;
            } else if (code == 7) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
            } else if (code == 8) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE;
            } else if (code == 9) {
                mCurrentEffect |= TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
            } else if (code == 22) {
                mCurrentEffect &= ~(TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_DIM);
            } else if (code == 23) {
                mCurrentEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_ITALIC;
            } else if (code == 24) {
                mCurrentEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
            } else if (code == 25) {
                mCurrentEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_BLINK;
            } else if (code == 27) {
                mCurrentEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_INVERSE;
            } else if (code == 28) {
                mCurrentEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE;
            } else if (code == 29) {
                mCurrentEffect &= ~TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH;
            } else if (code >= 30 && code <= 37) {
                mCurrentForeColor = code - 30;
            } else if (code == 39) {
                mCurrentForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
            } else if (code >= 40 && code <= 47) {
                mCurrentBackColor = code - 40;
            } else if (code == 49) {
                mCurrentBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
            } else if (code >= 90 && code <= 97) {
                mCurrentForeColor = 8 + (code - 90);
            } else if (code >= 100 && code <= 107) {
                mCurrentBackColor = 8 + (code - 100);
            } else if (code == 38 && i + 2 < args.length && args[i + 1] == 5) {
                mCurrentForeColor = args[i + 2] & 0xFF;
                i += 2;
            } else if (code == 38 && i + 4 < args.length && args[i + 1] == 2) {
                int r = args[i + 2] & 0xFF;
                int g = args[i + 3] & 0xFF;
                int b = args[i + 4] & 0xFF;
                mCurrentForeColor = (r << 16) | (g << 8) | b;
                i += 4;
            } else if (code == 48 && i + 2 < args.length && args[i + 1] == 5) {
                mCurrentBackColor = args[i + 2] & 0xFF;
                i += 2;
            } else if (code == 48 && i + 4 < args.length && args[i + 1] == 2) {
                int r = args[i + 2] & 0xFF;
                int g = args[i + 3] & 0xFF;
                int b = args[i + 4] & 0xFF;
                mCurrentBackColor = (r << 16) | (g << 8) | b;
                i += 4;
            }
        }
    }

    private void resetStyle() {
        mCurrentForeColor = TextStyle.COLOR_INDEX_FOREGROUND;
        mCurrentBackColor = TextStyle.COLOR_INDEX_BACKGROUND;
        mCurrentEffect = 0;
    }

    public synchronized void resize(int newColumns, int newRows) {
        if (newColumns <= 0 || newRows <= 0) return;
        mColumns = newColumns;
        mRows = newRows;
        mScreenBuffer.resize(newColumns, newRows);
        if (mPrimaryBuffer != null) {
            mPrimaryBuffer.resize(newColumns, newRows);
        }
        if (mCursorRow >= newRows) mCursorRow = newRows - 1;
        if (mCursorCol >= newColumns) mCursorCol = newColumns - 1;
        resetScrollRegion();
    }

    public boolean isAlternateBuffer() {
        return mUsingAltScreen;
    }

    public boolean isMouseTracking() {
        return mMouseTracking;
    }

    public boolean isMouseSgr() {
        return mMouseSgr;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}

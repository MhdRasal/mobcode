package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * High-performance terminal canvas renderer modelled on Termux's TerminalRenderer.
 *
 * Guarantees:
 * - Font cell width perfectly matches terminal column grid.
 * - Text runs are scaled horizontally to fill exact cell boundaries (zero cursor drift).
 * - Trailing blank spaces are not drawn as text (eliminates right gap).
 * - Full scrollback history rendering based on topRow.
 * - Genuine blinking cursor with zero gap from typed text.
 */
public final class TerminalRenderer {
    private final Paint mTextPaint = new Paint();
    private final Paint mBgPaint = new Paint();
    private final Paint mCursorPaint = new Paint();

    /** Width of a single monospace character cell, measured on 'X' (Termux convention). */
    public float mFontWidth;
    /** The Paint.getFontSpacing() value – vertical distance between baselines. */
    public int mFontLineSpacing;
    /** The Paint.ascent() value (negative). */
    public int mFontAscent;
    /** mFontLineSpacing + mFontAscent – the offset from top of first row to baseline. */
    public int mFontLineSpacingAndAscent;

    public TerminalRenderer(float textSize) {
        mTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        mTextPaint.setLetterSpacing(0f);

        mBgPaint.setStyle(Paint.Style.FILL);
        mCursorPaint.setStyle(Paint.Style.FILL);

        computeFontMetrics();
    }

    public void setTextSize(float textSize) {
        mTextPaint.setTextSize(textSize);
        computeFontMetrics();
    }

    private void computeFontMetrics() {
        // Termux: mFontWidth = mTextPaint.measureText("X");
        mFontWidth = mTextPaint.measureText("X");

        // Termux: mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());

        // Termux: mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());

        // Termux: mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
    }

    /**
     * Render the terminal emulator state onto the given Canvas.
     * Draws text as style-contiguous runs with canvas scaling for exact cell alignment.
     */
    public void render(TerminalEmulator emulator, Canvas canvas, int topRow, int selectionY, boolean cursorBlinkState) {
        if (emulator == null || canvas == null) return;

        TerminalBuffer buffer = emulator.mScreenBuffer;
        TerminalColorScheme colors = emulator.mColorScheme;
        int rows = emulator.mRows;
        int cols = emulator.mColumns;
        int[] palette = colors.mColors;
        int defaultBg = palette[TextStyle.COLOR_INDEX_BACKGROUND];

        // 1. Clear canvas with default background
        canvas.drawColor(defaultBg);

        int cursorCol = Math.min(emulator.mCursorCol, Math.max(0, cols - 1));
        int cursorRow = emulator.mCursorRow;
        boolean cursorVisible = emulator.mCursorVisible && cursorBlinkState && topRow == 0;

        // 2. Draw each visible row
        float heightOffset = mFontLineSpacingAndAscent;
        for (int r = 0; r < rows; r++) {
            heightOffset += mFontLineSpacing;

            TerminalRow row = buffer.getRowAt(topRow, r);
            if (row == null) continue;

            char[] line = row.mText;
            int lineLen = Math.min(line.length, cols);

            // Find last non-space character or non-default background cell
            int lastContentCol = -1;
            for (int c = lineLen - 1; c >= 0; c--) {
                char ch = (c < line.length) ? line[c] : ' ';
                int bg = colors.getColor(TextStyle.decodeBackColor(row.getStyle(c)));
                if (ch > ' ' || bg != defaultBg) {
                    lastContentCol = c;
                    break;
                }
            }

            if (lastContentCol < 0) {
                // Entire row is empty default background
                continue;
            }

            int endCol = lastContentCol + 1;

            // Draw style-contiguous runs
            long lastStyle = -1;
            int runStart = 0;

            for (int c = 0; c <= endCol; c++) {
                long style = (c < endCol) ? row.getStyle(c) : -2;

                if (style != lastStyle) {
                    if (c > runStart && lastStyle != -1) {
                        drawTextRun(canvas, line, palette, heightOffset,
                                runStart, c - runStart, lastStyle,
                                defaultBg, colors);
                    }
                    lastStyle = style;
                    runStart = c;
                }
            }
        }

        // 3. Draw genuine terminal cursor relative to visible viewport (topRow)
        int screenCursorRow = cursorRow - topRow;
        if (cursorVisible && cursorCol >= 0 && cursorCol < cols && screenCursorRow >= 0 && screenCursorRow < rows) {
            TerminalRow cursorLine = buffer.getRowAt(topRow, screenCursorRow);
            String cursorText = "";
            int cursorWidth = 1;
            if (cursorLine != null) {
                char ch = cursorLine.getChar(cursorCol);
                if (ch > ' ') {
                    cursorText = String.valueOf(ch);
                    if (Character.isHighSurrogate(ch) && cursorCol + 1 < cols) {
                        char low = cursorLine.getChar(cursorCol + 1);
                        if (Character.isLowSurrogate(low)) {
                            cursorText = new String(new char[]{ch, low});
                            cursorWidth = 2;
                        }
                    } else {
                        cursorWidth = Math.max(1, WcWidth.width(ch));
                    }
                }
            }
            if (cursorCol + cursorWidth > cols) {
                cursorWidth = Math.max(1, cols - cursorCol);
            }

            float curX = cursorCol * mFontWidth;
            float curBaselineY = mFontLineSpacingAndAscent + (screenCursorRow + 1) * mFontLineSpacing;
            float curTopY = curBaselineY - mFontLineSpacingAndAscent + mFontAscent;
            float curBottomY = curBaselineY;

            mCursorPaint.setColor(palette[TextStyle.COLOR_INDEX_CURSOR]);
            canvas.drawRect(curX, curTopY, curX + cursorWidth * mFontWidth, curBottomY, mCursorPaint);

            if (!cursorText.isEmpty()) {
                mTextPaint.setColor(defaultBg);
                mTextPaint.setFakeBoldText(false);
                mTextPaint.setUnderlineText(false);
                mTextPaint.setStrikeThruText(false);
                mTextPaint.setTextSkewX(0f);

                float targetWidth = cursorWidth * mFontWidth;
                float chWidth = mTextPaint.measureText(cursorText);
                float charScaleX = (chWidth > 0 && Math.abs(chWidth - targetWidth) > 0.05f) ? (targetWidth / chWidth) : 1.0f;
                if (charScaleX != 1.0f) {
                    canvas.save();
                    canvas.scale(charScaleX, 1.0f);
                    canvas.drawText(cursorText, curX / charScaleX, curBaselineY - mFontLineSpacingAndAscent, mTextPaint);
                    canvas.restore();
                } else {
                    canvas.drawText(cursorText, curX, curBaselineY - mFontLineSpacingAndAscent, mTextPaint);
                }
            }
        }
    }

    /**
     * Draw a contiguous run of characters sharing the same style.
     * Uses canvas scaling to guarantee exact alignment with the terminal column grid.
     */
    private void drawTextRun(Canvas canvas, char[] line, int[] palette, float baselineY,
                             int startCol, int runLen, long style,
                             int defaultBg, TerminalColorScheme colors) {

        int foreColorIndex = TextStyle.decodeForeColor(style);
        int backColorIndex = TextStyle.decodeBackColor(style);
        int effect = TextStyle.decodeEffect(style);

        int fg = colors.getColor(foreColorIndex);
        int bg = colors.getColor(backColorIndex);

        boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;
        boolean invisible = (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0;

        // Bold → bright colors for first 8 ANSI colors
        if (bold && foreColorIndex >= 0 && foreColorIndex < 8) {
            fg = colors.getColor(foreColorIndex + 8);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) {
            int tmp = fg;
            fg = bg;
            bg = tmp;
        }

        float left = startCol * mFontWidth;
        float right = left + runLen * mFontWidth;
        float rowTop = baselineY - mFontLineSpacingAndAscent + mFontAscent;

        // 1. Draw cell background if non-default
        if (bg != defaultBg) {
            mBgPaint.setColor(bg);
            canvas.drawRect(left, rowTop, right, baselineY, mBgPaint);
        }

        // 2. Draw text
        if (!invisible) {
            if (dim) {
                int red = ((fg >> 16) & 0xFF) * 2 / 3;
                int green = ((fg >> 8) & 0xFF) * 2 / 3;
                int blue = (fg & 0xFF) * 2 / 3;
                fg = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }

            mTextPaint.setColor(fg);
            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0f);
            mTextPaint.setStrikeThruText(strikeThrough);

            // Measure actual text width
            float mes = mTextPaint.measureText(line, startCol, runLen);
            float targetWidth = runLen * mFontWidth;

            // Scale canvas horizontally if font advance deviates from cell grid
            boolean scaleNeeded = mes > 0 && Math.abs(mes - targetWidth) > 0.05f;
            if (scaleNeeded) {
                float scaleX = targetWidth / mes;
                canvas.save();
                canvas.scale(scaleX, 1.0f);
                canvas.drawText(line, startCol, runLen, left / scaleX, baselineY - mFontLineSpacingAndAscent, mTextPaint);
                canvas.restore();
            } else {
                canvas.drawText(line, startCol, runLen, left, baselineY - mFontLineSpacingAndAscent, mTextPaint);
            }
        }
    }
}

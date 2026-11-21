package com.termux.terminal;

/**
 * Standard 256 ANSI color palette and 24-bit TrueColor renderer for Termux terminal.
 */
public final class TerminalColorScheme {
    public final int[] mColors = new int[260];

    public TerminalColorScheme() {
        reset();
    }

    public void reset() {
        // Standard 16 ANSI colors
        mColors[0]  = 0xFF000000; // Black
        mColors[1]  = 0xFFCD3131; // Red
        mColors[2]  = 0xFF0DBC79; // Green
        mColors[3]  = 0xFFE5E510; // Yellow
        mColors[4]  = 0xFF2472C8; // Blue
        mColors[5]  = 0xFFBC3FBC; // Magenta
        mColors[6]  = 0xFF11A8CD; // Cyan
        mColors[7]  = 0xFFE5E5E5; // White

        // Bright variants
        mColors[8]  = 0xFF666666; // Bright Black
        mColors[9]  = 0xFFF14C4C; // Bright Red
        mColors[10] = 0xFF23D18B; // Bright Green
        mColors[11] = 0xFFF5F543; // Bright Yellow
        mColors[12] = 0xFF3B8EEA; // Bright Blue
        mColors[13] = 0xFFD670D6; // Bright Magenta
        mColors[14] = 0xFF29B8DB; // Bright Cyan
        mColors[15] = 0xFFFFFFFF; // Bright White

        // 6x6x6 color cube (indices 16 to 231)
        int index = 16;
        int[] cubeLevels = {0, 95, 135, 175, 215, 255};
        for (int r : cubeLevels) {
            for (int g : cubeLevels) {
                for (int b : cubeLevels) {
                    mColors[index++] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }

        // Grayscale 24 steps (indices 232 to 255)
        for (int i = 0; i < 24; i++) {
            int gray = 8 + i * 10;
            mColors[232 + i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }

        // Default foreground, background, and cursor
        mColors[TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFFFFFFF;
        mColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF0D1117; // Modern Termux/GitHub Dark
        mColors[TextStyle.COLOR_INDEX_CURSOR]     = 0xFF58A6FF; // Cursor cyan/blue
    }

    public int getColor(int index) {
        if (index >= 0 && index < mColors.length) {
            return mColors[index];
        }
        // 24-bit TrueColor packed directly as RGB (0xRRGGBB)
        return 0xFF000000 | (index & 0xFFFFFF);
    }
}
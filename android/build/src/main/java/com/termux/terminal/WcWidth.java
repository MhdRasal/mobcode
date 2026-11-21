package com.termux.terminal;

/**
 * Unicode character width calculation.
 */
public final class WcWidth {
    public static int width(int codePoint) {
        if (codePoint == 0 || codePoint == 0x034F || (codePoint >= 0x200B && codePoint <= 0x200F)) {
            return 0;
        }
        if (codePoint < 32 || (codePoint >= 0x7F && codePoint < 0xA0)) {
            return 0;
        }
        if (codePoint >= 0xFE00 && codePoint <= 0xFE0F) {
            return 0;
        }
        if (codePoint >= 0xE0100 && codePoint <= 0xE01EF) {
            return 0;
        }
        int type = Character.getType(codePoint);
        if (type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT) {
            return 0;
        }
        // Basic fullwidth ranges (CJK, Wide Emojis)
        if (codePoint >= 0x1100 &&
            (codePoint <= 0x115F ||
             codePoint == 0x2329 || codePoint == 0x232A ||
             (codePoint >= 0x2E80 && codePoint <= 0xA4CF && codePoint != 0x303F) ||
             (codePoint >= 0xAC00 && codePoint <= 0xD7A3) ||
             (codePoint >= 0xF900 && codePoint <= 0xFAFF) ||
             (codePoint >= 0xFE10 && codePoint <= 0xFE19) ||
             (codePoint >= 0xFE30 && codePoint <= 0xFE6F) ||
             (codePoint >= 0xFF00 && codePoint <= 0xFF60) ||
             (codePoint >= 0xFFE0 && codePoint <= 0xFFE6) ||
             (codePoint >= 0x1F300 && codePoint <= 0x1F64F) ||
             (codePoint >= 0x1F900 && codePoint <= 0x1F9FF))) {
            return 2;
        }
        return 1;
    }
}

package com.termux.terminal;

/**
 * Text style bit packing utilities supporting 24-bit TrueColor and ANSI styles.
 */
public final class TextStyle {
    public static final int CHARACTER_ATTRIBUTE_BOLD = 1;
    public static final int CHARACTER_ATTRIBUTE_ITALIC = 2;
    public static final int CHARACTER_ATTRIBUTE_UNDERLINE = 4;
    public static final int CHARACTER_ATTRIBUTE_BLINK = 8;
    public static final int CHARACTER_ATTRIBUTE_INVERSE = 16;
    public static final int CHARACTER_ATTRIBUTE_INVISIBLE = 32;
    public static final int CHARACTER_ATTRIBUTE_STRIKETHROUGH = 64;
    public static final int CHARACTER_ATTRIBUTE_PROTECTED = 128;
    public static final int CHARACTER_ATTRIBUTE_DIM = 256;

    public static final int COLOR_INDEX_FOREGROUND = 256;
    public static final int COLOR_INDEX_BACKGROUND = 257;
    public static final int COLOR_INDEX_CURSOR = 258;

    public static long encode(int foreColor, int backColor, int effect) {
        return ((long) (effect & 0x1FF)) | (((long) (foreColor & 0xFFFFFF)) << 9) | (((long) (backColor & 0xFFFFFF)) << 33);
    }

    public static int decodeForeColor(long style) {
        return (int) ((style >>> 9) & 0xFFFFFF);
    }

    public static int decodeBackColor(long style) {
        return (int) ((style >>> 33) & 0xFFFFFF);
    }

    public static int decodeEffect(long style) {
        return (int) (style & 0x1FF);
    }
}
package com.termux.terminal;

import android.view.KeyEvent;

/**
 * Maps Android KeyEvents to terminal ANSI escape sequences.
 */
public final class KeyHandler {

    public static String getCode(int keyCode, int metaState, boolean cursorKeysApplication, boolean keypadApplication) {
        boolean ctrlDown = (metaState & KeyEvent.META_CTRL_ON) != 0;
        boolean altDown = (metaState & KeyEvent.META_ALT_ON) != 0;
        boolean shiftDown = (metaState & KeyEvent.META_SHIFT_ON) != 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
                return "\r";
            case KeyEvent.KEYCODE_DEL:
                return "\u007F"; // Backspace DEL
            case KeyEvent.KEYCODE_TAB:
                return shiftDown ? "\u001B[Z" : "\t";
            case KeyEvent.KEYCODE_ESCAPE:
                return "\u001B";
            case KeyEvent.KEYCODE_DPAD_UP:
                return "\u001B[A";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "\u001B[B";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "\u001B[C";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "\u001B[D";
            case KeyEvent.KEYCODE_MOVE_HOME:
                return "\u001B[H";
            case KeyEvent.KEYCODE_MOVE_END:
                return "\u001B[F";
            case KeyEvent.KEYCODE_PAGE_UP:
                return "\u001B[5~";
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return "\u001B[6~";
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return "\u001B[3~";
            case KeyEvent.KEYCODE_INSERT:
                return "\u001B[2~";
            default:
                return null;
        }
    }
}

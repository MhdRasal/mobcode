package com.termux.shared.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * Termux-style two-row Extra Keys Toolbar above the Android keyboard.
 * Row 1: ESC, /, -, HOME, ▲, END, PGUP
 * Row 2: TAB, CTRL, ALT, ◀, ▼, ▶, PGDN
 */
public class ExtraKeysView extends LinearLayout {

    private TerminalView mTerminalView;

    // Modifier states
    private boolean mCtrlActive = false;
    private boolean mAltActive = false;
    private Button mCtrlButton;
    private Button mAltButton;

    private static final int COLOR_NORMAL_BG = 0xFF18181B;
    private static final int COLOR_ACTIVE_BG = 0xFF2E1065;
    private static final int COLOR_TEXT = 0xFFFAFAFA;

    public ExtraKeysView(Context context) {
        super(context);
        init();
    }

    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setTerminalView(TerminalView terminalView) {
        mTerminalView = terminalView;
        if (mTerminalView != null) {
            mTerminalView.setExtraKeysView(this);
        }
    }

    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF09090B);
        setPadding(4, 4, 4, 4);

        // Row 1: ESC, /, -, HOME, UP, END, PGUP
        String[] row1 = {"ESC", "/", "-", "HOME", "▲", "END", "PGUP"};
        // Row 2: TAB, CTRL, ALT, LEFT, DOWN, RIGHT, PGDN
        String[] row2 = {"TAB", "CTRL", "ALT", "◀", "▼", "▶", "PGDN"};

        addView(createRow(row1, 1));
        addView(createRow(row2, 2));
    }

    private LinearLayout createRow(String[] keys, int rowNum) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, 2, 0, 2);
        row.setLayoutParams(rowParams);

        for (String key : keys) {
            Button btn = new Button(getContext());
            btn.setText(key);
            btn.setTextColor(COLOR_TEXT);
            btn.setTextSize(12f);
            btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            btn.setBackgroundColor(COLOR_NORMAL_BG);
            btn.setPadding(0, 10, 0, 10);
            btn.setGravity(Gravity.CENTER);

            // Crucial: do not steal focus from TerminalView!
            btn.setFocusable(false);
            btn.setFocusableInTouchMode(false);

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    0, LayoutParams.WRAP_CONTENT, 1.0f);
            btnParams.setMargins(2, 0, 2, 0);
            btn.setLayoutParams(btnParams);

            if ("CTRL".equals(key)) {
                mCtrlButton = btn;
            } else if ("ALT".equals(key)) {
                mAltButton = btn;
            }

            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                onKeyClicked(key);
                // Immediately return/maintain focus on TerminalView
                if (mTerminalView != null) {
                    mTerminalView.requestFocus();
                    mTerminalView.requestFocusFromTouch();
                }
            });

            row.addView(btn);
        }
        return row;
    }

    private void onKeyClicked(String key) {
        if (mTerminalView == null) return;
        TerminalSession session = mTerminalView.getCurrentSession();

        switch (key) {
            case "CTRL":
                mCtrlActive = !mCtrlActive;
                updateModifierUI();
                return;
            case "ALT":
                mAltActive = !mAltActive;
                updateModifierUI();
                return;
            default:
                break;
        }

        if (session == null) return;

        switch (key) {
            case "ESC":
                session.write("\u001B");
                break;
            case "TAB":
                session.write("\t");
                break;
            case "▲": // UP
                session.write("\u001B[A");
                break;
            case "▼": // DOWN
                session.write("\u001B[B");
                break;
            case "◀": // LEFT
                session.write("\u001B[D");
                break;
            case "▶": // RIGHT
                session.write("\u001B[C");
                break;
            case "HOME":
                session.write("\u001B[H");
                break;
            case "END":
                session.write("\u001B[F");
                break;
            case "PGUP":
                session.write("\u001B[5~");
                break;
            case "PGDN":
                session.write("\u001B[6~");
                break;
            case "/":
                session.write("/");
                break;
            case "-":
                session.write("-");
                break;
            default:
                session.write(key);
                break;
        }

        // Reset modifiers after single use
        if (mCtrlActive || mAltActive) {
            mCtrlActive = false;
            mAltActive = false;
            updateModifierUI();
        }
    }

    public boolean isCtrlActive() {
        return mCtrlActive;
    }

    public boolean isAltActive() {
        return mAltActive;
    }

    public void consumeModifiers() {
        if (mCtrlActive || mAltActive) {
            mCtrlActive = false;
            mAltActive = false;
            updateModifierUI();
        }
    }

    private void updateModifierUI() {
        if (mCtrlButton != null) {
            mCtrlButton.setBackgroundColor(mCtrlActive ? COLOR_ACTIVE_BG : COLOR_NORMAL_BG);
        }
        if (mAltButton != null) {
            mAltButton.setBackgroundColor(mAltActive ? COLOR_ACTIVE_BG : COLOR_NORMAL_BG);
        }
    }

    public void toggleKeyboard() {
        if (mTerminalView != null) {
            mTerminalView.requestFocus();
            mTerminalView.requestFocusFromTouch();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        }
    }
}
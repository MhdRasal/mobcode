package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.termux.shared.view.ExtraKeysView;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * Native Android TerminalView rendering Termux's TerminalSession directly onto Canvas.
 */
public class TerminalView extends View implements TerminalSessionClient {
    private TerminalSession mTermSession;
    private TerminalRenderer mRenderer;
    private ExtraKeysView mExtraKeysView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mCursorBlinkState = true;
    private String mComposingText = "";

    // Scrolling & Touch Gestures
    private int mTopRow = 0; // Ranges from -historySize to 0
    private float mDownX;
    private float mDownY;
    private float mLastY;
    private boolean mIsScrolling = false;
    private float mScrollRemainder = 0f;
    private int mTouchSlop = 16;
    private int mActivePointerId = -1;
    private boolean mScrollMode = false;
    private boolean mLongPressed = false;
    private float mDensity = 1f;
    private final Paint mScrollPaint = new Paint();

    private final Runnable mLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsScrolling) {
                mScrollMode = true;
                mLongPressed = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        }
    };

    private final Runnable mBlinkRunnable = new Runnable() {
        @Override
        public void run() {
            mCursorBlinkState = !mCursorBlinkState;
            invalidate();
            mHandler.postDelayed(this, 500);
        }
    };

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        mDensity = getContext().getResources().getDisplayMetrics().density;
        mScrollPaint.setAntiAlias(true);
        mScrollPaint.setColor(0x9958A6FF);
        mScrollPaint.setStyle(Paint.Style.FILL);

        float scaledDensity = getContext().getResources().getDisplayMetrics().scaledDensity;
        float textSizePx = 14f * scaledDensity;
        mRenderer = new TerminalRenderer(textSizePx);
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public void attachSession(TerminalSession session) {
        if (mTermSession == session) return;
        mTermSession = session;
        if (mTermSession != null) {
            mTermSession.setClient(this);
            updateTerminalSize();
        }
        invalidate();
    }

    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    public void setTextSize(float textSize) {
        mRenderer.setTextSize(textSize);
        updateTerminalSize();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.post(mBlinkRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mBlinkRunnable);
        mHandler.removeCallbacks(mLongPressRunnable);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateTerminalSize();
    }

    private void updateTerminalSize() {
        if (getWidth() <= 0 || getHeight() <= 0 || mRenderer == null) return;
        // Termux: columns = viewWidth / mFontWidth
        int cols = Math.max(4, (int) (getWidth() / mRenderer.mFontWidth));
        // Termux: rows = (viewHeight - mFontLineSpacingAndAscent) / mFontLineSpacing
        int rows = Math.max(4, (getHeight() - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);
        if (mTermSession != null) {
            mTermSession.updateSize(cols, rows);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mTermSession != null) {
            int history = mTermSession.mEmulator.mScreenBuffer.getHistorySize();
            if (mTopRow < -history) mTopRow = -history;
            if (mTopRow > 0) mTopRow = 0;
            mRenderer.render(mTermSession.mEmulator, canvas, mTopRow, 0, mCursorBlinkState);
            drawScrollIndicator(canvas);
        }
    }

    private void drawScrollIndicator(Canvas canvas) {
        if (mTopRow >= 0 || mTermSession == null || mTermSession.mEmulator == null) return;
        int history = mTermSession.mEmulator.mScreenBuffer.getHistorySize();
        int rows = mTermSession.mEmulator.mRows;
        if (history <= 0 || rows <= 0) return;

        float trackTop = mDensity * 4f;
        float trackBottom = getHeight() - mDensity * 4f;
        float visible = (float) rows / (rows + history);
        float thumbHeight = Math.max(mDensity * 24f, (trackBottom - trackTop) * visible);
        float progress = (float) (-mTopRow) / history;
        float thumbTop = trackTop + progress * (trackBottom - trackTop - thumbHeight);
        float width = Math.max(3f, mDensity * 3f);
        float right = getWidth() - mDensity * 2f;
        canvas.drawRoundRect(right - width, thumbTop, right, thumbTop + thumbHeight, width, width, mScrollPaint);
    }

    public void doScroll(int deltaRows) {
        if (mTermSession == null || mTermSession.mEmulator == null) return;

        // In mouse tracking mode or alternate screen buffer (OpenCode, less, nano, vim):
        // Send SGR / X10 mouse wheel escape sequences so the TUI scrolls its chat/log viewport
        // without navigating readline command history!
        if (mTermSession.mEmulator.isMouseTracking() || mTermSession.mEmulator.isAlternateBuffer()) {
            int cols = mTermSession.mEmulator.mColumns;
            int rows = mTermSession.mEmulator.mRows;
            int col = Math.max(1, Math.min(cols, cols / 2));
            int row = Math.max(1, Math.min(rows, rows / 2));
            int button = (deltaRows < 0) ? 64 : 65; // 64 = Wheel Up, 65 = Wheel Down
            int count = Math.min(4, Math.max(1, Math.abs(deltaRows)));

            for (int i = 0; i < count; i++) {
                // SGR mouse mode (standard for OpenCode, Claude Code, and modern terminal TUIs)
                mTermSession.write(String.format(java.util.Locale.US, "\033[<%d;%d;%dM", button, col, row));
                if (!mTermSession.mEmulator.isMouseSgr()) {
                    byte[] x10 = new byte[]{0x1b, '[', 'M', (byte) (button + 32), (byte) (col + 32), (byte) (row + 32)};
                    mTermSession.write(new String(x10, java.nio.charset.StandardCharsets.ISO_8859_1));
                }
            }
            return;
        }

        // Normal scrollback history buffer (antigravity, shell, build logs)
        int historySize = mTermSession.mEmulator.mScreenBuffer.getHistorySize();
        if (historySize <= 0) return;

        int newTopRow = mTopRow + deltaRows;
        newTopRow = Math.max(-historySize, Math.min(0, newTopRow));
        if (newTopRow != mTopRow) {
            mTopRow = newTopRow;
            invalidate();
        }
    }

    public void scrollToBottom() {
        mScrollMode = false;
        mLongPressed = false;
        if (mTopRow != 0) {
            mTopRow = 0;
            invalidate();
        }
    }

    private void showKeyboardIfHidden() {
        requestFocus();
        requestFocusFromTouch();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    public void toggleKeyboard() {
        requestFocus();
        requestFocusFromTouch();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mLastY = event.getY();
                mActivePointerId = event.getPointerId(0);
                mIsScrolling = false;
                mLongPressed = false;
                mScrollRemainder = 0f;
                mScrollMode = event.getPointerCount() > 1;
                mHandler.removeCallbacks(mLongPressRunnable);
                if (!mScrollMode) {
                    mHandler.postDelayed(mLongPressRunnable, ViewConfiguration.getLongPressTimeout());
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                mScrollMode = true;
                mHandler.removeCallbacks(mLongPressRunnable);
                return true;

            case MotionEvent.ACTION_MOVE:
                int pointerIndex = event.findPointerIndex(mActivePointerId);
                if (pointerIndex < 0) pointerIndex = 0;
                float y = event.getY(pointerIndex);
                float x = event.getX(pointerIndex);
                float dy = y - mLastY;
                float totalDistY = Math.abs(y - mDownY);
                float totalDistX = Math.abs(x - mDownX);

                if (!mIsScrolling && (mScrollMode || (totalDistY > mTouchSlop && totalDistY > totalDistX))) {
                    mIsScrolling = true;
                    mHandler.removeCallbacks(mLongPressRunnable);
                }

                if (mIsScrolling && mRenderer != null && mRenderer.mFontLineSpacing > 0) {
                    mScrollRemainder += dy;
                    int deltaRows = (int) (mScrollRemainder / mRenderer.mFontLineSpacing);
                    if (deltaRows != 0) {
                        mScrollRemainder -= deltaRows * mRenderer.mFontLineSpacing;
                        doScroll(-deltaRows);
                    }
                    mLastY = y;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                int upIndex = event.getActionIndex();
                if (event.getPointerId(upIndex) == mActivePointerId) {
                    int newIndex = upIndex == 0 ? 1 : 0;
                    if (newIndex < event.getPointerCount()) {
                        mActivePointerId = event.getPointerId(newIndex);
                        mLastY = event.getY(newIndex);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mLongPressRunnable);
                boolean wasScrolling = mIsScrolling || mLongPressed || mScrollMode;
                mIsScrolling = false;
                mLongPressed = false;
                mScrollMode = false;
                mActivePointerId = -1;
                if (wasScrolling) {
                    invalidate();
                } else {
                    performClick();
                    showKeyboardIfHidden();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mLongPressRunnable);
                mIsScrolling = false;
                mLongPressed = false;
                mScrollMode = false;
                mActivePointerId = -1;
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Enforce visible password mode to prevent keyboards from buffering/delaying input
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_FULLSCREEN;

        return new BaseInputConnection(this, true) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (text == null) return true;
                String str = text.toString();
                // Clear any previous composing text
                int oldLen = mComposingText.length();
                for (int i = 0; i < oldLen; i++) {
                    sendCharToTerminal('\u007F', false, false);
                }
                mComposingText = "";
                sendTextToTerminal(str);
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                if (text == null) return true;
                String str = text.toString();
                int oldLen = mComposingText.length();
                for (int i = 0; i < oldLen; i++) {
                    sendCharToTerminal('\u007F', false, false);
                }
                mComposingText = str;
                sendTextToTerminal(str);
                return true;
            }

            @Override
            public boolean finishComposingText() {
                mComposingText = "";
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) {
                    sendCharToTerminal('\u007F', false, false);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    return onKeyDown(event.getKeyCode(), event);
                } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && event.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN) {
                    String chars = event.getCharacters();
                    if (chars != null && !chars.isEmpty()) {
                        sendTextToTerminal(chars);
                        return true;
                    }
                }
                return true;
            }
        };
    }

    public void sendTextToTerminal(CharSequence text) {
        if (mTermSession == null || text == null) return;
        scrollToBottom();
        boolean ctrl = mExtraKeysView != null && mExtraKeysView.isCtrlActive();
        boolean alt = mExtraKeysView != null && mExtraKeysView.isAltActive();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sendCharToTerminal(c, ctrl, alt);
        }

        if (mExtraKeysView != null && (ctrl || alt)) {
            mExtraKeysView.consumeModifiers();
        }
    }

    public void sendCharToTerminal(char c, boolean ctrl, boolean alt) {
        if (mTermSession == null) return;
        if (ctrl) {
            if (c >= 'a' && c <= 'z') c = (char) (c - 'a' + 1);
            else if (c >= 'A' && c <= 'Z') c = (char) (c - 'A' + 1);
            else if (c == ' ' || c == '@') c = 0;
            else if (c == '[') c = 27;
            else if (c == '\\') c = 28;
            else if (c == ']') c = 29;
            else if (c == '^') c = 30;
            else if (c == '_') c = 31;
        }
        if (alt) {
            mTermSession.write("\u001B");
        }
        mTermSession.write(String.valueOf(c));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mTermSession == null) return super.onKeyDown(keyCode, event);
        scrollToBottom();

        boolean ctrl = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0 ||
                       (mExtraKeysView != null && mExtraKeysView.isCtrlActive());
        boolean alt = (event.getMetaState() & KeyEvent.META_ALT_ON) != 0 ||
                      (mExtraKeysView != null && mExtraKeysView.isAltActive());

        // Check KeyHandler translation with modifier state
        int metaState = event.getMetaState();
        if (ctrl) metaState |= KeyEvent.META_CTRL_ON;
        if (alt) metaState |= KeyEvent.META_ALT_ON;

        String code = KeyHandler.getCode(keyCode, metaState, false, false);
        if (code != null) {
            mTermSession.write(code);
            if (mExtraKeysView != null && (ctrl || alt)) {
                mExtraKeysView.consumeModifiers();
            }
            return true;
        }

        // CTRL + A-Z keys directly
        if (ctrl && keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            char ctrlChar = (char) (keyCode - KeyEvent.KEYCODE_A + 1);
            if (alt) mTermSession.write("\u001B");
            mTermSession.write(String.valueOf(ctrlChar));
            if (mExtraKeysView != null) mExtraKeysView.consumeModifiers();
            return true;
        }

        // Printable unicode characters
        int unicodeChar = event.getUnicodeChar(event.getMetaState() & ~KeyEvent.META_CTRL_ON & ~KeyEvent.META_ALT_ON);
        if (unicodeChar > 0) {
            sendCharToTerminal((char) unicodeChar, ctrl, alt);
            if (mExtraKeysView != null && (ctrl || alt)) {
                mExtraKeysView.consumeModifiers();
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    // TerminalSessionClient implementation
    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (changedSession == mTermSession) {
            invalidate();
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession, String newTitle) {}

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (finishedSession == mTermSession) {
            invalidate();
        }
    }

    @Override
    public void onClipboardCopy(TerminalSession session, String text) {}

    @Override
    public void onBell(TerminalSession session) {}
}
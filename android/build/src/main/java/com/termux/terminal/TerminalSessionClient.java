package com.termux.terminal;

/**
 * Callbacks from TerminalSession to client (UI).
 */
public interface TerminalSessionClient {
    void onTextChanged(TerminalSession changedSession);
    void onTitleChanged(TerminalSession changedSession, String newTitle);
    void onSessionFinished(TerminalSession finishedSession);
    void onClipboardCopy(TerminalSession session, String text);
    void onBell(TerminalSession session);
}

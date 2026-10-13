package com.solarized.firedown.p2pshare;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What a share screen does with the session that may already exist when it
 * opens. Pure Java (primitives in, a verdict out) so
 * {@code scripts/p2p-harness} can check every case on a plain JVM; the
 * screens only gather the inputs from {@link ShareState}.
 *
 * <p>Two shipped bugs are the reason it exists. (1) The Send screen attached
 * only to a LIVE session, so tapping the finished "Sent" notification (which
 * opens the screen with no arguments) or rotating the "Sent" screen started a
 * brand-new share of the same file: a new offer, the service and the hidden
 * session held for 15 minutes, READY on screen instead of the outcome. (2)
 * Starting a share always tore down the previous session, so opening Send
 * for another file, or pasting a code on the Receive screen, silently killed
 * a transfer running in the background.
 */
public final class P2pSessionPolicy {

    private P2pSessionPolicy() {
    }

    /** The Send screen's choice. */
    public enum SendEntry {
        /** Show the existing send session (live or its outcome). */
        ATTACH,
        /** Start a new share of the file the screen was opened for. */
        START,
        /** Another transfer is moving bytes; say so and do not start. */
        BUSY,
        /** Nothing to show (opened without a file and no send session exists). */
        NOTHING
    }

    /**
     * @param hasState        a session snapshot exists (live or terminal)
     * @param stateIsSend     it is a SEND session
     * @param stateLive       it has not reached DONE/ERROR
     * @param stateInTransfer it is connecting or moving bytes ({@link ShareState#isInTransfer()})
     * @param statePath       the file the send session shares (null for a receive)
     * @param argPath         the file the screen was opened for; null when it
     *                        was opened by the share notification
     * @param restored        the screen is being recreated (rotation, process
     *                        restore) — never a reason to start anything
     */
    @NonNull
    public static SendEntry sendEntry(boolean hasState, boolean stateIsSend, boolean stateLive,
                                      boolean stateInTransfer, @Nullable String statePath,
                                      @Nullable String argPath, boolean restored) {
        boolean sendSession = hasState && stateIsSend;
        if (argPath == null || restored) {
            return sendSession ? SendEntry.ATTACH : SendEntry.NOTHING;
        }
        if (sendSession && stateLive && argPath.equals(statePath)) {
            return SendEntry.ATTACH;
        }
        if (blocksNewSession(hasState, stateLive, stateInTransfer)) {
            return SendEntry.BUSY;
        }
        return SendEntry.START;
    }

    /**
     * Whether a new session (send or receive) must not replace the current
     * one: true only while it is connecting or moving bytes. A share still
     * waiting for an accept, or a finished one, is replaced as before — one
     * session at a time, the newest wins.
     */
    public static boolean blocksNewSession(boolean hasState, boolean stateLive,
                                           boolean stateInTransfer) {
        return hasState && stateLive && stateInTransfer;
    }
}

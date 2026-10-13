package com.solarized.firedown.p2pshare;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.solarized.firedown.R;

/**
 * The ONE mapping from a share error code to the honest user-facing message,
 * shared by the two screens and the service's final notification so the
 * phone never says two different things about one failure.
 */
public final class P2pShareCopy {

    private P2pShareCopy() {
    }

    /**
     * @param vpnActive     a VPN is up on THIS device — it is almost certainly
     *                      what killed the direct path (full-tunnel VPN =
     *                      symmetric NAT the user doesn't control) and, unlike
     *                      CGNAT, has a user-actionable fix, so it is named
     * @param relayMissing  the session had no relay credentials
     *                      ({@link ShareState#relayAvailable} FALSE)
     * @param arrivedRemote receiver only: the offer came by link/paste. A
     *                      no-path there is usually a DEAD SENDER, not a
     *                      topology problem: the offer code is self-contained
     *                      and the mailbox serves it for its whole TTL, so a
     *                      link previews convincingly long after the sender
     *                      closed the share. The copy says "may have closed"
     *                      rather than asserting a cause it hasn't verified
     *                      (a real CGNAT↔CGNAT pair lands here too). A VPN on
     *                      this device still wins — more specific, actionable.
     */
    @StringRes
    public static int errorText(@NonNull String code, boolean vpnActive, boolean arrivedRemote,
                                boolean relayMissing) {
        if ("no-path".equals(code)) {
            if (vpnActive) {
                return R.string.p2p_error_no_path_vpn;
            }
            if (relayMissing) {
                // The share ran without the relay (no credentials came back):
                // a cross-network pair had no path to try. Say that, not
                // "try the same Wi-Fi".
                return R.string.p2p_error_no_path_norelay;
            }
            return arrivedRemote ? R.string.p2p_error_no_path_link : R.string.p2p_error_no_path;
        }
        if (ShareState.ERROR_LOST.equals(code)) {
            return R.string.p2p_error_lost;
        }
        if (ShareState.ERROR_STALLED.equals(code)) {
            return R.string.p2p_error_stalled;
        }
        if (ShareState.NOTICE_BAD_CODE.equals(code)) {
            return R.string.p2p_error_bad_code;
        }
        if (ShareState.ERROR_TIMEOUT.equals(code)) {
            return R.string.p2p_error_timeout;
        }
        return R.string.p2p_error_generic;
    }
}

package com.solarized.firedown.p2pshare;

/**
 * When a cached TURN credential may still be handed to a NEW share session.
 * Pure arithmetic, kept out of the controller so {@code scripts/p2p-harness}
 * pins it on a plain JVM.
 *
 * <p>The rule it replaces reused a credential until a minute before its
 * expiry. coturn checks the credential's timestamp on EVERY authenticated
 * request, allocation refreshes included, so a share that started at minute
 * 55 of a one-hour credential had five minutes of relay: the refresh was
 * refused, the relay candidate died, ICE went disconnected and then failed —
 * on-device "fails even with the TURN server". With a minted offer now
 * waiting up to 15 minutes for an accept before any byte moves, the relay
 * allocation could even expire BEFORE the receiver accepted. A credential is
 * therefore reused only with {@link #MIN_REMAINING_MS} of life left; a fresh
 * one is fetched otherwise, and the still-valid old one is the fallback when
 * that fetch fails (a short relay life beats no relay).
 */
final class RelayCredsPolicy {

    private RelayCredsPolicy() {
    }

    /**
     * Life a credential must still have to be reused by a new session: the
     * offer's whole accept window plus a transfer of ordinary length.
     * (The API mints one-hour credentials by default; a longer
     * FIREDOWN_TURN_TTL_SECONDS on the server is what covers hour-long
     * relayed transfers — a credential cannot be swapped under a live
     * allocation.)
     */
    static final long MIN_REMAINING_MS = 30L * 60 * 1000;

    /** The safety margin before coturn stops honouring the credential. */
    static final long EXPIRY_MARGIN_MS = 60L * 1000;

    /** Wall-clock expiry to record for a credential minted now with {@code ttlSeconds}. */
    static long expiryFor(long nowMs, long ttlSeconds) {
        return nowMs + Math.max(0, ttlSeconds * 1000 - EXPIRY_MARGIN_MS);
    }

    /** True when a cached credential is fresh enough to start a new session on. */
    static boolean reusable(long nowMs, long expiryMs) {
        return expiryMs - nowMs >= MIN_REMAINING_MS;
    }

    /** True while the credential is still honoured at all (the fetch-failed fallback). */
    static boolean stillValid(long nowMs, long expiryMs) {
        return expiryMs > nowMs;
    }

    /**
     * Whether the network is worth trying the relay-credential fetch on.
     * Skips ONLY when the answer is certain: no active network at all, or a
     * captive portal (a login page answers every request). It used to also
     * require NET_CAPABILITY_VALIDATED, which a network shown as "connected,
     * no internet" never gets even when it does reach the internet (blocked
     * probe servers, partial connectivity, some VPNs and firewalls), so those
     * shares lost the relay for good. Unknown capabilities read as "try".
     */
    static boolean networkAllowsFetch(boolean hasNetwork, boolean capabilitiesKnown,
                                      boolean internet, boolean captivePortal) {
        if (!hasNetwork) {
            return false;
        }
        if (!capabilitiesKnown) {
            return true;
        }
        return internet && !captivePortal;
    }
}

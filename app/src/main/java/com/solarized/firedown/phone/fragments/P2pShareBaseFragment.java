package com.solarized.firedown.phone.fragments;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavBackStackEntry;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.R;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.p2pshare.P2pShareCopy;
import com.solarized.firedown.p2pshare.ShareState;
import com.solarized.firedown.utils.ClipboardHelper;
import com.solarized.firedown.utils.NavigationUtils;
import com.solarized.firedown.utils.QrCodes;

import javax.inject.Inject;

/**
 * Shared plumbing for the two P2P share screens (send / receive). Both are
 * WINDOWS onto {@link P2pShareController}'s session, never its owner:
 *
 * <ul>
 *   <li><b>Render from the published snapshot.</b> The screen observes
 *       {@link P2pShareController#getState()} and repaints the whole stage
 *       from each {@link ShareState} ({@link #render}); it keeps no session
 *       state of its own, so a screen opened onto a running session (the
 *       notification's tap, a rotation) shows exactly what the one that
 *       started it would. A one-shot {@link ShareState#notice} is shown once
 *       per {@link ShareState#noticeSeq}.</li>
 *   <li><b>Leaving the screen does not end a transfer</b> — the foreground
 *       service carries it and the notification brings the user back. Back
 *       and the toolbar's up simply leave; only the explicit Cancel (screen
 *       or notification) stops a session, confirmed while bytes are moving.
 *       What a screen MUST do on leaving is release the things that are
 *       screen-scoped (the receiver's nearby visibility, a pre-accept
 *       preview, a shown outcome) — each subclass's onDestroyView.</li>
 *   <li>QR encode, share-sheet/clipboard code exchange, scan-result
 *       observation, keep-screen-on, the honest footer.</li>
 * </ul>
 *
 * <p>Hilt: the {@code @Inject} field lives here (unannotated-base pattern);
 * the concrete fragments carry {@code @AndroidEntryPoint}.
 */
public abstract class P2pShareBaseFragment extends BaseFocusFragment {

    @Inject
    protected P2pShareController mP2pController;

    private int mShownNoticeSeq;
    private boolean mVpnRelayHintShown;

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (mToolbar != null) {
            mToolbar.setNavigationOnClickListener(v -> close());
        }
        // The footer sits below the scroll area — pad it clear of the nav bar.
        View footer = view.findViewById(R.id.p2p_footer);
        if (footer != null) {
            int basePadding = footer.getPaddingBottom();
            ViewCompat.setOnApplyWindowInsetsListener(footer, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        basePadding + bars.bottom);
                return windowInsets;
            });
        }
        observeScanResult();
        mP2pController.getState().observe(getViewLifecycleOwner(), this::onStateChanged);
    }

    private void onStateChanged(@Nullable ShareState state) {
        if (getView() == null) {
            return;
        }
        if (state != null && state.notice != null && state.noticeSeq != mShownNoticeSeq) {
            mShownNoticeSeq = state.noticeSeq;
            showNotice(state);
            // Shown: drop it from the state so a screen that attaches later
            // (rotation, the notification) does not show it again.
            mP2pController.consumeNotice(state.noticeSeq);
        }
        render(state);
    }

    /** Repaint the whole screen from the snapshot ({@code null} = no session). */
    protected abstract void render(@Nullable ShareState state);

    private void showNotice(@NonNull ShareState state) {
        String arg = state.noticeArg == null ? "" : state.noticeArg;
        switch (state.notice == null ? "" : state.notice) {
            case ShareState.NOTICE_DECLINED -> makeSnack(R.string.p2p_declined);
            case ShareState.NOTICE_UNREACHABLE -> makeSnack(getString(R.string.p2p_nearby_unreachable, arg));
            case ShareState.NOTICE_BUSY -> makeSnack(getString(R.string.p2p_nearby_busy, arg));
            case ShareState.NOTICE_BAD_CODE -> makeSnack(R.string.p2p_error_bad_code);
            default -> {
            }
        }
    }

    /**
     * The explicit Cancel: confirmed while bytes are moving (an accidental
     * tap must not silently discard a half-done transfer), immediate
     * otherwise. Ends the session and leaves.
     */
    protected void confirmCancel() {
        if (mP2pController.isTransferActive()) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.p2p_abandon_message)
                    .setPositiveButton(R.string.p2p_abandon_confirm, (d, w) -> {
                        mP2pController.stop();
                        close();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            mP2pController.stop();
            close();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Keep the screen on: a QR may be shown across the room and a
        // transfer's progress is being watched.
        if (mActivity != null) {
            mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onPause() {
        if (mActivity != null) {
            mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onPause();
    }

    /** The nav destination id of the concrete screen (for pop). */
    protected abstract int destinationId();

    /** A code arrived from the scanner (or its paste fallback). */
    protected abstract void onCodeScanned(@NonNull String code);

    protected void close() {
        NavigationUtils.popBackStackSafe(mNavController, destinationId());
    }

    /**
     * The scanner returns its result through the previous back-stack entry's
     * SavedStateHandle (the CancelOperationDialogFragment pattern). Observed
     * on the LiveData so the value survives the navigation round-trip.
     */
    private void observeScanResult() {
        if (mNavController == null) {
            return;
        }
        NavBackStackEntry entry = mNavController.getCurrentBackStackEntry();
        if (entry == null) {
            return;
        }
        entry.getSavedStateHandle().<String>getLiveData(P2pScanFragment.RESULT_CODE)
                .observe(getViewLifecycleOwner(), code -> {
                    if (code == null || code.isEmpty()) {
                        return;
                    }
                    // Consume: a config change must not re-deliver the code.
                    entry.getSavedStateHandle().set(P2pScanFragment.RESULT_CODE, "");
                    onCodeScanned(code);
                });
    }

    protected void navigateToScanner(@NonNull String expectedPrefix, boolean reply) {
        Bundle bundle = new Bundle();
        bundle.putString(P2pScanFragment.ARG_PREFIX, expectedPrefix);
        bundle.putBoolean(P2pScanFragment.ARG_REPLY, reply);
        NavigationUtils.navigateSafe(mNavController, R.id.p2p_scan, bundle);
    }

    /**
     * @return true if the QR rendered; false if the content was too large to
     * encode (the caller should fall back to "send code another way" rather
     * than leave a blank white box).
     */
    protected boolean setQr(@Nullable ImageView imageView, @NonNull String content) {
        if (imageView == null) {
            return false;
        }
        Bitmap code = QrCodes.encode(content);
        if (code != null) {
            imageView.setImageBitmap(code);
            return true;
        }
        return false;
    }

    /** Offer the code through the share sheet (messenger, email, …). */
    protected void shareCode(@NonNull String code) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, code);
        startActivity(Intent.createChooser(intent, getString(R.string.p2p_share_code)));
    }

    /**
     * Paste fallback for a code that arrived over a messenger. Returns null
     * (with a snackbar) when the clipboard holds nothing usable.
     */
    @Nullable
    protected String readCodeFromClipboard(@NonNull String expectedPrefix) {
        // Accept both a bare code and a firedown://p2p/<code> deep link (a user
        // may copy either the code or the share-sheet link).
        String code = P2pShareController.stripDeepLink(ClipboardHelper.readTrimmedText(getContext()));
        if (code.isEmpty()) {
            makeSnack(R.string.p2p_clipboard_empty);
            return null;
        }
        if (!code.startsWith(expectedPrefix)) {
            makeSnack(R.string.p2p_error_bad_code);
            return null;
        }
        return code;
    }

    protected void makeSnack(int textResId) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, textResId, Snackbar.LENGTH_LONG).show();
        }
    }

    protected void makeSnack(@NonNull CharSequence text) {
        View view = getView();
        if (view != null) {
            Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
        }
    }

    /** The honest message for a terminal error (see {@link P2pShareCopy}). */
    protected int errorText(@NonNull ShareState state) {
        String code = state.errorCode == null ? ShareState.ERROR_ENGINE : state.errorCode;
        return P2pShareCopy.errorText(code, isVpnActive(), state.arrivedRemote,
                Boolean.FALSE.equals(state.relayAvailable));
    }

    /**
     * Honest footer: "never touches a server" is set ONLY once the engine has
     * actually read the selected candidate pair ({@link ShareState#relayed}
     * non-null). reportTransport is best-effort and stays silent when
     * getStats is missing, its promise rejects, or no pair matches — with an
     * optimistic default, every one of those left a RELAYED transfer claiming
     * the file never touched a server. An unverified privacy claim degrades
     * to the weaker statement, never to the stronger one.
     */
    protected void bindFooter(@NonNull View root, @Nullable ShareState state) {
        TextView footer = root.findViewById(R.id.p2p_footer);
        if (footer == null) {
            return;
        }
        if (state == null || state.relayed == null) {
            footer.setText(R.string.p2p_footer_connecting);
        } else {
            footer.setText(state.relayed ? R.string.p2p_footer_relayed : R.string.p2p_footer);
            maybeHintVpnRelay(state.relayed);
        }
    }

    /** The shared transfer card: bar, "x of y · rate", the reconnecting spinner. */
    protected void bindProgress(@NonNull View root, @NonNull ShareState state) {
        LinearProgressIndicator bar = root.findViewById(R.id.p2p_progress_bar);
        if (state.total > 0) {
            bar.setProgress((int) (state.done * 100 / state.total));
        }
        ((TextView) root.findViewById(R.id.p2p_progress_text)).setText(
                getString(R.string.p2p_progress_of,
                        Formatter.formatShortFileSize(requireContext(), state.done),
                        Formatter.formatShortFileSize(requireContext(), state.total),
                        Formatter.formatShortFileSize(requireContext(), state.rate)));
        root.findViewById(R.id.p2p_reconnecting)
                .setVisibility(state.reconnecting ? View.VISIBLE : View.GONE);
    }

    /**
     * True when the device's active network is a VPN. Best-effort: any failure
     * reads as "no VPN" so the hint machinery can never break a share. Binder
     * reads of connectivity state get the same defensive treatment as the
     * clipboard chip (a decorative affordance never gets to crash the app).
     */
    protected boolean isVpnActive() {
        try {
            Context context = getContext();
            if (context == null) {
                return false;
            }
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * One-time hint when the transfer is running but RELAYED and a VPN is up:
     * the user can get a direct connection next time by excluding Firedown
     * from the VPN (or pausing it). Once per share screen — the transfer
     * works, so one nudge is enough; repeating it on every repaint would be
     * noise.
     */
    private void maybeHintVpnRelay(boolean relayed) {
        if (!relayed || mVpnRelayHintShown || !isVpnActive()) {
            return;
        }
        mVpnRelayHintShown = true;
        makeSnack(R.string.p2p_hint_vpn);
    }
}

package com.solarized.firedown.phone.fragments;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.snackbar.Snackbar;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.Keys;
import com.solarized.firedown.R;
import com.solarized.firedown.StoragePaths;
import com.solarized.firedown.data.RestoredFileAccess;
import com.solarized.firedown.data.entity.DownloadEntity;
import com.solarized.firedown.p2pshare.P2pNearbyDiscovery;
import com.solarized.firedown.p2pshare.P2pSessionPolicy;
import com.solarized.firedown.p2pshare.P2pShareController;
import com.solarized.firedown.p2pshare.ShareState;
import com.solarized.firedown.utils.ClipboardHelper;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.FragmentArgs;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * "Send directly" — the sender side of the P2P share. The engine mints a
 * WebRTC offer; it reaches the other phone one of three ways — a tap on it
 * in the NEARBY list (phones with Receive open on this Wi-Fi, found over
 * NSD; the whole offer is POSTed to the phone), a LINK through any messenger
 * ("Anywhere"), or the QR folded under it — and the answer comes back over
 * the LAN or the rendezvous mailbox (or, last resort, pasted/scanned); then
 * the file streams device-to-device over the DataChannel.
 *
 * <p>Screen walk: PREPARING (spinner) → READY (nearby list + link) →
 * CONNECTING → TRANSFER → DONE / ERROR (with "Try again"). Rendered from the
 * controller's {@link ShareState}; the session outlives this screen (the
 * notification brings the user back, and this fragment then ATTACHES to the
 * running session instead of starting another).
 */
@AndroidEntryPoint
public class P2pSendFragment extends P2pShareBaseFragment {

    private DownloadEntity mDownloadEntity;
    private View mView;
    private boolean mQrShown;
    private String mRenderedQrCode;
    private String mLastClipTried;
    private P2pNearbyDiscovery mDiscovery;
    private List<P2pNearbyDiscovery.Device> mDevices = Collections.emptyList();
    private ShareState mLast;
    // Decided in onCreateView (P2pSessionPolicy), acted on in onViewCreated.
    private boolean mStartOnCreate;

    /** Clipboard auto-pickup runs on WINDOW FOCUS gain, not onResume: since
     *  API 29 getPrimaryClip() returns null unless the app currently holds
     *  input focus, which is granted AFTER onResume — an onResume read comes
     *  back empty on modern devices and the pickup would silently never fire. */
    private final ViewTreeObserver.OnWindowFocusChangeListener mFocusListener =
            hasFocus -> {
                if (hasFocus) {
                    maybePickupReplyFromClipboard();
                }
            };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Null when reopened from the service notification — the live
        // session's entity is used then (onCreateView).
        mDownloadEntity = FragmentArgs.parcelable(this, Keys.ITEM_ID, DownloadEntity.class);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // Attach to the send session that exists (live, or its outcome when
        // the finished notification was tapped or the screen rotated), start
        // a share of the file the screen was opened for, or refuse while
        // another transfer moves bytes — never silently replace it.
        ShareState live = mP2pController.peekState();
        String argPath = mDownloadEntity == null ? null : mDownloadEntity.getFilePath();
        P2pSessionPolicy.SendEntry entry = P2pSessionPolicy.sendEntry(
                live != null,
                live != null && live.role == ShareState.Role.SEND,
                live != null && live.isLive(),
                live != null && live.isInTransfer(),
                live != null && live.entity != null ? live.entity.getFilePath() : null,
                argPath,
                savedInstanceState != null);
        switch (entry) {
            case ATTACH -> mDownloadEntity = live.entity;
            case START -> mStartOnCreate = true;
            case BUSY -> {
                Snackbar.make(requireActivity().findViewById(Window.ID_ANDROID_CONTENT),
                        R.string.p2p_busy_other_transfer, Snackbar.LENGTH_LONG).show();
                mDownloadEntity = null;
            }
            case NOTHING -> mDownloadEntity = null;
        }
        // Defensive: vault entries must never be shareable, and a screen with
        // nothing to send (no arg, no session, or another transfer busy) has
        // nothing to show.
        if (mDownloadEntity == null || mDownloadEntity.isFileSafe()
                || mDownloadEntity.getFilePath() == null) {
            mStartOnCreate = false;
            if (mNavController != null) {
                mNavController.popBackStack();
            }
            return null;
        }

        mView = inflater.inflate(R.layout.fragment_p2p_send, container, false);
        mToolbar = mView.findViewById(R.id.toolbar);

        File file = new File(mDownloadEntity.getFilePath());
        String mime = FileUriHelper.getMimeTypeFromFile(file.getName());

        ShapeableImageView thumb = mView.findViewById(R.id.p2p_thumb);
        GlideHelper.load(mDownloadEntity, new RequestOptions(), thumb);
        ((TextView) mView.findViewById(R.id.p2p_file_name)).setText(file.getName());
        String size = StoragePaths.convertToStringRepresentation(
                RestoredFileAccess.length(requireContext(), mDownloadEntity.getFilePath()));
        String mimeWord = FileUriHelper.getLongMimeText(requireContext(), mime);
        ((TextView) mView.findViewById(R.id.p2p_file_tag)).setText(
                mimeWord != null
                        ? String.format(Locale.ROOT, "%s · %s", mimeWord, size)
                        : size);

        mView.findViewById(R.id.p2p_send_short).setOnClickListener(v -> shareOfferLink(false));
        mView.findViewById(R.id.p2p_send_private).setOnClickListener(v -> shareOfferLink(true));
        // "Send link again" (waiting card) re-shares whichever mode was chosen.
        mView.findViewById(R.id.p2p_send_link_again).setOnClickListener(v ->
                shareOfferLink(mLast != null && mLast.linkPrivate));
        mView.findViewById(R.id.p2p_show_qr).setOnClickListener(v -> {
            mQrShown = !mQrShown;
            updateQr();
        });
        mView.findViewById(R.id.p2p_scan_reply).setOnClickListener(v ->
                navigateToScanner(P2pShareController.ANSWER_PREFIX, true));
        mView.findViewById(R.id.p2p_paste_reply).setOnClickListener(v -> {
            String code = readCodeFromClipboard(P2pShareController.ANSWER_PREFIX);
            if (code != null) {
                mP2pController.provideAnswer(code);
            }
        });
        mView.findViewById(R.id.p2p_stop).setOnClickListener(v -> onStopTapped());
        mView.findViewById(R.id.p2p_retry).setOnClickListener(v -> restart());

        mView.getViewTreeObserver().addOnWindowFocusChangeListener(mFocusListener);

        return mView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Decided in onCreateView: attach, or start a share for this file.
        if (mStartOnCreate) {
            mStartOnCreate = false;
            mP2pController.startSend(mDownloadEntity);
        }
        // The nearby list lives with the view: discovery costs nothing to run
        // through every stage and is shown only while READY.
        mDiscovery = new P2pNearbyDiscovery(requireContext(), this::onDevicesChanged);
        mDiscovery.start();
    }

    @Override
    public void onDestroyView() {
        if (mDiscovery != null) {
            mDiscovery.stop();
            mDiscovery = null;
        }
        if (mView != null) {
            mView.getViewTreeObserver().removeOnWindowFocusChangeListener(mFocusListener);
        }
        // Leaving keeps a RUNNING session (the service carries it); a shown
        // outcome is consumed so the next Send opens clean.
        ShareState state = mP2pController.peekState();
        if (state != null && state.role == ShareState.Role.SEND && !state.isLive()) {
            mP2pController.dismissFinished();
        }
        mView = null;
        super.onDestroyView();
    }

    @Override
    protected int destinationId() {
        return R.id.p2p_send;
    }

    @Override
    protected void onCodeScanned(@NonNull String code) {
        mP2pController.provideAnswer(code);
    }

    private void restart() {
        if (mP2pController.isBusyWithTransfer()) {
            makeSnack(R.string.p2p_busy_other_transfer); // startSend would refuse silently
            return;
        }
        mQrShown = false;
        mRenderedQrCode = null;
        mLastClipTried = null;
        mP2pController.startSend(mDownloadEntity);
    }

    private void onStopTapped() {
        if (mLast != null && !mLast.isLive()) {
            mP2pController.dismissFinished();
            close();
        } else {
            confirmCancel();
        }
    }

    /**
     * Share the offer as an https link (firedown.app/s#&lt;code&gt;), in one of two
     * modes. {@code privateMode} false = the SHORT link: the controller brokers
     * the offer lazily ({@link P2pShareController#resolveShortLink}) and hands
     * back the compact {@code FDO1.<id>} reference (or the full link on
     * timeout/failure). {@code privateMode} true = the PRIVATE link: the full
     * self-contained {@code FDS1.} code, which uploads nothing to a server.
     * Sharing marks the session link-shared (the waiting card) — on the
     * STATE, so a sender who leaves and returns still sees it.
     */
    private void shareOfferLink(boolean privateMode) {
        if (mLast == null || mLast.offerCode == null) {
            return;
        }
        if (privateMode) {
            // The whole offer rides in the link — nothing is uploaded to a server.
            completeShare(P2pShareController.toHttpsLink(mLast.offerCode), true);
        } else {
            // Short: broker the offer LAZILY (first tap uploads it), bounded so a
            // slow network can't hang the share sheet — the controller hands back
            // the short link or the full one on timeout/failure. Never a dead tap.
            mP2pController.resolveShortLink(link -> completeShare(link, false));
        }
    }

    /** Fire the OS share sheet with {@code content} and mark the session
     *  link-shared. Guarded for a null content and a torn-down view (the
     *  short-link path resolves asynchronously). */
    private void completeShare(@Nullable String content, boolean privateMode) {
        if (mView == null || content == null) {
            return;
        }
        shareCode(content);
        mP2pController.markLinkShared(privateMode);
    }

    /**
     * Auto-pickup: a sender who copied the receiver's reply in a messenger and
     * switched back shouldn't have to find a paste button. Fired from
     * {@link #mFocusListener} on window-focus gain (see its note on the API 29
     * focus requirement). Only while READY with a link out, silent when the
     * clipboard has no reply, and each clip value is tried once — a soft
     * bad-code snack must not re-fire on every focus flip. Android's paste
     * toast makes the read visible to the user; that's honest, not a bug.
     */
    private void maybePickupReplyFromClipboard() {
        if (mView == null || mLast == null || !mLast.linkShared
                || mLast.stage != ShareState.Stage.READY) {
            return;
        }
        // A clip copied before this share started is a reply to an EARLIER
        // offer; feeding it in would only draw a "not valid" message (the
        // engine refuses another offer's answer).
        long copiedAt = ClipboardHelper.primaryClipCopiedAt(getContext());
        if (copiedAt > 0 && copiedAt < mLast.startedAt) {
            return;
        }
        String code = P2pShareController.stripDeepLink(
                ClipboardHelper.readTrimmedText(getContext()));
        if (!code.startsWith(P2pShareController.ANSWER_PREFIX)
                || code.equals(mLastClipTried)) {
            return;
        }
        mLastClipTried = code;
        makeSnack(R.string.p2p_reply_detected);
        mP2pController.provideAnswer(code);
    }

    /* ── nearby list ────────────────────────────────────────────────────── */

    private void onDevicesChanged(@NonNull List<P2pNearbyDiscovery.Device> devices) {
        mDevices = devices;
        renderDevices();
    }

    /**
     * Rebuild the rows (a handful at most — no adapter warranted). A row's
     * second line is its state: the phone the offer was handed to reads
     * "Sent · waiting", every other "Tap to send". Tapping another phone while
     * one is pending is allowed — the first answer to arrive wins.
     */
    private void renderDevices() {
        if (mView == null) {
            return;
        }
        LinearLayout list = mView.findViewById(R.id.p2p_nearby_list);
        list.removeAllViews();
        boolean ready = mLast != null && mLast.stage == ShareState.Stage.READY;
        String pending = mLast == null ? null : mLast.peerDevice;
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (P2pNearbyDiscovery.Device device : mDevices) {
            View row = inflater.inflate(R.layout.item_p2p_device, list, false);
            ((TextView) row.findViewById(R.id.p2p_device_name)).setText(device.name);
            boolean sentHere = device.name.equals(pending);
            ((TextView) row.findViewById(R.id.p2p_device_status)).setText(
                    sentHere ? R.string.p2p_nearby_sent : R.string.p2p_nearby_tap);
            row.setEnabled(ready);
            row.setAlpha(ready ? 1f : 0.5f);
            row.setOnClickListener(v -> mP2pController.sendOfferToNearby(device));
            list.addView(row);
        }
        mView.findViewById(R.id.p2p_nearby_empty)
                .setVisibility(mDevices.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /* ── rendering ──────────────────────────────────────────────────────── */

    @Override
    protected void render(@Nullable ShareState state) {
        if (mView == null) {
            return;
        }
        if (state == null || state.role != ShareState.Role.SEND) {
            // No session (cancelled from the notification while this screen
            // was open, or between stop() and close()): nothing to show.
            mLast = null;
            showStage(R.id.p2p_status_group);
            ((TextView) mView.findViewById(R.id.p2p_status)).setText(R.string.p2p_preparing);
            mView.findViewById(R.id.p2p_stop).setVisibility(View.GONE);
            bindFooter(mView, null);
            return;
        }
        mLast = state;
        mView.findViewById(R.id.p2p_live_dot)
                .setVisibility(state.isLive() ? View.VISIBLE : View.GONE);
        MaterialButton stop = mView.findViewById(R.id.p2p_stop);
        TextView status = mView.findViewById(R.id.p2p_status);
        switch (state.stage) {
            case PREPARING, OFFERED -> {
                showStage(R.id.p2p_status_group);
                status.setText(R.string.p2p_preparing);
                stop.setVisibility(View.GONE);
            }
            case READY -> {
                showStage(R.id.p2p_ready_group);
                updateReadySubstages(state);
                updateQr();
                renderDevices();
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case CONNECTING, CONNECTED -> {
                showStage(R.id.p2p_status_group);
                status.setText(state.stage == ShareState.Stage.CONNECTED
                        ? R.string.p2p_state_connected : R.string.p2p_state_connecting);
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case TRANSFERRING -> {
                showStage(R.id.p2p_progress_group);
                bindProgress(mView, state);
                stop.setText(R.string.cancel);
                stop.setVisibility(View.VISIBLE);
            }
            case DONE -> {
                showStage(R.id.p2p_done_group);
                ((TextView) mView.findViewById(R.id.p2p_done_text)).setText(
                        getString(R.string.p2p_done_sent_detail,
                                Formatter.formatShortFileSize(requireContext(), state.bytes)));
                stop.setText(R.string.p2p_done);
                stop.setVisibility(View.VISIBLE);
            }
            case ERROR -> {
                showStage(R.id.p2p_error_group);
                ((TextView) mView.findViewById(R.id.p2p_error)).setText(errorText(state));
                stop.setVisibility(View.GONE);
            }
        }
        bindFooter(mView, state);
    }

    /** The READY stage's Anywhere card: share actions pre-share, the waiting
     *  card once a link went out. The actions hide rather than relabelling,
     *  so a static status never sits next to a control dressed the same way. */
    private void updateReadySubstages(@NonNull ShareState state) {
        mView.findViewById(R.id.p2p_waiting_group)
                .setVisibility(state.linkShared ? View.VISIBLE : View.GONE);
        mView.findViewById(R.id.p2p_send_actions)
                .setVisibility(state.linkShared ? View.GONE : View.VISIBLE);
    }

    /** The QR fold: rendered lazily, once per offer code. */
    private void updateQr() {
        if (mView == null) {
            return;
        }
        MaterialButton toggle = mView.findViewById(R.id.p2p_show_qr);
        toggle.setText(mQrShown ? R.string.p2p_hide_qr : R.string.p2p_show_qr);
        View group = mView.findViewById(R.id.p2p_qr_group);
        String code = mLast == null ? null : mLast.offerCode;
        if (!mQrShown || code == null) {
            group.setVisibility(View.GONE);
            return;
        }
        group.setVisibility(View.VISIBLE);
        if (code.equals(mRenderedQrCode)) {
            return;
        }
        mRenderedQrCode = code;
        // Encode the deep link (not the bare code) so a scan with the phone's
        // own camera offers "open in Firedown"; the in-app scanner unwraps it.
        boolean rendered = setQr(mView.findViewById(R.id.p2p_qr),
                P2pShareController.toDeepLink(code));
        // If the code is too large to fit a QR, don't leave a blank white box
        // — hide it and steer the user to the link.
        mView.findViewById(R.id.p2p_qr_card).setVisibility(rendered ? View.VISIBLE : View.GONE);
        ((TextView) mView.findViewById(R.id.p2p_send_hint)).setText(
                rendered ? R.string.p2p_code_hint_send : R.string.p2p_qr_too_large);
    }

    private void showStage(int visibleId) {
        int[] stages = {R.id.p2p_status_group, R.id.p2p_ready_group, R.id.p2p_progress_group,
                R.id.p2p_done_group, R.id.p2p_error_group};
        for (int id : stages) {
            mView.findViewById(id).setVisibility(id == visibleId ? View.VISIBLE : View.GONE);
        }
    }
}

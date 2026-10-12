package com.solarized.firedown.phone.fragments;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.text.Editable;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import com.solarized.firedown.ui.IncognitoColors;
import com.solarized.firedown.Keys;
import com.solarized.firedown.Preferences;
import com.solarized.firedown.R;
import com.solarized.firedown.data.entity.GeckoStateEntity;
import com.solarized.firedown.data.entity.AutoCompleteEntity;
import com.solarized.firedown.autocomplete.AutoCompleteViewModel;
import com.solarized.firedown.data.models.BrowserDialogViewModel;
import com.solarized.firedown.data.models.BrowserURIViewModel;
import com.solarized.firedown.data.models.GeckoStateViewModel;
import com.solarized.firedown.data.models.IncognitoStateViewModel;
import com.solarized.firedown.geckoview.GeckoState;
import com.solarized.firedown.geckoview.GeckoToolbar;
import com.solarized.firedown.geckoview.HomeToolbarDock;
import com.solarized.firedown.manager.DownloadRequest;

import com.solarized.firedown.phone.DownloadsActivity;
import com.solarized.firedown.phone.SettingsActivity;
import com.solarized.firedown.sync.CloudBackupManager;
import com.solarized.firedown.sync.VaultBackupWorker;
import com.solarized.firedown.sync.StorageApiClient;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.solarized.firedown.phone.VaultActivity;
import com.solarized.firedown.autocomplete.AutoCompleteEditText;
import com.solarized.firedown.autocomplete.AutoCompleteView;
import com.solarized.firedown.autocomplete.AutoCompleteViewBehavior;
import com.solarized.firedown.geckoview.toolbar.BottomNavigationBar;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.adapters.SearchAutocompleteAdapter;
import com.solarized.firedown.ui.diffs.SearchDiffCallback;
import com.solarized.firedown.IntentActions;
import com.solarized.firedown.utils.NavigationUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
public class HomeFragment extends BaseBrowserFragment implements BottomNavigationBar.OnBottomBarListener,
        AutoCompleteEditText.OnCommitListener, AutoCompleteEditText.OnFilterListener, AutoCompleteEditText.OnFocusChangedListener,
        AutoCompleteEditText.OnTextChangedListener, AutoCompleteEditText.OnSearchStateChangeListener,
        GeckoToolbar.OnToolbarListener , OnItemClickListener {


    private static final String TAG = HomeFragment.class.getName();
    private BrowserURIViewModel mBrowserURIViewModel;
    private BrowserDialogViewModel mBrowserDialogViewModel;
    private GeckoStateViewModel mGeckoStateViewModel;
    private IncognitoStateViewModel mIncognitoStateViewModel;
    private AutoCompleteEditText mAutoCompleteEditText;
    private AutoCompleteView mAutoCompleteView;
    private View mNewTabView;
    private GeckoToolbar mGeckoToolbar;
    private BottomNavigationBar mBottomNavigationBar;
    private View mHomeScroll;
    // The home is the mark over the field (home v6): the flame, the wordmark
    // and the search pill, centred — nothing else at rest. The pill is the
    // REAL GeckoToolbar, docked over a slot in the content by HomeToolbarDock
    // and raised to the top on focus; see the dock's class doc and the layout
    // file comment. The one-line pair of counters that used to sit under the
    // wordmark (trackers blocked · bytes saved) went with it: it wrapped onto
    // two rows on 360dp phones in the long locales, and a figure is not what
    // the home is for. Everything else (tabs, downloads badge, vault) lives in
    // the bottom bar / menu — no dashboard.
    private View mBrandMark;
    private HomeToolbarDock mToolbarDock;

    // Cloud Backup chip/card (home v5): ALL cloud state on home lives HERE —
    // never as a third subtitle counter (that was built and reverted; see the
    // layout comment). Two EVIDENCE-based states only, see applyBackupPill:
    // the "Paused" CARD (metered credit ran out, setUp-gated) and the
    // "Backing up…" / "Waiting to back up" CHIP (a live backup WorkInfo).
    // Nothing is shown at rest. The resting "N backed up" line and the
    // "Show backup status on home" switch that gated it were REMOVED together
    // (home v5): the line was a standing claim the maintainer had already
    // demoted once, the switch existed only to let the user silence it, and
    // the two states that remain need no opt-out — a transient chip clears
    // itself and a deletion countdown must never be hideable.
    /** The transfer chip — "Backing up…" / "Waiting to back up" ONLY; GONE
     *  otherwise. Its ground, ink and upload glyph are static XML. */
    private MaterialCardView mBackupPill;
    private TextView mBackupPillText;
    /** The deadline card — a different SILHOUETTE from the chip, never shown
     *  at the same time. See applyBackupPill. */
    private MaterialCardView mBackupCard;
    private TextView mBackupCardTitle;
    private TextView mBackupCardDetail;
    /** An identified backup worker is actually TRANSFERRING right now. */
    private boolean mCloudRunning;
    /** …or merely enqueued (constraints unmet / retry backoff) — rendered as
     *  "Waiting to back up", never "Backing up…". */
    private boolean mCloudQueued;
    /** Where a chip/card tap goes, decided when the state was RENDERED
     *  (transfer → Backups list, Paused → Cloud screen). */
    private boolean mPillToFiles;
    /** Drops a stale loadStatus result when a newer refresh started (two rapid
     *  resumes complete in NETWORK order, not call order). */
    private int mCloudStatusGen;
    private StorageApiClient.Quota mCloudQuota;
    // The brand flame doubles as the live "a download is running" indicator:
    // a soft ember glow that breathes behind the logo while the active+queued
    // count (the same signal as the bottom-bar badge) is > 0, and is GONE
    // otherwise so the idle home is just the wordmark. Identity-as-status, no
    // extra widget — status that's actionable lives on the bottom bar (the
    // badge); the home only gives the calm ambient heartbeat.
    private View mBrandGlow;
    private Animator mBrandGlowAnimator;
    @Inject
    CloudBackupManager mCloudBackup;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAutoCompleteViewModel = new ViewModelProvider(this).get(AutoCompleteViewModel.class);
        mGeckoStateViewModel = new ViewModelProvider(mActivity).get(GeckoStateViewModel.class);
        mIncognitoStateViewModel = new ViewModelProvider(mActivity).get(IncognitoStateViewModel.class);
        mBrowserURIViewModel = new ViewModelProvider(mActivity).get(BrowserURIViewModel.class);
        mBrowserDialogViewModel = new ViewModelProvider(mActivity).get(BrowserDialogViewModel.class);


        // This callback will only be called when MyFragment is at least Started.
        OnBackPressedCallback callback = new OnBackPressedCallback(true /* enabled by default */) {
            @Override
            public void handleOnBackPressed() {
                if (dismissAutocompleteOverlayIfVisible()) return;
                setEnabled(false);
                mActivity.getOnBackPressedDispatcher().onBackPressed();
            }
        };

        mActivity.getOnBackPressedDispatcher().addCallback(this, callback);

    }

    @Override
    public void onResume() {
        super.onResume();
        // Being on Home means the user is on the home tab; if the tab list is
        // empty (e.g. just closed the last tab), materialise it so the count and
        // the Tabs screen agree that there's >=1 tab. No-op when a tab exists.
        if (mGeckoStateViewModel != null) {
            mGeckoStateViewModel.ensureHomeTabIfEmpty();
        }
        // Cloud Backup status can change while away (a backup finishes, credit is
        // added, or it's set up for the first time) — recompute the home line.
        refreshCloudStatus();
    }

    /**
     * Back handling while the URL-bar autocomplete overlay is up — TWO steps,
     * so a single back never skips one. While the soft keyboard is showing, the
     * first back only LOWERS it (the overlay and the typed query stay); a second
     * back (keyboard already down) dismisses the overlay. Returns true when it
     * consumed the press, false (overlay not visible) so the caller falls
     * through to its default back behavior.
     */
    private boolean dismissAutocompleteOverlayIfVisible() {
        if (mAutoCompleteView == null || mAutoCompleteView.getVisibility() != View.VISIBLE) {
            return false;
        }
        hideKeyboard(mAutoCompleteEditText);
        mGeckoToolbar.clearFocus();
        mGeckoToolbar.startAnimation(false);
        mGeckoToolbar.updateViewVisibility(false);
        mAutoCompleteView.updateVisibility(false);
        return true;
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        View v = inflater.inflate(R.layout.fragment_home, container, false);

        mNewTabView = v.findViewById(R.id.bottom_new_tab);
        mAutoCompleteView = v.findViewById(R.id.auto_complete_view);

        mHomeScroll = v.findViewById(R.id.home_scroll);
        mBottomNavigationBar = v.findViewById(R.id.bottom_app_bar);


        // The brand mark group (faded while the pill is raised) and the ember
        // glow behind the flame.
        mBrandMark = v.findViewById(R.id.home_brand_mark);
        mBrandGlow = v.findViewById(R.id.home_brand_glow);

        // Cloud Backup chip/card — see applyBackupPill for the state machine.
        // Tap routes by the state CURRENTLY SHOWN (mPillToFiles is set at
        // render, so a tap can't race a state flip between render and click):
        // transfer states → the Backups list, Paused → the Cloud status screen.
        mBackupPill = v.findViewById(R.id.home_backup_pill);
        mBackupPillText = v.findViewById(R.id.home_backup_pill_text);
        mBackupCard = v.findViewById(R.id.home_backup_card);
        mBackupCardTitle = v.findViewById(R.id.home_backup_card_title);
        mBackupCardDetail = v.findViewById(R.id.home_backup_card_detail);
        View.OnClickListener openCloud = view -> {
            Intent intent = new Intent(mActivity, SettingsActivity.class);
            intent.putExtra(mPillToFiles
                            ? SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES
                            : SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP,
                    true);
            startActivity(intent);
        };
        if (mBackupPill != null) {
            mBackupPill.setOnClickListener(openCloud);
        }
        if (mBackupCard != null) {
            // The card only ever renders the paused state, so unlike the pill it
            // needs no mPillToFiles routing — it always opens the Cloud status
            // screen, where the top-up lives.
            mBackupCard.setOnClickListener(view -> {
                Intent intent = new Intent(mActivity, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP, true);
                startActivity(intent);
            });
            // RESTORE — the card's second verb, Signal's "Download your backup
            // data" on a lapsed plan: opens the Backups list with its "Restore
            // all to this phone" confirmation armed, so a user who freed phone
            // copies (Storage → Free up space) can pull everything back before
            // the grace period ends and the reap deletes the only copy. Its
            // own tap target; the card itself stays TOP UP.
            View restore = mBackupCard.findViewById(R.id.home_backup_card_restore);
            if (restore != null) {
                restore.setOnClickListener(view -> {
                    Intent intent = new Intent(mActivity, SettingsActivity.class);
                    intent.putExtra(SettingsActivity.EXTRA_OPEN_CLOUD_BACKUP_FILES, true);
                    intent.putExtra(SettingsActivity.EXTRA_RESTORE_ALL, true);
                    startActivity(intent);
                });
            }
        }
        WorkManager.getInstance(mActivity.getApplicationContext())
                .getWorkInfosByTagLiveData(CloudBackupManager.WORK_TAG)
                .observe(getViewLifecycleOwner(), infos -> {
                    // RUNNING and ENQUEUED tracked SEPARATELY: an enqueued
                    // worker may be hours from transferring (retry backoff, or
                    // waiting for network offline), and rendering it as
                    // "Backing up…" was the audit's honesty gap — it gets its
                    // own "Waiting to back up" copy instead.
                    boolean running = false;
                    boolean queued = false;
                    if (infos != null) {
                        for (WorkInfo wi : infos) {
                            // Only IDENTIFIED backup workers count: restores and
                            // legacy pre-tag WorkSpecs render no row anywhere, so
                            // they must not raise a transfer pill pointing at an
                            // empty list (the on-device ghost state).
                            if (!hasBackupTag(wi)) {
                                continue;
                            }
                            WorkInfo.State st = wi.getState();
                            if (st == WorkInfo.State.RUNNING) {
                                running = true;
                                break; // strongest state — nothing can upgrade it
                            }
                            if (st == WorkInfo.State.ENQUEUED) {
                                queued = true;
                            }
                        }
                    }
                    mCloudRunning = running;
                    mCloudQueued = queued;
                    applyBackupPill();
                });

        mBottomNavigationBar.setListener(this);

        // Bookmarks is the flat middle-slot button in the bottom bar
        // (see onBottomBarButtonClick's R.id.search_button branch); the
        // URL bar at the top covers search. The former hero FAB was
        // removed in favour of this plain in-bar affordance.

        mGeckoToolbar = v.findViewById(R.id.toolbar_layout);
        mGeckoToolbar.setListener(this);

        // The suggestions overlay follows the toolbar's translation + height
        // (the browser's behavior, installed in code because it needs the
        // view reference): docked, it sits under the mid-screen pill; raised,
        // it rides up with the pill and opens under it at the top.
        CoordinatorLayout.LayoutParams overlayParams =
                (CoordinatorLayout.LayoutParams) mAutoCompleteView.getLayoutParams();
        overlayParams.setBehavior(new AutoCompleteViewBehavior(
                mAutoCompleteView.getContext(), null, mAutoCompleteView));

        // The pill IS the toolbar: docked over home_search_slot at rest, raised
        // to the browser's toolbar rect on focus (see HomeToolbarDock).
        mToolbarDock = new HomeToolbarDock(mGeckoToolbar,
                v.findViewById(R.id.home_search_slot), mHomeScroll, mBrandMark);

        mAutoCompleteEditText = mGeckoToolbar.getAutoCompleteEditText();
        mAutoCompleteEditText.setOnTextChangedListener(this);
        mAutoCompleteEditText.setOnCommitListener(this);
        mAutoCompleteEditText.setOnSearchStateChangeListener(this);
        mAutoCompleteEditText.setOnFilterListener(this);
        mAutoCompleteEditText.setOnFocusChangeListener(this);

        mSearchAutocompleteAdapter = new SearchAutocompleteAdapter(mActivity, new SearchDiffCallback(), this);
        mAutoCompleteView.getRecyclerView().setAdapter(mSearchAutocompleteAdapter);

        // Most-visited tile tap → open the URL (same path as the clipboard chip).
        mAutoCompleteView.setMostVisitedClickListener(url -> openUri(url));
        // Long-press a tile → confirm dialog (in AutoCompleteView) → hide the
        // site from the strip (blocklist; history untouched), then refresh.
        mAutoCompleteView.setMostVisitedRemoveListener(
                url -> mAutoCompleteViewModel.hideFromMostVisited(url));

        mAutoCompleteView.setClipboardCallback(new AutoCompleteView.OnClipboardListener() {
            @Override
            public void onClipboardClick(CharSequence text) {
                if(!TextUtils.isEmpty(text)){
                    String uri = mSearchRepository.parseUri(text.toString());
                    openUri(uri);
                }
            }

            @Override
            public void onClipboardLongClick(CharSequence text) {

            }
        });

        return v;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "onViewCreated");

        // Download-count badge on the bottom bar's Downloads button —
        // same source BrowserFragment's regular-mode chrome uses
        // (TaskRepository.getRegularCount keeps vault/incognito
        // downloads out, so private activity never advertises itself
        // here). Home is never incognito-themed (HomeIncognitoFragment
        // is its own fragment), so no mIsIncognitoThemed gate is
        // needed. This badge used to be deliberately omitted while the
        // active-download strip card existed; with that card removed
        // (redundant with both the ongoing download notification and
        // the Downloads entry card), the badge is the one lightweight
        // 'something is downloading' cue left on Home.
        mTaskViewModel.getRegularCount().observe(getViewLifecycleOwner(), count -> {
            mBottomNavigationBar.onBadgeCount(count);
            // Same active+queued signal drives the breathing ember behind the
            // flame: on while something downloads, off (idle wordmark) at 0.
            setBrandGlowActive(count != null && count > 0);
        });

        // Floor the displayed count at 1: while Home is on screen the user IS
        // on the home tab, so the counter must never read 0 (it could after a
        // close-all / close-last, or a race where the home GeckoState isn't in
        // the list yet - the current tab is still at least tab 1).
        mGeckoStateViewModel.getTabsCount().observe(getViewLifecycleOwner(), count
                -> mBottomNavigationBar.onTabsCount(count == null ? 1 : Math.max(1, count)));

        mAutoCompleteViewModel.setIncognito(false);

        mAutoCompleteViewModel.getAutoComplete().observe(getViewLifecycleOwner(), mObservableResult -> {
            if (TextUtils.isEmpty(mObservableResult))
                mAutoCompleteEditText.noAutocompleteResult();
            else
                mAutoCompleteEditText.applyAutocompleteResult(mObservableResult);
        });

        mAutoCompleteViewModel.getWebSearch().observe(getViewLifecycleOwner(), mObservableWebSearch -> {
            if (mObservableWebSearch == null || mObservableWebSearch.isEmpty()) {
                mAutoCompleteView.showEmpty();
            } else {
                mAutoCompleteView.hideAll();
            }
            Log.d(TAG, "Size :" + (mObservableWebSearch != null ? mObservableWebSearch.size() : 0));
            mSearchAutocompleteAdapter.submitList(mObservableWebSearch);

        });

        // Empty-focus most-visited strip — its own view, populated here and
        // toggled by showEmpty()/hideAll() (visibility only, no list diff).
        mAutoCompleteViewModel.getMostVisited().observe(getViewLifecycleOwner(),
                list -> mAutoCompleteView.setMostVisited(list));

        // NOTE: HomeFragment intentionally does NOT observe
        // BrowserURIViewModel.getEvents().  IntentHandler owns all tab
        // activation and navigation.  HomeFragment only uses
        // BrowserURIViewModel to *produce* events (openUri, openSessionId)
        // — never to consume them.

        mBrowserDialogViewModel.getOptionsEvent().observe(getViewLifecycleOwner(), mOptionEntity -> {

            int id = mOptionEntity.getId();

            if(id == R.id.action_download){
                DownloadRequest request = mOptionEntity.getDownloadRequest();
                if (request != null) {
                    startDownload(request, mBottomNavigationBar, R.id.anchor_view);
                }
            } else if(id == R.id.action_delete_clipboard){
                mAutoCompleteView.hideClipboard();
            } else if(id == R.id.new_tab){
                flashNewTab(mNewTabView);
                addNewTab();
            } else if(id == R.id.new_incognito_tab){
                GeckoStateEntity entity = new GeckoStateEntity(true);
                entity.setIncognito(true);
                GeckoState geckoState = new GeckoState(entity);
                mIncognitoStateViewModel.setGeckoState(geckoState, true);
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_home_incognito);
            } else if (id == R.id.popup_history) {
                NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_history);
            } else if (id == R.id.popup_vault) {
                mStartForResult.launch(new Intent(mActivity, VaultActivity.class));
            } else if (id == R.id.popup_sync) {
                Intent syncIntent = new Intent(mActivity, SettingsActivity.class);
                syncIntent.putExtra(SettingsActivity.EXTRA_OPEN_SYNC, true);
                mStartForResult.launch(syncIntent);
            } else if (id == R.id.popup_settings) {
                Intent settingsIntent = new Intent(mActivity, SettingsActivity.class);
                mStartForResult.launch(settingsIntent);
            } else if (id == R.id.popup_quit) {
                quitApp();
            }


        });

        //Clear text on resume
        mAutoCompleteEditText.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mAutoCompleteEditText.getViewTreeObserver().removeOnPreDrawListener(this);
                mGeckoToolbar.clearText();
                return true;
            }
        });


        ViewCompat.setOnApplyWindowInsetsListener(mHomeScroll, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // Top AND bottom insets as padding: no AppBarLayout reserves the
            // top any more (the toolbar floats over the content, see the
            // layout comment), so the content centres itself between the
            // status strip and the bottom bar.
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        ViewCompat.setOnApplyWindowInsetsListener(mGeckoToolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // The status inset is a top MARGIN here, never padding. The
            // toolbar is docked mid-screen at rest (HomeToolbarDock): a
            // self-padded box would carry the whole status strip down with
            // it and paint, opaque, over the wordmark. With a margin the box
            // is a plain 64dp bar laid out at y = inset — the browser's exact
            // toolbar rect, which is what makes the commit hand-off seamless.
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (params.topMargin != insets.top) {
                params.topMargin = insets.top;
                v.setLayoutParams(params);
            }
            v.setPadding(insets.left, 0, insets.right, 0);
            return WindowInsetsCompat.CONSUMED;
        });


        ViewCompat.setOnApplyWindowInsetsListener(mAutoCompleteView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            // Apply the insets as padding to the view. Here, set all the dimensions
            // as appropriate to your layout. You can also update the view's margin if
            // more appropriate.
            v.setPadding(insets.left, 0, insets.right, 0);

            // Return CONSUMED if you don't want the window insets to keep passing down
            // to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });


        // As addObserver() does not automatically remove the observer, we
        // call removeObserver() manually when the view lifecycle is destroyed
        getViewLifecycleOwner().getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (Lifecycle.Event.ON_CREATE.equals(event)) {
                Log.d(TAG, "onCreate");
                mGeckoObserverRegistry.register(HomeFragment.this);
            }  else if (Lifecycle.Event.ON_PAUSE.equals(event) || Lifecycle.Event.ON_STOP.equals(event)) {
                Log.d(TAG, "onPause");
                mStop = true;
            } else if (Lifecycle.Event.ON_RESUME.equals(event)) {
                Log.d(TAG, "onResume");
                mStop = false;
            } else if (Lifecycle.Event.ON_DESTROY.equals(event)) {
                // The registry holds STRONG references (see GeckoObserverRegistry):
                // without this, every recreated HomeFragment leaks its predecessor
                // (fragment + view tree) and the stale instance keeps receiving
                // callbacks. Mirrors HomeIncognitoFragment/BrowserFragment.
                mGeckoObserverRegistry.unregister(HomeFragment.this);
            }
        });


        // Always ensure normal (non-incognito) theme. When navigating here
        // from HomeIncognitoFragment (e.g. after "Close all" from notification),
        // the system bars and views may still have incognito colors because
        // HomeIncognitoFragment.onDestroyView hasn't run yet. Resetting
        // unconditionally is safe — setting normal colors when already normal
        // is a visual no-op.
        resetWindowTheme();
        mBottomNavigationBar.updateTheme(mActivity, false);
        // Flat bar (Firefox-parity): the bar is now SURFACE, so the scrim
        // under the system-nav strip matches at surface too (its top hairline
        // provides the division, not a tonal step). See
        // BaseFocusFragment.setNavScrimColor.
        setNavScrimColor(IncognitoColors.getSurface(mActivity, false));
        // Home chrome: QUIET top (toolbar = surface, merging with the
        // canvas — tonalHolder=false) and now a flat-surface bottom too, so
        // the whole window is one surface tone. paintSystemBars mirrors that
        // on the WINDOW layer for Android <= 14, where the theme's opaque bar
        // attrs would otherwise paint the strips.
        mGeckoToolbar.updateTheme(mActivity, false, false);
        paintSystemBars(
                IncognitoColors.getSurface(mActivity, false),
                IncognitoColors.getSurface(mActivity, false));
        mAutoCompleteView.updateTheme(mActivity, false);
        mSearchAutocompleteAdapter.setIncognito(false);


    }

    /**
     * Shows/hides the breathing ember behind the flame. ON while a download is
     * active (a soft, constant "something's happening" pulse — deliberately NOT
     * progress-linked: how-far belongs to the bottom bar, the home only says
     * whether); OFF returns the home to the plain wordmark. The glow is sized
     * to the flame and grown into a halo purely by the SCALE transform, so it
     * costs no layout and bleeds past its box (wrappers clipChildren="false").
     */
    private void setBrandGlowActive(boolean active) {
        if (mBrandGlow == null) return;
        if (active) {
            mBrandGlow.setVisibility(View.VISIBLE);
            if (mBrandGlowAnimator == null) {
                mBrandGlowAnimator = buildBrandGlowAnimator(mBrandGlow);
            }
            if (!mBrandGlowAnimator.isStarted()) {
                mBrandGlowAnimator.start();
            }
        } else {
            if (mBrandGlowAnimator != null) {
                mBrandGlowAnimator.cancel();
            }
            mBrandGlow.setVisibility(View.GONE);
            mBrandGlow.setAlpha(0f);
        }
    }

    /**
     * A slow alpha+scale breath, infinite and reversing, on the ember view.
     * Soft on purpose (never fully dies, never flares) so it reads as a calm
     * heartbeat rather than a notification blink; the scale carries the halo
     * out beyond the flame.
     */
    private static Animator buildBrandGlowAnimator(View v) {
        v.setScaleX(1.3f);
        v.setScaleY(1.3f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(v, View.ALPHA, 0.15f, 0.42f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(v, View.SCALE_X, 1.3f, 1.6f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1.3f, 1.6f);
        ObjectAnimator[] parts = {alpha, scaleX, scaleY};
        for (ObjectAnimator part : parts) {
            part.setRepeatCount(ValueAnimator.INFINITE);
            part.setRepeatMode(ValueAnimator.REVERSE);
        }
        AnimatorSet set = new AnimatorSet();
        set.setDuration(2200);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.playTogether(alpha, scaleX, scaleY);
        return set;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mBrandGlowAnimator != null) {
            mBrandGlowAnimator.cancel();
            mBrandGlowAnimator = null;
        }
        if (mToolbarDock != null) {
            mToolbarDock.release();
            mToolbarDock = null;
        }
        mBrandMark = null;
        mBrandGlow = null;
        mHomeScroll = null;
        mAutoCompleteView = null;
        mGeckoToolbar = null;
        mNewTabView = null;
        mBottomNavigationBar = null;
        mBackupPill = null;
        mBackupPillText = null;
        mBackupCard = null;
        mBackupCardTitle = null;
        mBackupCardDetail = null;
    }

    /**
     * Loads the quota when set up (the pill's "Paused" input), then re-renders
     * the pill. Called on resume; the transfer-active state comes from the
     * WorkManager observer wired in onViewCreated.
     */
    private void refreshCloudStatus() {
        if (mBackupPill == null) {
            return;
        }
        if (!mCloudBackup.isSetUp()) {
            // Not set up (fresh install, never used, or erased): drop the quota
            // so a later render can't resurrect a stale paused card, and hide
            // the surfaces now rather than waiting on a network round-trip that
            // will never be made.
            mCloudQuota = null;
            applyBackupPill();
            return;
        }
        // CACHED-FIRST, the status hero's rule: render from the last successful
        // pull so the card is already right on entry, then let the async result
        // update it in place.
        CloudBackupManager.Status cached = mCloudBackup.lastStatus();
        if (cached != null) {
            mCloudQuota = cached.quota;
        }
        applyBackupPill();
        // One combined load with the guarded reconciliation: retiring flips
        // isSetUp() false (card hides); a heal makes the paused check reachable.
        // Gen-guarded so two rapid resumes can't apply results in the wrong
        // order (network order != call order on the manager's pooled executor).
        final int gen = ++mCloudStatusGen;
        mCloudBackup.loadStatus(status -> {
            if (isAdded() && mBackupPill != null && gen == mCloudStatusGen) {
                mCloudQuota = status.quota;
                applyBackupPill();
            }
        });
    }

    /** Whether a WorkInfo carries the backup identity tags stamped at enqueue
     *  (restores and legacy pre-tag WorkSpecs don't). */
    private static boolean hasBackupTag(WorkInfo wi) {
        for (String tag : wi.getTags()) {
            if (tag.startsWith(VaultBackupWorker.TAG_NAME)) {
                return true;
            }
        }
        return false;
    }

    /** Renders the home cloud surface from the latest transfer/quota state.
     *  Three rungs, highest first:
     *
     *  <ol>
     *    <li><b>Paused</b> — metered credit ran out. The CARD, tap → the status
     *        screen. It BEATS both transfer states because in grace every upload
     *        402s at create, so a doomed queued backup rendering "Backing up…"
     *        would hide the one actionable fact (top up).</li>
     *    <li><b>Backing up…</b> — an identified backup worker is RUNNING.</li>
     *    <li><b>Waiting to back up</b> — enqueued only (constraints unmet /
     *        retry backoff; hours may pass with nothing transferring, so it
     *        never claims to be backing up).</li>
     *  </ol>
     *
     *  <p>Nothing at rest. Every state is EVIDENCE-based — a paused quota, a
     *  live WorkInfo — and that evidence only exists because the user engaged
     *  with the feature, so the states are self-gating (this is why "Backing
     *  up…" is deliberately NOT setUp-gated: the very FIRST backup runs before
     *  markEnabled lands, and suppressing it would blank the chip for exactly
     *  the transfer that most wants reporting). The old RESTING "N backed up"
     *  rung had no such evidence — a standing claim off a network total — and
     *  was removed (home v5) together with the settings switch that let the
     *  user hide it; the Backups doors are the Downloads overflow and the Cloud
     *  screen.
     *
     *  <p><b>Visibility contract.</b> At most ONE of chip / card is ever
     *  VISIBLE, and every branch sets BOTH (they are persistent views that
     *  flip, so a one-sided set leaves the previous state on screen). Both are
     *  GONE when nothing is happening — the calm home is the default, and a
     *  fresh install is exactly as bare as before. */
    private void applyBackupPill() {
        if (mBackupPill == null || mBackupPillText == null) {
            return;
        }
        String text = null;
        boolean attention = false;
        boolean toFiles = false;
        boolean setUp = mCloudBackup.isSetUp();
        if (setUp && mCloudQuota != null && mCloudQuota.metered && mCloudQuota.readOnly) {
            text = getString(R.string.home_cloud_paused);
            attention = true;
        } else if (mCloudRunning) {
            text = getString(R.string.home_cloud_backing_up);
            toFiles = true;
        } else if (mCloudQueued) {
            text = getString(R.string.home_cloud_waiting);
            toFiles = true;
        }
        if (text == null) {
            mBackupPill.setVisibility(View.GONE);
            if (mBackupCard != null) {
                mBackupCard.setVisibility(View.GONE);
            }
            return;
        }
        mPillToFiles = toFiles;
        // The states are told apart by SHAPE, not colour. A live/promised
        // transfer is a small filled chip (fill is earned by WORK — transient,
        // self-clearing); the DEADLINE is a wide two-line card with a verb,
        // because it reports 30 days ending in deleted files rather than
        // something that resolves itself. Neither carries a semantic hue — see
        // the note in values/colors.xml for why the amber container that
        // briefly lived here was removed.
        if (attention) {
            mBackupPill.setVisibility(View.GONE);
            showBackupCard(text);
            return;
        }
        if (mBackupCard != null) {
            mBackupCard.setVisibility(View.GONE);
        }
        mBackupPillText.setText(text);
        mBackupPill.setVisibility(View.VISIBLE);
    }

    /** Renders the deadline card. The detail line is the COUNTDOWN — "3 days
     *  left before your files are removed" is what makes the state actionable,
     *  where "Paused" only named it — computed from the quota's graceUntil,
     *  which the server already sends, so this needs no API change.
     *
     *  <p>Falls back to the title alone when graceUntil is missing or
     *  unparseable (an older server, a clock skew): the card still says the
     *  backup is paused, it just can't say for how long. Never shows a negative
     *  or zero count — past the deadline the reap is already due, so it reads
     *  "today" rather than a stale number. */
    private void showBackupCard(String title) {
        if (mBackupCard == null || mBackupCardTitle == null) {
            return;
        }
        mBackupCardTitle.setText(title);
        if (mBackupCardDetail != null) {
            String detail = graceCountdown();
            mBackupCardDetail.setText(detail);
            mBackupCardDetail.setVisibility(detail == null ? View.GONE : View.VISIBLE);
        }
        mBackupCard.setVisibility(View.VISIBLE);
    }

    /** Whole days from now until the read-only grace ends, as the localized
     *  plural, or null when the deadline isn't known. */
    @Nullable
    private String graceCountdown() {
        if (mCloudQuota == null || mCloudQuota.graceUntil == null) {
            return null;
        }
        try {
            long days = ChronoUnit.DAYS.between(Instant.now(),
                    OffsetDateTime.parse(mCloudQuota.graceUntil).toInstant());
            int shown = (int) Math.max(1, days);
            return getResources().getQuantityString(
                    R.plurals.home_cloud_grace_days, shown, shown);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onBottomBarButtonClick(View v, int id) {
        if (id == R.id.more_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_home_popup, R.id.home);
        } else if(id == R.id.tab_button){
            Bundle args = new Bundle();
            args.putBoolean(Keys.OPEN_INCOGNITO, false);
            NavigationUtils.navigateSafe(mNavController, R.id.tabs, R.id.home, args);
        } else if(id == R.id.downloads_button){
            Intent downloadsIntent = new Intent(mActivity, DownloadsActivity.class);
            mStartForResult.launch(downloadsIntent);
        } else if(id == R.id.new_tab_button){
            flashNewTab(mNewTabView);
            addNewTab();
        } else if (id == R.id.search_button) {
            // The middle slot is the flat Bookmarks button.
            NavigationUtils.navigateSafe(mNavController, R.id.action_home_to_bookmarks);
        }
    }

    @Override
    public boolean onBottomBarButtonLongClick(View v, int id){
        if (id == R.id.new_tab_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_new_tabs, R.id.home);
            return true;
        }
        // Home intentionally has no other long-press affordances —
        // every bottom-bar slot already has a visible-on-home
        // entry (Downloads card, Safe Folder card, the Bookmarks
        // hero FAB). Hidden long-press gestures were the right call
        // when the slot had no on-screen surface (BrowserFragment
        // keeps the Downloads long-press sheet for that reason),
        // not here.
        return false;
    }

    @Override
    public void onCommit() {
        Editable editable = mAutoCompleteEditText.getText();
        if(editable != null){
            String text = editable.toString();
            if (!TextUtils.isEmpty(text)) {
                openUri(text);
            }
        }
    }

    @Override
    public void onRefreshAutoComplete(String text) {

    }

    @Override
    public void onFocusChanged(boolean hasFocus) {
        // The pill rises to the top of the screen on focus and docks back over
        // the slot on blur — every blur path (back, outside tap, commit) ends
        // here, so this is the one hook the dock needs.
        if (mToolbarDock != null) {
            mToolbarDock.setRaised(hasFocus);
        }
        mGeckoToolbar.updateViewVisibility(hasFocus);
        mGeckoToolbar.setAutoCompleteVisible(hasFocus);
        mGeckoToolbar.startAnimation(hasFocus);
        // On focus with an empty field, load the most-visited tiles (the strip
        // observer fills its own view; showEmpty() reveals it once it has tiles);
        // on blur just clear. showEmpty() paints the clipboard chip immediately.
        if (hasFocus) {
            mAutoCompleteViewModel.loadMostVisited();
        } else {
            mAutoCompleteViewModel.resetEngines();
        }
        mAutoCompleteView.showEmpty();
        mAutoCompleteView.updateVisibility(hasFocus);
    }

    @Override
    public void onTextChanged(String afterText, String currentText) {
        if(TextUtils.isEmpty(afterText)){
            mAutoCompleteViewModel.loadMostVisited();
            mAutoCompleteView.showEmpty();
        } else {
            mAutoCompleteViewModel.search(afterText);
        }
    }


    @Override
    public void onSearchStateChanged(boolean hasFocus) {
        mGeckoToolbar.updateSearchView(hasFocus);
    }


    private void openUri(String text){
        // Format here, not downstream. BrowserFragment.setGeckoViewSession
        // only runs parseUri when opening a brand-new GeckoSession, so a
        // toolbar commit that lands on an already-open session would
        // otherwise pass the raw query straight to loadUri.
        String url = mSearchRepository.parseUri(text);
        Log.d(TAG, "openUri: url=" + url);
        GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();
        GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
        Log.d(TAG, "openUri: using geckoState id=" + geckoStateEntity.getId()
                + " wasHome=" + geckoStateEntity.isHome());
        geckoStateEntity.setHome(false);
        geckoStateEntity.setUri(url);
        // If this entity was previously a visited tab (now navigated back to
        // home in-process), it may still carry the serialized SessionState of
        // its last URL. Without clearing, BrowserFragment.setGeckoViewSession
        // would take the hasRestoredState branch and let restoreState
        // navigate to the OLD URL instead of the URL the user just typed.
        geckoStateEntity.setSessionState("");
        mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_URI);
        Log.d(TAG, "openUri: event fired, navigating to browser");
        NavigationUtils.navigateToBrowser(mNavController, false);
    }


    private void openSessionId(int sessionId){
        Log.d(TAG, "openSessionId: sessionId=" + sessionId);
        GeckoState geckoState = mGeckoStateViewModel.getGeckoState(sessionId);
        if (geckoState == null) {
            Log.w(TAG, "openSessionId: GeckoState not found for id=" + sessionId);
            return;
        }
        mGeckoStateViewModel.setGeckoState(geckoState, true);
        GeckoStateEntity geckoStateEntity = geckoState.getGeckoStateEntity();
        Log.d(TAG, "openSessionId: firing OPEN_SESSION for id=" + geckoStateEntity.getId()
                + " uri=" + geckoStateEntity.getUri());
        mBrowserURIViewModel.onEventSelected(geckoStateEntity, IntentActions.OPEN_SESSION);
        NavigationUtils.navigateToBrowser(mNavController, false);
    }


    private void addNewTab() {
        GeckoState geckoState = new GeckoState(new GeckoStateEntity(true));
        Log.d(TAG, "addNewTab: created home tab id=" + geckoState.getEntityId());
        mGeckoStateViewModel.setGeckoState(geckoState, true);
    }


    @Override
    public void onToolbarButtonClick(View v, int id) {
        if (id == R.id.clear_button) {
            // Field is now empty again → most-visited strip (its observer fills
            // the strip; showEmpty reveals it when it has tiles).
            mAutoCompleteViewModel.loadMostVisited();
            mAutoCompleteView.showEmpty();
            mGeckoToolbar.clearText();
        }else if (id == R.id.security_button) {
            NavigationUtils.navigateSafe(mNavController, R.id.dialog_search_engine, R.id.home);
        }
    }

    @Override
    public void onToolbarKey(int keyCode, KeyEvent event) {

    }

    @Override
    public void onItemClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;
        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            int type = searchEntity.getType();
            if(type == AutoCompleteEntity.TAB){
                int sessionId = searchEntity.getSessionId();
                openSessionId(sessionId);
            }else{
                String text = mSearchRepository.parseUri(searchEntity.getSubText());
                openUri(text);
            }
        }
    }

    @Override
    public void onLongClick(int position, int resId) {
        if (position == RecyclerView.NO_POSITION)
            return;
        if (resId == R.id.item_search) {
            AutoCompleteEntity searchEntity = mSearchAutocompleteAdapter.getCurrentList().get(position);
            String uri = mSearchRepository.parseUri(searchEntity.getSubText());
            GeckoState geckoState = mGeckoStateViewModel.getCurrentGeckoState();
            geckoState.setEntityUri(uri);
            openUri(uri);
            mAutoCompleteViewModel.clearClipboard();
        }
    }

    @Override
    public void onItemVariantClick(int position, int variant, int resId) {

    }


}
package com.solarized.firedown.ui.adapters;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.solarized.firedown.GlideHelper;
import com.solarized.firedown.GlideRequestOptions;
import com.solarized.firedown.R;
import com.solarized.firedown.data.capture.CaptureItem;
import com.solarized.firedown.data.capture.CaptureMark;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;
import com.solarized.firedown.data.entity.FFmpegTagEntity;
import com.solarized.firedown.ui.CheckerboardDrawable;
import com.solarized.firedown.ui.FallbackInks;
import com.solarized.firedown.ui.OnItemClickListener;
import com.solarized.firedown.ui.diffs.CaptureItemDiffCallback;
import com.solarized.firedown.utils.DateUtils;
import com.solarized.firedown.utils.FileUriHelper;
import com.solarized.firedown.utils.SelectionStyling;
import com.solarized.firedown.utils.Utils;
import com.solarized.firedown.utils.WebUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Captured sheet's adapter: 16:9 TILES for videos, ROWS for audio and
 * other files, COMPACT rows for subtitles, SQUARES for images, plus the group
 * HEADER and the "Earlier in this tab" DIVIDER. The view model builds the
 * item list (see {@link CaptureItem}); this class only renders it. Selection
 * is keyed by capture uid, so it survives the list re-sorting under it.
 */
public class BrowserOptionAdapter extends ListAdapter<CaptureItem, RecyclerView.ViewHolder> {

    /** A selection or action-mode change: rebind the selection chrome only. */
    private static final Object PAYLOAD_SELECTION = new Object();

    /** Square text shadow, the grid caption's value (DownloadItemAdapter). */
    private static final int PHOTO_TEXT_SHADOW = 0x80000000;
    /** Square progress track over a picture: the white of the caption, faint. */
    private static final int PHOTO_TRACK = 0x4DFFFFFF;
    /** "1280x720", "24×24": a resolution tag's two numbers. */
    private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*[x×X]\\s*(\\d+)");
    /** An SVG no larger than this (px, either side) is an icon: drawn inside
     *  capture_icon_inset rather than stretched over the whole square. */
    private static final int ICON_MAX_PX = 96;

    private final OnItemClickListener mOnItemClickListener;
    private final HashSet<Integer> mSelected = new HashSet<>();
    private final RequestOptions mRowOptions;
    private final RequestOptions mSquareOptions;
    private final RequestOptions mTileOptions;
    private final int mProgressInk;
    private final int mIconInset;
    private final int mProgressTrack;
    private boolean mActionMode;
    /** Resolved on the first square bind (needs a themed Context). */
    @Nullable private FallbackInks mFallbackInks;

    public BrowserOptionAdapter(@NonNull Context context, @NonNull OnItemClickListener onItemClickListener) {
        super(new CaptureItemDiffCallback());
        mOnItemClickListener = onItemClickListener;
        int radius = context.getResources().getDimensionPixelOffset(R.dimen.card_radius);
        mRowOptions = new RequestOptions().apply(RequestOptions.bitmapTransform(new RoundedCorners(radius)));
        // A square crops to its own (square) size BEFORE rounding: rounding the
        // decoded bitmap and letting the ImageView crop it would cut the
        // rounded corners off any picture that isn't square.
        mSquareOptions = new RequestOptions().transform(new CenterCrop(), new RoundedCorners(radius));
        // A tile's picture card clips its own corners, and GlideHelper crops
        // video frames itself: no transform of ours.
        mTileOptions = new RequestOptions();
        mIconInset = context.getResources().getDimensionPixelSize(R.dimen.capture_icon_inset);
        mProgressInk = ContextCompat.getColor(context, R.color.progress_indicator);
        mProgressTrack = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(context, android.R.attr.colorPrimary, Color.BLACK), 0x33);
    }

    /**
     * The span a position takes on the sheet's grid. The grid's span count is
     * the least common multiple of the square and tile counts (6 for three
     * squares and two tiles on a phone), so both divide it evenly.
     */
    public int spanSize(int position, int spanCount, int squareColumns, int tileColumns) {
        if (position < 0 || position >= getItemCount()) return spanCount;
        int type = getItem(position).type;
        if (type == CaptureItem.TYPE_SQUARE) return spanCount / Math.max(1, squareColumns);
        if (type == CaptureItem.TYPE_TILE) return spanCount / Math.max(1, tileColumns);
        return spanCount;
    }

    @Nullable
    public CaptureItem getItemAt(int position) {
        if (position < 0 || position >= getItemCount()) return null;
        return getItem(position);
    }

    /** The capture at a position, or null for a header, the divider or a
     *  stale position. */
    @Nullable
    public BrowserDownloadEntity getEntityAt(int position) {
        CaptureItem item = getItemAt(position);
        return item != null ? item.entity : null;
    }

    /**
     * Glide options for a capture's thumbnail, shared by the bind and the
     * fragment's preloader so the preload-warmed bitmap and the bind-time
     * request resolve to the same cache key. A clone every time: RequestOptions
     * mutates in place, and the shared base would otherwise carry the last
     * capture's metadata into the next request.
     */
    @NonNull
    public RequestOptions optionsFor(@NonNull BrowserDownloadEntity entity, int itemType) {
        String fileUrl = entity.getFileUrl();
        RequestOptions base = switch (itemType) {
            case CaptureItem.TYPE_SQUARE -> mSquareOptions;
            case CaptureItem.TYPE_TILE -> mTileOptions;
            default -> mRowOptions;
        };
        return base.clone()
                .set(GlideRequestOptions.MIMETYPE, resolveMimeType(entity.getMimeType(), fileUrl))
                .set(GlideRequestOptions.FILEPATH, fileUrl)
                .set(GlideRequestOptions.HEADERS, entity.getFileHeaders())
                .set(GlideRequestOptions.KEY, String.valueOf(entity.getUid()));
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof CaptureHolder capture) {
            GlideHelper.clearSafe(capture.image);
        }
    }

    // ── ViewHolder creation ──────────────────────────────────────────────

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return switch (viewType) {
            case CaptureItem.TYPE_SQUARE -> new SquareHolder(
                    inflater.inflate(R.layout.fragment_browser_options_square, parent, false),
                    mOnItemClickListener);
            case CaptureItem.TYPE_TILE -> new TileHolder(
                    inflater.inflate(R.layout.fragment_browser_options_tile, parent, false),
                    mOnItemClickListener);
            case CaptureItem.TYPE_COMPACT -> new RowHolder(
                    inflater.inflate(R.layout.fragment_browser_options_compact, parent, false),
                    mOnItemClickListener, true);
            case CaptureItem.TYPE_HEADER -> new HeaderHolder(
                    inflater.inflate(R.layout.fragment_browser_options_section_header, parent, false));
            case CaptureItem.TYPE_DIVIDER -> new DividerHolder(
                    inflater.inflate(R.layout.fragment_browser_options_section_divider, parent, false));
            default -> new RowHolder(
                    inflater.inflate(R.layout.fragment_browser_options_item_list, parent, false),
                    mOnItemClickListener, false);
        };
    }

    // ── Binding ──────────────────────────────────────────────────────────

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (payloads.isEmpty() || !(holder instanceof CaptureHolder capture)) {
            onBindViewHolder(holder, position);
            return;
        }
        CaptureItem item = getItem(position);
        for (Object payload : payloads) {
            if (payload != CaptureItemDiffCallback.PAYLOAD_MARK && payload != PAYLOAD_SELECTION) {
                onBindViewHolder(holder, position);
                return;
            }
        }
        if (payloads.contains(PAYLOAD_SELECTION)) {
            bindSelection(capture, item);
        }
        if (payloads.contains(CaptureItemDiffCallback.PAYLOAD_MARK)) {
            if (capture instanceof RowHolder row) {
                bindRowMark(row, item);
            } else if (capture instanceof SquareHolder square) {
                bindSquareMark(square, item);
            } else if (capture instanceof TileHolder tile) {
                bindTileMark(tile, item);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CaptureItem item = getItem(position);
        if (holder instanceof RowHolder row) {
            bindRow(row, item);
        } else if (holder instanceof SquareHolder square) {
            bindSquare(square, item);
        } else if (holder instanceof TileHolder tile) {
            bindTile(tile, item);
        } else if (holder instanceof HeaderHolder header) {
            header.count.setText(String.format(Locale.getDefault(), "%d", item.count));
        } else if (holder instanceof DividerHolder divider) {
            divider.count.setText(String.format(Locale.getDefault(), "%d", item.count));
            divider.count.setVisibility(item.count > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void bindRow(@NonNull RowHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        if (holder.compact) {
            bindCompact(holder, item);
            return;
        }
        Context context = holder.itemView.getContext();

        holder.boundUid = entity.getUid();
        holder.setTextOrHide(holder.fileName, entity.getFileName());

        // The row's ONE action slot carries the ⋮ of a multi-variant capture
        // (→ the quality picker) and nothing else; see BrowserOptionFragment's
        // onItemClick. Copy URL lives behind long-press → multi-select. The
        // accessible name is set in every bind (the holder recycles) — the
        // screen reader once announced the button as a bare "Button".
        holder.hasVariants = entity.getHasVariants();
        holder.more.setIconResource(R.drawable.ic_baseline_more_vert_24);
        holder.more.setContentDescription(context.getString(R.string.capture_show_variants));

        GlideHelper.load(entity, optionsFor(entity, CaptureItem.TYPE_ROW), holder.image);
        bindSelection(holder, item);
        bindRowMark(holder, item);
    }

    /**
     * A subtitle track: the mime glyph in brandInk instead of a thumbnail (a
     * text file has no picture), and the track's LANGUAGE as the title when
     * the parser named one — "English (auto)" says more than a repeat of the
     * video's name, which moves to the end of the meta line so two English
     * tracks of two videos can still be told apart (rowMeta).
     */
    private void bindCompact(@NonNull RowHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        Context context = holder.itemView.getContext();

        holder.boundUid = entity.getUid();
        String language = languageFact(entity);
        holder.setTextOrHide(holder.fileName,
                TextUtils.isEmpty(language) ? entity.getFileName() : language);
        holder.hasVariants = false;

        GlideHelper.clearSafe(holder.image);
        holder.image.setImageResource(FileUriHelper.getMimeTypeIcon(entity.getMimeType()));
        ImageViewCompat.setImageTintList(holder.image, ColorStateList.valueOf(
                MaterialColors.getColor(context, R.attr.brandInk, mProgressInk)));
        bindSelection(holder, item);
        bindRowMark(holder, item);
    }

    /** The meta line and the progress bar: everything a mark changes. */
    private void bindRowMark(@NonNull RowHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        holder.meta.setText(rowMeta(context, item, holder.compact));
        holder.meta.setVisibility(holder.meta.length() > 0 ? View.VISIBLE : View.GONE);
        bindProgress(holder.progress, item.mark, mProgressTrack);
    }

    /**
     * "[status] · [TYPE] · facts · [domain]" — one line, one spannable.
     * The status leads because it is the one thing on the line that changes;
     * while a download runs it replaces the type label (the line is about the
     * download then, and "Downloading · 42% · VIDEO · 720p" would wrap).
     * A compact (subtitle) row has no facts to list beyond the language its
     * title already shows; it ends with the file's name instead, after the
     * domain, so the ellipsis eats the name rather than the domain.
     */
    @NonNull
    private CharSequence rowMeta(@NonNull Context context, @NonNull CaptureItem item,
                                 boolean compact) {
        BrowserDownloadEntity entity = item.entity;
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (entity == null) return text;
        CaptureMark mark = item.mark;
        boolean downloading = mark != null && mark.state == CaptureMark.PROGRESS;
        if (mark != null) {
            appendStyled(text, statusText(context, mark), statusColor(context, mark), Typeface.BOLD, 0);
        }
        if (item.showType && !downloading) {
            String label = FileUriHelper.getLongMimeText(context, entity.getMimeType());
            if (!TextUtils.isEmpty(label)) {
                // MimePrimaryLabel's look, as spans: 11sp bold caps in onSurface.
                appendStyled(text, label.toUpperCase(Locale.getDefault()),
                        MaterialColors.getColor(context,
                                com.google.android.material.R.attr.colorOnSurface, Color.BLACK),
                        Typeface.BOLD,
                        context.getResources().getDimensionPixelSize(R.dimen.capture_type_label_size));
            }
        }
        if (!compact) {
            appendFact(text, primaryFact(entity));
            appendFact(text, durationFact(entity));
            appendCaptionCount(context, text, item);
        }
        if (item.showDomain) {
            appendFact(text, domainOf(entity));
        }
        if (compact && !TextUtils.isEmpty(languageFact(entity))) {
            appendFact(text, entity.getFileName());
        }
        return text;
    }

    private static void appendCaptionCount(@NonNull Context context,
                                           @NonNull SpannableStringBuilder text,
                                           @NonNull CaptureItem item) {
        if (item.captionCount > 0) {
            appendStyled(text, context.getString(R.string.caption_count_badge, item.captionCount),
                    MaterialColors.getColor(context, android.R.attr.colorPrimary, Color.BLACK),
                    Typeface.NORMAL, 0);
        }
    }

    @Nullable
    private static String domainOf(@NonNull BrowserDownloadEntity entity) {
        String origin = entity.getFileOrigin();
        return WebUtils.getDomainName(TextUtils.isEmpty(origin) ? entity.getFileUrl() : origin);
    }

    // ── Tiles ────────────────────────────────────────────────────────────

    /**
     * A video: the picture leads, the name follows. Quality and duration sit
     * on the picture (white over the scrim, or FallbackInks on the mime
     * placeholder — applyTileGround); the line under the name is status, the
     * caption count and, for an earlier page, the domain.
     */
    private void bindTile(@NonNull TileHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        Context context = holder.itemView.getContext();
        if (mFallbackInks == null) {
            mFallbackInks = FallbackInks.resolve(context);
        }

        holder.setTextOrHide(holder.fileName, entity.getFileName());
        holder.setTextOrHide(holder.quality, primaryFact(entity));
        holder.setTextOrHide(holder.duration, durationFact(entity));
        holder.bottomBlock.setVisibility(holder.quality.getVisibility() == View.VISIBLE
                || holder.duration.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);

        // The ⋮ of a multi-variant capture (→ the quality picker), as on a row.
        holder.hasVariants = entity.getHasVariants();
        holder.more.setContentDescription(context.getString(R.string.capture_show_variants));

        final int boundUid = entity.getUid();
        holder.boundUid = boundUid;
        holder.photo = true;
        applyTileGround(holder);
        GlideHelper.load(entity, optionsFor(entity, CaptureItem.TYPE_TILE), holder.image, fallback -> {
            if (holder.boundUid == boundUid) {
                holder.photo = !fallback;
                applyTileGround(holder);
            }
        });
        bindSelection(holder, item);
        bindTileMark(holder, item);
    }

    private void bindTileMark(@NonNull TileHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        BrowserDownloadEntity entity = item.entity;
        SpannableStringBuilder text = new SpannableStringBuilder();
        CaptureMark mark = item.mark;
        if (mark != null) {
            appendStyled(text, statusText(context, mark), statusColor(context, mark), Typeface.BOLD, 0);
        }
        appendCaptionCount(context, text, item);
        if (item.showDomain && entity != null) {
            appendFact(text, domainOf(entity));
        }
        holder.meta.setText(text);
        holder.meta.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
        bindProgress(holder.progress, mark, holder.photo ? PHOTO_TRACK : mProgressTrack);
    }

    /** The square's ground rule (applySquareGround) for a tile's picture, plus
     *  the ⋮: white over a picture, the placeholder's action ink on the glyph. */
    private void applyTileGround(@NonNull TileHolder holder) {
        if (mFallbackInks == null) return;
        if (holder.photo) {
            holder.bottomBlock.setBackgroundResource(R.drawable.bottom_scrim);
            setPhotoInk(holder.quality, true);
            setPhotoInk(holder.duration, true);
            holder.more.setIconTint(ColorStateList.valueOf(Color.WHITE));
        } else {
            holder.bottomBlock.setBackground(null);
            setPhotoInk(holder.quality, false);
            setPhotoInk(holder.duration, false);
            holder.more.setIconTint(mFallbackInks.action);
        }
        if (holder.progress.getVisibility() == View.VISIBLE) {
            holder.progress.setTrackColor(holder.photo ? PHOTO_TRACK : mProgressTrack);
        }
    }

    private void setPhotoInk(@NonNull TextView view, boolean photo) {
        if (photo || mFallbackInks == null) {
            view.setTextColor(Color.WHITE);
            view.setShadowLayer(2f, 0f, 1f, PHOTO_TEXT_SHADOW);
        } else {
            view.setTextColor(mFallbackInks.label);
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
    }

    private void bindSquare(@NonNull SquareHolder holder, @NonNull CaptureItem item) {
        BrowserDownloadEntity entity = item.entity;
        if (entity == null) return;
        Context context = holder.itemView.getContext();
        if (mFallbackInks == null) {
            mFallbackInks = FallbackInks.resolve(context);
        }

        // Dimensions, led by GIF / SVG while unfiltered: under the "Images"
        // header a plain image needs no label, but a GIF among them is worth
        // knowing. The resolution stays under a chip too (it's the one fact a
        // picture's thumbnail doesn't show).
        String mime = entity.getMimeType();
        String label = item.showType && (FileUriHelper.isGIF(mime) || FileUriHelper.isSVG(mime))
                ? FileUriHelper.getLongMimeText(context, mime).toUpperCase(Locale.getDefault())
                : null;
        String caption = joinFacts(label, primaryFact(entity));
        holder.setTextOrHide(holder.caption, caption);
        holder.item.setContentDescription(entity.getFileName());

        // A small SVG is an icon: drawn at its own scale inside an inset
        // instead of stretched over the square (a 24px player glyph filled the
        // whole tile as a black blob). An SVG with no known size is treated as
        // one too — on a web page an SVG is nearly always an icon or a logo.
        boolean icon = FileUriHelper.isSVG(mime) && !isLargerThan(primaryFact(entity), ICON_MAX_PX);
        holder.image.setScaleType(icon ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        int inset = icon ? mIconInset : 0;
        holder.image.setPadding(inset, inset, inset, inset);

        final int boundUid = entity.getUid();
        holder.boundUid = boundUid;
        holder.photo = true;
        // The checkerboard goes on only once a real picture is there, so a
        // square still loading shows the card's plain ground, not a flash of
        // checks; the mime placeholder is opaque and needs none.
        holder.image.setBackground(null);
        applySquareGround(holder);
        GlideHelper.load(entity, optionsFor(entity, CaptureItem.TYPE_SQUARE), holder.image, fallback -> {
            if (holder.boundUid == boundUid) {
                holder.photo = !fallback;
                holder.image.setBackground(fallback ? null : holder.checker);
                if (fallback) {
                    // The mime placeholder fills the square like any other's.
                    holder.image.setPadding(0, 0, 0, 0);
                }
                applySquareGround(holder);
            }
        });
        bindSelection(holder, item);
        bindSquareMark(holder, item);
    }

    private void bindSquareMark(@NonNull SquareHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        CaptureMark mark = item.mark;
        int badge = 0;
        if (mark != null) {
            badge = switch (mark.state) {
                case CaptureMark.SAVED -> R.drawable.capture_badge_saved;
                case CaptureMark.PENDING -> R.drawable.capture_badge_pending;
                case CaptureMark.FAILED -> R.drawable.capture_badge_failed;
                default -> 0;
            };
        }
        if (badge != 0) {
            holder.badge.setImageResource(badge);
            holder.badge.setContentDescription(statusText(context, mark));
            holder.badge.setVisibility(View.VISIBLE);
        } else {
            holder.badge.setImageDrawable(null);
            holder.badge.setContentDescription(null);
            holder.badge.setVisibility(View.GONE);
        }
        bindProgress(holder.progress, mark, holder.photo ? PHOTO_TRACK : mProgressTrack);
        holder.bottomBlock.setVisibility(
                holder.caption.getVisibility() == View.VISIBLE || badge != 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * The square's caption ink follows what the slot ends up showing: white
     * with a shadow over the scrim on a picture, FallbackInks on the mime
     * placeholder (dark on the light theme's wash), with no scrim there — a
     * gradient over a flat colour is a smudge. The badge glyph carries its own
     * baked shadow, which reads on both. Same rule as the grid tiles'
     * applyGridGround.
     */
    private void applySquareGround(@NonNull SquareHolder holder) {
        if (mFallbackInks == null) return;
        if (holder.photo) {
            holder.bottomBlock.setBackgroundResource(R.drawable.bottom_scrim);
            holder.caption.setTextColor(Color.WHITE);
            holder.caption.setShadowLayer(2f, 0f, 1f, PHOTO_TEXT_SHADOW);
        } else {
            holder.bottomBlock.setBackground(null);
            holder.caption.setTextColor(mFallbackInks.label);
            holder.caption.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
        if (holder.progress.getVisibility() == View.VISIBLE) {
            holder.progress.setTrackColor(holder.photo ? PHOTO_TRACK : mProgressTrack);
        }
    }

    private void bindProgress(@NonNull LinearProgressIndicator progress, @Nullable CaptureMark mark,
                              int track) {
        if (mark == null || mark.state != CaptureMark.PROGRESS) {
            progress.setVisibility(View.GONE);
            return;
        }
        progress.setIndicatorColor(mProgressInk);
        progress.setTrackColor(track);
        // Only on a real change: re-setting it restarts the indeterminate
        // animation, and a mark payload arrives on every progress tick.
        if (progress.isIndeterminate() != mark.indeterminate) {
            progress.setIndeterminate(mark.indeterminate);
        }
        if (!mark.indeterminate) {
            progress.setProgressCompat(mark.percent, progress.getVisibility() == View.VISIBLE);
        }
        progress.setVisibility(View.VISIBLE);
    }

    /**
     * Selection chrome, matching Downloads/Bookmarks/History: a ROW gets the
     * tonal wash on its card plus the check in the ⋮'s slot (the ⋮ goes
     * INVISIBLE, holding the width, so nothing reflows); a SQUARE gets the 2dp
     * stroke plus the corner check — no slot to borrow, and a wash would fight
     * the picture. Ink is brandInk (SelectionStyling), resolved off the holder's
     * own context: in a private tab the sheet inflates under the incognito
     * overlay, whose brandInk is its own.
     */
    private void bindSelection(@NonNull CaptureHolder holder, @NonNull CaptureItem item) {
        Context context = holder.itemView.getContext();
        boolean selected = mActionMode && mSelected.contains(item.uid());
        holder.checkedView.setVisibility(mActionMode ? View.VISIBLE : View.GONE);
        holder.checkedView.setImageDrawable(!mActionMode ? null
                : selected ? SelectionStyling.checkedDrawable(context)
                : SelectionStyling.uncheckedDrawable(context));
        if (holder instanceof RowHolder row) {
            row.item.setCardBackgroundColor(selected
                    ? SelectionStyling.selectedCardWashOver(context,
                            com.google.android.material.R.attr.colorSurfaceContainerLow)
                    : MaterialColors.getColor(context,
                            com.google.android.material.R.attr.colorSurfaceContainerLow,
                            Color.TRANSPARENT));
            row.more.setVisibility(mActionMode ? View.INVISIBLE
                    : (row.hasVariants ? View.VISIBLE : View.GONE));
        } else if (holder instanceof TileHolder tile) {
            // The stroke goes on the PICTURE: the tile's root card is the
            // transparent touch target around picture and text.
            tile.picture.setStrokeColor(selected ? SelectionStyling.selectionInk(context) : Color.TRANSPARENT);
            tile.more.setVisibility(mActionMode ? View.INVISIBLE
                    : (tile.hasVariants ? View.VISIBLE : View.GONE));
        } else {
            holder.item.setStrokeColor(selected ? SelectionStyling.selectionInk(context) : Color.TRANSPARENT);
        }
    }

    // ── Facts ────────────────────────────────────────────────────────────

    /**
     * The capture's leading fact: video quality ("1080p"), image dimensions,
     * or a subtitle's language — whichever tag the parser set, the last one
     * winning as it always did; an untyped tag only when none of those exist.
     */
    @Nullable
    private static String primaryFact(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) return null;
        String primary = null;
        String untyped = null;
        for (FFmpegTagEntity tag : tags) {
            if (tag == null || TextUtils.isEmpty(tag.getText())) continue;
            switch (tag.getType()) {
                case FFmpegTagEntity.TYPE_QUALITY, FFmpegTagEntity.TYPE_RESOLUTION,
                     FFmpegTagEntity.TYPE_LANGUAGE -> primary = tag.getText();
                case FFmpegTagEntity.TYPE_DURATION -> { }
                default -> {
                    if (untyped == null) untyped = tag.getText();
                }
            }
        }
        return primary != null ? primary : untyped;
    }

    /** A subtitle track's language tag ("English (auto)"), or null. */
    @Nullable
    private static String languageFact(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) return null;
        for (FFmpegTagEntity tag : tags) {
            if (tag != null && tag.getType() == FFmpegTagEntity.TYPE_LANGUAGE
                    && !TextUtils.isEmpty(tag.getText())) {
                return tag.getText();
            }
        }
        return null;
    }

    /** Whether a "WxH" fact names a side larger than {@code maxPx}; false
     *  when there is no size to read. */
    private static boolean isLargerThan(@Nullable String size, int maxPx) {
        if (TextUtils.isEmpty(size)) return false;
        Matcher m = SIZE.matcher(size);
        if (!m.find()) return false;
        try {
            return Integer.parseInt(m.group(1)) > maxPx || Integer.parseInt(m.group(2)) > maxPx;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Duration, trimmed at render exactly as Downloads does
     *  (DateUtils.compactDuration: "00:00:11" → "0:11"). */
    @Nullable
    private static String durationFact(@NonNull BrowserDownloadEntity entity) {
        List<FFmpegTagEntity> tags = entity.getTags();
        if (tags == null) return null;
        for (FFmpegTagEntity tag : tags) {
            if (tag != null && tag.getType() == FFmpegTagEntity.TYPE_DURATION
                    && !TextUtils.isEmpty(tag.getText())) {
                return DateUtils.compactDuration(tag.getText());
            }
        }
        return null;
    }

    @NonNull
    private static String statusText(@NonNull Context context, @NonNull CaptureMark mark) {
        return switch (mark.state) {
            case CaptureMark.PROGRESS -> mark.finishing
                    ? context.getString(R.string.download_finishing)
                    : mark.indeterminate
                            ? context.getString(R.string.downloading)
                            : context.getString(R.string.capture_status_downloading, mark.percent);
            case CaptureMark.PENDING -> context.getString(R.string.download_queued);
            case CaptureMark.SAVED -> context.getString(R.string.capture_status_saved);
            default -> context.getString(R.string.capture_status_failed);
        };
    }

    /** Progress in the progress ink (the bar's own colour), a failure in the
     *  error colour, saved and pending in plain onSurface — the bold weight
     *  already sets them apart from the facts. */
    private int statusColor(@NonNull Context context, @NonNull CaptureMark mark) {
        return switch (mark.state) {
            case CaptureMark.PROGRESS -> mProgressInk;
            case CaptureMark.FAILED -> MaterialColors.getColor(context,
                    androidx.appcompat.R.attr.colorError, Color.RED);
            default -> MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        };
    }

    private static void appendFact(@NonNull SpannableStringBuilder text, @Nullable String fact) {
        if (TextUtils.isEmpty(fact)) return;
        if (text.length() > 0) text.append(" · ");
        text.append(fact);
    }

    private static void appendStyled(@NonNull SpannableStringBuilder text, @NonNull String value,
                                     int color, int style, int sizePx) {
        if (value.isEmpty()) return;
        if (text.length() > 0) text.append(" · ");
        int start = text.length();
        text.append(value);
        int end = text.length();
        text.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (style != Typeface.NORMAL) {
            text.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sizePx > 0) {
            text.setSpan(new AbsoluteSizeSpan(sizePx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    @Nullable
    private static String joinFacts(@Nullable String a, @Nullable String b) {
        if (TextUtils.isEmpty(a)) return b;
        if (TextUtils.isEmpty(b)) return a;
        return a + " · " + b;
    }


    // ── Selection (keyed by UID) ─────────────────────────────────────────

    public void toggleSelected(int position) {
        CaptureItem item = getItemAt(position);
        if (item == null || !item.isCapture()) return;
        int uid = item.uid();
        if (!mSelected.remove(uid)) {
            mSelected.add(uid);
        }
        notifyItemChanged(position, PAYLOAD_SELECTION);
    }

    public int getSelectedSize() {
        return mSelected.size();
    }

    public HashSet<Integer> getSelected() {
        return mSelected;
    }

    public void clearSelection() {
        mSelected.clear();
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    public void selectAll() {
        for (int i = 0; i < getItemCount(); i++) {
            CaptureItem item = getItem(i);
            if (item.isCapture()) {
                mSelected.add(item.uid());
            }
        }
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }

    public void setActionMode(boolean value) {
        mActionMode = value;
        notifyItemRangeChanged(0, getItemCount(), PAYLOAD_SELECTION);
    }


    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Falls back to extension-based detection when the entity's stored mime
     * type is missing or generic.
     */
    public static String resolveMimeType(@Nullable String mimeType, String fileUrl) {
        if (TextUtils.isEmpty(mimeType) || FileUriHelper.isMimeTypeUnknown(mimeType)) {
            return FileUriHelper.getMimeTypeFromFile(fileUrl);
        }
        return mimeType;
    }


    // ── ViewHolders ──────────────────────────────────────────────────────

    /** A row, a tile or a square: a capture, clickable, selectable. */
    abstract static class CaptureHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener, View.OnLongClickListener {

        final OnItemClickListener listener;
        final MaterialCardView item;
        final AppCompatImageView image;
        final AppCompatImageView checkedView;
        /** The uid this holder binds now — a Glide callback lands
         *  asynchronously and must not repaint a recycled holder. */
        int boundUid = -1;

        CaptureHolder(@NonNull View view, OnItemClickListener listener) {
            super(view);
            this.listener = listener;
            item = view.findViewById(R.id.item);
            image = view.findViewById(R.id.image);
            checkedView = view.findViewById(R.id.item_download_more_checked);
            item.setOnClickListener(this);
            item.setOnLongClickListener(this);
        }

        void setTextOrHide(@Nullable TextView tv, @Nullable String text) {
            if (tv == null) return;
            if (TextUtils.isEmpty(text)) {
                tv.setVisibility(View.GONE);
                return;
            }
            tv.setVisibility(View.VISIBLE);
            tv.setText(text);
        }

        @Override
        public void onClick(View v) {
            int position = getBindingAdapterPosition();
            if (listener != null && position != RecyclerView.NO_POSITION) {
                listener.onItemClick(position, v.getId());
            }
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getBindingAdapterPosition();
            if (listener != null && position != RecyclerView.NO_POSITION) {
                listener.onLongClick(position, v.getId());
                return true;
            }
            return false;
        }
    }

    static final class RowHolder extends CaptureHolder {
        final TextView fileName;
        final TextView meta;
        final MaterialButton more;
        final LinearProgressIndicator progress;
        /** A subtitle's compact row: glyph instead of thumbnail, language as
         *  the title (bindCompact). */
        final boolean compact;
        boolean hasVariants;

        RowHolder(@NonNull View view, OnItemClickListener listener, boolean compact) {
            super(view, listener);
            this.compact = compact;
            fileName = view.findViewById(R.id.file_name);
            meta = view.findViewById(R.id.row_meta);
            more = view.findViewById(R.id.item_download_more);
            progress = view.findViewById(R.id.row_progress);
            image.setClipToOutline(true);
            more.setOnClickListener(this);
            Utils.expandTouchArea(more);
        }
    }

    static final class TileHolder extends CaptureHolder {
        final MaterialCardView picture;
        final View bottomBlock;
        final TextView quality;
        final TextView duration;
        final TextView fileName;
        final TextView meta;
        final MaterialButton more;
        final LinearProgressIndicator progress;
        /** The picture shows a real frame or poster (vs the mime placeholder). */
        boolean photo = true;
        boolean hasVariants;

        TileHolder(@NonNull View view, OnItemClickListener listener) {
            super(view, listener);
            picture = view.findViewById(R.id.tile_picture);
            bottomBlock = view.findViewById(R.id.bottom_block);
            quality = view.findViewById(R.id.tile_quality);
            duration = view.findViewById(R.id.tile_duration);
            fileName = view.findViewById(R.id.file_name);
            meta = view.findViewById(R.id.row_meta);
            more = view.findViewById(R.id.item_download_more);
            progress = view.findViewById(R.id.tile_progress);
            more.setOnClickListener(this);
            Utils.expandTouchArea(more);
        }
    }

    static final class SquareHolder extends CaptureHolder {
        final View bottomBlock;
        final TextView caption;
        final AppCompatImageView badge;
        final LinearProgressIndicator progress;
        /** Shown behind a loaded picture, through its transparent pixels. */
        final CheckerboardDrawable checker;
        /** The slot shows a real picture (vs the mime placeholder). */
        boolean photo = true;

        SquareHolder(@NonNull View view, OnItemClickListener listener) {
            super(view, listener);
            checker = new CheckerboardDrawable(view.getContext());
            bottomBlock = view.findViewById(R.id.bottom_block);
            caption = view.findViewById(R.id.square_caption);
            badge = view.findViewById(R.id.square_badge);
            progress = view.findViewById(R.id.square_progress);
        }
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView count;

        HeaderHolder(@NonNull View view) {
            super(view);
            count = view.findViewById(R.id.section_count);
        }
    }

    static final class DividerHolder extends RecyclerView.ViewHolder {
        final TextView count;

        DividerHolder(@NonNull View view) {
            super(view);
            count = view.findViewById(R.id.section_count);
        }
    }
}

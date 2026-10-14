package com.solarized.firedown.ui.diffs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;

import com.solarized.firedown.data.capture.CaptureItem;
import com.solarized.firedown.data.entity.BrowserDownloadEntity;

import org.apache.commons.collections4.CollectionUtils;

import java.util.Objects;

/**
 * Diffs the Captured sheet's items. A change that touches only the download
 * mark (a progress tick, pending → downloading → saved) comes back as
 * {@link #PAYLOAD_MARK}, so the adapter rebinds the status alone and the
 * thumbnail never reloads while a download runs.
 */
public class CaptureItemDiffCallback extends DiffUtil.ItemCallback<CaptureItem> {

    public static final Object PAYLOAD_MARK = new Object();

    @Override
    public boolean areItemsTheSame(@NonNull CaptureItem oldItem, @NonNull CaptureItem newItem) {
        return oldItem.sameItemAs(newItem);
    }

    @Override
    public boolean areContentsTheSame(@NonNull CaptureItem oldItem, @NonNull CaptureItem newItem) {
        return oldItem.sameMarkAs(newItem) && sameExceptMark(oldItem, newItem);
    }

    @Nullable
    @Override
    public Object getChangePayload(@NonNull CaptureItem oldItem, @NonNull CaptureItem newItem) {
        return sameExceptMark(oldItem, newItem) ? PAYLOAD_MARK : null;
    }

    private static boolean sameExceptMark(@NonNull CaptureItem oldItem, @NonNull CaptureItem newItem) {
        return oldItem.samePresentationAs(newItem) && sameEntity(oldItem.entity, newItem.entity);
    }

    private static boolean sameEntity(@Nullable BrowserDownloadEntity a, @Nullable BrowserDownloadEntity b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.getFileName(), b.getFileName())
                && Objects.equals(a.getFileUrl(), b.getFileUrl())
                && Objects.equals(a.getFileOrigin(), b.getFileOrigin())
                && Objects.equals(a.getFileDuration(), b.getFileDuration())
                && a.getDurationTime() == b.getDurationTime()
                && Objects.equals(a.getMimeType(), b.getMimeType())
                && Objects.equals(a.getFileHeaders(), b.getFileHeaders())
                && a.getHasVariants() == b.getHasVariants()
                // Collection equality ignoring order: variants and tags arrive
                // in whatever order the parser emitted them.
                && CollectionUtils.isEqualCollection(a.getStreams(), b.getStreams())
                && CollectionUtils.isEqualCollection(a.getTags(), b.getTags());
    }
}

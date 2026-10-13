package com.solarized.firedown.sync;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * A WebDAV server the user signed in to: the collection backups go under,
 * the sign-in, and the certificate the user chose to trust when the phone
 * itself does not ({@link #pinSha256}). Immutable; equality covers every
 * field, so a changed password or pin is a different config (and a fresh
 * client — see {@link BackupStores}).
 */
public final class WebDavConfig {

    /** The collection {@code Firedown Backup} is made in, with a trailing
     *  slash: the address the check resolved, after redirects. */
    @NonNull
    public final String url;
    @NonNull
    public final String user;
    @NonNull
    public final String password;
    /** Lowercase hex SHA-256 of the server's leaf certificate, trusted on
     *  top of the phone's own trust store; null when none was pinned. */
    @Nullable
    public final String pinSha256;

    public WebDavConfig(@NonNull String url, @NonNull String user, @NonNull String password,
                        @Nullable String pinSha256) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.pinSha256 = pinSha256;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebDavConfig)) {
            return false;
        }
        WebDavConfig other = (WebDavConfig) o;
        return url.equals(other.url) && user.equals(other.user)
                && password.equals(other.password)
                && Objects.equals(pinSha256, other.pinSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, user, password, pinSha256);
    }

    /** No password in it, ever: this ends up in logs. */
    @NonNull
    @Override
    public String toString() {
        return "WebDavConfig{" + url + ", user=" + user
                + (pinSha256 == null ? "" : ", pinned") + "}";
    }
}

# Security

Firedown is a browser and downloader for Android. It has no accounts, no
telemetry and no analytics, and its two sync features — Backup (encrypted
downloads in a folder you choose) and bookmark sync — are built so that
whoever holds the bytes **cannot read them**: not the storage provider behind
the folder, not the bookmark server, not us.

This file is for people who want to check that claim in source rather than take
it, and for anyone who has found a way to break it. There is a plain-language
version of the same material at <https://firedown.app/security>.

## Reporting a vulnerability

**Email <info@solarized.dev>** instead of opening a public issue — including for
the server-side service, which is not in this repository.

Helpful but not required: what you did, what you expected, what happened
instead, and the build you were on. A clear description of the flaw is worth
more than a working exploit.

This is a very small project. You will get a human reply, though not
necessarily a fast one, and there is no bug bounty. You will get credit if you
want it, and a straight answer about whether and when it is fixed.

## What is in this repository

The Android app. **Everything that encrypts anything runs here**, which is the
point: the security of Backup does not depend on trusting code you cannot
read, and there is no Firedown server in the backup path at all.

| Area | Where |
|---|---|
| Key derivation from the recovery code | `app/src/main/java/com/solarized/firedown/sync/crypto/SyncIdentity.java` |
| Per-file encryption for backed-up files | `.../sync/crypto/VaultCrypto.java` |
| Encrypted bookmark blob + per-file sidecars | `.../sync/crypto/BookmarkBlob.java` |
| The backup folder (what lands where) | `.../sync/FolderStore.java` |
| Backup / restore pipeline | `.../sync/VaultEngine.java` |
| Bookmark-sync request signing (the signed byte string) | `.../sync/crypto/Canonical.java` |

The tests beside them are worth reading too. Several assert **exact bytes**
against fixed vectors rather than merely checking that a round trip works — a
round trip succeeds happily against an implementation that is wrong but
self-consistent, which is a real failure mode we have hit. `VaultCryptoTest`
is the clearest example: the encrypted-file format is what every existing
backup folder holds, so it is pinned to the byte.

Bookmark sync talks to one server, `api.firedown.app`, and **that server is
not public.** It does not need to be in order to check the claims below —
encryption and key derivation happen on the client, so a reader of this
repository can confirm that no key is ever sent anywhere.

## Threat model

Whoever holds the bytes is treated as **untrusted**. For Backup that is the
provider behind the folder you picked (Google Drive, Dropbox, Nextcloud, the
SD card's finder); for bookmark sync it is our server. Assume either could be
compromised, seized, subpoenaed, or misconfigured. Your files and bookmarks
should stay unreadable in all of those cases.

What holds under that assumption:

- **Files are encrypted on the device before they are copied into the
  folder**, each under its own key. The folder receives ciphertext and opaque
  file names; the provider's own sync carries it wherever it carries it.
- **Every key derives from a recovery code that never leaves the device.** It
  is not written to the folder and not transmitted during bookmark sync. We
  hold no copy and cannot generate one.
- **The file index is itself encrypted.** Each backed-up file sits beside a
  small encrypted sidecar holding its name, type, dates and preview image;
  the provider can list the pair, not open it.
- **Each encrypted piece is bound to its position in a specific file**, so a
  hostile provider cannot reorder pieces, swap them between files, or replay
  one file's data into another without decryption failing.
- **Bookmarks reach our server as one encrypted blob** under a key the
  server never sees. It stores and returns it; it cannot open it.

What does **not** hold — listed because a threat model that records only wins
is useless:

- **Metadata is visible.** File sizes, file counts and backup times are
  readable from the folder, and the provider knows which device wrote it.
  Bookmark sync shows our server your IP address and when you sync.
- **The provider is yours to trust for availability, not for secrecy.** A
  provider that deletes, corrupts or signs you out of the folder loses the
  backup; it cannot read it.
- **A compromised device defeats all of it.** Encryption happens on the phone;
  if the phone is compromised, plaintext is available there.
- **A lost recovery code is unrecoverable**, deliberately. There is no reset
  path, because a reset path is exactly what would let someone else in.
- **The Safe Folder is local-only.** It is excluded from Backup entirely and
  is protected by your device lock, not by the recovery code.

## Known limits we have not fixed

Real, currently true, and disclosed on purpose.

- **HTTP header spoofing cannot defeat TLS fingerprinting.** Where the
  downloader reproduces a browser's request headers to fetch media, a server
  that also fingerprints the TLS handshake (JA3/JA4) can still tell the
  difference. We do not claim otherwise.
- **Translated privacy and security pages are not legally reviewed.** The
  English versions govern, and each translated page says so.

## History

Until October 2026 Firedown ran a hosted, paid Cloud Backup
(`storage.firedown.app`, with an anonymous payment mint and a browser
client). It was closed and the code removed; Backup now writes to a folder
the user owns, in the same encrypted format. The git history carries the
old design and its security notes.

## Scope

In scope: the Android app in this repository and the service behind
`api.firedown.app` (bookmark sync, crash reports, feedback, P2P signaling).

Out of scope: findings that require a rooted or already-compromised device;
vulnerabilities in GeckoView itself (report those to
[Mozilla](https://www.mozilla.org/en-US/security/bug-bounty/)); missing
hardening headers on the marketing site with no demonstrated impact; and
scanner output with no analysis attached.

Anything that lets a party other than the user read backed-up file contents,
file names, bookmarks, or a recovery code is in scope and serious, however
unlikely the path.

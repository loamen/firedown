# Server sunset — the plan, step by step

Firedown stops running a paid cloud. The hosted Cloud Backup closes, the
"cloud" VPS that holds storage + mint + the bookmark API is destroyed, and
probably the TURN relay's box with it. What survives on a Firedown server
afterwards holds no user data: the website, the update ping it serves, the
crash collector, feedback, bookmark sync (until it moves into a folder) and
the P2P mailbox. A folder backup (Google Drive, Dropbox, an SD card through
the Storage Access Framework) comes LATER as its own feature, with no
server to migrate from — users bring their files home to the phone first.

This file is the checklist. Tick boxes as steps land; each phase names its
repo, its gate, and the verification that closes it. Decisions are recorded
here so they are not re-derived.

## Decisions (2026-10-12)

- **Order: the app update first, then the server moves.** Release N turns
  every WRITE door of Cloud Backup off (new backups, purchases, pairing) and
  keeps every READ door (restore, restore all, stream, delete) until a
  stated date. Only then does the api move host and the cloud VPS go dark.
  So most installs stop talking to storage/mint before those hosts vanish,
  and the ~six accounts holding files get weeks to restore.
- **No ciphertext migration tool.** The earlier idea (copy objects from the
  server into the folder under the same keys) is dropped: the sunset release
  makes everyone restore to the phone, and the folder backup, when it
  ships, backs up from the phone. Fewer moving parts, no server dependency
  in the folder feature.
- **`api.firedown.app` keeps its NAME and moves HOST.** Every APK in the wild
  posts crashes, feedback and P2P signaling to that name
  (`Preferences.SYNC_DEFAULT_BACKEND`), and the release build PUTs its R8
  mapping there. A rename would cut off every install that never updates.
  Crash and feedback move TOGETHER with bookmark sync, as the whole
  `firedown-api` binary: two tables in its one Postgres database plus the
  mappings directory. Slimming the binary is a later pass.
- **Stats never move.** DAU/MAU come from the www nginx log of `/status.json`
  (`firedown-website/ops/stats`), not from the API.
- **The TURN relay is independent of the cloud VPS** (`turn.firedown.app`
  is its own box) and the client already degrades to direct + STUN when
  `/v1/relay/creds` answers 404. Dropping it needs no app change: delete
  `FIREDOWN_TURN_SECRET` from `api.env`, destroy the box.
- **Stop selling the day release N goes out** — server side, so old APKs
  stop too: deactivate every mint keyset and the app's buy screen shows its
  existing "No storage credit is available to buy right now".

## Today's hosts

| host | what runs there | fate |
|---|---|---|
| **www** (nginx, Let's Encrypt, Cloudflare-fronted) | static site, `status.json`, `ops/stats` timers | stays; GAINS `firedown-api` + Postgres (Phase 2) |
| **cloud VPS** (OVH; Caddy, Postgres) | `storage-api` + R2, `mint-api` + phoenixd + pruned bitcoind, `firedown-api` (bookmarks, crash, feedback, P2P, relay creds) | loses `firedown-api` (Phase 2), destroyed (Phase 3) |
| **turn** (coturn) | the TURN relay | destroyed (Phase 3), unless kept on purpose |

## Phase 1 — release N: Cloud Backup read-only (`firedown`)

- [x] **1.1 The switch** — `sync/CloudSunset.java` (`ACTIVE` + `DELETE_AT_MS`),
      consulted at every write door: download sheet row, multi-select action
      and batch path, finished-notification action + receiver,
      `VaultBackupWorker` (pre-update jobs fail with `SLUG_CLOSED`), buy CTA /
      overflow / screen, pairing, `registerInBackground`, the Storage
      screen's upsell and "Free up space". Voice: the Cloud hero, the Backups
      header, the Downloads banner (own dismissal key), the account watch
      (closing notice twice at most), the Storage review confirm. Strings in
      16 locales. CLAUDE.md carries the section.
- [ ] **1.2 Set the date** — `CloudSunset.DELETE_AT_MS` to the real deletion
      instant (UTC midnight), the same one the operator reaps on in 3.4. Pick
      it ≥ 6 weeks after the release date.
- [ ] **1.3 Build + device pass** — a debug build (the sandbox cannot compile:
      no Android SDK / GeckoView AAR here, so 1.1 is reviewed, not compiled).
      Check: the ⋮ row and the multi-select icon are gone; the finished
      notification has no Back up action; the Cloud screen shows the notice
      (set-up and keyless devices); the Backups list restores, streams and
      deletes and its overflow has no top-up; the Downloads banner shows the
      date for a set-up account and dismisses; the Storage screen has neither
      card; a queued backup from the previous build fails as "Not uploaded:
      Cloud Backup is closing"; the watch notification appears once.
- [ ] **1.4 Release** — `./gradlew bumpVersion`, the CHANGELOG bullet ("Cloud
      Backup is closing on <date>: new backups are off, restore everything
      you want to keep to this phone before then"), `assembleRelease` (the
      mapping upload goes to the api — still on the cloud VPS at this
      point, fine), `releaseStatus`, publish the APK, commit `status.json` on
      the website.
- [ ] **1.5 Server, the same day** — stop selling:
      `UPDATE mint_keysets SET active = false;` on the mint DB. Issue/redeem
      stay up for the on-chain late window (3.2).

Gate: release N is the update every install is offered.

## Phase 2 — move `api.firedown.app` to the www host (`firedown-api` + ops)

Code is done (branch `sunset/api-to-www`); the rest is host work with the
exact commands in `deploy/deploy-runbook.md` §11.

- [x] **2.1 Provisioner nginx mode + runbook §11** (and the `keep()` fix it
      exposed: the env file was truncated before the heredoc read it, so
      re-runs reset every operator value; TURN/relay/signaling knobs now
      written through `keep()`).
- [ ] **2.2 Build + ship** the binary, the provisioner, the landing page and
      the Origin Certificate to www.
- [ ] **2.3 Freeze + dump** on the cloud VPS: stop the service, `pg_dump`, tar
      the mappings, copy `api.env`.
- [ ] **2.4 Provision on www** (seed `api.env` FIRST), restore, untar, start,
      local health check.
- [ ] **2.5 DNS**: `api.firedown.app` → www, proxied.
- [ ] **2.6 Verify through the edge**: health commit; feedback → ntfy and the
      page; `uploadReleaseMapping` 2xx; a cross-network P2P share; a bookmark
      edit syncs; `/v1/crashes` retraces an old report.
- [ ] **2.7 Decommission the copy** on the cloud VPS; install the weekly
      backup on www; merge the two doc branches
      (`firedown-api` `sunset/api-to-www`, `firedown-website`
      `sunset/api-vhost-note`).

Gate: 2.6 all green.

## Phase 3 — destroy the cloud VPS (and the TURN box)

- [ ] **3.1 Read-only at the server too** (belt for old APKs; release N no
      longer writes): a Cloudflare rule blocking `POST`/`PUT` on
      `storage.firedown.app/v1/*` — restores are `GET`.
- [ ] **3.2 Mint**: 30 days after the last quote (the on-chain late window,
      `OnchainPollPolicy.LATE_PAYMENT_WINDOW_MS`), stop `mint-api`, close the
      phoenixd channel and sweep its balance to the cold wallet; bitcoind is
      watch-only.
- [ ] **3.3 Pairing + web client**: nothing to do server-side before 3.4; the
      web client dies with storage.
- [ ] **3.4 Delete at `DELETE_AT_MS`**: `storage-api --reap`, delete the R2
      bucket and its token, drop the storage + mint databases, cancel OVH
      Automated Backup, destroy the VPS. Keep the last off-site dump for the
      retention period, then delete it.
- [ ] **3.5 TURN**: remove `FIREDOWN_TURN_SECRET` (and `_URLS`) from
      `/etc/firedown/api.env` on www, restart `firedown-api` (creds → 404,
      shares degrade to direct + STUN), destroy the box, delete the DNS
      record.
- [ ] **3.6 The old-APK tail**: point `storage.firedown.app` and
      `mint.firedown.app` at www and serve `410 Gone` for `/v1/*` for six
      months (`StorageApiClient` maps a 4xx to a fatal error, so a pre-N
      install fails once instead of retrying for hours). Then delete the
      records and the Origin Certificate if nothing else uses it.

## Phase 4 — delete the code and the website copy (after 3.4)

- [ ] **4.1 `firedown`** — release N+1, verified at deletion time from today's
      inventory: `sync/CloudSunset` (its job is done), `StorageApiClient`,
      `MintClient`, `CreditPurchase`, `PendingPurchase`, `CreditSettleWorker`,
      `CreditSettlement`, `OnchainPollPolicy`, `PaymentRequests`,
      `PurchaseError`, `PairClient`, `crypto/PairSeal`,
      `crypto/BlindSignature`, `VaultEngine`, `VaultObjectReader`,
      `VaultObjectInputStream`, `VaultDataSource`, `VaultBackupWorker`,
      `VaultRestoreWorker`, `CloudWatchWorker`,
      `CloudBackupNotificationReceiver`, `VaultSmokeTest`, `CloudBackupManager`
      and the Cloud screens (`SyncSettingsFragment` keeps the recovery code +
      bookmarks switch; `CloudBackupListFragment`, the item sheet,
      `CloudBackupStreamActivity`, `VaultObjectModelLoader`,
      `BuyCreditFragment` + view model, `BuyBitcoinSheetDialogFragment`,
      `PairApprovalViewModel` + dialog, `CloudStatusPreference`,
      `CloudBuyButtonPreference`, `CloudDeleteFragment`'s backups row go);
      the Downloads banner's cloud stages, the Storage screen's two cards,
      the review list's cloud marks, `EXTRA_RESTORE_ALL`; the
      `Preferences.CLOUD_*`, `STORAGE_DEFAULT_BACKEND`, mint/pair URL
      constants; the payment JVM tests; every `cloud_*`, `buy_credit_*`,
      `pair_*` string in 16 locales; the Cloud Backup sections of
      `CLAUDE.md`. KEEP: `crypto/Pow` (crash and feedback), `SyncIdentity`,
      `VaultCrypto`, `BookmarkBlob`, `Merge`, `VaultManifest` (the folder
      backup reuses the format), `AnonymousPost`, `CrashUploader`,
      `FeedbackFragment`, bookmark sync.
- [ ] **4.2 `firedown-api`**: delete `cmd/storage-api`, `cmd/mint-api`,
      `internal/storage`, `internal/mint`, `internal/shared/blind`,
      `deploy/storage-provision.sh`, `deploy/mint-provision.sh`,
      `deploy/turn-provision.sh`, the two landing pages,
      `docs/cloud-storage-spec.md`, `docs/cloud-mint-spec.md`,
      `docs/web-client-cors.md`, the storage vectors; narrow
      `deploy/cloudflare-waf-setup.sh` to `api.firedown.app`; the `/v1/pair`
      and relay routes; runbook §§2–9 become history.
- [ ] **4.3 `firedown-website`**: delete `backup/` and the `tools/fd-*.mjs`
      web-client tests, `SYNC_AND_STORAGE_WEBSITE_TASK.md`; rewrite the Cloud
      Backup part of `security.html` (+18 locales); scrub "cloud backup" /
      "storage credit" from `features.html`, `install.html`, `privacy.html`
      and the 18 `*/index.html`; `sitemap.xml` and `robots.txt` lose
      `/backup`.

## Later — the folder backup (its own plan, when wanted)

The canvas sketches (https://claude.ai/artifact/8Mv2onybPc3UfidGjwjVgJ) and
the design notes stand: SAF folder, encrypted objects in the existing
format, per-object sidecar as the commit marker, index as a rebuildable
cache, bookmarks as per-device blobs merged by `Merge`. It needs no server
and no migration; it backs up from the phone.

## Status log

- 2026-10-12 — plan written, then RE-CUT (maintainer): the app update goes
  first, and the folder backup is decoupled from the shutdown. Phase 1.1
  implemented on `ccr-4d141886-ro0eii` (uncompiled here — 1.3 is the build
  + device pass). Phase 2.1 done on `firedown-api` `sunset/api-to-www`; on
  the way the provisioner's `keep()` turned out never to have preserved a
  re-run's operator values (truncate-before-read), fixed and pinned by a
  seeded-file test.

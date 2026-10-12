# Server sunset — the plan, step by step

Firedown stops running a paid cloud. Backups move to a folder the user picks
(Google Drive, Dropbox, Proton Drive, an SD card — anything with a Storage
Access Framework provider), bookmark sync moves into the same folder, and the
"cloud" VPS that holds storage + mint + the bookmark API is destroyed. What
survives on a Firedown server afterwards holds no user data: the website, the
update ping it serves, the crash collector, feedback, the P2P mailbox and the
relay-credentials route.

This file is the checklist. Tick boxes as steps land; each phase names its
repo, its gate, and the verification that closes it. Decisions are recorded
here so they are not re-derived.

## Decisions (2026-10-12)

- **`api.firedown.app` keeps its NAME and moves HOST.** Every APK in the wild
  posts crashes, feedback and P2P signaling to that name
  (`Preferences.SYNC_DEFAULT_BACKEND`), and the release build PUTs its R8
  mapping there. Renaming the host would silently cut off every install that
  never updates — exactly the installs whose crash reports matter. So the
  service moves to the www host and the DNS record is repointed; the app and
  the Gradle task change nothing.
- **Crash and feedback migrate TOGETHER, as the whole `firedown-api` binary,
  with bookmark sync still inside it.** They are two tables (`crash_reports`,
  `feedback`) in the one Postgres database the binary opens unconditionally,
  plus the mappings directory on disk. Slimming the binary is a later pass;
  moving it whole is the runbook's existing §10 recipe run in reverse, with
  zero code change in the API.
- **Stats never move.** DAU/MAU come from the www nginx log of `/status.json`
  (`firedown-website/ops/stats`), not from the API. Blocked-ads counts are
  on-device. Nothing to do.
- **The TURN relay is untouched by this plan.** `turn.firedown.app` runs on
  its OWN cheap box (`deploy/turn-provision.sh`), not on the cloud VPS; its
  secret rides `api.env` to the new host. Keeping or dropping it is a separate
  cost decision; dropping it needs no app change (a 404 on
  `/v1/relay/creds` degrades to direct + STUN by design).
- **No app change is allowed to depend on the server being gone.** Release N
  (the folder backend) must work against the live storage service for the
  migration, and must keep working after the service is dead. The old-APK
  tail is handled by serving 410 on the dead hostnames for a while.

## Today's hosts

| host | what runs there | fate |
|---|---|---|
| **www** (nginx, Let's Encrypt, Cloudflare-fronted) | static site, `status.json`, `ops/stats` timers | stays; GAINS `firedown-api` + Postgres (Phase 1) |
| **cloud VPS** (OVH; Caddy, Postgres) | `storage-api` + R2, `mint-api` + phoenixd + pruned bitcoind, `firedown-api` (bookmarks, crash, feedback, P2P, relay creds) | loses `firedown-api` (Phase 1), then destroyed (Phase 3) |
| **turn** (coturn) | the TURN relay | unchanged |

## Phase 1 — move `api.firedown.app` to the www host (`firedown-api` + ops)

No app change. One binary, one database, one directory.

- [x] **1.1 Provisioner learns nginx** (`deploy/sync-api-provision.sh`,
      `FIREDOWN_PROXY=nginx`): skips the Caddy package and the Caddy site,
      writes an nginx server block for `api.firedown.app` instead (TLS from
      the Cloudflare Origin Certificate, optional Authenticated Origin Pulls
      via `ssl_verify_client`, `/v1/` proxied to `127.0.0.1:7777` with the
      three trusted headers `X-Real-Client-IP` / `X-Real-Country` /
      `X-CF-Ray` copied from `CF-Connecting-IP` / `CF-IPCountry` / `CF-Ray`,
      a body cap big enough for the gzipped mapping PUT, a read timeout above
      the 25 s P2P long-poll, its own access log so `ops/stats` never sees
      it, the landing page at `/`), and leaves the host firewall alone
      (`FIREDOWN_SKIP_FIREWALL=1` default in nginx mode — www already governs
      443). Runbook §11 documents the move.
- [ ] **1.2 Build + ship**: `make build` → `dist/firedown-api`; copy the
      binary, `deploy/sync-api-provision.sh`, `deploy/api-landing.html` and
      the cloud VPS's Origin Certificate (`/etc/caddy/tls/origin.pem` +
      `.key`, it covers `*.firedown.app`) to www.
- [ ] **1.3 Freeze + dump on the cloud VPS**: `systemctl stop firedown-api
      firedown-gc.timer`; `pg_dump -Fc firedown`; `tar` of
      `/var/lib/firedown/mappings`; copy `/etc/firedown/api.env` for its
      operator values (`FIREDOWN_CRASH_ADMIN_TOKEN`, the two ntfy URLs,
      `FIREDOWN_TURN_SECRET`, `FIREDOWN_MAX_ACCOUNTS`).
- [ ] **1.4 Provision on www** in nginx mode, restore the dump over the empty
      schema, untar the mappings, start; `curl 127.0.0.1:7777/v1/health`.
- [ ] **1.5 DNS**: Cloudflare A record `api.firedown.app` → www IP, proxied.
      The WAF skip rule and the register rate-limit rule key on the hostname,
      so nothing else changes.
- [ ] **1.6 Verify through the edge**, each one a real client path:
      `/v1/health` reports the shipped commit; Settings → Send feedback lands
      on `/v1/feedback` and pings ntfy; `./gradlew uploadReleaseMapping`
      (`firedown.mappingForce=true` is set, so a re-upload of the current
      build is fine) returns 2xx; a cross-network P2P share connects without
      the human-relayed reply; bookmark sync pushes after a bookmark edit;
      `/v1/crashes` opens with the token and retraces an old report (proves
      the mappings came across).
- [ ] **1.7 Decommission the old copy**: `systemctl disable --now
      firedown-api firedown-gc.timer` on the cloud VPS, remove
      `/etc/caddy/conf.d/api.caddy`, reload Caddy. Install the weekly
      `firedown-backup` on www (it tars the mappings beside the dump).
- [ ] **1.8 Docs**: `firedown-api/CLAUDE.md` Deploy section + `deploy/README.md`
      say "api on www; cloud VPS = storage + mint only";
      `firedown-website/README.md` points at the api vhost the provisioner
      owns.

Gate: 1.6 all green. Nothing in Phases 2–4 starts before this closes.

## Phase 2 — the app: folder backend, migration, bookmarks in the folder (`firedown`)

Release N. Ships while the storage service is still READABLE, because the
migration copies ciphertext out of it.

- [ ] **2.1 `FolderStore`** (SAF): `ACTION_OPEN_DOCUMENT_TREE`, persisted
      grant (the `DownloadBackupMirror.rememberRestoreTree` precedent), layout
      `Firedown Backup/<account>/objects/<objectId>.fdvc` + a per-object
      `.entry` sidecar written LAST as the commit marker, `index.fdsb` as a
      rebuildable cache (never the truth), orphan sweep after 24 h, ids
      minted on the client. Folder is the truth; there is no OCC over a
      sync-lagged folder.
- [ ] **2.2 `VaultEngine` over `FolderStore`**: the eight `StorageApiClient`
      call sites become folder reads/writes; chunk format, DEK wrapping and
      the FDVC2 AAD stay byte-identical (`VaultCryptoTest` + the web vectors
      keep passing); `VaultObjectReader` reads a `DocumentFile`.
- [ ] **2.3 Migration** ("Move my backups to the folder" on the Cloud screen
      while `isSetUp()` against the server): pull the manifest, copy every
      object chunk by chunk into the folder under the SAME objectId / DEK /
      AAD, write the sidecars, then mark the account migrated. Resumable per
      object; verified by restoring one migrated file on a second device.
- [ ] **2.4 Bookmarks in the folder**: per-device `bookmarks/<deviceId>.fdsb`
      (the `BookmarkBlob` framing), union + LWW + tombstones through
      `Merge.java` on read; `SyncManager` over the folder; the
      `/v1/sync/*`, `/v1/register*`, `/v1/quota` calls go.
- [ ] **2.5 UI** per the canvas (frames 1–8): hero = folder + usage from the
      index, "Choose backup folder" CTA, "Change folder", the folder-lost
      state, the download ⋮ "Back up to <provider>" / "Set up backup" rows;
      recovery-code reveal/adopt unchanged; `CloudWatchWorker` becomes a
      folder-reachability check that posts ONE notification on a failed
      backup. Strings in all 16 locales. Nothing on Home (main's rule).
- [ ] **2.6 In-app notice**: the Cloud screen states the storage closing date
      (`<DATE-RO>` read-only, `<DATE-OFF>` deleted) for an account that has
      not migrated; the notice copy is honest about what is lost.
- [ ] **2.7 Release N**: `bumpVersion`, CHANGELOG, `releaseStatus`,
      `status.json` committed on the website. Dates in 2.6 give every user at
      least `<N weeks>` before `<DATE-RO>`.

Gate: release N is the update every install is offered, and `<DATE-RO>` is at
least four weeks after it went out.

## Phase 3 — wind the cloud VPS down (ops, `firedown-api`)

Order matters: selling stops first, settlement stays alive for the on-chain
late window, reads stay alive until every migrator has had time.

- [ ] **3.1 Stop selling** (any time after Phase 1): deactivate every mint
      keyset — `UPDATE mint_keysets SET active = false;` — so `/v1/mint/keys`
      is empty and the app's buy screen shows its existing "No storage credit
      is available to buy right now". Issue/redeem keep running.
- [ ] **3.2 Keep the mint up 30 days past the last quote** — the on-chain
      late window (`OnchainPollPolicy.LATE_PAYMENT_WINDOW_MS`) during which a
      paid-but-unconfirmed purchase still settles. Then stop `mint-api`,
      close the phoenixd channel and sweep its balance to the cold wallet;
      bitcoind is watch-only (nothing to sweep server-side).
- [ ] **3.3 Read-only at `<DATE-RO>`**: `storage-api --suspend-account` per
      account is the existing operator switch for writes; for a global
      freeze, a Cloudflare rule blocking `POST`/`PUT` on
      `storage.firedown.app/v1/*` is enough (restores are `GET`). Release N
      no longer writes there anyway; this is belt for old APKs.
- [ ] **3.4 Delete at `<DATE-OFF>`**: `storage-api --reap`, then delete the R2
      bucket and its API token, drop the three databases, revoke the Origin
      Certificate if nothing else uses it, cancel OVH Automated Backup,
      destroy the VPS. Keep the last `firedown-backup` dump off-site for the
      bookmark accounts' retention period, then delete it.
- [ ] **3.5 The old-APK tail**: point `storage.firedown.app` and
      `mint.firedown.app` at www and serve `410 Gone` for `/v1/*` for six
      months. `StorageApiClient` maps a 4xx to `FatalException`, so an old
      install fails its backup once with an error row instead of retrying
      for hours; `MintClient` shows the generic error. Then delete the
      records.

## Phase 4 — delete the code and the website copy

- [ ] **4.1 `firedown`** — verified at deletion time, from today's inventory:
      `sync/StorageApiClient`, `MintClient`, `CreditPurchase`,
      `PendingPurchase`, `CreditSettleWorker`, `CreditSettlement`,
      `OnchainPollPolicy`, `PaymentRequests`, `PurchaseError`, `PairClient`,
      `crypto/PairSeal`, `crypto/BlindSignature`, `SyncApiClient`,
      `SyncEngine`, `SyncWorker`, `SyncScheduler` (replaced by the folder
      sync), `VaultSmokeTest`; `settings/BuyCreditFragment`,
      `BuyCreditViewModel`, `BuyBitcoinSheetDialogFragment`,
      `PairApprovalViewModel` + its dialog, `CloudBuyButtonPreference`, the
      credit meter / grace / roadmap step ② in `CloudStatusPreference`; the
      `EXTRA_RESTORE_ALL` deep link (restore-all itself stays — it is the
      new-phone flow); `Preferences.STORAGE_DEFAULT_BACKEND`, `CLOUD_PLAN_*`,
      `CLOUD_TOPUP_*`, `CLOUD_GRACE_NOTIFIED_UNTIL`,
      `CLOUD_REAP_NOTIFIED_UNTIL`, the mint/pair URLs; the JVM tests under
      `app/src/test/.../sync/` for the mint and payments; the buy / credit /
      grace / pairing strings in 16 locales; the Cloud Backup section of
      `CLAUDE.md` rewritten for the folder model. KEEP: `crypto/Pow` (crash
      and feedback solve with it), `SyncIdentity` (the storage key
      derivation is what makes the migration a ciphertext copy),
      `VaultCrypto`, `BookmarkBlob`, `Merge`, `VaultManifest`,
      `CloudBackupStreamActivity` and `VaultObjectModelLoader` (now over the
      folder), `AnonymousPost`, `CrashUploader`, `FeedbackFragment`.
- [ ] **4.2 `firedown-api`**: delete `cmd/storage-api`, `cmd/mint-api`,
      `internal/storage`, `internal/mint`, `internal/shared/blind`,
      `deploy/storage-provision.sh`, `deploy/mint-provision.sh`,
      `deploy/storage-landing.html`, `deploy/mint-landing.html`,
      `docs/cloud-storage-spec.md`, `docs/cloud-mint-spec.md`,
      `docs/web-client-cors.md`, `tests/api-vectors` for storage; narrow
      `deploy/cloudflare-waf-setup.sh` to `api.firedown.app`; runbook §§2–9
      become history. A later pass may strip the `/v1/sync*` + register
      routes and the `accounts` table once the dormancy GC has emptied them.
- [ ] **4.3 `firedown-website`**: delete `backup/` and the `tools/fd-*.mjs`
      web-client tests, `SYNC_AND_STORAGE_WEBSITE_TASK.md`; rewrite the Cloud
      Backup part of `security.html` (+18 locales) for the folder model;
      scrub "cloud backup" / "storage credit" from `features.html`,
      `install.html`, `privacy.html` and the 18 `*/index.html`; `sitemap.xml`
      and `robots.txt` lose `/backup`.

## Status log

- 2026-10-12 — plan written. 1.1 done on `firedown-api` branch
  `sunset/api-to-www` (provisioner nginx mode + runbook §11); the website
  README pointer is on `firedown-website` branch `sunset/api-vhost-note`.
  Found on the way: `sync-api-provision.sh` wrote its env file with
  `cat > "$ENV_FILE" <<EOF`, which truncates the file BEFORE the heredoc's
  `$(keep …)` reads it — every re-run silently reset the crash token, the
  ntfy URLs and the TURN secret (storage/mint were never affected). Fixed
  (temp file + mv); the TURN/relay/signaling knobs are now written through
  `keep()` too. 1.2–1.8 are host actions: runbook §11 has the exact
  commands.

# Browse-first VR content delivery and local retention — R1 design

Status: **architecture + pure policy reference only**. Catalog UI, RustFS downloads,
persistent inventory, runtime eviction coordinator and real-device acceptance
are NOT implemented in this PR.

## Decisions

### DEC-DLV-01: Browse before downloading
The catalog is metadata, not a command to download large VR binaries. Users
can inspect the title, summary, images, optional low-bandwidth preview video,
locale, educational goals, available platforms, compressed size and estimated
installed size, release status and entitlement before selecting Download.
Preview images/video are separate lightweight resources and may have their own
bounded cache. Catalog browsing NEVER installs content.

The catalog itself is paginated, filtered by locale, subject and visibility,
and compatible with guest/public access. Authentication is required when
requesting grants for restricted packages. An unlicensed package remains
visible only where the publication policy allows.

### DEC-DLV-02: On-demand installation
User action: view detail -> Download -> scoped grant -> download into resumable
staging -> signed-manifest verification -> verify file hashes and exact
platform/runtime compatibility -> atomic install -> inventory update ->
Open lesson. Download is NOT equivalent to install success.

Use idempotency keys for grant requests and resumable chunks with offset/ETag
validation. Never silently execute untrusted downloaded native code.

### DEC-DLV-03: Local capacity limit
Default: **maximum three installed content slots** (configurable policy),
plus a separate byte budget and a minimum free-space reserve. The slot
counts *installed immutable releases*, not catalog cards or preview images.
A shared world needed by multiple lessons counts once when the same release
is reused. During controlled replacement there may briefly be four physical
packages, but at most three are **active/cache-owned installed slots** after
successful reconciliation.

At capacity, choose the **least recently opened**, eligible release (LRU),
NOT the third item visually displayed in the catalog. Exclude:
- active or mounted world; playing lesson;
- user-pinned content and teacher-required offline content;
- content being installed/updated/verified/uninstalled;
- releases marked protected by uncommitted learning evidence or dependent data;
- protected recovery/rollback release until the rollback window closes.

Progress and attempts are stored in a **separate durable local database**.
Eviction must never delete learning records. The coordinator may evict a
release with unsynced progress only when its progress is proven fully
independent and durably persisted; the reference policy fails closed and
treats that case as protected.

### DEC-DLV-04: Safe replacement without loss
A pure policy computes a plan; it does NOT delete files. For a 4th install:

1. Serialize cache mutations across all concurrent download/install/GC tasks
   using a persistent lock/lease and transactionally maintained inventory.
2. Re-read inventory and select eligible LRU by last successful open timestamp
   (tie-break by release ID). Lock the candidate against concurrent launch.
3. Ensure the requested package's compressed+expanded staging footprint fits,
   with free-space reserve and rollback headroom. If insufficient, block and
   present a clear choice; NEVER delete the candidate before the new content
   has been downloaded, verified and can be activated.
4. Finish staging and verify signed manifest, complete artifacts, size and
   checksum. Commit the new release's immutable files.
5. Transition the old release to EVICTION_PENDING. Make new inventory entry
   visible only as the capacity transaction commits.
6. Unmount and delete only a candidate not in use; verify it is still eligible,
   and update inventory after actual deletion. If failure/crash occurs, record
   reconciliation state. The app must never expose half-installed content.
7. On startup reconcile installed paths, signed records and EVICTION_PENDING;
   recover orphan staging and incomplete deletions safely.

For update of the same content, stage a new immutable release, validate it,
then atomically update the active pointer. Keep the previous release for
rollback if space allows; count/GC rules must account for the rollback lease.
Do not delete the only known-good copy before the new build launches.

### DEC-DLV-05: Limits are both count and bytes
Example defaults for product experimentation: 3 install slots, device-specific
byte budget, and an enforced minimum free-space reserve. Do not hardcode an
arbitrary GB limit before testing actual Quest storage footprints.
Disk planning must include staging, decompression and potential duplicate
installations, not just the compressed download size.

### DEC-DLV-06: Offline and entitlement
Previously installed content can reopen offline only under an explicit,
cryptographically verifiable offline license policy. No license is implicitly
granted by catalog browsing, a download URL or local file presence.
Expired/revoked grants prevent *new downloads*; revocation/expiry policy for
already installed content must be product-defined and tested.

Learning progress is locally durable, replay-safe and idempotently synced.
Eviction must not erase attempts, bookmarks or completion evidence.

## API and data contract candidates (for later implementation)

- `GET /api/v1/public/catalog?locale=kk-KZ`: metadata only; immutable
  `contentId`, `latestReleaseId`, `preview`, `estimatedBytes`,
  supported devices, availability flag; no download URL by default.
- `GET /api/v1/content/{contentId}`: details and compatible released versions;
  access filtering by visibility and tenant.
- `POST /api/v1/content/{contentId}/grants`: request a short-lived,
  identity/device/tenant-scoped signed grant after entitlement checks;
  return a manifest reference, NOT open bucket-wide access.
- `GET /api/v1/me/devices/{deviceId}/licenses`: scoped offline access
  metadata and renewal rules.

Client-owned states:
`NOT_INSTALLED -> QUEUED -> DOWNLOADING -> VERIFYING -> STAGED ->
INSTALLED -> READY -> IN_USE` and `FAILED`, `EVICTION_PENDING`,
`REMOVING`, `RECOVERY_REQUIRED`. Retry is safe and idempotent.

Model:
- CatalogContent = educational discovery metadata; version independent.
- ContentRelease = immutable platform-specific cooked package.
- InstalledRelease = device-local physical cache with lastOpenedAt and flags.
- LearningAttempt = independent durable learner data; never stored inside
  InstallRoot.

## R1 implementation in this PR

`LocalContentRetentionPolicy` is a pure deterministic selector supporting
default count 3; it blocks eviction of active/pinned/busy/protected content,
handles idempotent reinstallation, detects over-capacity and selects LRU with
stable tie-breaking. It is NOT a transactional eviction coordinator, has
no actual persistent storage, and does not enforce byte budgets.

## Mandatory test gates (before production use)

1. Browse/preview metadata causes zero VR-package downloads.
2. Third installed release leaves all three; fourth triggers eligible LRU.
3. Active, pinned, lesson-in-progress and unsynced-protected worlds NEVER evicted.
4. Cross-device and cross-tenant grants cannot access different content.
5. Power loss during download, atomic publish or deletion is recoverable.
6. Inadequate staging space preserves old installed and learner data.
7. Update fails -> old release remains installed and launchable.
8. All content protected -> user sees actionable storage warning.
9. Shared asset reference counting prevents deleting referenced content.
10. Actual packaged Windows and real Quest acceptance with offline reopen.

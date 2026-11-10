# VR Content Manager — RustFS on-demand reference implementation (R1)

Updated: 2026-11-10. Status: **JVM on-device protocol prototype**, not native Unreal C++.
Branch: `feat/r1-signed-content-delivery-foundation`, Draft PR #3.

## User journey and separation of duties

1. The client loads the **public/scoped catalog metadata**. Browsing, filtering and
   opening the detail page do **NOT** download VR artifacts. Covers/posters/video
   are distinct bounded preview cache items.
2. The user selects **Download** for a compatible published release.
3. Backend authorizes tenant/device/entitlement, selects a platform-specific,
   runtime-compatible release, and supplies a signed immutable release manifest,
   detached Ed25519 signature and individual short-lived **RustFS S3 presigned
   GET URLs**. These authorization endpoints are **not yet implemented**.
4. The local VRContentManager downloads real HTTP bytes using GET, ETag,
   If-Range and Range for resumption. Redirects to other hosts are rejected.
   Every URL must match the configured trusted RustFS origin; production HTTPS.
5. Client checks signature with a trusted pinned public key, the exact runtime
   and platform, file size and SHA-256. A bad partial is discarded to permit
   a clean retry.
6. The installer copies into a staging directory and atomically renames it
   to the final immutable installation directory; registry is committed.
7. The user selects **Open**. The native VR runtime must enforce an offline
   entitlement check before mounting the content. Runtime opening, physics,
   lesson progress and native rendering are **not implemented** by Java.
8. When at three installations, the fourth causes a deterministic selection
   of the oldest *eligible* installation, never the third item in the UI.

**Implementation placement:** JVM classes live in `backend/` so their API
and behavior can be verified by CI. They are **reference/client-side code**
and must NOT be wired into Spring Boot's server request path or run against
server disk. Quest and Windows need a native C++ `IVRContentInstaller`
implementation plus local journal/registry, verified against golden test vectors.

## Configuration contract

`backend/.env.content-delivery.example` documents:

- `RUSTFS_BASE_URL`: scheme + trusted RustFS origin, e.g.
  `https://rustfs.example.org` (example only, not live)
- `VR_CONTENT_SIGNING_PUBLIC_KEY_B64`: pinned X.509 encoded Ed25519 PUBLIC key;
  the private key stays in a secured publisher/signing service.
- `VR_CONTENT_LOCAL_ROOT`: per-device installed content root.
- `VR_CONTENT_PLATFORM`: `quest` or `windows` as defined in published release.
- `VR_CONTENT_RUNTIME_BUILD_ID`: exact native executable/cooked build identity.
- `VR_CONTENT_MAX_INSTALLED`: default 3.
- `VR_CONTENT_DISK_RESERVE_BYTES`: default 512 MiB, platform tuning required.
- `VR_CONTENT_ALLOW_HTTP_LOCAL_TESTS`: false in production; only localhost
  mock testing is permitted when true.

Until `RUSTFS_BASE_URL` and the pinned key are configured, manager creation
fails clearly and **downloads remain disabled**. Catalog browsing is independent.
No credentials or production endpoint values are committed. Presigned URLs
must be supplied only by the authenticated grants service.

`VRContentManagerFactory.fromEnvironment(System.getenv())` is the bootstrap
factory. It does not automatically inject values into Spring or expose any
public download endpoint.

## API sketch for authenticated grants (future backend work)

```json
{
  "releaseId": "physics-quest-r001",
  "manifest": "<canonical signed manifest transport encoding>",
  "signature": "<base64 detached signature>",
  "grantExpiresAt": "2026-11-10T14:30:00Z",
  "artifactDownloads": [
    {
      "name": "physics.pak",
      "url": "<per-object temporary RustFS presigned GET URL>"
    }
  ]
}
```

Do **not** log URL query signatures, embed bucket credentials in the client,
expose unrestricted listing or treat an S3 presign as a perpetual offline
license. Grant expiry requires obtaining fresh presigned URLs while the
downloaded valid partial files can still be used for Range resume.

## Local registry, limits, crash safety

- `LocalReleaseRegistry` is a locally durable, atomically replaced inventory
  file, not an entitlement authority and not a user progress database.
- LRU uses `markOpened(releaseId)`, called **only after** a successful
  runtime open. The user may mark packages pinned via `setPinned`.
- `downloadAndInstall(..., mountedOrActiveReleaseIds)` receives the
  runtime's active set. Native port must hold shared leases so a world cannot
  begin mounting while an eviction is in progress.
- A preflight requires at least `2 * compressedArtifactBytes + reserve`
  usable storage because the JVM reference installer copies downloaded files
  into staging. This is a conservative lower-bound, **not** a production
  estimate for uncompressed runtime assets.
- At capacity, new content is installed and registered before the selected
  old release is removed. The temporary fourth physical package is allowed;
  cleanup returns to three logical slots. If there is no safe LRU or free
  staging space, keep installed content and show a storage warning.
- `.evicting-<releaseId>` journals interrupted removal, and recovery
  completes only if the caller confirms the package is not active.
- Orphan `.staging-*` installer folders are cleaned under a filesystem lock
  on recovery. Existing `.part` remains for future resume.
- Existing untracked installed folders are **not** silently deleted.
  Reinstalling the same release can reattach the exact signed contents;
  otherwise manual reconciliation is required.
- Progress/evidence/assignments are stored **outside** the installation root;
  cache deletion must never remove learner data.
- Explicit cross-process local file lock serializes operations. It is
  intentionally coarse: the reference manager holds it during HTTP download;
  production should use durable per-download leases to avoid blocking UI.
- Persistent inventory and atomic rename do not by themselves guarantee
  durability under sudden power loss. Device fsync and crash tests remain.

## Tests and acceptance

JUnit:
- `RustFsRangeDownloaderTest`: actual local HTTP Range+If-Range resume,
  refuse redirect and different origins, reject insecure HTTP.
- `VRContentManagerTest`: mock RustFS downloads and signed installation,
  persistent registry reopen, pin/active LRU protection, staging and eviction
  journal crash recovery, corrupt response then clean retry, insufficient space.
- `AtomicReleaseInstallerTest`: signed metadata, SHA-256 and failed signatures.
- `LocalContentRetentionPolicyTest`: 3-slot decisions and exclusions.

Run: `cd backend && mvn -B clean verify`. CI is in
`.github/workflows/vr-content-delivery.yml`. Treat only an **exact HEAD**
successful CI run as qualification of this JVM scope.

## Not done / cannot be declared production ready

1. RustFS live deployment, actual `RUSTFS_BASE_URL`, credentials and
   presigning backend endpoint; integration against a real S3/RustFS instance.
2. Grant authn/authz, tenant/entitlement enforcement, offline license,
   trust-root rotation and revocation; no signing-service integration.
3. Byte-budget/expanded-size capacity planner, app lifecycle pause/resume,
   concurrent runtime launch/eviction lease, native OS free-space API.
4. Production-grade durable journaling/CRC, checksum on every offline reopen,
   hostile-local-filesystem symlink/TOCTOU review, fsync of directory metadata.
5. Cross-language canonical manifest golden vectors and Unreal C++ port,
   Quest Pak/IoStore mounting, packaged Windows and physical Quest tests.
6. UI catalog detail and preview, user-initiated download confirmation and
   storage manager screen.

The reference implementation is intentionally fail-closed. **Do not merge
as a production RustFS/Quest feature** based solely on JVM mock HTTP tests.

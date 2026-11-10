# R1 signed VR content delivery — reference foundation

Status: **reference implementation only**. This is not an Unreal Engine runtime,
a real CDN/RustFS transfer, a publisher endpoint, nor validated Quest dynamic mounting.

## Trust and deployment boundary

Publisher builds a **platform-specific cooked asset set** (Windows and Quest
are separate releases) from vetted source assets. A trusted, offline-controlled
Ed25519 signing service signs the exact `canonicalBytes(release)` output;
private signing keys MUST NOT be embedded in applications or stored in Git.
The application ships with a pinned, rotating trusted public-key set; the R1
Java proof-of-contract accepts a pinned single public key.

Pipeline:

1. Package and hash artifacts in the trusted build environment.
2. Produce an immutable release ID and metadata (module, platform, exact runtime build, entry level).
3. Sign deterministic release bytes with Ed25519; publish immutable artifact URLs.
4. Grant access only after tenant/device/license authorization.
5. Download files into staging; verify signature and compatibility BEFORE activation.
6. Check all artifact names, sizes and SHA-256 digests; reject missing/unexpected files.
7. Atomically rename verified release directory, otherwise fail closed.
8. On restart, reverify content against the signed release before use.

## Reference implementation

- `SignedReleaseVerifier`: deterministic manifest representation, authenticity,
  compatibility and streaming SHA-256 artifact checking.
- `AtomicReleaseInstaller`: idempotent staging, verified copies, atomic activation,
  cleanup after failure.
- `AtomicReleaseInstallerTest`: signed install, retries, corruption,
  forged signature, runtime mismatch, path traversal, and unexpected artifacts.

### Canonical protocol (version 1)

Binary message, big-endian (Java DataOutputStream): `writeUTF("VRTA-RELEASE-V1")`,
then `writeUTF` of release ID, module ID, platform, runtime build ID, entry
level; `writeInt` artifact count, then for each artifact sorted by ASCII
artifact name: `writeUTF(name)`, `writeLong(sizeBytes)`,
`writeUTF(lowercaseSha256Hex)`. `writeUTF` uses Java **modified UTF-8**
with 16-bit length; all current fields are constrained to ASCII. Producers
in C++ must use the same framing or use vetted golden binary test vectors.
Ed25519 signs these exact bytes; no JSON reserialization is used.

## Explicitly NOT production ready

- The installer is a JVM **reference implementation**, not a Quest installer.
- No HTTP download, cancellation, backpressure, durable resume, disk quota,
  cleanup/GC policy, update rollback or malware/content audit yet.
- No publisher key management, trusted-key rotation, revocation or licensing
  enforcement yet.
- No security-reviewed Symlink/TOCTOU defence against a hostile local filesystem.
  The staging root must be owned exclusively by the application.
- Atomic rename of a directory is not an fsync/durable-power-loss guarantee.
- Dynamic Pak/IoStore mounting and cooked asset compatibility **must pass**
  packaged Windows and real-device Quest acceptance before R3/R4 claims.

## Required next acceptance gates

1. Golden vectors for Java/C++ canonical protocol and signature verification.
2. Negative security tests for expired/revoked grants, downgrade and rollback.
3. RustFS/S3 signed-grant download integration under real auth.
4. Crash recovery, low disk, duplicate installs, concurrent updates and GC.
5. Windows packaged install → mount → lesson → restart → offline reopen.
6. Physical Quest APK acceptance, memory/thermal/fps budgets and offline reopen.

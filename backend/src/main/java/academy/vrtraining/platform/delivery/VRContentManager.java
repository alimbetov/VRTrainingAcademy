package academy.vrtraining.platform.delivery;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JVM reference on-device coordinator for a REAL RustFS/S3 HTTP GET pipeline.
 * Not a Spring controller and not a native Unreal subsystem.
 *
 * Caller is responsible for resolving signed grants and independently
 * validating offline entitlements. The returned URLs are sensitive and MUST
 * be scoped to the exact artifact and trusted origin.
 */
public final class VRContentManager {
    private final Path root;
    private final Path installs;
    private final Path downloads;
    private final LocalReleaseRegistry registry;
    private final RustFsRangeDownloader downloader;
    private final AtomicReleaseInstaller installer;
    private final long freeSpaceReserveBytes;
    private final int maxSlots;

    public VRContentManager(Path localRoot, URI rustfsBaseUrl,
                            boolean allowHttpForLocalTests, PublicKey pinnedPublicKey,
                            String devicePlatform, String runtimeBuild,
                            long freeSpaceReserveBytes, int maxSlots) {
        if (freeSpaceReserveBytes < 0 || maxSlots < 1)
            throw new IllegalArgumentException("Invalid storage budget");
        root = Objects.requireNonNull(localRoot).toAbsolutePath().normalize();
        installs = root.resolve("installed");
        downloads = root.resolve("downloads");
        registry = new LocalReleaseRegistry(root);
        downloader = new RustFsRangeDownloader(rustfsBaseUrl, allowHttpForLocalTests);
        installer = new AtomicReleaseInstaller(installs, pinnedPublicKey, devicePlatform, runtimeBuild);
        this.freeSpaceReserveBytes = freeSpaceReserveBytes;
        this.maxSlots = maxSlots;
        this.pinnedKey = Objects.requireNonNull(pinnedPublicKey);
        this.platform = Objects.requireNonNull(devicePlatform);
        this.runtime = Objects.requireNonNull(runtimeBuild);
    }

    /**
     * Executes downloads under a cross-process lock for a simple, fail-closed
     * reference design. Production should use persisted leases and a queue.
     * Do not send URLs from a public catalog; they must come from scoped grants.
     */
    public Path downloadAndInstall(SignedReleaseVerifier.Release release,
                                   byte[] signature, Map<String, URI> grantedArtifactUrls)
            throws IOException, GeneralSecurityException, InterruptedException {
        Objects.requireNonNull(release);
        Objects.requireNonNull(grantedArtifactUrls);
        Set<String> expected = release.artifacts().stream()
                .map(SignedReleaseVerifier.Artifact::name)
                .collect(java.util.stream.Collectors.toSet());
        if (!expected.equals(grantedArtifactUrls.keySet()))
            throw new GeneralSecurityException("Grant artifact list mismatch");
        Files.createDirectories(root);
        Files.createDirectories(installs);
        Files.createDirectories(downloads);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(installs)
                || Files.isSymbolicLink(downloads))
            throw new IOException("Unsafe storage root");

        try (var lockChannel = FileChannel.open(root.resolve(".manager.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            var existing = registry.read();
            checkInventoryConsistency(existing);
            var candidateState = existing.stream().map(e ->
                    new LocalContentRetentionPolicy.InstalledContent(e.releaseId(), e.bytes(),
                            e.lastOpenedAt(), false, e.pinned(), false,
                            e.protectedFromEviction())).toList();
            var decision = LocalContentRetentionPolicy.decide(candidateState,
                    release.releaseId(), maxSlots);
            if (!decision.allowed()) throw new IOException("Storage policy: " + decision.reason());

            long size = 0;
            for (var artifact : release.artifacts()) {
                size = Math.addExact(size, artifact.sizeBytes());
            }
            long required = Math.addExact(Math.multiplyExact(size, 2), freeSpaceReserveBytes);
            if (Files.getFileStore(root).getUsableSpace() < required)
                throw new IOException("Not enough disk space for safe staged installation");

            Path partialRoot = downloads.resolve(release.releaseId());
            Files.createDirectories(partialRoot);
            if (Files.isSymbolicLink(partialRoot)) throw new IOException("Unsafe download path");
            Map<String, Path> paths = new HashMap<>();
            for (var artifact : release.artifacts()) {
                Path part = partialRoot.resolve(artifact.name() + ".part");
                URI url = Objects.requireNonNull(grantedArtifactUrls.get(artifact.name()));
                paths.put(artifact.name(), downloader.download(url, part, artifact.sizeBytes()));
            }
            // Verify all downloaded files BEFORE granting the installer access.
            SignedReleaseVerifier.verifySignature(release, signature, getTrustedKey());
            SignedReleaseVerifier.verifyCompatibility(release, expectedPlatform(), expectedRuntime());
            SignedReleaseVerifier.verifyArtifacts(release, paths);
            Path installed = installer.install(release, signature, paths);

            List<LocalReleaseRegistry.Entry> after = new ArrayList<>(existing);
            boolean already = after.stream().anyMatch(e -> e.releaseId().equals(release.releaseId()));
            if (!already) {
                after.add(new LocalReleaseRegistry.Entry(release.releaseId(), size,
                        Instant.now(), false, false));
                // Journal new installation BEFORE any destructive action. If crash occurs,
                // a subsequent reconciliation can see the extra entry.
                registry.write(after);
            }
            if (decision.evictReleaseId().isPresent()) {
                String id = decision.evictReleaseId().orElseThrow();
                removeInstalledRelease(id);
                after.removeIf(e -> e.releaseId().equals(id));
                registry.write(after);
            }
            return installed;
        }
    }

    // Pinned values must be trusted through the same verifier as the installer.
    // Refactoring this into a native runtime is a separate acceptance gate.
    private PublicKey getTrustedKey() { return pinnedKey; }
    private String expectedPlatform() { return platform; }
    private String expectedRuntime() { return runtime; }
    private PublicKey pinnedKey;
    private String platform;
    private String runtime;

    /**
     * Reconciles only missing inventory paths; unknown directories are never
     * automatically deleted. Any over-capacity state is reported for manual
     * inspection instead of guessing which package to remove.
     */
    public List<LocalReleaseRegistry.Entry> recoverInventory() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(installs);
        try (var channel = FileChannel.open(root.resolve(".manager.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            var old = registry.read();
            var updated = old.stream().filter(e ->
                    Files.isDirectory(installs.resolve(e.releaseId()))).toList();
            if (updated.size() != old.size()) registry.write(updated);
            return updated;
        }
    }

    public List<LocalReleaseRegistry.Entry> inventory() throws IOException {
        return registry.read();
    }

    private void checkInventoryConsistency(List<LocalReleaseRegistry.Entry> entries) throws IOException {
        for (var entry : entries) {
            Path path = installs.resolve(entry.releaseId());
            if (!Files.isDirectory(path) || Files.isSymbolicLink(path))
                throw new IOException("Inventory reconciliation required");
        }
    }

    private void removeInstalledRelease(String releaseId) throws IOException {
        Path directory = installs.resolve(releaseId);
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory))
            throw new IOException("Invalid eviction target");
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
                    throw new IOException("Unexpected entry in installed package");
                Files.delete(path);
            }
        }
        Files.delete(directory);
    }
}

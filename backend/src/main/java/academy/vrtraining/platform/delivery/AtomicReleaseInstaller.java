package academy.vrtraining.platform.delivery;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * R1 JVM reference installer. Not an Unreal/Quest pak mounter.
 * Stages on the target filesystem; exposes no partially verified release.
 */
public final class AtomicReleaseInstaller {
    private final Path installRoot;
    private final PublicKey trustedKey;
    private final String platform;
    private final String runtimeBuildId;

    public AtomicReleaseInstaller(Path installRoot, PublicKey trustedKey,
                                  String platform, String runtimeBuildId) {
        this.installRoot = Objects.requireNonNull(installRoot);
        this.trustedKey = Objects.requireNonNull(trustedKey);
        this.platform = Objects.requireNonNull(platform);
        this.runtimeBuildId = Objects.requireNonNull(runtimeBuildId);
    }

    public synchronized Path install(SignedReleaseVerifier.Release release,
                                     byte[] detachedSignature,
                                     Map<String, Path> downloadedArtifacts)
            throws IOException, GeneralSecurityException {
        SignedReleaseVerifier.verifySignature(release, detachedSignature, trustedKey);
        SignedReleaseVerifier.verifyCompatibility(release, platform, runtimeBuildId);
        Files.createDirectories(installRoot);
        if (Files.isSymbolicLink(installRoot))
            throw new GeneralSecurityException("Install root must not be symlink");
        Path destination = installRoot.resolve(release.releaseId());
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(SignedReleaseVerifier.canonicalBytes(release)));
        if (Files.exists(destination)) {
            if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination))
                throw new GeneralSecurityException("Invalid install destination");
            Path marker = destination.resolve(".release-sha256");
            if (Files.isRegularFile(marker)
                    && Files.readString(marker).equals(fingerprint)) {
                var installed = new java.util.HashMap<String, Path>();
                for (var artifact : release.artifacts())
                    installed.put(artifact.name(), destination.resolve(artifact.name()));
                SignedReleaseVerifier.verifyArtifacts(release, installed);
                return destination;
            }
            throw new GeneralSecurityException("Release ID already exists with different contents");
        }

        // Revalidate original sources before staging; then verify copies after staging.
        SignedReleaseVerifier.verifyArtifacts(release, downloadedArtifacts);
        Path staging = Files.createDirectory(
                installRoot.resolve(".staging-" + UUID.randomUUID()));
        boolean committed = false;
        try {
            var staged = new java.util.HashMap<String, Path>();
            for (var artifact : release.artifacts()) {
                Path to = staging.resolve(artifact.name());
                Files.copy(downloadedArtifacts.get(artifact.name()), to);
                staged.put(artifact.name(), to);
            }
            SignedReleaseVerifier.verifyArtifacts(release, staged);
            Files.writeString(staging.resolve(".release-sha256"), fingerprint);
            // Deliberately fail closed on filesystems without atomic rename.
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic activation unsupported", e);
            }
            committed = true;
            return destination;
        } finally {
            if (!committed) {
                try (var files = Files.list(staging)) {
                    for (Path path : files.toList()) Files.deleteIfExists(path);
                }
                Files.deleteIfExists(staging);
            }
        }
    }
}

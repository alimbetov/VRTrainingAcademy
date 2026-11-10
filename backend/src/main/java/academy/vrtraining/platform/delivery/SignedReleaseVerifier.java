package academy.vrtraining.platform.delivery;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * R1 proof-of-contract: signed immutable release descriptor and artifact verification.
 * The signature covers the deterministic binary encoding of all manifest fields.
 * The public key MUST be pinned by the client; never take it from the manifest.
 */
public final class SignedReleaseVerifier {
    private SignedReleaseVerifier() {}

    public record Artifact(String name, long sizeBytes, String sha256Hex) {
        public Artifact {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || name.equals(".") || name.equals(".."))
                throw new IllegalArgumentException("Invalid artifact name");
            if (sizeBytes < 0) throw new IllegalArgumentException("Negative artifact size");
            if (sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid SHA-256");
        }
    }

    public record Release(String releaseId, String moduleId, String platform,
                          String runtimeBuildId, String entryLevel, List<Artifact> artifacts) {
        public Release {
            releaseId = safeId(releaseId);
            moduleId = safeId(moduleId);
            platform = safeId(platform);
            runtimeBuildId = safeId(runtimeBuildId);
            entryLevel = safeId(entryLevel);
            artifacts = List.copyOf(Objects.requireNonNull(artifacts));
            if (artifacts.isEmpty()) throw new IllegalArgumentException("Empty artifact list");
            Set<String> names = new HashSet<>();
            for (Artifact a : artifacts) {
                if (!names.add(a.name())) throw new IllegalArgumentException("Duplicate artifact");
            }
        }
    }

    private static String safeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("Invalid identifier");
        return value;
    }

    /** Protocol v1: ASCII magic, Java DataOutputStream UTF (modified UTF-8), signed fields. */
    public static byte[] canonicalBytes(Release release) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeUTF("VRTA-RELEASE-V1");
            out.writeUTF(release.releaseId());
            out.writeUTF(release.moduleId());
            out.writeUTF(release.platform());
            out.writeUTF(release.runtimeBuildId());
            out.writeUTF(release.entryLevel());
            var sorted = release.artifacts().stream()
                    .sorted(Comparator.comparing(Artifact::name)).toList();
            out.writeInt(sorted.size());
            for (var artifact : sorted) {
                out.writeUTF(artifact.name());
                out.writeLong(artifact.sizeBytes());
                out.writeUTF(artifact.sha256Hex());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Manifest cannot be encoded", e);
        }
    }

    public static void verifySignature(Release release, byte[] signatureBytes,
                                       PublicKey trustedKey) throws GeneralSecurityException {
        Objects.requireNonNull(trustedKey, "trustedKey");
        Objects.requireNonNull(signatureBytes, "signatureBytes");
        if (!"Ed25519".equalsIgnoreCase(trustedKey.getAlgorithm())
                && !"EdDSA".equalsIgnoreCase(trustedKey.getAlgorithm()))
            throw new GeneralSecurityException("Unsupported key type");
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(trustedKey);
        verifier.update(canonicalBytes(release));
        if (!verifier.verify(signatureBytes))
            throw new GeneralSecurityException("Invalid release signature");
    }

    public static void verifyCompatibility(Release release, String expectedPlatform,
                                           String expectedRuntimeBuildId) {
        if (!release.platform().equals(expectedPlatform)
                || !release.runtimeBuildId().equals(expectedRuntimeBuildId))
            throw new IllegalArgumentException("Incompatible platform/runtime");
    }

    public static void verifyArtifacts(Release release, Map<String, Path> artifactPaths)
            throws IOException, GeneralSecurityException {
        if (!artifactPaths.keySet().equals(release.artifacts().stream()
                .map(Artifact::name).collect(java.util.stream.Collectors.toSet())))
            throw new GeneralSecurityException("Missing or unexpected artifacts");
        for (Artifact artifact : release.artifacts()) {
            Path path = Objects.requireNonNull(artifactPaths.get(artifact.name()));
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path))
                throw new GeneralSecurityException("Not a regular artifact");
            if (Files.size(path) != artifact.sizeBytes())
                throw new GeneralSecurityException("Artifact size mismatch: " + artifact.name());
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            if (!MessageDigest.isEqual(digest.digest(), java.util.HexFormat.of()
                    .parseHex(artifact.sha256Hex())))
                throw new GeneralSecurityException("Artifact hash mismatch: " + artifact.name());
        }
    }
}

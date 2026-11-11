package academy.vrtraining.platform.content;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Stable cross-language release v2 signing contract for cooked Quest lesson data.
 * The existing signed R1 v1 descriptor cannot represent a full Unreal
 * /Game/Lessons/... entry level; this new protocol MUST NOT be silently
 * interpreted as the older R1 manifest.
 *
 * Ed25519 verifies publisher provenance, NOT whether a cooked level mounts.
 */
public final class SignedQuestReleaseV2 {
    private SignedQuestReleaseV2() {}

    public static final byte[] MAGIC = "VRTA-QUEST-RELEASE-V2\n".getBytes(StandardCharsets.US_ASCII);

    public record Artifact(String name, long sizeBytes, String sha256Hex) {
        public Artifact {
            if (!safePakName(name) || sizeBytes <= 0
                    || sizeBytes > QuestLessonUploadService.MAX_ARTIFACT_BYTES
                    || sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Unsafe Quest release artifact");
        }
    }

    public record Release(String releaseId, String lessonId, String collectionId,
                          String platform, String engineVersion, String runtimeBuildId,
                          String entryLevel, List<Artifact> artifacts) {
        public Release {
            if (!safeId(releaseId) || !safeId(lessonId) || !safeId(collectionId)
                    || !"Android_ASTC".equals(platform) || !"5.6".equals(engineVersion)
                    || !safeId(runtimeBuildId) || !safeEntryLevel(entryLevel))
                throw new IllegalArgumentException("Incompatible Quest release");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            if (artifacts.isEmpty() || artifacts.size() > 32)
                throw new IllegalArgumentException("Artifact count must be 1..32");
            var names = new HashSet<String>();
            for (var a : artifacts) {
                if (!names.add(a.name())) throw new IllegalArgumentException("Duplicate artifact");
            }
        }
    }

    /** Bridge from verified upload metadata into the single-artifact v2 candidate.
     * Calling this method DOES NOT issue a trusted publisher signature or
     * hardware compatibility certification.
     */
    public static Release fromUpload(QuestLessonUploadService.Request request) {
        QuestLessonUploadService.validate(request);
        return new Release(request.releaseId(), request.lessonId(), request.collectionId(),
                request.platform(), request.engineVersion(), request.runtimeBuildId(),
                request.entryLevel(), List.of(new Artifact(request.artifactName(),
                        request.artifactSizeBytes(), request.sha256Hex())));
    }

    public static byte[] canonicalBytes(Release release) {
        Objects.requireNonNull(release);
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.write(MAGIC);
            putAscii(out, release.releaseId());
            putAscii(out, release.lessonId());
            putAscii(out, release.collectionId());
            putAscii(out, release.platform());
            putAscii(out, release.engineVersion());
            putAscii(out, release.runtimeBuildId());
            putAscii(out, release.entryLevel());
            var sorted = release.artifacts().stream()
                    .sorted(Comparator.comparing(Artifact::name)).toList();
            out.writeInt(sorted.size());
            for (var a : sorted) {
                putAscii(out, a.name());
                out.writeLong(a.sizeBytes());
                out.write(HexFormat.of().parseHex(a.sha256Hex()));
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unexpected canonical encoding failure", e);
        }
    }

    private static void putAscii(DataOutputStream out, String value) throws IOException {
        byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(ascii.length);
        out.write(ascii);
    }

    public static void verifySignature(Release release, byte[] detachedSignature,
                                       PublicKey pinnedPublisherKey)
            throws GeneralSecurityException {
        Objects.requireNonNull(pinnedPublisherKey, "pinnedPublisherKey");
        Objects.requireNonNull(detachedSignature, "signature");
        if (!"Ed25519".equalsIgnoreCase(pinnedPublisherKey.getAlgorithm())
                && !"EdDSA".equalsIgnoreCase(pinnedPublisherKey.getAlgorithm()))
            throw new GeneralSecurityException("Publisher key must be Ed25519");
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pinnedPublisherKey);
        verifier.update(canonicalBytes(release));
        if (!verifier.verify(detachedSignature))
            throw new GeneralSecurityException("Quest release signature rejected");
    }

    public static void verifyRuntime(Release release, String runtimeBuildId,
                                     String engineVersion, String platform) {
        if (!release.runtimeBuildId().equals(runtimeBuildId)
                || !release.engineVersion().equals(engineVersion)
                || !release.platform().equals(platform))
            throw new IllegalArgumentException("Release not certified for this runtime");
    }

    public static void verifyArtifactDigest(Artifact artifact, byte[] bytes)
            throws GeneralSecurityException {
        if (bytes.length != artifact.sizeBytes())
            throw new GeneralSecurityException("Artifact size mismatch");
        var hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        if (!MessageDigest.isEqual(hash, HexFormat.of().parseHex(artifact.sha256Hex())))
            throw new GeneralSecurityException("Artifact digest mismatch");
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                && !id.contains("..");
    }

    private static boolean safeEntryLevel(String path) {
        return path != null && path.length() <= 240
                && path.matches("/Game/Lessons/(?:[A-Za-z][A-Za-z0-9_]*/)*[A-Za-z][A-Za-z0-9_]*")
                && !path.contains("..");
    }

    private static boolean safePakName(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.pak")
                && !name.contains("..");
    }
}

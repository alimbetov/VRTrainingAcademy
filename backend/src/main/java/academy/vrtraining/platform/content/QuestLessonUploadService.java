package academy.vrtraining.platform.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Untrusted Quest lesson ingestion. Upload success is strictly STAGED, never
 * CERTIFIED or PUBLISHED. Publication requires a separate trusted native
 * compatibility and hardware qualification process.
 */
public final class QuestLessonUploadService {
    public static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;

    @FunctionalInterface
    public interface UploadAuthorization {
        boolean mayUpload(String trustedSubject, UUID organizationId);
    }

    @FunctionalInterface
    public interface ArtifactStore {
        void store(Path verifiedFile, String quarantinedKey) throws IOException;
    }

    public record Request(UUID organizationId, String releaseId, String lessonId,
                          String collectionId, String platform, String engineVersion,
                          String runtimeBuildId, String artifactName, long artifactSizeBytes,
                          String sha256Hex) {}

    public record Receipt(String releaseId, String artifactName, long sizeBytes,
                          String sha256Hex, String quarantineObjectKey, String state) {}

    private final UploadAuthorization authorization;
    private final ArtifactStore storage;

    public QuestLessonUploadService(UploadAuthorization authorization, ArtifactStore storage) {
        this.authorization = Objects.requireNonNull(authorization);
        this.storage = Objects.requireNonNull(storage);
    }

    public Receipt stage(String trustedSubject, Request request, InputStream stream)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(stream, "stream");
        // Never trust a client-supplied owner, published flag or certificate.
        if (trustedSubject == null || trustedSubject.isBlank()
                || request.organizationId() == null
                || !authorization.mayUpload(trustedSubject, request.organizationId()))
            throw new SecurityException("Uploader membership not authorized");

        validate(request);
        Path temp = Files.createTempFile("vrta-quest-stage-", ".part");
        try {
            var sha = sha256();
            long readTotal = 0;
            try (OutputStream output = Files.newOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                int count;
                while ((count = stream.read(buf)) != -1) {
                    readTotal = Math.addExact(readTotal, count);
                    if (readTotal > MAX_ARTIFACT_BYTES || readTotal > request.artifactSizeBytes())
                        throw new IllegalArgumentException("Artifact exceeds declared or allowed size");
                    sha.update(buf, 0, count);
                    output.write(buf, 0, count);
                }
            }
            if (readTotal != request.artifactSizeBytes())
                throw new IllegalArgumentException("Artifact size mismatch");
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    request.sha256Hex().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IllegalArgumentException("Artifact SHA-256 mismatch");

            // Separate quarantine prefix; NOT the published content namespace.
            String key = "quarantine/quest-lessons/" + request.organizationId()
                    + "/" + request.releaseId() + "/" + UUID.randomUUID() + "/"
                    + request.artifactName();
            storage.store(temp, key);
            return new Receipt(request.releaseId(), request.artifactName(), readTotal,
                    actual, key, "STAGED");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void validate(Request r) {
        if (r.organizationId() == null
                || !safeId(r.releaseId()) || !safeId(r.lessonId()) || !safeId(r.collectionId())
                || !"Android_ASTC".equals(r.platform())
                || !"5.6".equals(r.engineVersion())
                || !safeId(r.runtimeBuildId())
                || r.artifactName() == null
                || !r.artifactName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.pak")
                || r.artifactName().contains("..")
                || r.artifactSizeBytes() <= 0 || r.artifactSizeBytes() > MAX_ARTIFACT_BYTES
                || r.sha256Hex() == null || !r.sha256Hex().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid Quest lesson package metadata");
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                && !id.contains("..");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

package academy.vrtraining.platform.delivery.grant;

import java.util.List;
import java.util.Optional;

/**
 * Persistence adapter contract. Implement with PostgreSQL after the
 * publication/entitlement domain exists. No demo catalog grants download.
 */
public interface PublishedReleasePort {
    Optional<PublishedRelease> findPublished(String contentId, String platform, String runtimeBuildId);

    record Artifact(String name, String objectKey, long sizeBytes, String sha256Hex) {
        public Artifact {
            if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || objectKey == null || objectKey.isBlank() || objectKey.startsWith("/")
                    || objectKey.contains("..") || objectKey.contains("\\")
                    || sizeBytes < 0 || sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid published artifact");
        }
    }
    record PublishedRelease(String contentId, String releaseId, String platform,
                            String runtimeBuildId, String bucket, List<Artifact> artifacts,
                            String signedManifestBase64, String signatureBase64) {
        public PublishedRelease {
            if (contentId == null || contentId.isBlank() || releaseId == null || releaseId.isBlank()
                    || bucket == null || bucket.isBlank()
                    || signedManifestBase64 == null || signedManifestBase64.isBlank()
                    || signatureBase64 == null || signatureBase64.isBlank())
                throw new IllegalArgumentException("Incomplete published release");
            artifacts = List.copyOf(artifacts);
            if (artifacts.isEmpty()) throw new IllegalArgumentException("No artifacts");
        }
    }
}

package academy.vrtraining.platform.delivery.grant;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A grant is scoped to the JWT subject and tenant, a published immutable
 * release, runtime identity, and a short TTL. No public catalog entitlement.
 */
@Service
public class DownloadGrantService {
    public enum Transport { DIRECT_RUSTFS, BACKEND_PROXY }
    public interface ArtifactUrlSigner {
        URI presignGet(String bucket, String key, Duration ttl);
    }

    public record ArtifactGrant(String name, String url, long sizeBytes, String sha256Hex) {}
    public record Grant(String releaseId, Transport transport, Instant expiresAt,
                        String signedManifestBase64, String signatureBase64,
                        List<ArtifactGrant> artifacts) {}

    private final PublishedReleasePort releases;
    private final DownloadEntitlementPort entitlements;
    private final ArtifactUrlSigner signer;
    private final Transport transport;
    private final Clock clock;
    private final Duration ttl;

    public DownloadGrantService(PublishedReleasePort releases, DownloadEntitlementPort entitlements,
            ArtifactUrlSigner signer,
            @Value("${vr.delivery.transport:DIRECT_RUSTFS}") Transport transport,
            @Value("${vr.delivery.grant-ttl-seconds:300}") int ttlSeconds) {
        this(releases, entitlements, signer, transport, Clock.systemUTC(), Duration.ofSeconds(ttlSeconds));
    }

    DownloadGrantService(PublishedReleasePort releases, DownloadEntitlementPort entitlements,
            ArtifactUrlSigner signer, Transport transport, Clock clock, Duration ttl) {
        this.releases = Objects.requireNonNull(releases);
        this.entitlements = Objects.requireNonNull(entitlements);
        this.signer = Objects.requireNonNull(signer);
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
        if (ttl.compareTo(Duration.ofSeconds(30)) < 0
                || ttl.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("Grant TTL must be 30..900 seconds");
        this.ttl = ttl;
    }

    public Grant issue(String subject, String tenant, String contentId, String platform, String runtimeBuild) {
        PublishedReleasePort.PublishedRelease release = requireAuthorized(
                subject, tenant, contentId, platform, runtimeBuild);
        Instant expires = clock.instant().plus(ttl);
        var artifacts = release.artifacts().stream().map(a -> {
            String url = transport == Transport.DIRECT_RUSTFS
                    ? signer.presignGet(release.bucket(), a.objectKey(), ttl).toString()
                    : "/api/v1/me/content/" + contentId + "/artifacts/" + a.name()
                        + "?platform=" + platform + "&runtimeBuildId=" + runtimeBuild;
            return new ArtifactGrant(a.name(), url, a.sizeBytes(), a.sha256Hex());
        }).toList();
        return new Grant(release.releaseId(), transport, expires,
                release.signedManifestBase64(), release.signatureBase64(), artifacts);
    }

    public PublishedReleasePort.PublishedRelease requireAuthorized(String subject, String tenant,
            String contentId, String platform, String runtimeBuild) {
        if (!valid(subject) || !valid(tenant) || !valid(contentId)
                || !valid(platform) || !valid(runtimeBuild))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid scope");
        if (!entitlements.canDownload(subject, tenant, contentId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Content not authorized");
        return releases.findPublished(contentId, platform, runtimeBuild)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Compatible published release not found"));
    }

    private static boolean valid(String s) {
        return s != null && s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }
}

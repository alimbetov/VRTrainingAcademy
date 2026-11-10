package academy.vrtraining.platform.delivery.grant;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DownloadGrantServiceTest {
    private static final String SHA = "a".repeat(64);
    private static final PublishedReleasePort.PublishedRelease RELEASE =
            new PublishedReleasePort.PublishedRelease("physics", "release-001", "quest",
                "runtime-1", "vr-packages",
                List.of(new PublishedReleasePort.Artifact("world.pak",
                        "immutable/physics/release-001/world.pak", 123, SHA)),
                "manifest-base64", "signature-base64");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);

    @Test void directModePresignsOnlyAuthorizedPublishedObjects() {
        AtomicInteger signs = new AtomicInteger();
        var service = new DownloadGrantService(
            (content, platform, runtime) ->
                content.equals("physics") && platform.equals("quest")
                && runtime.equals("runtime-1") ? Optional.of(RELEASE) : Optional.empty(),
            (subject, tenant, content) ->
                subject.equals("user-1") && tenant.equals("tenant-1")
                    && content.equals("physics"),
            (bucket, key, ttl) -> {
                signs.incrementAndGet();
                assertEquals("vr-packages", bucket);
                assertEquals("immutable/physics/release-001/world.pak", key);
                assertEquals(Duration.ofMinutes(5), ttl);
                return URI.create("https://rustfs.example.test/vr-packages/object?signature=fake");
            },
            DownloadGrantService.Transport.DIRECT_RUSTFS, CLOCK, Duration.ofMinutes(5));
        var result = service.issue("user-1", "tenant-1", "physics", "quest", "runtime-1");
        assertEquals(1, signs.get());
        assertEquals("https://rustfs.example.test/vr-packages/object?signature=fake",
                result.artifacts().getFirst().url());
        assertEquals(Instant.parse("2026-11-10T12:05:00Z"), result.expiresAt());
        assertEquals("manifest-base64", result.signedManifestBase64());
        assertEquals(DownloadGrantService.Transport.DIRECT_RUSTFS, result.transport());
        var denied = assertThrows(ResponseStatusException.class,
            () -> service.issue("user-1", "other-tenant", "physics", "quest", "runtime-1"));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals(1, signs.get());
        var missing = assertThrows(ResponseStatusException.class,
            () -> service.issue("user-1", "tenant-1", "physics", "windows", "runtime-1"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test void proxyModeDoesNotPresignAndUsesAuthenticatedApiPath() {
        var service = new DownloadGrantService(
                (content, platform, runtime) -> Optional.of(RELEASE),
                (subject, tenant, content) -> true,
                (bucket, key, ttl) -> { throw new AssertionError("Must not call S3 signer"); },
                DownloadGrantService.Transport.BACKEND_PROXY, CLOCK, Duration.ofSeconds(120));
        var grant = service.issue("user-1", "tenant-1", "physics", "quest", "runtime-1");
        assertEquals(DownloadGrantService.Transport.BACKEND_PROXY, grant.transport());
        assertEquals("/api/v1/me/content/physics/artifacts/world.pak?platform=quest&runtimeBuildId=runtime-1",
                grant.artifacts().getFirst().url());
    }

    @Test void deniesUnconfiguredPolicyAndRejectsUnsafeInputs() {
        var deny = new DownloadGrantService(
                (content, platform, runtime) -> Optional.of(RELEASE),
                (subject, tenant, content) -> false,
                (bucket, key, ttl) -> URI.create("https://rustfs.example.test/x"),
                DownloadGrantService.Transport.DIRECT_RUSTFS, CLOCK, Duration.ofMinutes(5));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> deny.issue("user-1", "tenant-1", "physics", "quest", "runtime-1"))
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> deny.issue("user-1", "tenant-1", "../physics", "quest", "runtime-1"))
                .getStatusCode());
        assertThrows(IllegalArgumentException.class, () -> new DownloadGrantService(
                (content, platform, runtime) -> Optional.of(RELEASE),
                (subject, tenant, content) -> true,
                (bucket, key, ttl) -> URI.create("https://rustfs.example.test/x"),
                DownloadGrantService.Transport.DIRECT_RUSTFS, CLOCK, Duration.ofHours(1)));
    }
}

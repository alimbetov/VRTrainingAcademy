package academy.vrtraining.platform.delivery.grant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import academy.vrtraining.platform.licensing.*;
import academy.vrtraining.platform.licensing.LicenseContracts.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract bridge to EXISTING R2 DownloadGrantService, not a new downloader.
 * No real RustFS/network calls in this test; signed publication is mocked.
 */
class LicensedDownloadGrantBridgeTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID org = UUID.randomUUID();
    private final LicenseStore licenses = mock(LicenseStore.class);

    private DownloadGrantService service() {
        when(licenses.organization(org)).thenReturn(Optional.of(new Organization(
                org, "School", "owner@example.org", "owner", NOW)));
        when(licenses.entitlements(eq(org), eq(NOW))).thenReturn(
                new Entitlements(org, true, 2, Set.of("DEMO"), NOW.plusSeconds(3600)));
        PublishedContentCollectionPort collections = contentId ->
                "physics-demo".equals(contentId) ? Optional.of("DEMO") : Optional.empty();
        var entitlement = new LicensedOwnerDownloadEntitlement(licenses, collections, clock);
        PublishedReleasePort published = (contentId,platform,build) ->
                "physics-demo".equals(contentId) && "quest".equals(platform)
                ? Optional.of(new PublishedReleasePort.PublishedRelease(
                    contentId, "release-1", "quest", build, "vrassets",
                    List.of(new PublishedReleasePort.Artifact("world.pak", "objects/world.pak",
                            1000, "a".repeat(64))), "manifestBase64", "signatureBase64"))
                : Optional.empty();
        return new DownloadGrantService(published, entitlement,
                (bucket,key,ttl) -> URI.create("https://content.example.invalid/" + key),
                DownloadGrantService.Transport.DIRECT_RUSTFS, clock, Duration.ofMinutes(5));
    }

    @Test void ownerWithActiveTrialCanRequestPreviouslyPublishedDemoRelease() {
        var grant = service().issue("owner",org.toString(),"physics-demo","quest","runtime-1");
        assertEquals("release-1", grant.releaseId());
        assertEquals(1, grant.artifacts().size());
        assertEquals(NOW.plusSeconds(300),grant.expiresAt());
    }

    @Test void wrongOwnerTenantUnpublishedCollectionOrExpiredTrialAreDenied() {
        var s = service();
        assertThrows(ResponseStatusException.class,
                () -> s.issue("forged-owner",org.toString(),"physics-demo","quest","runtime-1"));
        assertThrows(ResponseStatusException.class,
                () -> s.issue("owner",UUID.randomUUID().toString(),"physics-demo","quest","runtime-1"));
        assertThrows(ResponseStatusException.class,
                () -> s.issue("owner",org.toString(),"unpublished","quest","runtime-1"));
        when(licenses.entitlements(eq(org),eq(NOW))).thenReturn(
                new Entitlements(org,false,0,Set.of(),NOW));
        assertThrows(ResponseStatusException.class,
                () -> s.issue("owner",org.toString(),"physics-demo","quest","runtime-1"));
    }
}

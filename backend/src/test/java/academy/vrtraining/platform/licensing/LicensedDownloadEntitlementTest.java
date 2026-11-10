package academy.vrtraining.platform.licensing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import academy.vrtraining.platform.device.DeviceActivationService;
import academy.vrtraining.platform.licensing.LicenseContracts.*;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LicensedDownloadEntitlementTest {
    private final Instant now = Instant.parse("2026-11-10T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID orgId = UUID.randomUUID();
    private final LicenseStore licenses = mock(LicenseStore.class);
    private final PublishedContentCollectionPort published =
            content -> "physics".equals(content) ? Optional.of("PHYSICS") : Optional.empty();

    private void activeLicense() {
        when(licenses.organization(orgId)).thenReturn(Optional.of(
                new Organization(orgId,"School","owner@example.org","owner-oidc",now)));
        when(licenses.entitlements(eq(orgId), eq(now))).thenReturn(
                new Entitlements(orgId,true,2,Set.of("PHYSICS"),now.plusSeconds(3600)));
    }

    @Test void webGrantRequiresOwnerTenantLicenseAndPublishedCollection() {
        activeLicense();
        var policy = new LicensedOwnerDownloadEntitlement(licenses, published, clock);
        assertTrue(policy.canDownload("owner-oidc",orgId.toString(),"physics"));
        assertFalse(policy.canDownload("other-user",orgId.toString(),"physics"));
        assertFalse(policy.canDownload("owner-oidc",UUID.randomUUID().toString(),"physics"));
        assertFalse(policy.canDownload("owner-oidc",orgId.toString(),"unknown"));
        assertFalse(policy.canDownload("owner-oidc","forged-tenant","physics"));
    }

    @Test void expiredLicenseCannotIssueNewGrant() {
        activeLicense();
        when(licenses.entitlements(eq(orgId), eq(now))).thenReturn(
                new Entitlements(orgId,false,0,Set.of(),now));
        assertFalse(new LicensedOwnerDownloadEntitlement(licenses,published,clock)
                .canDownload("owner-oidc",orgId.toString(),"physics"));
    }

    @Test void verifiedQuestRequiresActiveKeyRegisteredDeviceAndEntitlement() throws Exception {
        activeLicense();
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID deviceId = UUID.randomUUID();
        var devices = mock(DeviceActivationService.Store.class);
        when(devices.isDeviceWithinCurrentSeatLimit(eq(deviceId), eq(now))).thenReturn(true);
        when(devices.findDevice(deviceId)).thenReturn(new DeviceActivationService.Device(
                deviceId,orgId.toString(),pair.getPublic(),DeviceActivationService.State.ACTIVE));
        var service = new LicensedDeviceContentAccess(licenses,devices,published,clock);
        assertTrue(service.canDownloadForVerifiedDevice(deviceId,"physics"));
        when(devices.isDeviceWithinCurrentSeatLimit(eq(deviceId), eq(now))).thenReturn(false);
        assertFalse(service.canDownloadForVerifiedDevice(deviceId,"physics"),
                "Registered device cannot download when outside current seat limit");
        when(devices.isDeviceWithinCurrentSeatLimit(eq(deviceId), eq(now))).thenReturn(true);
        assertFalse(service.canDownloadForVerifiedDevice(deviceId,"not-published"));
        when(devices.findDevice(deviceId)).thenReturn(new DeviceActivationService.Device(
                deviceId,orgId.toString(),pair.getPublic(),DeviceActivationService.State.REVOKED));
        assertFalse(service.canDownloadForVerifiedDevice(deviceId,"physics"));
        assertFalse(service.canDownloadForVerifiedDevice(UUID.randomUUID(),"physics"));
    }
}

package academy.vrtraining.platform.licensing;

import academy.vrtraining.platform.device.DeviceActivationService;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain authorization after the caller has PROVEN registered device key
 * possession. deviceId arguments are NOT credentials; do not expose this
 * directly as an HTTP endpoint using a client-submitted deviceId.
 */
public final class LicensedDeviceContentAccess {
    private final LicenseStore licenses;
    private final DeviceActivationService.Store devices;
    private final PublishedContentCollectionPort publication;
    private final Clock clock;

    public LicensedDeviceContentAccess(LicenseStore licenses, DeviceActivationService.Store devices,
                                       PublishedContentCollectionPort publication, Clock clock) {
        this.licenses = Objects.requireNonNull(licenses);
        this.devices = Objects.requireNonNull(devices);
        this.publication = Objects.requireNonNull(publication);
        this.clock = Objects.requireNonNull(clock);
    }

    public boolean canDownloadForVerifiedDevice(UUID verifiedDeviceId, String contentId) {
        if (verifiedDeviceId == null || contentId == null || contentId.isBlank()) return false;
        var device = devices.findDevice(verifiedDeviceId);
        if (device == null || device.state() != DeviceActivationService.State.ACTIVE
                || !devices.isDeviceWithinCurrentSeatLimit(verifiedDeviceId, clock.instant())) return false;
        try {
            UUID organizationId = UUID.fromString(device.tenantId());
            var organization = licenses.organization(organizationId);
            if (organization.isEmpty()) return false;
            var rights = licenses.entitlements(organizationId, clock.instant());
            if (!rights.active()) return false;
            return publication.publishedCollectionOf(contentId)
                    .filter(rights.collections()::contains).isPresent();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

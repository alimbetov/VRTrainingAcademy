package academy.vrtraining.platform.licensing;

import academy.vrtraining.platform.delivery.grant.DownloadEntitlementPort;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * OWNER-only WEB grant authorization. This does NOT authorize a Quest:
 * device grants require separately verified sender-constrained credentials.
 * Subject is never accepted from request body. No account member fallback.
 */
public final class LicensedOwnerDownloadEntitlement implements DownloadEntitlementPort {
    private final LicenseStore licenses;
    private final PublishedContentCollectionPort published;
    private final Clock clock;

    public LicensedOwnerDownloadEntitlement(LicenseStore licenses,
                                            PublishedContentCollectionPort published, Clock clock) {
        this.licenses = Objects.requireNonNull(licenses);
        this.published = Objects.requireNonNull(published);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public boolean canDownload(String subjectId, String tenantId, String contentId) {
        if (subjectId == null || subjectId.isBlank() || contentId == null || contentId.isBlank())
            return false;
        try {
            UUID organizationId = UUID.fromString(tenantId);
            var organization = licenses.organization(organizationId);
            if (organization.isEmpty() || !subjectId.equals(organization.get().ownerSubject()))
                return false;
            var rights = licenses.entitlements(organizationId, clock.instant());
            if (!rights.active()) return false;
            var collection = published.publishedCollectionOf(contentId);
            return collection.isPresent() && rights.collections().contains(collection.get());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

package academy.vrtraining.platform.licensing;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import academy.vrtraining.platform.licensing.LicenseContracts.*;

public interface LicenseStore {
    /** Transactional creation: one lifetime trial per verified owner subject. */
    Organization createTrial(String ownerSubject, String verifiedEmail, String organizationName,
                             Instant now, Instant trialEndsAt, int deviceLimit, Set<String> collections);
    Optional<Organization> byOwner(String ownerSubject);
    Optional<Organization> organization(UUID organizationId);
    LicenseRequest createRequest(UUID organizationId, String ownerSubject, int requestedDevices,
                                 Set<String> collections, Instant now);
    Optional<LicenseRequest> request(UUID requestId);
    /** Atomic PENDING -> APPROVED and license issuance, rejecting concurrent duplicates. */
    License approve(UUID requestId, Instant from, Instant to, int deviceLimit,
                    Set<String> collections, String adminSubject);
    Entitlements entitlements(UUID organizationId, Instant at);
}

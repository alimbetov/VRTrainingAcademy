package academy.vrtraining.platform.licensing;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Organization owns rights; neither teacher nor student owns the license. */
public final class LicenseContracts {
    private LicenseContracts() {}
    public enum LicenseKind { TRIAL, COMMERCIAL, TRIAL_EXTENSION }
    public enum RequestStatus { PENDING, APPROVED, REJECTED }
    public record Organization(UUID id, String name, String verifiedContactEmail,
                               String ownerSubject, Instant createdAt) {}
    public record License(UUID id, UUID organizationId, LicenseKind kind, Instant startsAt,
                          Instant expiresAt, int deviceLimit, Set<String> collections,
                          UUID requestId) {
        public License { collections = Set.copyOf(collections); }
    }
    public record LicenseRequest(UUID id, UUID organizationId, String requestedBy,
                                 int requestedDevices, Set<String> collections,
                                 RequestStatus status, Instant createdAt) {
        public LicenseRequest { collections = Set.copyOf(collections); }
    }
    public record Entitlements(UUID organizationId, boolean active,
                               int deviceLimit, Set<String> collections, Instant validUntil) {
        public Entitlements { collections = Set.copyOf(collections); }
    }
}

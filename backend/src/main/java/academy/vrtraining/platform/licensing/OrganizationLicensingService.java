package academy.vrtraining.platform.licensing;

import academy.vrtraining.platform.licensing.LicenseContracts.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * MVP: verified OIDC owner creates a 30-day trial, requests commercial terms,
 * platform admin grants them manually. No payment gateway or password transfer.
 */
public final class OrganizationLicensingService {
    private static final Duration TRIAL_DURATION = Duration.ofDays(30);
    private static final Set<String> TRIAL_COLLECTIONS = Set.of("DEMO");
    private final LicenseStore store;
    private final LicensingAuthorization authorization;
    private final Clock clock;

    public OrganizationLicensingService(LicenseStore store, LicensingAuthorization authorization,
                                        Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
        this.clock = Objects.requireNonNull(clock);
    }

    public Organization register(String authenticatedSubject, String verifiedEmail,
                                 boolean emailVerified, String organizationName) {
        requireSubject(authenticatedSubject);
        if (!emailVerified || verifiedEmail == null || verifiedEmail.length() > 254
                || !verifiedEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
            throw new SecurityException("Verified account email required");
        if (organizationName == null || organizationName.isBlank()
                || organizationName.strip().length() > 120)
            throw new IllegalArgumentException("Invalid organization name");
        var now = clock.instant();
        return store.createTrial(authenticatedSubject, verifiedEmail, organizationName.strip(),
                now, now.plus(TRIAL_DURATION), 2, TRIAL_COLLECTIONS);
    }

    public Organization myOrganization(String authenticatedSubject) {
        requireSubject(authenticatedSubject);
        return store.byOwner(authenticatedSubject)
                .orElseThrow(() -> new SecurityException("No owner organization"));
    }

    public LicenseRequest requestCommercial(String authenticatedSubject, int deviceLimit,
                                            Set<String> collectionIds) {
        var organization = myOrganization(authenticatedSubject);
        requireTerms(deviceLimit, collectionIds);
        return store.createRequest(organization.id(), authenticatedSubject, deviceLimit,
                Set.copyOf(collectionIds), clock.instant());
    }

    public License grant(String adminSubject, UUID requestId, int deviceLimit,
                         Set<String> collectionIds, java.time.Instant expiresAt) {
        requireSubject(adminSubject);
        if (!authorization.isPlatformAdministrator(adminSubject))
            throw new SecurityException("Platform administrator required");
        requireTerms(deviceLimit, collectionIds);
        var now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plus(Duration.ofDays(3660))))
            throw new IllegalArgumentException("Invalid license validity");
        var request = store.request(Objects.requireNonNull(requestId))
                .orElseThrow(() -> new SecurityException("Unknown license request"));
        if (request.status() != RequestStatus.PENDING)
            throw new IllegalStateException("License request already resolved");
        if (deviceLimit > request.requestedDevices()
                || !request.collections().containsAll(collectionIds))
            throw new SecurityException("Issue only requested scope");
        return store.approve(requestId, now, expiresAt, deviceLimit,
                Set.copyOf(collectionIds), adminSubject);
    }

    public Entitlements myEntitlements(String ownerSubject) {
        return store.entitlements(myOrganization(ownerSubject).id(), clock.instant());
    }

    private static void requireTerms(int devices, Set<String> collections) {
        if (devices < 1 || devices > 10000 || collections == null
                || collections.isEmpty() || collections.size() > 100
                || collections.stream().anyMatch(x -> x == null
                || !x.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            throw new IllegalArgumentException("Invalid license terms");
    }

    private static void requireSubject(String value) {
        if (value == null || value.isBlank() || value.length() > 255)
            throw new SecurityException("Authenticated subject required");
    }
}

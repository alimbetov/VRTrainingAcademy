package academy.vrtraining.platform.licensing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import academy.vrtraining.platform.licensing.LicenseContracts.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Business RED/GREEN contract suite; no database/network needed.
 * A test must never consider email or request JSON sufficient authority.
 */
class OrganizationLicensingServiceContractTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private final LicenseStore store = mock(LicenseStore.class);
    private final LicensingAuthorization admin = subject -> "platform-admin".equals(subject);
    private final LicensableCollectionCatalog catalog =
            ids -> Set.of("PHYSICS", "CHEMISTRY").containsAll(ids);
    private final UUID orgId = UUID.randomUUID();
    private OrganizationLicensingService service;

    @BeforeEach void setup() {
        service = new OrganizationLicensingService(store, admin, catalog,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void verifiedOwnerGetsExactlyThirtyDaysAndTwoDemoSeats() {
        when(store.createTrial(anyString(), anyString(), anyString(), any(), any(), anyInt(), anySet()))
                .thenAnswer(call -> new Organization(orgId, call.getArgument(2), call.getArgument(1),
                        call.getArgument(0), call.getArgument(3)));
        var org = service.register("oidc-owner", "owner@school.example", true, "  School  ");
        assertEquals("School", org.name());
        verify(store).createTrial("oidc-owner", "owner@school.example", "School", NOW,
                NOW.plus(Duration.ofDays(30)), 2, Set.of("DEMO"));
    }

    @Test void unverifiedOrMalformedEmailCannotCreateTrial() {
        assertThrows(SecurityException.class,
                () -> service.register("owner", "owner@school.example", false, "School"));
        assertThrows(SecurityException.class,
                () -> service.register("owner", "not-an-email", true, "School"));
        assertThrows(SecurityException.class,
                () -> service.register("", "owner@school.example", true, "School"));
        verifyNoInteractions(store);
    }

    @Test void emptyAndOversizedOrganizationNamesRejectedBeforeWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> service.register("owner", "owner@school.example", true, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> service.register("owner", "owner@school.example", true, "X".repeat(121)));
        verifyNoInteractions(store);
    }

    @Test void commercialRequestUsesAuthenticatedOwnerNotRequestProvidedOrganization() {
        var organization = new Organization(orgId, "School", "owner@example.org", "owner", NOW);
        when(store.byOwner("owner")).thenReturn(Optional.of(organization));
        when(store.createRequest(eq(orgId), eq("owner"), eq(5), eq(Set.of("PHYSICS")), eq(NOW)))
                .thenReturn(new LicenseRequest(UUID.randomUUID(), orgId, "owner", 5,
                        Set.of("PHYSICS"), RequestStatus.PENDING, NOW));
        var request = service.requestCommercial("owner", 5, Set.of("PHYSICS"));
        assertEquals(orgId, request.organizationId());
        verify(store).createRequest(orgId, "owner", 5, Set.of("PHYSICS"), NOW);
        assertThrows(SecurityException.class,
                () -> service.requestCommercial("another-owner", 5, Set.of("PHYSICS")));
    }

    @Test void rejectedTermsNeverReachPersistence() {
        when(store.byOwner("owner")).thenReturn(Optional.of(
                new Organization(orgId, "School", "owner@example.org", "owner", NOW)));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestCommercial("owner", 0, Set.of("PHYSICS")));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestCommercial("owner", 10001, Set.of("PHYSICS")));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestCommercial("owner", 2, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestCommercial("owner", 2, Set.of("../escape")));
        verify(store, never()).createRequest(any(), anyString(), anyInt(), anySet(), any());
    }

    @Test void teacherCannotReadOrGrantCommercialLicense() {
        var requestId = UUID.randomUUID();
        assertThrows(SecurityException.class, () -> service.getRequestForAdmin("teacher", requestId));
        assertThrows(SecurityException.class, () ->
                service.grant("teacher", requestId, 2, Set.of("PHYSICS"),
                        NOW.plus(Duration.ofDays(365))));
        verify(store, never()).request(any());
        verify(store, never()).approve(any(), any(), any(), anyInt(), anySet(), anyString());
    }

    @Test void adminCannotGrantUnpublishedOrUnrequestedCollectionsOrSeats() {
        var id = UUID.randomUUID();
        when(store.request(id)).thenReturn(Optional.of(new LicenseRequest(
                id, orgId, "owner", 3, Set.of("PHYSICS"), RequestStatus.PENDING, NOW)));
        var deadline = NOW.plus(Duration.ofDays(365));
        assertThrows(SecurityException.class, () ->
                service.grant("platform-admin", id, 1, Set.of("UNPUBLISHED"), deadline));
        assertThrows(SecurityException.class, () ->
                service.grant("platform-admin", id, 4, Set.of("PHYSICS"), deadline));
        assertThrows(SecurityException.class, () ->
                service.grant("platform-admin", id, 2, Set.of("CHEMISTRY"), deadline));
        verify(store, never()).approve(any(), any(), any(), anyInt(), anySet(), anyString());
    }

    @Test void expiredOrExcessivelyLongCommercialGrantRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                service.grant("platform-admin", UUID.randomUUID(), 2, Set.of("PHYSICS"), NOW));
        assertThrows(IllegalArgumentException.class, () ->
                service.grant("platform-admin", UUID.randomUUID(), 2, Set.of("PHYSICS"),
                        NOW.plus(Duration.ofDays(3661))));
        verify(store, never()).approve(any(), any(), any(), anyInt(), anySet(), anyString());
    }

    @Test void authorizedApprovalPreservesSelectedScope() {
        var id = UUID.randomUUID();
        var expires = NOW.plus(Duration.ofDays(365));
        when(store.request(id)).thenReturn(Optional.of(new LicenseRequest(
                id, orgId, "owner", 5, Set.of("PHYSICS", "CHEMISTRY"),
                RequestStatus.PENDING, NOW)));
        when(store.approve(eq(id), eq(NOW), eq(expires), eq(3),
                eq(Set.of("PHYSICS")), eq("platform-admin")))
                .thenReturn(new License(UUID.randomUUID(), orgId, LicenseKind.COMMERCIAL,
                        NOW, expires, 3, Set.of("PHYSICS"), id));
        var license = service.grant("platform-admin", id, 3, Set.of("PHYSICS"), expires);
        assertEquals(3, license.deviceLimit());
        assertEquals(Set.of("PHYSICS"), license.collections());
        verify(store).approve(id, NOW, expires, 3, Set.of("PHYSICS"), "platform-admin");
    }
}

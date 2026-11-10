package academy.vrtraining.platform.licensing;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "VRTA_TEST_PG_JDBC_URL", matches = ".+")
class PostgresLicenseStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    @Test void trialIsOneTimeCommercialApprovalAtomicAndExpiredRightsDenied() throws Exception {
        String url = System.getenv("VRTA_TEST_PG_JDBC_URL");
        String user = System.getenv("VRTA_TEST_PG_USER");
        String password = System.getenv("VRTA_TEST_PG_PASSWORD");
        String sql = Files.readString(Path.of("db/migrations/R4__organization_trial_manual_licensing.sql"))
                .lines().map(line -> line.stripLeading().startsWith("--") ? "" : line)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        try (var db = DriverManager.getConnection(url, user, password);
             var stmt = db.createStatement()) {
            for (String command : sql.split(";"))
                if (!command.isBlank()) stmt.execute(command);
        }
        var store = new PostgresLicenseStore(url, user, password);
        var svc = new OrganizationLicensingService(store, subject -> "platform-admin".equals(subject),
                collections -> Set.of("PHYSICS", "CHEMISTRY").containsAll(collections),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var org = svc.register("oidc-subject-1", "owner@school.example", true, "Example School");
        assertEquals(org.id(), svc.register("oidc-subject-1", "owner@school.example", true, "Example School").id(),
                "Retry must not restart the trial");
        var rights = svc.myEntitlements("oidc-subject-1");
        assertTrue(rights.active());
        assertEquals(2, rights.deviceLimit());
        assertEquals(Set.of("DEMO"), rights.collections());
        assertEquals(NOW.plus(Duration.ofDays(30)), rights.validUntil());

        assertThrows(SecurityException.class, () ->
                svc.register("oidc-subject-2", "unverified@school.example", false, "Fake School"));
        assertThrows(SecurityException.class, () ->
                svc.requestCommercial("unknown-owner", 5, Set.of("PHYSICS")));
        var request = svc.requestCommercial("oidc-subject-1", 5, Set.of("PHYSICS", "CHEMISTRY"));
        assertThrows(SecurityException.class, () ->
                svc.grant("teacher", request.id(), 5, Set.of("PHYSICS"), NOW.plus(Duration.ofDays(365))));
        assertThrows(SecurityException.class, () ->
                svc.grant("platform-admin", request.id(), 6, Set.of("PHYSICS"), NOW.plus(Duration.ofDays(365))));
        var license = svc.grant("platform-admin", request.id(), 5, Set.of("PHYSICS"),
                NOW.plus(Duration.ofDays(365)));
        assertEquals(LicenseContracts.LicenseKind.COMMERCIAL, license.kind());
        assertThrows(LicenseConflictException.class, () ->
                svc.grant("platform-admin", request.id(), 5, Set.of("PHYSICS"),
                        NOW.plus(Duration.ofDays(365))), "Second approval rejected");
        rights = svc.myEntitlements("oidc-subject-1");
        assertEquals(5, rights.deviceLimit());
        assertEquals(Set.of("PHYSICS"), rights.collections());
        assertEquals(NOW.plus(Duration.ofDays(365)), rights.validUntil());

        var future = new OrganizationLicensingService(store, subject -> false,
                collections -> false, Clock.fixed(NOW.plus(Duration.ofDays(366)), ZoneOffset.UTC));
        assertFalse(future.myEntitlements("oidc-subject-1").active());
        assertTrue(future.myEntitlements("oidc-subject-1").collections().isEmpty());
    }
}

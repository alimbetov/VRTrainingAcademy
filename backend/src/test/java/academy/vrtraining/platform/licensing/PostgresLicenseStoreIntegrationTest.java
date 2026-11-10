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
                .replace("CREATE TABLE platform.", "CREATE TABLE IF NOT EXISTS platform.")
                .replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
                .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ")
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
        assertEquals(request.id(), svc.getRequestForAdmin("platform-admin", request.id()).id());
        assertThrows(SecurityException.class,
                () -> svc.getRequestForAdmin("teacher", request.id()));
        assertThrows(SecurityException.class,
                () -> svc.grant("platform-admin", request.id(), 5, Set.of("DRAFT_UNKNOWN"),
                        NOW.plus(Duration.ofDays(365))));
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

        var secondRequest = svc.requestCommercial("oidc-subject-1", 4, Set.of("PHYSICS"));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> task = () -> {
                latch.await();
                try {
                    svc.grant("platform-admin", secondRequest.id(), 4, Set.of("PHYSICS"),
                            NOW.plus(Duration.ofDays(180)));
                    return true;
                } catch (LicenseConflictException ex) {
                    return false;
                }
            };
            var a = executor.submit(task);
            var b = executor.submit(task);
            latch.countDown();
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0),
                    "Concurrent approvals must create exactly one license");
        } finally {
            executor.shutdownNow();
        }
        // License boundary: exactly expiresAt is NOT valid; one nanosecond
        // before it is valid. No global clock assumptions in PostgreSQL.
        assertTrue(store.entitlements(org.id(), NOW.plus(Duration.ofDays(180))
                        .minusNanos(1)).active());
        assertFalse(store.entitlements(org.id(), NOW.plus(Duration.ofDays(365))).active());
        // Suspended organizations cannot use otherwise valid commercial rights.
        try (var db = DriverManager.getConnection(url, user, password);
             var stmt = db.prepareStatement(
                "UPDATE platform.organization_account SET status='SUSPENDED' WHERE organization_id=?")) {
            stmt.setObject(1, org.id());
            assertEquals(1, stmt.executeUpdate());
        }
        assertFalse(store.entitlements(org.id(), NOW.plus(Duration.ofDays(1))).active());
        try (var db = DriverManager.getConnection(url, user, password);
             var stmt = db.prepareStatement(
                "UPDATE platform.organization_account SET status='ACTIVE' WHERE organization_id=?")) {
            stmt.setObject(1, org.id());
            assertEquals(1, stmt.executeUpdate());
        }
        assertTrue(store.entitlements(org.id(), NOW.plus(Duration.ofDays(1))).active());

        // Two simultaneous signups from the same verified owner must never
        // generate two organizations or restart the 30-day trial.
        var signupExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var sameOwner = "trial-race-" + java.util.UUID.randomUUID();
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<java.util.UUID> signup = () -> {
                latch.await();
                return store.createTrial(sameOwner, "race@school.example", "Race School",
                        NOW, NOW.plus(Duration.ofDays(30)), 2, Set.of("DEMO")).id();
            };
            var s1 = signupExecutor.submit(signup);
            var s2 = signupExecutor.submit(signup);
            latch.countDown();
            var createdId = s1.get();
            assertEquals(createdId, s2.get());
            try (var db = DriverManager.getConnection(url, user, password);
                 var stmt = db.prepareStatement(
                    "SELECT count(*) FROM platform.organization_license " +
                    "WHERE organization_id=? AND kind='TRIAL'")) {
                stmt.setObject(1, createdId);
                try (var rows = stmt.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getLong(1));
                }
            }
        } finally {
            signupExecutor.shutdownNow();
        }

        var future = new OrganizationLicensingService(store, subject -> false,
                collections -> false, Clock.fixed(NOW.plus(Duration.ofDays(366)), ZoneOffset.UTC));
        assertFalse(future.myEntitlements("oidc-subject-1").active());
        assertTrue(future.myEntitlements("oidc-subject-1").collections().isEmpty());
    }
}

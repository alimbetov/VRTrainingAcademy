package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;

import academy.vrtraining.education.classroom.ClassroomContracts.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="VRTA_TEST_PG_JDBC_URL", matches=".+")
class PostgresClassroomStoreIntegrationTest {
    @Test void schemaBoundariesUniqueNicknamesAndIdempotentProgress() throws Exception {
        String url = System.getenv("VRTA_TEST_PG_JDBC_URL");
        String user = System.getenv("VRTA_TEST_PG_USER");
        String password = System.getenv("VRTA_TEST_PG_PASSWORD");
        String sql = Files.readString(Path.of("db/migrations/R3__classroom_learning_foundation.sql"));
        sql += System.lineSeparator() + Files.readString(Path.of("db/migrations/R3_2__device_activation_foundation.sql"));
        sql += System.lineSeparator() + Files.readString(Path.of("db/migrations/R3_3__daily_student_numbers.sql"));
        sql += System.lineSeparator() + Files.readString(Path.of("db/migrations/R3_4__device_locale_and_collection.sql"));
        sql += System.lineSeparator() + Files.readString(Path.of("db/migrations/R3_5__selected_lesson_release.sql"));
        sql += System.lineSeparator() + Files.readString(Path.of("db/migrations/R4__organization_trial_manual_licensing.sql"))
                .replace("CREATE TABLE platform.", "CREATE TABLE IF NOT EXISTS platform.")
                .replace("CREATE UNIQUE INDEX ", "CREATE UNIQUE INDEX IF NOT EXISTS ")
                .replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ");
        sql = sql.lines().map(line -> line.stripLeading().startsWith("--") ? "" : line)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        try (var db = DriverManager.getConnection(url, user, password);
             var statement = db.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
        try (var db = DriverManager.getConnection(url, user, password);
             var statement = db.prepareStatement(
                "INSERT INTO platform.registered_device(device_id,tenant_id,public_key_spki,state,activated_at) VALUES (?,?,?,?,now())")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "school-1");
            statement.setBytes(3, new byte[]{1, 2, 3});
            statement.setString(4, "UNKNOWN");
            assertThrows(java.sql.SQLException.class, statement::executeUpdate);
        }
        // Device activation: trusted admin consent and possession proof,
        // persisted atomically in real PostgreSQL.
        var pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var deviceStore = new academy.vrtraining.platform.device.PostgresDeviceActivationStore(url, user, password);
        var rights = new academy.vrtraining.platform.device.DeviceActivationService.AdministratorAuthorization() {
            public boolean mayActivateDevice(String teacher, String tenant) {
                return teacher.equals("teacher-1") && tenant.equals("school-1");
            }
            public boolean mayRevokeDevice(String teacher, String tenant) {
                return mayActivateDevice(teacher, tenant);
            }
        };
        var activation = new academy.vrtraining.platform.device.DeviceActivationService(
                deviceStore, rights, java.time.Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"),
                        java.time.ZoneOffset.UTC), new java.security.SecureRandom(), new byte[32]);
        var request = activation.begin(pair.getPublic());
        assertThrows(java.security.GeneralSecurityException.class, () ->
                activation.approve("teacher-1", "school-1", request.flowId(), "AAAA-AAAA"));
        activation.approve("teacher-1", "school-1", request.flowId(), request.userCode());
        var proof = java.security.Signature.getInstance("Ed25519");
        proof.initSign(pair.getPrivate());
        proof.update(academy.vrtraining.platform.device.DeviceActivationService.bindingMessage(
                request.flowId(), request.challenge()));
        var registered = activation.complete(request.flowId(), proof.sign());
        assertEquals("school-1", deviceStore.findDevice(registered.deviceId()).tenantId());
        assertThrows(java.security.GeneralSecurityException.class,
                () -> activation.complete(request.flowId(), new byte[64]));
        activation.revoke("teacher-1", "school-1", registered.deviceId());
        assertEquals(academy.vrtraining.platform.device.DeviceActivationService.State.REVOKED,
                deviceStore.findDevice(registered.deviceId()).state());

        var licenseStore = new academy.vrtraining.platform.licensing.PostgresLicenseStore(url,user,password);
        var testNow = Instant.parse("2026-11-10T12:00:00Z");
        var licensedOrg = licenseStore.createTrial("seats-" + UUID.randomUUID(),
                "owner@example.org","Seat School",testNow,testNow.plusSeconds(30L*86400),
                2,java.util.Set.of("DEMO"));
        var guarded = new academy.vrtraining.platform.device.PostgresDeviceActivationStore(
                url,user,password,true);
        var admin = new academy.vrtraining.platform.device.DeviceActivationService.AdministratorAuthorization() {
            public boolean mayActivateDevice(String subject, String tenant) {
                return "owner".equals(subject) && licensedOrg.id().toString().equals(tenant);
            }
            public boolean mayRevokeDevice(String subject, String tenant) {
                return mayActivateDevice(subject,tenant);
            }
        };
        var guardService = new academy.vrtraining.platform.device.DeviceActivationService(
                guarded,admin,java.time.Clock.fixed(testNow,java.time.ZoneOffset.UTC),
                new java.security.SecureRandom(),new byte[32]);
        var first = approvedFlow(guardService,licensedOrg.id().toString());
        var a = guardService.complete(first.flowId(),first.proof());
        var second = approvedFlow(guardService,licensedOrg.id().toString());
        var third = approvedFlow(guardService,licensedOrg.id().toString());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> task2 = () -> {
                latch.await();
                try { guardService.complete(second.flowId(),second.proof()); return true; }
                catch (SecurityException ex) { return false; }
            };
            java.util.concurrent.Callable<Boolean> task3 = () -> {
                latch.await();
                try { guardService.complete(third.flowId(),third.proof()); return true; }
                catch (SecurityException ex) { return false; }
            };
            var p2=pool.submit(task2);
            var p3=pool.submit(task3);
            latch.countDown();
            assertEquals(1,(p2.get()?1:0)+(p3.get()?1:0),
                    "Concurrent activation cannot oversubscribe last seat");
        } finally { pool.shutdownNow(); }
        var devicePolicy = new academy.vrtraining.platform.licensing.LicensedDeviceContentAccess(
                licenseStore,guarded,
                contentId -> "demo-physics".equals(contentId)
                        ? java.util.Optional.of("DEMO") : java.util.Optional.empty(),
                java.time.Clock.fixed(testNow,java.time.ZoneOffset.UTC));
        assertTrue(devicePolicy.canDownloadForVerifiedDevice(a.deviceId(),"demo-physics"));
        assertFalse(devicePolicy.canDownloadForVerifiedDevice(a.deviceId(),"not-published"));
        assertTrue(guarded.isDeviceWithinCurrentSeatLimit(a.deviceId(), testNow));
        // Commercial downgrade to one seat must immediately restrict online
        // access, even if both registered-device rows remain ACTIVE.
        UUID downgradeLicenseId = UUID.randomUUID();
        try (var db = DriverManager.getConnection(url,user,password);
             var stmt = db.prepareStatement(
                "INSERT INTO platform.organization_license(license_id,organization_id,kind," +
                "starts_at,expires_at,device_limit,allowed_collections,issued_by_subject,issued_at)" +
                " VALUES(?,?,'COMMERCIAL',?,?,1,ARRAY['DEMO']::text[],'platform-admin',?)")) {
            stmt.setObject(1,downgradeLicenseId);
            stmt.setObject(2,licensedOrg.id());
            stmt.setTimestamp(3,java.sql.Timestamp.from(testNow));
            stmt.setTimestamp(4,java.sql.Timestamp.from(testNow.plusSeconds(3600)));
            stmt.setTimestamp(5,java.sql.Timestamp.from(testNow));
            assertEquals(1,stmt.executeUpdate());
        }
        java.util.List<UUID> currentlyActive = new java.util.ArrayList<>();
        try (var db = DriverManager.getConnection(url,user,password);
             var stmt = db.prepareStatement(
                "SELECT device_id FROM platform.registered_device " +
                "WHERE tenant_id=? AND state='ACTIVE'")) {
            stmt.setString(1,licensedOrg.id().toString());
            try (var rows=stmt.executeQuery()) {
                while(rows.next()) currentlyActive.add((UUID) rows.getObject(1));
            }
        }
        assertEquals(2,currentlyActive.size());
        assertEquals(1,currentlyActive.stream()
                .filter(id -> devicePolicy.canDownloadForVerifiedDevice(id,"demo-physics")).count(),
                "Downgrading seat limit to one must deny surplus ACTIVE Quest access");
        try (var db = DriverManager.getConnection(url,user,password);
             var stmt = db.prepareStatement(
                 "DELETE FROM platform.organization_license WHERE license_id=?")) {
            stmt.setObject(1,downgradeLicenseId);
            assertEquals(1,stmt.executeUpdate());
        }
        assertEquals(2,currentlyActive.stream()
                .filter(id -> devicePolicy.canDownloadForVerifiedDevice(id,"demo-physics")).count(),
                "Restoring two seats must reauthorize both registered Quest devices");
        guardService.revoke("owner",licensedOrg.id().toString(),a.deviceId());
        assertFalse(devicePolicy.canDownloadForVerifiedDevice(a.deviceId(),"demo-physics"));
        var fourth=approvedFlow(guardService,licensedOrg.id().toString());
        assertEquals(academy.vrtraining.platform.device.DeviceActivationService.State.ACTIVE,
                guardService.complete(fourth.flowId(),fourth.proof()).state());
        var expired = new academy.vrtraining.platform.device.DeviceActivationService(
                guarded,admin,java.time.Clock.fixed(testNow.plusSeconds(31L*86400),
                java.time.ZoneOffset.UTC),new java.security.SecureRandom(),new byte[32]);
        var late = approvedFlow(expired,licensedOrg.id().toString());
        assertThrows(SecurityException.class, () -> expired.complete(late.flowId(),late.proof()));

        var store = new PostgresClassroomStore(url, user, password);
        var classroom = store.createClassroom("school-1", "7А", "teacher-1");
        var student = store.createStudent(classroom.id(), "Айша");
        assertThrows(IllegalStateException.class,
                () -> store.createStudent(classroom.id(), "АЙША"));
        assertEquals(1, store.students(classroom.id()).size());
        var numbered = store.assignNumber(classroom.id(), 247);
        assertEquals(247, numbered.number());
        assertEquals(2, store.students(classroom.id()).size());
        assertTrue(store.students(classroom.id()).stream()
                .anyMatch(s -> s.id().equals(numbered.id()) && s.nickname().equals("247")));
        assertThrows(IllegalStateException.class, () -> store.assignNumber(classroom.id(), 247));
        assertThrows(IllegalArgumentException.class, () -> store.assignNumber(classroom.id(), 99));
        var secondClass = store.createClassroom("school-1", "7Б", "teacher-1");
        assertEquals(247, store.assignNumber(secondClass.id(), 247).number());

        UUID device = UUID.randomUUID();
        Instant from = Instant.parse("2026-11-10T12:00:00Z");
        var lesson = store.createSession(classroom.id(), device, "physics", "r1",
                from, from.plusSeconds(3600));
        assertThrows(SecurityException.class, () -> store.beginAttempt(
                lesson.id(), UUID.randomUUID(), device, from.plusSeconds(60)));
        var attempt = store.beginAttempt(lesson.id(), student.id(), device, from.plusSeconds(60));
        var event = new ProgressEvent(UUID.randomUUID(), attempt.id(), 1, "TASK_COMPLETED",
                20, from.plusSeconds(120));
        assertTrue(store.appendEvent(event));
        assertFalse(store.appendEvent(event));
        assertThrows(IllegalStateException.class,
                () -> store.appendEvent(new ProgressEvent(event.eventId(), attempt.id(), 1,
                        "TASK_COMPLETED", 90, from.plusSeconds(120))));
        assertThrows(IllegalStateException.class,
                () -> store.appendEvent(new ProgressEvent(UUID.randomUUID(), attempt.id(), 1,
                        "TASK_COMPLETED", 90, from.plusSeconds(120))));
        assertEquals(20, store.scores(lesson.id()).getFirst().score());
        assertEquals(20, store.scoresForDay(classroom.id(),
                java.time.LocalDate.of(2026, 11, 10)).getFirst().score());
        assertTrue(store.scoresForDay(classroom.id(),
                java.time.LocalDate.of(2026, 11, 11)).isEmpty());
        // Yesterday's records stay stored; today's report starts at zero.
        assertEquals(20, store.scores(lesson.id()).getFirst().score());
        var selectionSession = store.createSession(classroom.id(), device, "UNSELECTED", "r1",
                from, from.plusSeconds(3600));
        var journeyStore = new PostgresLearnerJourneyRepository(url, user, password);
        assertEquals(numbered.id(), journeyStore.studentByNumber(classroom.id(), 247));
        assertNull(journeyStore.assignedCollection(selectionSession.id()));
        assertThrows(SecurityException.class, () -> journeyStore.beginSelectedAttempt(
                selectionSession.id(), device, numbered.id(), "PHYSICS", "kk-KZ", "PHY-001", "release-1",
                from.plusSeconds(70)));
        assertTrue(journeyStore.assign(selectionSession.id(), classroom.id(), device, "PHYSICS"));
        assertEquals("PHYSICS", journeyStore.assignedCollection(selectionSession.id()));
        assertFalse(journeyStore.assign(selectionSession.id(), classroom.id(), UUID.randomUUID(), "CHEMISTRY"));
        assertThrows(SecurityException.class, () -> journeyStore.beginSelectedAttempt(
                selectionSession.id(), UUID.randomUUID(), numbered.id(), "PHYSICS", "kk-KZ",
                "PHY-001", "release-1", from.plusSeconds(70)));
        assertThrows(SecurityException.class, () -> journeyStore.beginSelectedAttempt(
                selectionSession.id(), device, numbered.id(), "OLD_COLLECTION", "kk-KZ",
                "PHY-001", "release-1", from.plusSeconds(70)));
        var selectedAttempt = journeyStore.beginSelectedAttempt(
                selectionSession.id(), device, numbered.id(), "PHYSICS", "kk-KZ", "PHY-001", "release-1",
                from.plusSeconds(70));
        assertEquals(numbered.id(), selectedAttempt.studentId());
        assertFalse(journeyStore.assign(selectionSession.id(), classroom.id(), device, "CHEMISTRY"));
        assertEquals("PHYSICS", journeyStore.assignedCollection(selectionSession.id()));
        try (var db = DriverManager.getConnection(url, user, password);
             var stmt = db.prepareStatement(
                 "SELECT learner_locale,selected_lesson_id,selected_release_id " +
                 "FROM education.learning_attempt WHERE attempt_id=?")) {
            stmt.setObject(1, selectedAttempt.id());
            try (var r = stmt.executeQuery()) {
                assertTrue(r.next());
                assertEquals("kk-KZ", r.getString(1));
                assertEquals("PHY-001", r.getString(2));
                assertEquals("release-1", r.getString(3));
            }
        }
    }

    private record ApprovedDeviceFlow(UUID flowId, byte[] proof) {}
    private static ApprovedDeviceFlow approvedFlow(
            academy.vrtraining.platform.device.DeviceActivationService service, String tenant)
            throws Exception {
        var key=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var flow=service.begin(key.getPublic());
        service.approve("owner",tenant,flow.flowId(),flow.userCode());
        var signer=java.security.Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(academy.vrtraining.platform.device.DeviceActivationService.bindingMessage(
                flow.flowId(),flow.challenge()));
        return new ApprovedDeviceFlow(flow.flowId(),signer.sign());
    }
}

package academy.vrtraining.platform.content.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="VRTA_TEST_PG_JDBC_URL", matches=".+")
class PostgresContentCatalogStoreIntegrationTest {
    private final String url = System.getenv("VRTA_TEST_PG_JDBC_URL");
    private final String user = System.getenv("VRTA_TEST_PG_USER");
    private final String password = System.getenv("VRTA_TEST_PG_PASSWORD");

    private void migrate() throws Exception {
        String sql = Files.readString(Path.of("db/migrations/R5_1__content_catalog_foundation.sql"))
                .lines().map(line -> line.stripLeading().startsWith("--") ? "" : line)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        try (var db = DriverManager.getConnection(url, user, password);
             var stmt = db.createStatement()) {
            for (String part : sql.split(";")) if (!part.isBlank()) stmt.execute(part);
        }
    }

    private PostgresContentCatalogStore store() {
        return new PostgresContentCatalogStore(url, user, password);
    }

    @Test void exactSubjectPlacementPersistsAndWrongParentCannotBeForged() throws Exception {
        migrate();
        var store = store();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var world = store.createWorld("WORLD_SCHOOL_" + suffix, 10);
        var area = store.createArea(world.id(), "AREA_SCIENCE_" + suffix, 10);
        var physics = store.createSubject(area.id(), "SUBJECT_PHYSICS_" + suffix, 20);
        var chemistry = store.createSubject(area.id(), "SUBJECT_CHEM_" + suffix, 30);
        var lesson = store.createLesson("PHY_001_" + suffix, "oidc-author-" + suffix);
        var placement = store.placeLesson(lesson.id(), world.id(), area.id(), physics.id(), 5);

        var fetched = store().lessonsInSubject(world.id(), area.id(), physics.id());
        assertTrue(fetched.stream().anyMatch(l -> l.lessonId().equals(lesson.id())
                && l.placementId().equals(placement.id())));
        assertTrue(store.lessonsInSubject(world.id(), area.id(), chemistry.id()).stream()
                .noneMatch(l -> l.lessonId().equals(lesson.id())));

        // Multi-placement of same Lesson is allowed without duplicating identity.
        store.placeLesson(lesson.id(), world.id(), area.id(), chemistry.id(), 7);
        assertEquals(lesson.id(), store.lessonsInSubject(world.id(), area.id(), chemistry.id())
                .stream().filter(l -> l.lessonId().equals(lesson.id())).findFirst().orElseThrow().lessonId());
        assertThrows(IllegalArgumentException.class,
                () -> store.placeLesson(lesson.id(), world.id(), area.id(), physics.id(), 6),
                "Duplicate lesson in one subject is forbidden");

        var otherWorld = store.createWorld("WORLD_FACTORY_" + suffix, 20);
        var otherArea = store.createArea(otherWorld.id(), "AREA_SAFETY_" + suffix, 10);
        var otherSubject = store.createSubject(otherArea.id(), "SUBJECT_SAFETY_" + suffix, 10);
        assertThrows(IllegalArgumentException.class,
                () -> store.placeLesson(lesson.id(), world.id(), otherArea.id(), otherSubject.id(), 10),
                "World+Area must belong to same tree");
        assertThrows(IllegalArgumentException.class,
                () -> store.placeLesson(lesson.id(), world.id(), area.id(), otherSubject.id(), 10),
                "Area+Subject must belong to same tree");
        assertTrue(store.lessonsInSubject(world.id(), area.id(), otherSubject.id()).isEmpty());
    }

    @Test void databaseRejectsMissingNodesTranslationOrphansAndInvalidStatuses() throws Exception {
        migrate();
        var store = store();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var world = store.createWorld("WORLD_SAFE_" + suffix, 1);
        assertThrows(IllegalArgumentException.class,
                () -> store.createArea(UUID.randomUUID(), "AREA_UNKNOWN_" + suffix, 1));
        assertThrows(IllegalArgumentException.class,
                () -> store.createWorld("WORLD_SAFE_" + suffix, 2));
        assertThrows(IllegalArgumentException.class,
                () -> store.createWorld("..unsafe", 1));
        try (var db = DriverManager.getConnection(url, user, password)) {
            try (var stmt = db.prepareStatement(
                    "INSERT INTO content.world_translation(world_id,locale,title) VALUES (?,?,?)")) {
                stmt.setObject(1, UUID.randomUUID());
                stmt.setString(2, "ru-RU");
                stmt.setString(3, "Призрачный мир");
                assertThrows(SQLException.class, stmt::executeUpdate);
            }
            try (var stmt = db.prepareStatement(
                    "INSERT INTO content.world_translation(world_id,locale,title) VALUES (?,?,?)")) {
                stmt.setObject(1, world.id());
                stmt.setString(2, "ru-RU");
                stmt.setString(3, "Школа");
                assertEquals(1, stmt.executeUpdate());
            }
            try (var stmt = db.prepareStatement(
                    "UPDATE content.catalog_world SET status='INVALID' WHERE world_id=?")) {
                stmt.setObject(1, world.id());
                assertThrows(SQLException.class, stmt::executeUpdate);
            }
        }
    }

    @Test void atomicLessonCreationUnderSubjectRollsBackOnWrongTree() throws Exception {
        migrate();
        var store = store();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var world = store.createWorld("WORLD_ATOMIC_" + suffix, 1);
        var area = store.createArea(world.id(), "AREA_ATOMIC_" + suffix, 1);
        var subject = store.createSubject(area.id(), "SUBJECT_ATOMIC_" + suffix, 1);
        var unrelated = store.createWorld("WORLD_UNRELATED_" + suffix, 1);
        var saved = store.createLessonInSubject("LESSON_ATOMIC_" + suffix, "author",
                world.id(), area.id(), subject.id(), 10);
        assertEquals(1, store.lessonsInSubject(world.id(), area.id(), subject.id()).stream()
                .filter(x -> x.lessonId().equals(saved.lessonId())).count());
        String invalidCode = "LESSON_SHOULD_ROLLBACK_" + suffix;
        assertThrows(IllegalArgumentException.class, () -> store.createLessonInSubject(
                invalidCode, "author", unrelated.id(), area.id(), subject.id(), 10));
        try (var db = DriverManager.getConnection(url, user, password);
             var stmt = db.prepareStatement(
                     "SELECT count(*) FROM content.catalog_lesson WHERE code=?")) {
            stmt.setString(1, invalidCode);
            try (var rows = stmt.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "No orphan lesson on bad catalog placement");
            }
        }
    }

    @Test void concurrentPlacementsHaveExactlyOneWinner() throws Exception {
        migrate();
        var store = store();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var w = store.createWorld("WORLD_RACE_" + suffix, 1);
        var a = store.createArea(w.id(), "AREA_RACE_" + suffix, 1);
        var s = store.createSubject(a.id(), "SUBJECT_RACE_" + suffix, 1);
        var l = store.createLesson("LESSON_RACE_" + suffix, "author");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> action = () -> {
                latch.await();
                try {
                    store.placeLesson(l.id(), w.id(), a.id(), s.id(), 1);
                    return true;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            latch.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
            assertEquals(1, store.lessonsInSubject(w.id(), a.id(), s.id()).stream()
                    .filter(item -> item.lessonId().equals(l.id())).count());
        } finally { executor.shutdownNow(); }
    }
}

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
        sql = sql.lines().map(line -> line.stripLeading().startsWith("--") ? "" : line)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
        try (var db = DriverManager.getConnection(url, user, password);
             var statement = db.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
        var store = new PostgresClassroomStore(url, user, password);
        var classroom = store.createClassroom("school-1", "7А", "teacher-1");
        var student = store.createStudent(classroom.id(), "Айша");
        assertThrows(IllegalStateException.class,
                () -> store.createStudent(classroom.id(), "АЙША"));
        assertEquals(1, store.students(classroom.id()).size());

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
    }
}

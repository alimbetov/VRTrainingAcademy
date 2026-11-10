package academy.vrtraining.education.classroom;

import academy.vrtraining.education.classroom.ClassroomContracts.Attempt;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * PostgreSQL reference adapter for the number/language/lesson journey.
 * No HTTP device endpoint is published until trusted device PoP is available.
 * Transactional insertion re-checks membership, device, classroom assignment
 * and lesson revision, using only bound SQL parameters.
 */
public final class PostgresLearnerJourneyRepository implements NumberLanguageLessonJourney.Repository {
    private final String jdbcUrl, user, password;

    public PostgresLearnerJourneyRepository(String jdbcUrl, String user, String password) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL required");
        this.jdbcUrl = jdbcUrl;
        this.user = Objects.requireNonNull(user);
        this.password = Objects.requireNonNull(password);
    }

    private java.sql.Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    @Override public UUID studentByNumber(UUID classroomId, int number) {
        DailyStudentNumberPolicy.requireNumber(number);
        try (var db = connection();
             var stmt = db.prepareStatement(
                "SELECT student_id FROM education.student_profile " +
                "WHERE classroom_id=? AND student_number=? AND active=true")) {
            stmt.setObject(1, classroomId);
            stmt.setInt(2, number);
            try (var rows = stmt.executeQuery()) {
                return rows.next() ? (UUID) rows.getObject(1) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("Student number lookup failed", e); }
    }

    @Override public String assignedCollection(UUID sessionId) {
        try (var db = connection();
             var stmt = db.prepareStatement(
                 "SELECT collection_id FROM education.classroom_session WHERE session_id=? AND active=true")) {
            stmt.setObject(1, sessionId);
            try (var rows = stmt.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException e) { throw new IllegalStateException("Collection lookup failed", e); }
    }

    @Override public Attempt beginSelectedAttempt(UUID sessionId, UUID deviceId, UUID studentId,
                                                  String locale, String lessonId, String releaseId,
                                                  Instant now) {
        DeviceLearningSelectionPolicy.requireLocale(locale);
        UUID attemptId = UUID.randomUUID();
        try (var db = connection();
             var stmt = db.prepareStatement(
                "INSERT INTO education.learning_attempt(" +
                "attempt_id,session_id,student_id,device_id,lesson_revision,started_at," +
                "learner_locale,selected_lesson_id,selected_release_id) " +
                "SELECT ?,s.session_id,p.student_id,?,s.lesson_revision,?,?,?,? " +
                "FROM education.classroom_session s " +
                "JOIN education.student_profile p ON p.classroom_id=s.classroom_id " +
                "WHERE s.session_id=? AND s.device_id=? AND s.active=true " +
                "AND s.collection_id IS NOT NULL " +
                "AND p.student_id=? AND p.active=true AND p.student_number IS NOT NULL " +
                "AND ? >= s.begins_at AND ? < s.ends_at")) {
            stmt.setObject(1, attemptId);
            stmt.setObject(2, deviceId);
            stmt.setTimestamp(3, Timestamp.from(now));
            stmt.setString(4, locale);
            stmt.setString(5, lessonId);
            stmt.setString(6, releaseId);
            stmt.setObject(7, sessionId);
            stmt.setObject(8, deviceId);
            stmt.setObject(9, studentId);
            stmt.setTimestamp(10, Timestamp.from(now));
            stmt.setTimestamp(11, Timestamp.from(now));
            if (stmt.executeUpdate() != 1)
                throw new SecurityException("Session or student not authorized for lesson");
            return new Attempt(attemptId, sessionId, studentId, deviceId,
                    now, lessonRevision(sessionId));
        } catch (SQLException e) { throw new IllegalStateException("Selected attempt insertion failed", e); }
    }

    private String lessonRevision(UUID sessionId) {
        try (var db = connection();
             var stmt = db.prepareStatement(
                 "SELECT lesson_revision FROM education.classroom_session WHERE session_id=?")) {
            stmt.setObject(1, sessionId);
            try (var rows = stmt.executeQuery()) {
                if (!rows.next()) throw new SecurityException("Unknown session");
                return rows.getString(1);
            }
        } catch (SQLException e) { throw new IllegalStateException("Lesson revision lookup failed", e); }
    }
}

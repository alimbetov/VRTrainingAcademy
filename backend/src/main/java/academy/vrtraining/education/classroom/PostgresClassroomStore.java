package academy.vrtraining.education.classroom;

import academy.vrtraining.education.classroom.ClassroomContracts.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional PostgreSQL reference adapter; never enabled by default.
 * All queries use prepared parameters. Schema must be migrated beforehand.
 * Production should replace per-operation DriverManager with a bounded pool.
 */
public final class PostgresClassroomStore implements ClassroomStore {
    private final String url;
    private final String username;
    private final String password;
    private final java.time.ZoneId schoolTimezone;

    public PostgresClassroomStore(String url, String username, String password) {
        this(url, username, password, java.time.ZoneId.of("Asia/Almaty"));
    }

    public PostgresClassroomStore(String url, String username, String password,
                                 java.time.ZoneId schoolTimezone) {
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL JDBC URL required");
        this.url = url;
        this.username = java.util.Objects.requireNonNull(username);
        this.password = java.util.Objects.requireNonNull(password);
        this.schoolTimezone = java.util.Objects.requireNonNull(schoolTimezone);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private static RuntimeException failure(SQLException e) {
        return new IllegalStateException("Classroom persistence operation failed", e);
    }

    @Override public Classroom createClassroom(String tenant, String title, String teacher) {
        UUID id = UUID.randomUUID();
        try (var db = connect();
             var stmt = db.prepareStatement(
                "INSERT INTO education.classroom(classroom_id,tenant_id,teacher_subject,title) VALUES(?,?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setString(2, tenant);
            stmt.setString(3, teacher);
            stmt.setString(4, title);
            stmt.executeUpdate();
            return new Classroom(id, tenant, title, teacher);
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Optional<Classroom> findClassroom(UUID id) {
        try (var db = connect();
             var stmt = db.prepareStatement(
                "SELECT classroom_id,tenant_id,title,teacher_subject FROM education.classroom WHERE classroom_id=?")) {
            stmt.setObject(1, id);
            try (var rows = stmt.executeQuery()) {
                return rows.next() ? Optional.of(new Classroom(
                    (UUID) rows.getObject(1), rows.getString(2),
                    rows.getString(3), rows.getString(4))) : Optional.empty();
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Student createStudent(UUID classroomId, String nickname) {
        UUID id = UUID.randomUUID();
        String normalized = Normalizer.normalize(nickname.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        if (normalized.length() > 40) throw new IllegalArgumentException("Nickname too long after normalization");
        try (var db = connect();
             var stmt = db.prepareStatement(
                "INSERT INTO education.student_profile(student_id,classroom_id,nickname,normalized_nickname) VALUES(?,?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setObject(2, classroomId);
            stmt.setString(3, nickname.strip());
            stmt.setString(4, normalized);
            stmt.executeUpdate();
            return new Student(id, classroomId, nickname.strip(), true);
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public NumberedStudent assignNumber(UUID classroomId, int number) {
        DailyStudentNumberPolicy.requireNumber(number);
        UUID id = UUID.randomUUID();
        try (var db = connect();
             var stmt = db.prepareStatement(
                "INSERT INTO education.student_profile(student_id,classroom_id,student_number,nickname,normalized_nickname) VALUES(?,?,?,NULL,NULL)")) {
            stmt.setObject(1, id);
            stmt.setObject(2, classroomId);
            stmt.setInt(3, number);
            stmt.executeUpdate();
            return new NumberedStudent(id, classroomId, number, true);
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public List<Student> students(UUID classroomId) {
        try (var db = connect();
             var stmt = db.prepareStatement(
                "SELECT student_id,classroom_id,COALESCE(student_number::text,nickname),active FROM education.student_profile WHERE classroom_id=? ORDER BY student_number,normalized_nickname")) {
            stmt.setObject(1, classroomId);
            try (var rows = stmt.executeQuery()) {
                var result = new ArrayList<Student>();
                while (rows.next()) result.add(new Student((UUID) rows.getObject(1),
                        (UUID) rows.getObject(2), rows.getString(3), rows.getBoolean(4)));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Session createSession(UUID classroomId, UUID deviceId, String lessonId,
                                           String revision, Instant startsAt, Instant endsAt) {
        UUID id = UUID.randomUUID();
        // Only an already registered/authorized device may be passed by the service.
        try (var db = connect();
             var stmt = db.prepareStatement(
                 "INSERT INTO education.classroom_session(session_id,classroom_id,device_id,lesson_id,lesson_revision,begins_at,ends_at,created_by_subject,school_timezone,teaching_date) " +
                 "SELECT ?,classroom_id,?,?,?,?,?,teacher_subject,?,? FROM education.classroom WHERE classroom_id=?")) {
            stmt.setObject(1, id);
            stmt.setObject(2, deviceId);
            stmt.setString(3, lessonId);
            stmt.setString(4, revision);
            stmt.setTimestamp(5, Timestamp.from(startsAt));
            stmt.setTimestamp(6, Timestamp.from(endsAt));
            stmt.setString(7, schoolTimezone.getId());
            stmt.setObject(8, DailyStudentNumberPolicy.teachingDate(startsAt, schoolTimezone));
            stmt.setObject(9, classroomId);
            if (stmt.executeUpdate() != 1) throw new IllegalStateException("Unknown classroom");
            return new Session(id, classroomId, deviceId, lessonId, revision, startsAt, endsAt, true);
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Optional<Session> findSession(UUID id) {
        try (var db = connect();
             var stmt = db.prepareStatement(
                "SELECT session_id,classroom_id,device_id,lesson_id,lesson_revision,begins_at,ends_at,active " +
                "FROM education.classroom_session WHERE session_id=?")) {
            stmt.setObject(1, id);
            try (var r = stmt.executeQuery()) {
                return r.next() ? Optional.of(new Session(
                    (UUID) r.getObject(1), (UUID) r.getObject(2),
                    (UUID) r.getObject(3), r.getString(4), r.getString(5),
                    r.getTimestamp(6).toInstant(), r.getTimestamp(7).toInstant(),
                    r.getBoolean(8))) : Optional.empty();
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Attempt beginAttempt(UUID sessionId, UUID studentId, UUID deviceId, Instant now) {
        UUID id = UUID.randomUUID();
        // DB consistency: student must be a member of the same classroom as the session.
        try (var db = connect();
             var stmt = db.prepareStatement(
                "INSERT INTO education.learning_attempt(attempt_id,session_id,student_id,device_id,lesson_revision,started_at) " +
                "SELECT ?,s.session_id,p.student_id,?,s.lesson_revision,? " +
                "FROM education.classroom_session s " +
                "JOIN education.student_profile p ON p.classroom_id=s.classroom_id " +
                "WHERE s.session_id=? AND s.device_id=? AND s.active=true AND p.student_id=? AND p.active=true " +
                "AND ? >= s.begins_at AND ? < s.ends_at")) {
            stmt.setObject(1, id);
            stmt.setObject(2, deviceId);
            stmt.setTimestamp(3, Timestamp.from(now));
            stmt.setObject(4, sessionId);
            stmt.setObject(5, deviceId);
            stmt.setObject(6, studentId);
            stmt.setTimestamp(7, Timestamp.from(now));
            stmt.setTimestamp(8, Timestamp.from(now));
            if (stmt.executeUpdate() != 1) throw new SecurityException("Invalid session/student/device");
            var session = findSession(sessionId).orElseThrow();
            return new Attempt(id, sessionId, studentId, deviceId, now, session.lessonRevision());
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public Optional<Attempt> findAttempt(UUID id) {
        try (var db = connect();
             var stmt = db.prepareStatement(
                "SELECT attempt_id,session_id,student_id,device_id,started_at,lesson_revision " +
                "FROM education.learning_attempt WHERE attempt_id=?")) {
            stmt.setObject(1, id);
            try (var r = stmt.executeQuery()) {
                return r.next() ? Optional.of(new Attempt(
                    (UUID) r.getObject(1), (UUID) r.getObject(2),
                    (UUID) r.getObject(3), (UUID) r.getObject(4),
                    r.getTimestamp(5).toInstant(), r.getString(6))) : Optional.empty();
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public boolean appendEvent(ProgressEvent event) {
        try (var db = connect()) {
            db.setAutoCommit(false);
            try {
                try (var stmt = db.prepareStatement(
                    "INSERT INTO education.learning_event(event_id,attempt_id,sequence_number,event_type,score_delta,occurred_at) " +
                    "VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                    stmt.setObject(1, event.eventId());
                    stmt.setObject(2, event.attemptId());
                    stmt.setInt(3, event.sequence());
                    stmt.setString(4, event.eventType());
                    stmt.setInt(5, event.scoreDelta());
                    stmt.setTimestamp(6, Timestamp.from(event.occurredAt()));
                    if (stmt.executeUpdate() == 1) {
                        db.commit();
                        return true;
                    }
                }
                // Distinguish harmless retry from event-id or sequence collision.
                try (var stmt = db.prepareStatement(
                    "SELECT attempt_id,sequence_number,event_type,score_delta,occurred_at " +
                    "FROM education.learning_event WHERE event_id=?")) {
                    stmt.setObject(1, event.eventId());
                    try (var r = stmt.executeQuery()) {
                        if (!r.next() || !((UUID)r.getObject(1)).equals(event.attemptId())
                                || r.getInt(2) != event.sequence()
                                || !r.getString(3).equals(event.eventType())
                                || r.getInt(4) != event.scoreDelta()
                                || !r.getTimestamp(5).toInstant().equals(event.occurredAt()))
                            throw new IllegalStateException("Conflicting event ID or sequence");
                    }
                }
                db.commit();
                return false;
            } catch (Exception e) {
                db.rollback();
                throw e;
            }
        } catch (SQLException e) { throw failure(e); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Event transaction failed", e); }
    }

    @Override public List<Score> scoresForDay(UUID classroomId, java.time.LocalDate day) {
        try (var db = connect();
             var stmt = db.prepareStatement(
                "SELECT a.student_id,a.attempt_id,COALESCE(SUM(e.score_delta),0)::integer," +
                "COUNT(e.event_id)::integer,COALESCE(MAX(e.occurred_at),a.started_at) " +
                "FROM education.classroom_session s " +
                "JOIN education.learning_attempt a ON a.session_id=s.session_id " +
                "LEFT JOIN education.learning_event e ON e.attempt_id=a.attempt_id " +
                "WHERE s.classroom_id=? AND s.teaching_date=? " +
                "GROUP BY a.student_id,a.attempt_id,a.started_at ORDER BY a.started_at")) {
            stmt.setObject(1, classroomId);
            stmt.setObject(2, day);
            try (var r = stmt.executeQuery()) {
                var result = new ArrayList<Score>();
                while (r.next()) result.add(new Score((UUID) r.getObject(1),
                        (UUID) r.getObject(2), r.getInt(3), r.getInt(4),
                        r.getTimestamp(5).toInstant()));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure(e); }
    }

    @Override public List<Score> scores(UUID sessionId) {
        try (var db = connect();
             var stmt = db.prepareStatement(
                "SELECT a.student_id,a.attempt_id,COALESCE(SUM(e.score_delta),0)::integer," +
                "COUNT(e.event_id)::integer,COALESCE(MAX(e.occurred_at),a.started_at) " +
                "FROM education.learning_attempt a LEFT JOIN education.learning_event e ON e.attempt_id=a.attempt_id " +
                "WHERE a.session_id=? GROUP BY a.student_id,a.attempt_id,a.started_at")) {
            stmt.setObject(1, sessionId);
            try (var r = stmt.executeQuery()) {
                var result = new ArrayList<Score>();
                while (r.next()) result.add(new Score((UUID) r.getObject(1),
                        (UUID) r.getObject(2), r.getInt(3), r.getInt(4),
                        r.getTimestamp(5).toInstant()));
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw failure(e); }
    }
}

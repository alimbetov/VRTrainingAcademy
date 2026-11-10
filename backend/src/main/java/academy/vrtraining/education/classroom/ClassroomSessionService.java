package academy.vrtraining.education.classroom;

import academy.vrtraining.education.classroom.ClassroomContracts.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain service. Nicknames select a participant, they DO NOT authenticate a
 * learner. High-stakes assessments need teacher confirmation/PIN.
 */
public final class ClassroomSessionService {
    private final ClassroomStore store;
    private final ClassroomAuthorization auth;
    private final Clock clock;
    private final java.time.ZoneId schoolTimezone;

    public ClassroomSessionService(ClassroomStore store, ClassroomAuthorization auth, Clock clock) {
        this(store, auth, clock, java.time.ZoneId.of("Asia/Almaty"));
    }

    public ClassroomSessionService(ClassroomStore store, ClassroomAuthorization auth,
                                   Clock clock, java.time.ZoneId schoolTimezone) {
        this.store = Objects.requireNonNull(store);
        this.auth = Objects.requireNonNull(auth);
        this.clock = Objects.requireNonNull(clock);
        this.schoolTimezone = Objects.requireNonNull(schoolTimezone);
    }

    public Classroom createClassroom(String tenantId, String teacherSubject, String title) {
        requireId(tenantId);
        requireId(teacherSubject);
        if (!auth.teacherMayCreateClassroom(teacherSubject, tenantId))
            throw new SecurityException("Teacher not authorized to create classroom");
        if (title == null || title.isBlank() || title.length() > 120)
            throw new IllegalArgumentException("Invalid classroom title");
        return store.createClassroom(tenantId, title.trim(), teacherSubject);
    }

    public NumberedStudent assignNumber(String tenantId, String teacher, UUID classroomId, int number) {
        classroomForTeacher(tenantId, teacher, classroomId);
        return store.assignNumber(classroomId, DailyStudentNumberPolicy.requireNumber(number));
    }

    public Student addStudent(String tenantId, String teacher, UUID classroomId, String nickname) {
        classroomForTeacher(tenantId, teacher, classroomId);
        if (nickname == null || nickname.isBlank() || nickname.length() > 40
                || nickname.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid nickname");
        return store.createStudent(classroomId, nickname.strip());
    }

    public Session schedule(String tenantId, String teacher, UUID classroomId, UUID deviceId,
                            String lessonId, String revision, Instant begins, Instant ends) {
        classroomForTeacher(tenantId, teacher, classroomId);
        Objects.requireNonNull(deviceId);
        requireId(lessonId);
        requireId(revision);
        Objects.requireNonNull(begins);
        Objects.requireNonNull(ends);
        if (!ends.isAfter(begins) || Duration.between(begins, ends).compareTo(Duration.ofHours(8)) > 0)
            throw new IllegalArgumentException("Invalid session window");
        return store.createSession(classroomId, deviceId, lessonId, revision, begins, ends);
    }

    public DeviceClassroomView roster(String tenantId, UUID authenticatedDeviceId, UUID sessionId) {
        Session session = authorizedSession(tenantId, authenticatedDeviceId, sessionId);
        var people = store.students(session.classroomId()).stream()
                .filter(Student::active)
                .map(s -> new Participant(s.id(), s.nickname())).toList();
        return new DeviceClassroomView(session, people);
    }

    public Attempt startAttempt(String tenantId, UUID authenticatedDeviceId, Selection selection) {
        Session session = authorizedSession(tenantId, authenticatedDeviceId, selection.sessionId());
        Instant now = clock.instant();
        if (!session.active() || now.isBefore(session.startsAt()) || !now.isBefore(session.endsAt()))
            throw new SecurityException("Session is not active");
        if (!session.deviceId().equals(selection.deviceId()))
            throw new SecurityException("Device mismatch");
        if (store.students(session.classroomId()).stream()
                .noneMatch(s -> s.active() && s.id().equals(selection.studentId())))
            throw new SecurityException("Student is not in session classroom");
        return store.beginAttempt(session.id(), selection.studentId(), authenticatedDeviceId, now);
    }

    /** This MVP receives events only for an attempt bound to the authenticated device. */
    public ProgressReceipt record(String tenantId, UUID authenticatedDeviceId, ProgressEvent event) {
        if (event == null || event.sequence() < 0 || event.sequence() > 1_000_000
                || event.eventType() == null || !event.eventType().matches("[A-Z_]{2,40}")
                || event.scoreDelta() < 0 || event.scoreDelta() > 100)
            throw new IllegalArgumentException("Invalid progress event");
        Attempt attempt = store.findAttempt(event.attemptId())
                .orElseThrow(() -> new SecurityException("Unknown attempt"));
        Session session = authorizedSession(tenantId, authenticatedDeviceId, attempt.sessionId());
        if (!attempt.deviceId().equals(authenticatedDeviceId)
                || !attempt.lessonRevision().equals(session.lessonRevision()))
            throw new SecurityException("Attempt scope mismatch");
        if (event.occurredAt() == null || event.occurredAt().isBefore(attempt.startedAt())
                || event.occurredAt().isAfter(session.endsAt().plus(Duration.ofHours(24))))
            throw new IllegalArgumentException("Invalid event timestamp");
        return new ProgressReceipt(event.eventId(), store.appendEvent(event));
    }

    public List<Score> statisticsToday(String tenantId, String teacher, UUID classroomId) {
        classroomForTeacher(tenantId, teacher, classroomId);
        return store.scoresForDay(classroomId,
                DailyStudentNumberPolicy.teachingDate(clock.instant(), schoolTimezone));
    }

    public List<Score> statistics(String tenantId, String teacher, UUID classroomId, UUID sessionId) {
        classroomForTeacher(tenantId, teacher, classroomId);
        Session session = store.findSession(sessionId).orElseThrow(() ->
                new SecurityException("Unknown session"));
        if (!session.classroomId().equals(classroomId))
            throw new SecurityException("Session classroom mismatch");
        return store.scores(sessionId);
    }

    private Classroom classroomForTeacher(String tenantId, String teacher, UUID classroomId) {
        Classroom classroom = store.findClassroom(classroomId)
                .orElseThrow(() -> new SecurityException("Classroom unavailable"));
        if (!classroom.tenantId().equals(tenantId)
                || !auth.teacherMayManage(teacher, tenantId, classroomId))
            throw new SecurityException("Not authorized for classroom");
        return classroom;
    }

    private Session authorizedSession(String tenantId, UUID deviceId, UUID sessionId) {
        Session session = store.findSession(sessionId)
                .orElseThrow(() -> new SecurityException("Unknown session"));
        Classroom classroom = store.findClassroom(session.classroomId())
                .orElseThrow(() -> new SecurityException("Unknown classroom"));
        if (!classroom.tenantId().equals(tenantId)
                || !session.deviceId().equals(deviceId)
                || !auth.deviceMayAttend(deviceId, tenantId, sessionId))
            throw new SecurityException("Device not authorized");
        return session;
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid identifier");
    }
}

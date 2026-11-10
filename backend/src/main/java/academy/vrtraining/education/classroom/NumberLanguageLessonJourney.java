package academy.vrtraining.education.classroom;

import academy.vrtraining.education.classroom.ClassroomContracts.*;
import academy.vrtraining.education.classroom.DeviceLearningSelectionPolicy.*;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Number -> language -> authorized lesson -> attempt. This application
 * service requires the caller to have independently authenticated the device.
 * No public REST device endpoint is exposed until proof of possession exists.
 */
public final class NumberLanguageLessonJourney {
    public interface Repository {
        /** Scope by classroom, active numeric slot and trusted tenant. */
        UUID studentByNumber(UUID classroomId, int number);
        /** Collection was assigned by authorized teacher; no client overrides. */
        String assignedCollection(UUID sessionId);
        /** Atomic insert, checking active device/session/student membership. */
        Attempt beginSelectedAttempt(UUID sessionId, UUID deviceId, UUID studentId,
                                     String collectionId, String locale, String lessonId, String releaseId,
                                     java.time.Instant now);
    }

    public record LessonMenu(UUID sessionId, int studentNumber, String locale,
                             List<LessonChoice> lessons) {}

    public record Start(UUID sessionId, int studentNumber, String locale, String lessonId) {}

    private final ClassroomStore classrooms;
    private final ClassroomAuthorization authorization;
    private final AuthorizedLessonCatalog catalog;
    private final Repository repository;
    private final Clock clock;

    public NumberLanguageLessonJourney(ClassroomStore classrooms, ClassroomAuthorization authorization,
                                       AuthorizedLessonCatalog catalog, Repository repository, Clock clock) {
        this.classrooms = Objects.requireNonNull(classrooms);
        this.authorization = Objects.requireNonNull(authorization);
        this.catalog = Objects.requireNonNull(catalog);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public LessonMenu lessons(String tenantId, UUID trustedDeviceId, UUID sessionId,
                              int number, String locale) {
        var context = context(tenantId, trustedDeviceId, sessionId, number);
        DeviceLearningSelectionPolicy.requireLocale(locale);
        String collection = repository.assignedCollection(sessionId);
        if (collection == null || collection.isBlank()) throw new SecurityException("No assigned collection");
        var candidates = catalog.eligibleLessons(tenantId, trustedDeviceId, collection, locale);
        return new LessonMenu(sessionId, number, locale,
                DeviceLearningSelectionPolicy.visibleLessons(locale, collection, candidates));
    }

    public Attempt start(String tenantId, UUID trustedDeviceId, Start request) {
        Objects.requireNonNull(request);
        var context = context(tenantId, trustedDeviceId, request.sessionId(), request.studentNumber());
        var now = clock.instant();
        if (!context.session.active() || now.isBefore(context.session.startsAt())
                || !now.isBefore(context.session.endsAt()))
            throw new SecurityException("Session expired or inactive");
        String collection = repository.assignedCollection(request.sessionId());
        if (collection == null || collection.isBlank()) throw new SecurityException("No assigned collection");
        var permitted = catalog.eligibleLessons(tenantId, trustedDeviceId, collection, request.locale());
        var lesson = DeviceLearningSelectionPolicy.requireLesson(request.locale(), collection,
                permitted, request.lessonId());
        return repository.beginSelectedAttempt(request.sessionId(), trustedDeviceId,
                context.studentId(), collection, request.locale(), lesson.lessonId(), lesson.releaseId(), now);
    }

    private Context context(String tenantId, UUID deviceId, UUID sessionId, int studentNumber) {
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(sessionId);
        DailyStudentNumberPolicy.requireNumber(studentNumber);
        Session session = classrooms.findSession(sessionId)
                .orElseThrow(() -> new SecurityException("Unknown session"));
        Classroom classroom = classrooms.findClassroom(session.classroomId())
                .orElseThrow(() -> new SecurityException("Unknown classroom"));
        if (!classroom.tenantId().equals(tenantId) || !session.deviceId().equals(deviceId)
                || !authorization.deviceMayAttend(deviceId, tenantId, sessionId))
            throw new SecurityException("Device/tenant not authorized");
        UUID student = repository.studentByNumber(session.classroomId(), studentNumber);
        if (student == null) throw new SecurityException("Number not assigned in classroom");
        return new Context(session, student);
    }

    private record Context(Session session, UUID studentId) {}
}

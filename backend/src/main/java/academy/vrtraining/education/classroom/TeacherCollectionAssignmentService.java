package academy.vrtraining.education.classroom;

import java.util.Objects;
import java.util.UUID;

/** Assign a published entitled collection; no learner can override it. */
public final class TeacherCollectionAssignmentService {
    public interface AssignmentStore {
        boolean assign(UUID sessionId, UUID classroomId, UUID deviceId, String collectionId);
    }

    private final ClassroomStore classrooms;
    private final ClassroomAuthorization authority;
    private final AuthorizedCollectionAccess collections;
    private final AssignmentStore assignments;

    public TeacherCollectionAssignmentService(ClassroomStore classrooms,
                                              ClassroomAuthorization authority,
                                              AuthorizedCollectionAccess collections,
                                              AssignmentStore assignments) {
        this.classrooms = Objects.requireNonNull(classrooms);
        this.authority = Objects.requireNonNull(authority);
        this.collections = Objects.requireNonNull(collections);
        this.assignments = Objects.requireNonNull(assignments);
    }

    public void assign(String tenantId, String teacherSubject, UUID classroomId,
                       UUID sessionId, String collectionId) {
        if (collectionId == null || !collectionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid collection ID");
        var classroom = classrooms.findClassroom(classroomId).orElseThrow(() ->
                new SecurityException("Classroom unavailable"));
        if (!classroom.tenantId().equals(tenantId)
                || !authority.teacherMayManage(teacherSubject, tenantId, classroomId))
            throw new SecurityException("Teacher not authorized");
        var session = classrooms.findSession(sessionId).orElseThrow(() ->
                new SecurityException("Session unavailable"));
        if (!session.classroomId().equals(classroomId) || !session.active())
            throw new SecurityException("Session not in classroom");
        if (!collections.mayAssign(tenantId, session.deviceId(), collectionId))
            throw new SecurityException("Collection unpublished, unlicensed or incompatible");
        if (!assignments.assign(sessionId, classroomId, session.deviceId(), collectionId))
            throw new SecurityException("Concurrent session change");
    }
}

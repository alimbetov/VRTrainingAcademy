package academy.vrtraining.platform.content.catalog;

import java.util.List;
import java.util.UUID;

/** R5.1 write boundary: trusted caller must authorize actions before invoking. */
public interface ContentCatalogStore {
    record Node(UUID id, UUID parentId, String code, String status, int sortOrder) {}
    record Lesson(UUID id, String code, String state, String authorSubject) {}
    record Placement(UUID id, UUID lessonId, UUID worldId, UUID areaId,
                     UUID subjectId, int sortOrder) {}
    record SubjectLesson(UUID lessonId, String code, String state, UUID placementId,
                         int sortOrder) {}

    Node createWorld(String code, int sortOrder);
    Node createArea(UUID worldId, String code, int sortOrder);
    Node createSubject(UUID areaId, String code, int sortOrder);
    Lesson createLesson(String code, String trustedAuthorSubject);
    Placement placeLesson(UUID lessonId, UUID worldId, UUID areaId,
                          UUID subjectId, int sortOrder);
    List<SubjectLesson> lessonsInSubject(UUID worldId, UUID areaId, UUID subjectId);
}

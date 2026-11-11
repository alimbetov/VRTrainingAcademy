package academy.vrtraining.platform.content.catalog;

import java.util.List;
import java.util.UUID;

/** R5.1 write boundary: trusted caller must authorize actions before invoking. */
public interface ContentCatalogStore {
    enum NodeKind { WORLD, AREA, SUBJECT, LESSON }
    record Translation(String locale, String title, String description) {}
    record Node(UUID id, UUID parentId, String code, String status, int sortOrder) {}
    record Lesson(UUID id, String code, String state, String authorSubject) {}
    record Placement(UUID id, UUID lessonId, UUID worldId, UUID areaId,
                     UUID subjectId, int sortOrder) {}
    record SubjectLesson(UUID lessonId, String code, String state, UUID placementId,
                         int sortOrder) {}

    void upsertTranslation(NodeKind kind, UUID nodeId, String locale, String title, String description);
    List<Translation> translations(NodeKind kind, UUID nodeId);
    Node createWorld(String code, int sortOrder);
    Node createArea(UUID worldId, String code, int sortOrder);
    Node createSubject(UUID areaId, String code, int sortOrder);
    Lesson createLesson(String code, String trustedAuthorSubject);
    /** Atomically creates a lesson in one exact World→Area→Subject path. */
    Placement createLessonInSubject(String code, String trustedAuthorSubject,
                                   UUID worldId, UUID areaId, UUID subjectId, int sortOrder);
    Placement placeLesson(UUID lessonId, UUID worldId, UUID areaId,
                          UUID subjectId, int sortOrder);
    List<SubjectLesson> lessonsInSubject(UUID worldId, UUID areaId, UUID subjectId);
}

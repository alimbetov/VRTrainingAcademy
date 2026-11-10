package academy.vrtraining.education.classroom;

import java.util.List;
import java.util.UUID;
import academy.vrtraining.education.classroom.DeviceLearningSelectionPolicy.LessonChoice;

/**
 * Trusted Content Domain boundary, NOT the public demo catalog.
 * Implementors must scope to tenant, registered device, assigned classroom
 * collection and platform/runtime before returning results.
 */
public interface AuthorizedLessonCatalog {
    List<LessonChoice> eligibleLessons(String tenantId, UUID registeredDeviceId,
                                       String collectionId, String locale);
}

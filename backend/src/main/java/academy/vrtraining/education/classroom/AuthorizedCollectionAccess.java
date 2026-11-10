package academy.vrtraining.education.classroom;

import java.util.UUID;

/**
 * Content Domain authority for assigning a collection to a classroom device.
 * Must enforce tenant licensing, published state, platform and runtime.
 */
@FunctionalInterface
public interface AuthorizedCollectionAccess {
    boolean mayAssign(String tenantId, UUID registeredDeviceId, String collectionId);
}

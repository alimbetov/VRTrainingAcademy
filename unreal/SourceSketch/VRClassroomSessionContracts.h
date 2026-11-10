// Non-compiling Unreal architecture sketch, NOT production UCLASS/USTRUCT.
// Native implementation is deferred until the .uproject exists.
// Keep source of truth in versioned API schemas + signed permit golden vectors.
#pragma once

// FGuid SessionId;
// FGuid DeviceId;
// FGuid StudentId; // Never use Nickname as persistence key.
// FString Nickname; // Display only, not learner authentication.
// FString LessonId, LessonRevision;
// FDateTime NotBefore, ExpiresAt;
//
// IVRClassroomClient responsibilities:
//   FetchSignedSessionRoster(AuthenticatedDeviceCredential);
//   VerifyOfflinePermit(PinnedPublisherKey, LocalDeviceKey, DeviceId, TenantId);
//   SelectStudent(StudentId) -> CreateAttemptBoundToDeviceAndSession;
//   RecordEvent(EventId, AttemptId, Sequence, EventType, CanonicalPayload);
//   PersistOutboxTransactionallyBeforeShowingSuccess;
//   RetryEventsUntilServerAcknowledgesEventId;
//   ClearCurrentLearnerAfterSessionEndWithoutDeletingInstalledContent;
//   Never accept server-supplied executable native content.
//   Never treat a nickname as proof of learner identity.

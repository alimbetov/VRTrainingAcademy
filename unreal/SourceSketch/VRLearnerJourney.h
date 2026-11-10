// Conceptual Unreal runtime UI contract, not compiled UE code.
// The repository does not yet contain a .uproject.
#pragma once

// EStudentJourneyScreen:
//   STUDENT_NUMBER -> LANGUAGE -> LESSON_LIST -> LESSON_RUNNING.
//
// EnterNumber(100..9999):
//   Must resolve a roster slot within a teacher-authorized DailySession.
// ChooseLanguage(kk-KZ|ru-RU|en-US):
//   A per-learner selection, never persisted globally on shared Quest.
// FetchLessonList:
//   Only server-authorized published + entitled lessons in assigned collection.
// ChooseLesson(lessonId):
//   Server REVALIDATES selection and issues an attempt bound to device,
//   numeric student UUID, session ID, locale, immutable lesson/release.
// StartLesson:
//   Obtain R2 Download Grant if required, cryptographically verify installed
//   content, then mount the signed release.
// EndOrSwitchStudent:
//   Flush authenticated event outbox, clear learner's transient context;
//   preserve the device's shared verified content cache.
//
// Security: never trust student number as proof of identity. Neither UI
// fixture nor public R0 demo catalog is an authorization authority.

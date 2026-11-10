# DEC-EDU-04 — Language at Quest activation, collection selection from list

Accepted product flow, 2026-11-10.

## User journey
1. Quest launches VRTrainingAcademy and displays a short activation code.
2. On the teacher's website, an authorized administrator signs in, confirms
   the device code, binds the Quest to a school and **chooses interface language**:
   Kazakh (kk-KZ), Russian (ru-RU), or English (en-US).
3. Backend persists the chosen locale in the registered device record.
4. On restart, Quest reads its authorized device configuration, applying
   interface localization; cached locale is available offline.
5. After student enters a **numeric student number** (no mandatory name),
   the application shows the **collection list** filtered for that school,
   this device, language, platform/runtime and published entitlements.
6. Student/teacher selects a collection; the selected release is retrieved
   through the existing R2 Download Grant, verified and installed; the lesson
   is then opened in a school-local Daily Classroom Session.
7. Teacher sees **today's** stats by number; prior days are kept for up to
   30 days subject to offline acknowledgments/holds.

## Decision
- Device locale is a **default device preference**, not part of the device
  identity or cryptographic trust proof. Authorized teacher/admin can later
  change it without factory resetting or reactivating the headset.
- No nickname required. Number belongs to a classroom, not a physical Quest.
- Collection is a domain concept above lesson, not an arbitrary URL or RustFS
  key. Existing public R0 catalog is a demo, **not the authorized collection list**.
- Do not send a client-selected tenant ID or trust client assertions that a
  collection is published or licensed. The Backend resolves publication and
  entitlement, then issues scoped Download Grant.
- A collection without localization in the activated locale must be excluded
  or explicitly use a separately approved fallback policy. MVP excludes it.
- Locale does not change an existing signed release. Different localized
  releases are immutable content packages with independent signatures.
- A collection is selected **per learning session**; the device language
  persists across days, but daily progress starts from zero.

## Draft implementation
- Pure policy `DeviceLearningSelectionPolicy` filters published/entitled/
  language-compatible choices; JUnit verifies negative authorization cases.
- SQL `R3_4__device_locale_and_collection.sql` adds persisted device locale and
  optional `collection_id` for classroom session (requires migration review).
- These are domain/database contracts, **not** a live catalog REST API.
- Teacher activation web form, device config update API, persisted collection
  publication table, license entitlement adapter and Unreal picker remain to do.
- Current R2 public demo catalog contains worlds and lessons only and MUST NOT
  be considered sufficient authority to return downloadable collections.

## API design (future, not yet exposed)
- `POST /api/v1/devices/activation/approve`: code, organization, locale;
  administrator authentication/organization membership required.
- `GET /api/v1/device/me/config`: bound locale and authorized session status.
- `GET /api/v1/device/me/collections`: only published, tenant-entitled,
  platform/runtime/localized collection metadata.
- `POST /api/v1/device/sessions/{sessionId}/collection`: selected collection ID;
  server rechecks ownership, entitlement, published release and session scope.
- Existing R2 download grant endpoint remains independent, with hardened
  device-bound authorization to be completed before real Quest deployment.

## Acceptance
- Same Quest stays in selected language across reboot and offline restart.
- Cross-tenant/unpublished/unlicensed/wrong locale collection hidden and
  rejected when ID is manually forged.
- Choice persists on a session, not as a global mutable current lesson.
- Changing language requires authorized administrator, and refreshes listing.
- Daily statistics and 30-day retention unaffected by language change.

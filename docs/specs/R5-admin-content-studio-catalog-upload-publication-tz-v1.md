# VRTrainingAcademy — ТЗ R5: Admin Content Studio, иерархия каталога, VR Upload и публикация

**Версия:** 1.0 · **Дата:** 2026-11-11 · **Статус:** PROPOSED / Scenario Design / Test-First  
**Ветка:** `feat/r5-quest-lesson-upload-contract` · **Родительская реализация:** Quest Runtime PR #10, R4 Integration PR #9  
**Главная цель:** Администратор размещает VR-урок **в конкретном каталоге/разделе**, загружает один или несколько совместимых Unreal-артефактов, получает доказательство совместимости, публикует неизменяемый релиз и контролирует доступ школ. Quest видит урок на правильном месте и может открыть **только проверенный** релиз.

> Важно: документ — ТЗ и критерии приёмки, не заявление о реализованном CRUD/публикации. На текущей ветке реализован только opt-in staging одного Pak с SHA-256 и отдельный подписываемый Java Quest Release V2. Production-публикация, Content Studio UI, нативный mount и Quest UAT отсутствуют.

## 1. Аудит текущей user story: что есть и чего нет

| Сценарий | Статус на HEAD до ТЗ | Фактический источник / разрыв |
|---|---|---|
| Схема `Knowledge Hub → World → Area → Subject → Lesson` | DESIGN / fixture | `unreal/docs/01-knowledge-hierarchy.md`, `unreal/contracts/catalog.snapshot.v1.example.json` |
| Публичный просмотр демонстрационного каталога | Реализован только R0 демо | `PublicCatalogController` содержит статические три урока и **не является** админ-каталогом |
| Авторизация платформенного администратора на лицензирование | Частично | `OrganizationLicensingController`, `LicensingAuthorization`; нет единой модели контентных ролей |
| Admin UI: вход, категории, управление уроками, загрузками, публикацией | **НЕТ** | Нет React Content Studio / реализованных административных экранов |
| Admin CRUD для World / Area / Subject | **НЕТ** | Нет persistence, REST и проверок parent ownership |
| Привязка урока к точному разделу и нескольким placements | **НЕТ** | В fixture placements есть; сохранения и административной операции нет |
| Привязка урока к лицензируемой Collection | Частично, небезопасно доверять upload metadata | Staging принимает `collectionId`, не подтверждает существование/доступность коллекции |
| Upload файла `.pak` | STAGED foundation, feature OFF | `QuestLessonUploadController`, `QuestLessonUploadService`; нет upload session в БД |
| Release Verification V2 | Java+Python контракты | `SignedQuestReleaseV2`; подпись не равна аппаратному сертификату |
| Certified → Published и разрешение Download Grant | **НЕТ** | `PublishedReleasePort` default-deny; нет persistent release registry |
| Quest Runtime Mount / Launch | **НЕТ** | `OpenDownloadedLesson` intentionally fail-closed |

### Уточнение предметной области: три разных пространства

1. **Таксономия каталога (navigation placement):** `World → Area → Subject → Lesson`. Определяет, **где пользователь увидит** урок. Категории принадлежат глобальному каталогу, с опциональными витринами/видимостью для организаций. Урок может иметь **несколько placements** при неизменном lesson ID.
2. **Collection (license / learning assignment):** именованный набор уроков/релизов, доступ к которому управляется организационной лицензией и назначением преподавателя. Не обязан совпадать с World/Area/Subject. **Collection не является родителем Subject**.
3. **Lesson / Experience / Release:** `Lesson → ExperienceDefinition → ContentModuleRelease(s) → Artifact(s)`. Один урок может иметь несколько релизов по платформе и версии; физические файлы лежат в RustFS. Смена пути каталога не меняет подписанный файл и идентификатор lessonId.

**Нормативное решение:** администратор при создании урока выбирает хотя бы один `subjectId` в дереве и один или несколько разрешённых `collectionId`. Backend разрешает placement только по валидному `World/Area/Subject` из БД и записывает его транзакционно; поля `collectionId` и `subjectId` в Upload JSON без серверной привязки **никогда не доказывают право**.

## 2. Роли и границы доверия

| Роль | Возможности | Запреты |
|---|---|---|
| `PLATFORM_ADMIN` | Управление глобальной таксономией, системными ролями, политикой публикации; контроль лицензий | Не может автоматически обходить испытания/подписывать непроверенный файл |
| `CONTENT_ADMIN` | CRUD World/Area/Subject при выданном scope, управление карточками/placements/collections, запуск сертификации, запрос публикации | Не может назначать себе роль и подписывать релиз без независимой проверки |
| `CONTENT_AUTHOR` | Создание и редактирование DRAFT уроков, загрузка исходных артефактов в разрешённые разделы, просмотр отчётов валидации | Не публикует и не меняет права на чужие категории |
| `CONTENT_REVIEWER` | Предметная, языковая и техническая проверка; approve/reject с причиной | Не изменяет проверяемый payload; не подписывает сам себе собственный релиз |
| `ORG_OWNER` | Права своей организации и преподавателей, лицензии, устройства | Не редактирует глобальный каталог по умолчанию |
| `TEACHER` | Выбор доступной Collection, занятие, статистика | Не выполняет публикацию/глобальный CRUD |
| `QUEST_DEVICE` | Чтение разрешённых опубликованных релизов через sender-constrained credentials | Не вызывает admin Upload/Publish |

Аутентификация — OIDC с проверкой issuer/audience/subject. **Авторизация — только доверенный PostgreSQL membership + scope/organization**, а не текст роли из запроса или произвольного JWT claim. Все write-операции аудитируются, включая old/new state, actor, organization, correlationId, timestamp. Для публикации требуется разграничение полномочий: создатель не должен самостоятельно подтверждать своё техническое аппаратное испытание.

## 3. Полная user story администратора

### US-ADM-01 — вход в Content Studio

**Как** CONTENT_ADMIN **я хочу** войти через OIDC и видеть доступные мне разделы, уроки, загрузки и задания на проверку, **чтобы** безопасно управлять публикациями.

**Acceptance:** неавторизованный API — 401; недостаточная роль — 403; права на чужой scope — 403; только allowlisted action/menu; JWT после изменения роли перепроверяется по политике ревокации.

### US-ADM-02 — создание World / Area / Subject

**Как** CONTENT_ADMIN **я хочу** создавать и редактировать `World`, `Area` и `Subject` в едином дереве, **чтобы** уроки были организованы по разделам.

**Journey:** Content Studio → «Каталог» → `World: Школа` → `Area: Естественные науки` → `Subject: Физика` → Save. У каждого узла: immutable code, локализованные titles (kk-KZ, ru-RU, en-US), parentId, `sortOrder`, `status DRAFT/PUBLISHED/ARCHIVED`. Удаление опубликованного узла с зависимостями запрещено; архивирование скрывает его из будущих витрин, не уничтожает исторические ссылки. Публикация проверяет отсутствие циклов, висячих ссылок и пропущенных обязательных переводов.

**Acceptance:** 201/200 для разрешённого CRUD; 409 при duplicate/cycle/child dependencies; изменение parent требует проверки всех placements; concurrent updates защищены optimistic `version`.

### US-ADM-03 — карточка урока в конкретном разделе

**Как** CONTENT_AUTHOR **я хочу** открыть конкретный Subject и нажать «Создать VR-урок», **чтобы** сохранить урок `PHY-001` именно в «Школа / Естественные науки / Физика».

**Journey:** дерево слева → выбранный Subject → «Создать урок» → название и описания на 3 языках → learning objective, difficulty, duration, cover → `ExperienceDefinition` → `Collection` → сохранение DRAFT. Запись placement: `(worldId, areaId, subjectId, lessonId, sortOrder)`. Нельзя создать урок через upload, минуя карточку и placement. Можно добавить второй placement, например «Завод / Электробезопасность»; lesson ID не дублируется.

**Acceptance:** выдаваемый lessonId серверный или проверенный авторский код; отсутствующий/чужой Subject, скрытая Collection — 403/404; второй placement не меняет releaseId; удаление placement не удаляет результаты учащихся.

### US-ADM-04 — привязка к лицензируемой Collection

**Как** CONTENT_ADMIN **я хочу** поместить урок в одну или несколько лицензируемых Collections, **чтобы** администраторы школ могли покупать/назначать корректные наборы.

**Acceptance:** разрешены только активные `Collection` с правами редактирования; `lessonId ↔ collectionId` хранится как отдельная версионированная связь; преподаватель видит только опубликованные, совместимые и лицензированные уроки; изменения состава Collection не расширяют чужую активную лицензию несанкционированно.

### US-ADM-05 — загрузка Unreal-пакета в выбранный урок

**Как** CONTENT_AUTHOR **я хочу** на вкладке релизов выбранного урока нажать «Загрузить сборку Quest», **чтобы** пакет однозначно связывался с `lessonId`, `subject placement`, `collection` и `runtimeBuildId`.

**Journey:** lesson DRAFT → `Releases` → выбор `Quest / Android_ASTC` + UE profile → upload `.pak` (или набор `.pak + .utoc + .ucas` в зависимости от утверждённого формата cook) → progress/retry → `STAGED`. Backend **берёт lessonId и collection membership из серверного LessonDraft**, а клиентский metadata используется только как дополнительная проверка совпадения.

**Acceptance:** 401/403 запрещённый автор; 409 несовпадение `lessonId/collection` с draft; 413 quota/size; 422 incompatible build/invalid digest; 202 only after exact hash verified and object durable; filename/path traversal denied; duplicate upload with same `Idempotency-Key` yields same `uploadId`; quarantine never available to Download Grant.

### US-ADM-06 — результаты проверки и исправление

**Как** CONTENT_AUTHOR **я хочу** видеть отчёт `Upload → Validate → Cook Compatibility → Quest Smoke`, **чтобы** исправить несовместимую сборку до публикации.

**Acceptance:** на карточке release показаны `buildProfile`, digest, missing dependencies, entryLevel, engine compatibility, device UAT, logs и точный код отказа; reject фиксирует immutable evidence; повторная загрузка создаёт **новую** revision, не мутирует подписанный content release.

### US-ADM-07 — независимое review и публикация

**Как** CONTENT_REVIEWER/CONTENT_ADMIN **я хочу** утвердить прошедший проверку релиз и опубликовать урок в выбранных разделах и Collections, **чтобы** он появился в каталоге школ.

**Acceptance:** без native certification `CERTIFIED` невозможен; `PUBLISHED` только при signed V2 + approved actor + успешном review; maker-checker для release; атомарная транзакция publication + published catalog view/outbox; published object immutable; rollback через новый active pointer, без перезаписи старой подписи.

### US-ADM-08 — контроль доступности на Quest

**Как** CONTENT_ADMIN **я хочу** видеть `PUBLISHED`, совместимые Quest профили и результаты installation/launch smoke, **чтобы** не обещать запуск урока на неподдерживаемых шлемах.

**Acceptance:** есть матрица `runtimeBuildId × engineVersion × platform × deviceProfile`; публикация не расширяет её автоматически. Неподдерживаемый Quest получает `INCOMPATIBLE` и не скачивает файл. Показатель «успешно протестировано на устройстве» имеет привязку к артефакту и конкретному smoke evidence.

### US-ADM-09 — каталог для школ, преподавателей и учащихся

**Как** ORG_OWNER / TEACHER / LEARNER **я хочу** видеть урок в выбранном World/Area/Subject и внутри назначенной лицензированной Collection, **чтобы** ученик запускал правильный опубликованный релиз.

**Acceptance:** catalog snapshot содержит placement+lesson+experience+release references; ACL проверяется независимо от публичного каталога; teacher назначает Collection; learner выбирает конкретный урок+язык; `attempt` закрепляет immutable release, а не mutable lesson pointer. Без интернета последняя проверенная snapshot и установленные релизы работают только в рамках подписанной офлайн-лицензии.

### US-ADM-10 — отзыв, архивирование, аудит и восстановление

**Как** PLATFORM_ADMIN / CONTENT_ADMIN **я хочу** отзывать релиз, архивировать урок, видеть историю и восстанавливать состояние, **чтобы** исключить повреждённый материал без удаления журналов обучения.

**Acceptance:** отзыв запрещает новые grants, не уничтожает уже собранные попытки и локальные результаты; офлайн-устройства соблюдают границу допустимого signed lease; аудит неизменяем; GC удаляет только устаревшие **неактивные** объекты по подтверждённой политике retention.

## 4. Модель данных PostgreSQL и границы контекстов

Схемы: `platform` (IAM, organizations, device/license), `content` (catalog, publication), `education` (pedagogy, classroom).

| Таблица `content.*` | Обязательные поля | Constraints |
|---|---|---|
| `world` | `world_id`, code, status, version, sort_order | code UNIQUE; immutable id |
| `area` | `area_id`, world_id, code, status, version, sort_order | FK world; unique code per world |
| `subject` | `subject_id`, area_id, code, status, version, sort_order | FK area; unique code per area |
| `catalog_translation` | node_type, node_id, locale, title, description | unique node+locale; supported locales |
| `lesson` | `lesson_id`, code, lifecycle, experience_id, author, version | immutable id; FK to trusted experience |
| `lesson_placement` | world_id, area_id, subject_id, lesson_id, sort_order | unique tuple; consistent chain validation |
| `collection` | collection_id, code, scope, lifecycle, version | unique code, trusted owner scope |
| `collection_lesson` | collection_id, lesson_id, valid_from, valid_to | FK; no duplicate active relation |
| `experience_definition` | experience_id, lesson_id, entry_level, interaction_contract_version | stable reference / versioned |
| `upload_session` | upload_id, organization_id, lesson_id, status, actor, idempotency_key, created_at | unique actor+scope+key; audit |
| `artifact` | artifact_id, upload_id, kind, object_key, sha256, size_bytes, format | unique upload+name; object immutability |
| `content_release` | release_id, lesson_id, platform, engine_version, runtime_build_id, state, manifest_sha256 | unique immutable releaseId; signed manifest |
| `certification_evidence` | evidence_id, release_id, device_profile, artifact_digest, runner_commit, verdict, log_key | append-only; exact SHA |
| `publication` | lesson_id, collection_id, release_id, published_at, approved_by, state | one active release per platform/profile policy |
| `content_outbox` | event_id, aggregate_id, type, payload, published_at | reliable downstream catalog change |
| `admin_audit` | event_id, actor, action, subject, prev_hash, new_hash, created_at | append-only; monotonic ordering |

Cross-scheme integration: `content` owns source of truth and exposes ports for Education/Platform; no direct cyclic imports or business joins. Use Liquibase changesets with additive migrations and Testcontainers/PostgreSQL 16 integration tests.

**Important licensing rule:** Membership and permissions live in Platform; global catalog placement is not owned by tenant; a licensed Collection is a *commercial/assignment* aggregate, not a taxonomy node.

## 5. Backend API и UI контракт

Все admin endpoints под `/api/v1/admin/content` с trusted actor context; `/api/v1/platform-admin` используется только для глобальной platform governance. **Это будущие эндпоинты**, за исключением существующего `POST /api/v1/content/quest/staging`.

| Method / endpoint (PROPOSED) | Назначение | Owner role |
|---|---|---|
| GET `/api/v1/admin/content/catalog/tree` | Дерево + counts, locale, filtering | CONTENT_ADMIN / AUTHOR / REVIEWER |
| POST/PATCH `/api/v1/admin/content/worlds`, `/{id}` | CRUD World | PLATFORM_ADMIN или scoped CONTENT_ADMIN |
| POST/PATCH `/api/v1/admin/content/areas`, `/{id}` | CRUD Area | Scoped CONTENT_ADMIN |
| POST/PATCH `/api/v1/admin/content/subjects`, `/{id}` | CRUD Subject | Scoped CONTENT_ADMIN |
| POST `/api/v1/admin/content/subjects/{subjectId}/lessons` | Создать урок с server-owned placement | CONTENT_AUTHOR |
| GET/PATCH `/api/v1/admin/content/lessons/{lessonId}` | Карточка, локализации, версии | Authorized author/admin |
| PUT/DELETE `/api/v1/admin/content/lessons/{lessonId}/placements/{subjectId}` | Дополнительное размещение / архивирование связи | CONTENT_ADMIN |
| POST/DELETE `/api/v1/admin/content/collections/{id}/lessons/{lessonId}` | Управление составом Collection | CONTENT_ADMIN |
| POST `/api/v1/admin/content/lessons/{lessonId}/uploads` | Создать durable UploadSession | CONTENT_AUTHOR |
| PUT `/api/v1/admin/content/uploads/{uploadId}/artifacts/{name}` | Загрузить проверяемый chunk/artifact | CONTENT_AUTHOR |
| POST `/api/v1/admin/content/uploads/{uploadId}/finalize` | Закрепить полный список digest и staged release | CONTENT_AUTHOR |
| GET `/api/v1/admin/content/releases/{releaseId}/qualification` | Read-only evidence | AUTHOR / REVIEWER |
| POST `/api/v1/admin/content/releases/{releaseId}/review` | Approve/reject | CONTENT_REVIEWER |
| POST `/api/v1/admin/content/releases/{releaseId}/publish` | Publish approved/certified release | CONTENT_ADMIN |
| POST `/api/v1/admin/content/releases/{releaseId}/revoke` | Disable new grants with audit | CONTENT_ADMIN/PLATFORM_ADMIN |

UI: адаптивная React/TypeScript Content Studio (MVP desktop), слева дерево World/Area/Subject, справа таблица Lesson с фильтрами и состояниями, в карточке вкладки `Основное / Переводы / Collections / Размещения / VR Releases / Проверки / История`; загрузка файла с прогрессом, ошибками, повтором, хешами и qualification report; confirm dialog для ревокации. Использовать единый backend RBAC и те же DTO, не отдельные browser-only permissions.

### UI navigation acceptance

```text
Content Studio
└── Каталог
    └── Школа [WORLD_SCHOOL]
        └── Естественные науки [AREA_NATSCI]
            └── Физика [SUBJECT_PHYSICS]
                └── Электрическая цепь [PHY-001]
                    ├── Карточка + локализации
                    ├── Размещения (1..N)
                    ├── Collections: PHYSICS_BASIC
                    ├── Релизы → Загрузить Quest
                    │   └── STAGED → VALIDATING → CERTIFIED
                    └── Review → Publish → PUBLISHED
```

## 6. Жизненный цикл и политика безопасности

```text
LESSON: DRAFT → READY_FOR_REVIEW → APPROVED → PUBLISHED → ARCHIVED
UPLOAD: CREATED → UPLOADING → STAGED → VALIDATING → VALIDATED | REJECTED
RELEASE: CANDIDATE → CERTIFICATION_PENDING → CERTIFIED | REJECTED
PUBLICATION: DRAFT → PUBLISHED → REVOKED
```

Условия:
- только доверенный publisher подписывает **полный авторитетный** manifest V2; upload metadata не может подписывать себе сертификат;
- `PUBLISHED` возможен только при наличии валидной Lesson, хотя бы одного разрешённого subject placement, Collection, комплекта артефактов, signed manifest и device evidence для выбранного профиля;
- `STAGED` никогда не возвращается в R2 Download Grant; CDN/S3 object keys принадлежат immutable published namespace и не переиспользуются для quarantine;
- `publication` и `content.outbox` записываются атомарно; signer не хранит приватный ключ в repo/JSON;
- автор не может обходить review, роль проверяется сервером на каждом действии; организации не могут менять чужой scope;
- смена placement/category не повреждает исторические learning attempts; публикация нового release не перезаписывает старый release;
- необработанные uploads чистятся по TTL; PUBLISHED и audit защищены от GC; защита от replay и race с revoke/expiry.

### Разрешение коллизии с действующим R5 staging

Сегодня `QuestLessonUploadService.Request` содержит `organizationId, lessonId, collectionId, entryLevel`, но сервер не сверяет их с persisted Content Domain. В R5.1 надо оставить старый endpoint как **временный внутренний staging contract**, отключённый по умолчанию; для Content Studio использовать server-bound `uploadId`, полученный из заранее созданного `LessonDraft`. Никакой автоматической записи placement из untrusted metadata. Для переходного периода контракт старого endpoint следует депрецировать и запретить его включение для публичных авторов до полноценного IAM и quota.

## 7. Test-First: минимальные RED-контракты

| ID | Тест (unit + PostgreSQL + HTTP + native) | Ожидание |
|---|---|---|
| CAT-01 | Create World → Area → Subject; reload PG | дерево сохраняет точные parent FK, translations, sort_order |
| CAT-02 | Create lesson under SUBJECT_PHYSICS | placement сохранён, в других Subject отсутствует |
| CAT-03 | Add second placement to same lesson | один lessonId, два placements, не дублируются releases |
| CAT-04 | Cross-tree forged world/area/subject | 409/422, ни одной записи placement |
| CAT-05 | Delete/archive used Subject | нет каскадного удаления исторических попыток |
| IAM-01 | Author role outside authorized scope | 403 and no DB/S3 side effect |
| IAM-02 | JWT subject with client-sent ADMIN role | 403, privilege escalation prevented |
| IAM-03 | Reviewer approves own release | 403/409, maker-checker enforced |
| UP-01 | Upload for existing draft and collection | STAGED durable upload_id, correct organization/lesson references |
| UP-02 | Upload metadata claims another lesson/collection | 403/409, zero S3 writes |
| UP-03 | SHA mismatch, broken range, wrong platform/entry | 422, quarantine cleanup and no certificate |
| UP-04 | Retry same Idempotency-Key after timeout | same uploadId and one final artifact |
| UP-05 | Concurrent uploads/finalize/revoke | one linearized immutable release; no phantom Published |
| UP-06 | Pak vs IoStore assets missing one dependency | qualification REJECTED, never PUBLISHED |
| PUB-01 | STAGED or VALIDATING file exposed by grant | 404/403, no signed URL |
| PUB-02 | CERTIFIED with stale digest/device profile | cannot publish |
| PUB-03 | PUBLISHED, then artifact changes | digest mismatch, publication invalidated |
| PUB-04 | Publish release and commit outbox atomically | catalog snapshot eventually reflects versioned release |
| PUB-05 | Grant on revoked/incompatible license/device | denied regardless of catalog visibility |
| UI-01 | Content Studio tree → Subject → Add Lesson → Upload → Review | labels, breadcrumbs, scope, validation and states correct |
| UI-02 | Out-of-scope subject / no teacher role | UI hides action, API independently returns 403 |
| QUEST-01 | Real certified release to supported Quest | device downloads, verifies, mounts, opens correct signed entry level |
| QUEST-02 | Wi-Fi off + restart Quest | local authorized lesson and progress recover |
| QUEST-03 | Corrupted package or unsupported runtime build | fails closed and preserves last-good lesson |

Test order: RED → independent Test Review → implementation → GREEN → regression → PostgreSQL/RustFS real smoke → Unreal C++ native build → Quest hardware UAT. Tests must record exact commit SHA and real runner/device evidence; no native claims from Python fixtures.

## 8. План PR и обязательные gates

| PR / стадия | Объём | Exit criterion |
|---|---|---|
| R5.1 / PR-A | Canonical Content Schema, migrations, taxonomy + placement, 3-locale titles, RBAC design | CAT-01..05 + IAM RED/GREEN on PostgreSQL |
| R5.2 / PR-B | Admin Content REST, trusted membership and audit + HTTP authorization | IAM-01..03 and admin contract tests |
| R5.3 / PR-C | React Content Studio: tree, lesson draft, placements/collections | UI-01/02 + real Backend E2E |
| R5.4 / PR-D | Durable UploadSession, multi-file/multipart RustFS quarantine, idempotency/quotas | UP-01..06 + RustFS container |
| R5.5 / PR-E | Automated certification and versioned signed V2 publisher + publication registry/outbox | PUB-01..04; no unsigned bypass |
| R5.6 / PR-F | Quest Device V2 grants + native verifier/downloader/mount | PUB-05 + native UBT/Android and QUEST-01 |
| R5.7 / PR-G | Quest offline lease/outbox, recovery and hardware release qualification | QUEST-02/03 and hardware certification logs |

**Explicit decision points before coding:**
- `DEC-CAT-01`: global taxonomy with scoped editing vs per-organization private catalogs. **Recommendation:** global taxonomy, tenant-specific Collections and visibility.
- `DEC-CAT-02`: lesson placement cardinality. **Recommendation:** one stable lesson with 1..N placements.
- `DEC-UP-01`: Android artifact mode. **Recommendation:** prove Pak-only baseline on actual Quest; add IoStore via versioned adapter, not guessed file support.
- `DEC-IAM-01`: separation PLATFORM_ADMIN vs CONTENT_ADMIN vs ORG_OWNER. **Recommendation:** independent trusted roles with scoped membership.
- `DEC-PUB-01`: publication gate. **Recommendation:** machine certification + maker-checker + signed immutable release; never upload=published.
- `DEC-VR-01`: certification matrix. **Recommendation:** exact UE/runtime/platform/device profile with reproducible evidence; no blanket compatibility claims.

## 9. Definition of Done и реальная пользовательская проверка

R5 Admin Publishing считается полностью реализованным только если:

**Admin Journey:** authorized CONTENT_ADMIN enters Content Studio → creates World/Area/Subject → creates Lesson in **exact Subject** → adds Collection → author uploads valid Android Quest package → manifest validates and stores **durable** staged state → reviewer approves after native/device smoke → publisher signs V2 → publication changes catalog and R2 device grant → assigned student selects language/lesson on Quest → exact signed Level opens online and after offline restart → score synchronizes without duplication → admin can revoke and audit the exact release.

Критерии безопасности: **zero cross-tenant writes, zero unauthenticated publication, zero unsigned package launch**, no loss of attempt history; negative tests and concurrent retries green.

**Current decision: NO-GO to release**, while preserving existing default-deny safe foundation. Do not mark this specification implemented merely because documentation and contract CI are green.

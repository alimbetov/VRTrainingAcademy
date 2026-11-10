-- R3 contract migration, run after R3_2 and R3_3.
-- Persist the administrator-chosen device locale. Changes require authorized
-- device-management permission; never trust a locale supplied on an asset GET.
ALTER TABLE platform.registered_device
    ADD COLUMN preferred_locale varchar(5) NOT NULL DEFAULT 'ru-RU',
    ADD CONSTRAINT ck_device_preferred_locale
    CHECK (preferred_locale IN ('kk-KZ', 'ru-RU', 'en-US'));

-- A classroom session may select an explicit collection from an authorized list.
-- Collection ID must resolve to an immutable published release in the content
-- domain, with tenant entitlement + runtime/platform/language compatibility.
-- The foreign key cannot be added until the content publication model exists.
ALTER TABLE education.classroom_session
    ADD COLUMN collection_id varchar(128);

-- Do not imply that arbitrary collection IDs are approved. When integrating
-- the session controller, validate before insert against PublishedReleasePort,
-- organization entitlements and the signed manifest.

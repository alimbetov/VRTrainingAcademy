-- R5.1: Content Catalog taxonomy and lesson placements.
-- Reference migration: apply in reviewed transactional migration process.
-- No public/admin API is enabled by this DDL alone.
CREATE SCHEMA IF NOT EXISTS content;

CREATE TABLE IF NOT EXISTS content.catalog_world (
    world_id uuid PRIMARY KEY,
    code varchar(128) NOT NULL UNIQUE,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    sort_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cat_world_code CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' AND position('..' in code) = 0),
    CONSTRAINT ck_cat_world_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    CONSTRAINT ck_cat_world_version CHECK (version >= 0)
);

CREATE TABLE IF NOT EXISTS content.catalog_area (
    area_id uuid PRIMARY KEY,
    world_id uuid NOT NULL REFERENCES content.catalog_world(world_id) ON DELETE RESTRICT,
    code varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    sort_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_cat_area_world_code UNIQUE (world_id, code),
    CONSTRAINT uq_cat_area_world_id UNIQUE (world_id, area_id),
    CONSTRAINT ck_cat_area_code CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' AND position('..' in code) = 0),
    CONSTRAINT ck_cat_area_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);

CREATE TABLE IF NOT EXISTS content.catalog_subject (
    subject_id uuid PRIMARY KEY,
    area_id uuid NOT NULL REFERENCES content.catalog_area(area_id) ON DELETE RESTRICT,
    code varchar(128) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    sort_order integer NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_cat_subject_area_code UNIQUE (area_id, code),
    CONSTRAINT uq_cat_subject_area_id UNIQUE (area_id, subject_id),
    CONSTRAINT ck_cat_subject_code CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' AND position('..' in code) = 0),
    CONSTRAINT ck_cat_subject_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);

CREATE TABLE IF NOT EXISTS content.catalog_translation (
    node_kind varchar(10) NOT NULL,
    node_id uuid NOT NULL,
    locale varchar(5) NOT NULL,
    title varchar(160) NOT NULL,
    description varchar(2000),
    CONSTRAINT pk_catalog_translation PRIMARY KEY (node_kind, node_id, locale),
    CONSTRAINT ck_cat_translation_kind CHECK (node_kind IN ('WORLD','AREA','SUBJECT','LESSON')),
    CONSTRAINT ck_cat_translation_locale CHECK (locale IN ('kk-KZ','ru-RU','en-US')),
    CONSTRAINT ck_cat_translation_title CHECK (length(trim(title)) > 0)
);
-- Polymorphic translations are not granted publication authority by themselves.
-- Service inserts translations only after the trusted parent row is resolved.

CREATE TABLE IF NOT EXISTS content.catalog_lesson (
    lesson_id uuid PRIMARY KEY,
    code varchar(128) NOT NULL UNIQUE,
    state varchar(16) NOT NULL DEFAULT 'DRAFT',
    created_by_subject varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_cat_lesson_code CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' AND position('..' in code) = 0),
    CONSTRAINT ck_cat_lesson_status CHECK (state IN ('DRAFT','READY_FOR_REVIEW','APPROVED','PUBLISHED','ARCHIVED')),
    CONSTRAINT ck_cat_lesson_author CHECK (length(trim(created_by_subject)) > 0)
);

CREATE TABLE IF NOT EXISTS content.lesson_placement (
    placement_id uuid PRIMARY KEY,
    lesson_id uuid NOT NULL REFERENCES content.catalog_lesson(lesson_id) ON DELETE RESTRICT,
    world_id uuid NOT NULL,
    area_id uuid NOT NULL,
    subject_id uuid NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_cat_lesson_subject UNIQUE (lesson_id, subject_id),
    CONSTRAINT fk_cat_placement_area_world FOREIGN KEY (world_id, area_id)
        REFERENCES content.catalog_area(world_id, area_id) ON DELETE RESTRICT,
    CONSTRAINT fk_cat_placement_subject_area FOREIGN KEY (area_id, subject_id)
        REFERENCES content.catalog_subject(area_id, subject_id) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS idx_cat_placements_subject_sort
    ON content.lesson_placement(subject_id, sort_order, lesson_id);
CREATE INDEX IF NOT EXISTS idx_cat_world_order ON content.catalog_world(sort_order, code);
CREATE INDEX IF NOT EXISTS idx_cat_area_world_order ON content.catalog_area(world_id, sort_order, code);
CREATE INDEX IF NOT EXISTS idx_cat_subject_area_order ON content.catalog_subject(area_id, sort_order, code);

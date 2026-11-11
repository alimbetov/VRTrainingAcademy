package academy.vrtraining.platform.content.catalog;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * R5.1 reference PostgreSQL adapter. Not a controller, not a grant authority.
 * Composite FK constraints independently enforce the exact tree path.
 * Authorization belongs to a future trusted Admin Content application service.
 */
public final class PostgresContentCatalogStore implements ContentCatalogStore {
    private final String jdbcUrl;
    private final String user;
    private final String password;

    public PostgresContentCatalogStore(String jdbcUrl, String user, String password) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL required");
        this.jdbcUrl = jdbcUrl;
        this.user = Objects.requireNonNull(user);
        this.password = Objects.requireNonNull(password);
    }

    @Override public List<Node> worlds() {
        return queryNodes("SELECT world_id,code,status,sort_order FROM content.catalog_world "
                + "ORDER BY sort_order,code", null, false);
    }

    @Override public List<Node> areas(UUID worldId) {
        return queryNodes("SELECT area_id,code,status,sort_order FROM content.catalog_area "
                + "WHERE world_id=? ORDER BY sort_order,code", Objects.requireNonNull(worldId), true);
    }

    @Override public List<Node> subjects(UUID areaId) {
        return queryNodes("SELECT subject_id,code,status,sort_order FROM content.catalog_subject "
                + "WHERE area_id=? ORDER BY sort_order,code", Objects.requireNonNull(areaId), true);
    }

    private List<Node> queryNodes(String sql, UUID parentId, boolean scoped) {
        var result = new ArrayList<Node>();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(sql)) {
            if (scoped) stmt.setObject(1, parentId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new Node((UUID) rs.getObject(1), parentId,
                            rs.getString(2), rs.getString(3), rs.getInt(4)));
                }
            }
        } catch (SQLException ex) { throw new IllegalStateException("Catalog tree query failed", ex); }
        return List.copyOf(result);
    }

    @Override public void upsertTranslation(NodeKind kind, UUID nodeId, String locale,
                                             String title, String description) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (locale == null || !List.of("kk-KZ", "ru-RU", "en-US").contains(locale))
            throw new IllegalArgumentException("Unsupported locale");
        if (title == null || title.isBlank() || title.length() > 160
                || (description != null && description.length() > 2000))
            throw new IllegalArgumentException("Invalid translation");
        String table = translationTable(kind);
        String column = translationColumn(kind);
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement("INSERT INTO content." + table
                     + "(" + column + ",locale,title,description) VALUES (?,?,?,?)"
                     + " ON CONFLICT (" + column + ",locale) DO UPDATE SET"
                     + " title=EXCLUDED.title,description=EXCLUDED.description")) {
            stmt.setObject(1, nodeId);
            stmt.setString(2, locale);
            stmt.setString(3, title.strip());
            stmt.setString(4, description);
            stmt.executeUpdate();
        } catch (SQLException ex) { throw conflictOrFailure("Translation upsert failed", ex); }
    }

    @Override public List<Translation> translations(NodeKind kind, UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        String table = translationTable(kind);
        String column = translationColumn(kind);
        var results = new ArrayList<Translation>();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement("SELECT locale,title,description FROM content."
                     + table + " WHERE " + column + "=? ORDER BY locale")) {
            stmt.setObject(1, nodeId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) results.add(new Translation(
                        rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException ex) { throw new IllegalStateException("Translation lookup failed", ex); }
        return List.copyOf(results);
    }

    private static String translationTable(NodeKind kind) {
        return switch (Objects.requireNonNull(kind)) {
            case WORLD -> "world_translation";
            case AREA -> "area_translation";
            case SUBJECT -> "subject_translation";
            case LESSON -> "lesson_translation";
        };
    }

    private static String translationColumn(NodeKind kind) {
        return switch (Objects.requireNonNull(kind)) {
            case WORLD -> "world_id";
            case AREA -> "area_id";
            case SUBJECT -> "subject_id";
            case LESSON -> "lesson_id";
        };
    }

    @Override public Node createWorld(String code, int sortOrder) {
        requireCode(code);
        UUID id = UUID.randomUUID();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(
                     "INSERT INTO content.catalog_world(world_id,code,sort_order) VALUES (?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setString(2, code);
            stmt.setInt(3, sortOrder);
            stmt.executeUpdate();
            return new Node(id, null, code, "DRAFT", sortOrder);
        } catch (SQLException ex) { throw conflictOrFailure("World creation failed", ex); }
    }

    @Override public Node createArea(UUID worldId, String code, int sortOrder) {
        Objects.requireNonNull(worldId, "worldId");
        requireCode(code);
        UUID id = UUID.randomUUID();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(
                     "INSERT INTO content.catalog_area(area_id,world_id,code,sort_order) VALUES (?,?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setObject(2, worldId);
            stmt.setString(3, code);
            stmt.setInt(4, sortOrder);
            stmt.executeUpdate();
            return new Node(id, worldId, code, "DRAFT", sortOrder);
        } catch (SQLException ex) { throw conflictOrFailure("Area creation failed", ex); }
    }

    @Override public Node createSubject(UUID areaId, String code, int sortOrder) {
        Objects.requireNonNull(areaId, "areaId");
        requireCode(code);
        UUID id = UUID.randomUUID();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(
                     "INSERT INTO content.catalog_subject(subject_id,area_id,code,sort_order) VALUES (?,?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setObject(2, areaId);
            stmt.setString(3, code);
            stmt.setInt(4, sortOrder);
            stmt.executeUpdate();
            return new Node(id, areaId, code, "DRAFT", sortOrder);
        } catch (SQLException ex) { throw conflictOrFailure("Subject creation failed", ex); }
    }

    @Override public Lesson createLesson(String code, String trustedAuthorSubject) {
        requireCode(code);
        if (trustedAuthorSubject == null || trustedAuthorSubject.isBlank()
                || trustedAuthorSubject.length() > 255)
            throw new IllegalArgumentException("Trusted author is required");
        UUID id = UUID.randomUUID();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(
                 "INSERT INTO content.catalog_lesson(lesson_id,code,created_by_subject) VALUES (?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setString(2, code);
            stmt.setString(3, trustedAuthorSubject);
            stmt.executeUpdate();
            return new Lesson(id, code, "DRAFT", trustedAuthorSubject);
        } catch (SQLException ex) { throw conflictOrFailure("Lesson creation failed", ex); }
    }

    @Override public Placement createLessonInSubject(String code, String trustedAuthorSubject,
                                                      UUID worldId, UUID areaId,
                                                      UUID subjectId, int sortOrder) {
        requireCode(code);
        if (trustedAuthorSubject == null || trustedAuthorSubject.isBlank()
                || trustedAuthorSubject.length() > 255)
            throw new IllegalArgumentException("Trusted author is required");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(subjectId, "subjectId");
        UUID lessonId = UUID.randomUUID();
        UUID placementId = UUID.randomUUID();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password)) {
            db.setAutoCommit(false);
            try {
                try (var lesson = db.prepareStatement(
                         "INSERT INTO content.catalog_lesson(lesson_id,code,created_by_subject) " +
                         "VALUES (?,?,?)")) {
                    lesson.setObject(1, lessonId);
                    lesson.setString(2, code);
                    lesson.setString(3, trustedAuthorSubject);
                    lesson.executeUpdate();
                }
                try (var placement = db.prepareStatement(
                         "INSERT INTO content.lesson_placement " +
                         "(placement_id,lesson_id,world_id,area_id,subject_id,sort_order) " +
                         "VALUES (?,?,?,?,?,?)")) {
                    placement.setObject(1, placementId);
                    placement.setObject(2, lessonId);
                    placement.setObject(3, worldId);
                    placement.setObject(4, areaId);
                    placement.setObject(5, subjectId);
                    placement.setInt(6, sortOrder);
                    placement.executeUpdate();
                }
                db.commit();
                return new Placement(placementId, lessonId, worldId, areaId, subjectId, sortOrder);
            } catch (SQLException ex) {
                try { db.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
        } catch (SQLException ex) { throw conflictOrFailure("Atomic lesson creation failed", ex); }
    }

    @Override public Placement placeLesson(UUID lessonId, UUID worldId, UUID areaId,
                                          UUID subjectId, int sortOrder) {
        Objects.requireNonNull(lessonId, "lessonId");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(subjectId, "subjectId");
        UUID id = UUID.randomUUID();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(
                 "INSERT INTO content.lesson_placement " +
                 "(placement_id,lesson_id,world_id,area_id,subject_id,sort_order) VALUES (?,?,?,?,?,?)")) {
            stmt.setObject(1, id);
            stmt.setObject(2, lessonId);
            stmt.setObject(3, worldId);
            stmt.setObject(4, areaId);
            stmt.setObject(5, subjectId);
            stmt.setInt(6, sortOrder);
            stmt.executeUpdate();
            return new Placement(id, lessonId, worldId, areaId, subjectId, sortOrder);
        } catch (SQLException ex) { throw conflictOrFailure("Lesson placement failed", ex); }
    }

    @Override public List<SubjectLesson> lessonsInSubject(UUID worldId, UUID areaId,
                                                           UUID subjectId) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(subjectId, "subjectId");
        var result = new ArrayList<SubjectLesson>();
        try (var db = DriverManager.getConnection(jdbcUrl, user, password);
             var stmt = db.prepareStatement(
                "SELECT l.lesson_id,l.code,l.state,p.placement_id,p.sort_order " +
                "FROM content.lesson_placement p " +
                "JOIN content.catalog_lesson l ON l.lesson_id=p.lesson_id " +
                "WHERE p.world_id=? AND p.area_id=? AND p.subject_id=? " +
                "ORDER BY p.sort_order,l.code")) {
            stmt.setObject(1, worldId);
            stmt.setObject(2, areaId);
            stmt.setObject(3, subjectId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(new SubjectLesson((UUID) rs.getObject(1), rs.getString(2),
                            rs.getString(3), (UUID) rs.getObject(4), rs.getInt(5)));
                }
            }
        } catch (SQLException ex) { throw new IllegalStateException("Catalog lookup failed", ex); }
        return List.copyOf(result);
    }

    private static void requireCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || code.contains(".."))
            throw new IllegalArgumentException("Invalid catalog code");
    }

    private static RuntimeException conflictOrFailure(String message, SQLException ex) {
        if ("23505".equals(ex.getSQLState()) || "23503".equals(ex.getSQLState())
                || "23514".equals(ex.getSQLState()))
            return new IllegalArgumentException(message + ": invalid or conflicting hierarchy", ex);
        return new IllegalStateException(message, ex);
    }
}

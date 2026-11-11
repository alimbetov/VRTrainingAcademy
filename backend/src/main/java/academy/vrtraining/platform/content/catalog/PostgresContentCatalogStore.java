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

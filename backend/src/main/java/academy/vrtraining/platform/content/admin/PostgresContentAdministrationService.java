package academy.vrtraining.platform.content.admin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * R5.2 trusted authoring operations. Actor comes ONLY from the verified JWT.
 * Membership, scope, mutation and audit are checked in ONE PostgreSQL transaction.
 * No direct published lesson or package release API is provided.
 */
public final class PostgresContentAdministrationService {
    private final String url, user, password;

    public record Created(UUID id, String state, long version) {}
    public record Change(UUID id, String state, long version) {}

    public PostgresContentAdministrationService(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL URL required");
        this.url = url;
        this.user = Objects.requireNonNull(user);
        this.password = Objects.requireNonNull(password);
    }

    @FunctionalInterface private interface Transaction<T> {
        T run(Connection db) throws SQLException;
    }

    private <T> T transact(Transaction<T> transaction) {
        try (var db = DriverManager.getConnection(url, user, password)) {
            db.setAutoCommit(false);
            try {
                T result = transaction.run(db);
                db.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try { db.rollback(); } catch (SQLException e) { exception.addSuppressed(e); }
                throw exception;
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()) || "23503".equals(e.getSQLState())
                    || "23514".equals(e.getSQLState()))
                throw new Conflict("Conflicting or invalid content relationship", e);
            throw new IllegalStateException("Content administrator database unavailable", e);
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
        public Conflict(String message, Throwable cause) { super(message, cause); }
    }

    private static void actor(String subject) {
        if (subject == null || subject.isBlank() || subject.length() > 255)
            throw new SecurityException("Authenticated actor is required");
    }

    private static void code(String code) {
        if (code == null || !code.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || code.contains("..")) throw new IllegalArgumentException("Invalid catalog code");
    }

    private static boolean member(Connection db, String subject, String role, UUID world)
            throws SQLException {
        String query = "SELECT 1 FROM content.admin_membership "
                + "WHERE subject=? AND role=? AND active=true AND organization_id IS NULL "
                + (world == null ? "AND scope_world_id IS NULL" : "AND scope_world_id=?")
                + " LIMIT 1";
        try (var s = db.prepareStatement(query)) {
            s.setString(1, subject);
            s.setString(2, role);
            if (world != null) s.setObject(3, world);
            try (var rows = s.executeQuery()) { return rows.next(); }
        }
    }

    private static void authorized(Connection db, String subject, UUID world, boolean author)
            throws SQLException {
        actor(subject);
        if (member(db, subject, "PLATFORM_ADMIN", null)) return;
        if (world != null && (member(db, subject, "CONTENT_ADMIN", world)
                || (author && member(db, subject, "CONTENT_AUTHOR", world)))) return;
        throw new SecurityException("Content administration scope denied");
    }

    private static UUID parentWorld(Connection db, UUID areaId) throws SQLException {
        try (var s = db.prepareStatement("SELECT world_id FROM content.catalog_area WHERE area_id=?")) {
            s.setObject(1, Objects.requireNonNull(areaId));
            try (var rows = s.executeQuery()) {
                if (!rows.next()) throw new Conflict("Unknown Area");
                return (UUID) rows.getObject(1);
            }
        }
    }

    private static UUID worldForSubject(Connection db, UUID subjectId) throws SQLException {
        try (var s = db.prepareStatement("SELECT a.world_id FROM content.catalog_subject t "
                + "JOIN content.catalog_area a ON a.area_id=t.area_id WHERE t.subject_id=?")) {
            s.setObject(1, Objects.requireNonNull(subjectId));
            try (var rows = s.executeQuery()) {
                if (!rows.next()) throw new Conflict("Unknown Subject");
                return (UUID) rows.getObject(1);
            }
        }
    }

    private static void audit(Connection db, String subject, String action, String objectType,
                              UUID objectId, String before, String after) throws SQLException {
        try (var s = db.prepareStatement("INSERT INTO content.admin_audit "
                + "(event_id,actor_subject,action,object_type,object_id,before_state,after_state) "
                + "VALUES (?,?,?,?,?,?,?)")) {
            s.setObject(1, UUID.randomUUID());
            s.setString(2, subject);
            s.setString(3, action);
            s.setString(4, objectType);
            s.setObject(5, objectId);
            s.setString(6, before);
            s.setString(7, after);
            s.executeUpdate();
        }
    }

    public record NodeView(UUID id, UUID parentId, String code, String state,
                           int sortOrder, long version) {}
    public record LessonView(UUID id, String code, String state, int sortOrder,
                             long version) {}

    /** Return only worlds for which trusted membership provides access. */
    public List<NodeView> worlds(String subject) {
        actor(subject);
        return transact(db -> {
            var result = new java.util.ArrayList<NodeView>();
            try (var stmt = db.prepareStatement(
                    "SELECT w.world_id,w.code,w.status,w.sort_order,w.version "
                    + "FROM content.catalog_world w WHERE EXISTS "
                    + "(SELECT 1 FROM content.admin_membership m WHERE m.subject=? "
                    + "AND m.active=true AND m.organization_id IS NULL AND "
                    + "((m.role='PLATFORM_ADMIN' AND m.scope_world_id IS NULL) OR "
                    + "(m.role IN ('CONTENT_ADMIN','CONTENT_AUTHOR') AND m.scope_world_id=w.world_id))) "
                    + "ORDER BY w.sort_order,w.code")) {
                stmt.setString(1,subject);
                try (var rows=stmt.executeQuery()) {
                    while (rows.next()) result.add(new NodeView((UUID)rows.getObject(1),
                            null,rows.getString(2),rows.getString(3),rows.getInt(4),rows.getLong(5)));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<NodeView> areas(String subject, UUID worldId) {
        return nodesForParent(subject, "AREA", Objects.requireNonNull(worldId));
    }

    public List<NodeView> subjects(String subject, UUID areaId) {
        return nodesForParent(subject, "SUBJECT", Objects.requireNonNull(areaId));
    }

    private List<NodeView> nodesForParent(String subject, String kind, UUID parentId) {
        actor(subject);
        return transact(db -> {
            UUID worldId = "AREA".equals(kind) ? parentId : parentWorld(db,parentId);
            authorized(db,subject,worldId,true);
            String sql = "AREA".equals(kind)
                    ? "SELECT area_id,code,status,sort_order,version FROM content.catalog_area "
                        + "WHERE world_id=? ORDER BY sort_order,code"
                    : "SELECT subject_id,code,status,sort_order,version FROM content.catalog_subject "
                        + "WHERE area_id=? ORDER BY sort_order,code";
            var result = new java.util.ArrayList<NodeView>();
            try (var s=db.prepareStatement(sql)) {
                s.setObject(1,parentId);
                try (var rows=s.executeQuery()) {
                    while(rows.next()) result.add(new NodeView((UUID)rows.getObject(1),parentId,
                            rows.getString(2),rows.getString(3),rows.getInt(4),rows.getLong(5)));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<LessonView> lessons(String subject, UUID subjectId) {
        actor(subject);
        return transact(db -> {
            authorized(db,subject,worldForSubject(db,subjectId),true);
            var result=new java.util.ArrayList<LessonView>();
            try (var s=db.prepareStatement("SELECT l.lesson_id,l.code,l.state,p.sort_order,l.version "
                    + "FROM content.lesson_placement p JOIN content.catalog_lesson l "
                    + "ON l.lesson_id=p.lesson_id WHERE p.subject_id=? "
                    + "ORDER BY p.sort_order,l.code")) {
                s.setObject(1,subjectId);
                try (var rows=s.executeQuery()) {
                    while(rows.next()) result.add(new LessonView((UUID)rows.getObject(1),
                            rows.getString(2),rows.getString(3),rows.getInt(4),rows.getLong(5)));
                }
            }
            return List.copyOf(result);
        });
    }

    /** Optimistic and audited reordering without permitting reparenting. */
    public Change reorder(String subject, String kind, UUID id, int sortOrder,
                          long expectedVersion) {
        actor(subject);
        if (!List.of("WORLD","AREA","SUBJECT","LESSON").contains(kind))
            throw new IllegalArgumentException("Unknown node kind");
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid version");
        return transact(db -> {
            authorized(db,subject,worldForNode(db,kind,id),false);
            if ("LESSON".equals(kind)) throw new Conflict(
                    "Reorder lesson through a scoped placement, not global lesson");
            String table=switch(kind) {
                case "WORLD" -> "catalog_world";
                case "AREA" -> "catalog_area";
                case "SUBJECT" -> "catalog_subject";
                default -> throw new IllegalArgumentException("Invalid kind");
            };
            String column=kind.toLowerCase(java.util.Locale.ROOT)+"_id";
            String beforeState;
            try (var s=db.prepareStatement("SELECT status FROM content."+table
                    +" WHERE "+column+"=? FOR UPDATE")) {
                s.setObject(1,id);
                try(var rows=s.executeQuery()) {
                    if(!rows.next()) throw new Conflict("Missing node");
                    beforeState=rows.getString(1);
                }
            }
            try(var s=db.prepareStatement("UPDATE content."+table
                    +" SET sort_order=?,version=version+1 WHERE "+column+"=? AND version=?")) {
                s.setInt(1,sortOrder);
                s.setObject(2,id);
                s.setLong(3,expectedVersion);
                if(s.executeUpdate()!=1) throw new Conflict("Stale catalog version");
            }
            audit(db,subject,"REORDER",kind,id,beforeState,
                    beforeState+" sortOrder="+sortOrder);
            return new Change(id,beforeState,expectedVersion+1);
        });
    }

    public Created createWorld(String subject, String worldCode, int sortOrder) {
        actor(subject);
        code(worldCode);
        return transact(db -> {
            authorized(db, subject, null, false);
            UUID id = UUID.randomUUID();
            try (var s = db.prepareStatement(
                    "INSERT INTO content.catalog_world(world_id,code,sort_order) VALUES (?,?,?)")) {
                s.setObject(1, id);
                s.setString(2, worldCode);
                s.setInt(3, sortOrder);
                s.executeUpdate();
            }
            audit(db, subject, "CREATE", "WORLD", id, null, "DRAFT");
            return new Created(id, "DRAFT", 0);
        });
    }

    public Created createArea(String subject, UUID worldId, String areaCode, int sortOrder) {
        actor(subject);
        code(areaCode);
        return transact(db -> {
            authorized(db, subject, Objects.requireNonNull(worldId), false);
            UUID id = UUID.randomUUID();
            try (var s = db.prepareStatement("INSERT INTO content.catalog_area "
                    + "(area_id,world_id,code,sort_order) VALUES (?,?,?,?)")) {
                s.setObject(1, id);
                s.setObject(2, worldId);
                s.setString(3, areaCode);
                s.setInt(4, sortOrder);
                s.executeUpdate();
            }
            audit(db, subject, "CREATE", "AREA", id, null, "DRAFT");
            return new Created(id, "DRAFT", 0);
        });
    }

    public Created createSubject(String subject, UUID areaId, String subjectCode, int sortOrder) {
        actor(subject);
        code(subjectCode);
        return transact(db -> {
            UUID worldId = parentWorld(db, areaId);
            authorized(db, subject, worldId, false);
            UUID id = UUID.randomUUID();
            try (var s = db.prepareStatement("INSERT INTO content.catalog_subject "
                    + "(subject_id,area_id,code,sort_order) VALUES (?,?,?,?)")) {
                s.setObject(1, id);
                s.setObject(2, areaId);
                s.setString(3, subjectCode);
                s.setInt(4, sortOrder);
                s.executeUpdate();
            }
            audit(db, subject, "CREATE", "SUBJECT", id, null, "DRAFT");
            return new Created(id, "DRAFT", 0);
        });
    }

    public Created createLesson(String subject, UUID subjectId, String lessonCode, int sortOrder) {
        actor(subject);
        code(lessonCode);
        return transact(db -> {
            UUID world = worldForSubject(db, subjectId);
            authorized(db, subject, world, true);
            UUID lessonId = UUID.randomUUID();
            try (var s = db.prepareStatement("INSERT INTO content.catalog_lesson "
                    + "(lesson_id,code,created_by_subject) VALUES (?,?,?)")) {
                s.setObject(1, lessonId);
                s.setString(2, lessonCode);
                s.setString(3, subject);
                s.executeUpdate();
            }
            try (var s = db.prepareStatement("INSERT INTO content.lesson_placement "
                    + "(placement_id,lesson_id,world_id,area_id,subject_id,sort_order) "
                    + "SELECT ?,?,a.world_id,t.area_id,t.subject_id,? "
                    + "FROM content.catalog_subject t JOIN content.catalog_area a "
                    + "ON a.area_id=t.area_id WHERE t.subject_id=? AND a.world_id=?")) {
                s.setObject(1, UUID.randomUUID());
                s.setObject(2, lessonId);
                s.setInt(3, sortOrder);
                s.setObject(4, subjectId);
                s.setObject(5, world);
                if (s.executeUpdate() != 1) throw new Conflict("Subject placement changed");
            }
            audit(db, subject, "CREATE", "LESSON", lessonId, null, "DRAFT");
            return new Created(lessonId, "DRAFT", 0);
        });
    }

    /** An existing DRAFT lesson may be placed in a second subject by a
     * trusted CONTENT_ADMIN who is authorized for both the source and target.
     */
    public Created addPlacement(String subject, UUID lessonId, UUID targetSubjectId,
                                int sortOrder) {
        actor(subject);
        Objects.requireNonNull(lessonId);
        Objects.requireNonNull(targetSubjectId);
        return transact(db -> {
            UUID targetWorld=worldForSubject(db,targetSubjectId);
            authorized(db,subject,targetWorld,false);
            String state;
            try (var s=db.prepareStatement(
                    "SELECT state FROM content.catalog_lesson WHERE lesson_id=? FOR UPDATE")) {
                s.setObject(1,lessonId);
                try(var rows=s.executeQuery()) {
                    if(!rows.next()) throw new Conflict("Lesson missing");
                    state=rows.getString(1);
                }
            }
            if(!"DRAFT".equals(state)) throw new Conflict(
                    "Only draft lessons may change catalog placements");
            int current=0;
            try (var s=db.prepareStatement(
                    "SELECT DISTINCT world_id FROM content.lesson_placement WHERE lesson_id=?")) {
                s.setObject(1,lessonId);
                try(var rows=s.executeQuery()) {
                    while(rows.next()) {
                        current++;
                        authorized(db,subject,(UUID)rows.getObject(1),false);
                    }
                }
            }
            if(current==0) throw new Conflict("Lesson has no trusted placement");
            UUID placementId=UUID.randomUUID();
            try (var s=db.prepareStatement(
                    "INSERT INTO content.lesson_placement "
                    + "(placement_id,lesson_id,world_id,area_id,subject_id,sort_order) "
                    + "SELECT ?,?,a.world_id,t.area_id,t.subject_id,? "
                    + "FROM content.catalog_subject t JOIN content.catalog_area a "
                    + "ON a.area_id=t.area_id WHERE t.subject_id=? AND a.world_id=?")) {
                s.setObject(1,placementId);
                s.setObject(2,lessonId);
                s.setInt(3,sortOrder);
                s.setObject(4,targetSubjectId);
                s.setObject(5,targetWorld);
                if(s.executeUpdate()!=1) throw new Conflict("Target subject invalid");
            }
            audit(db,subject,"PLACE","LESSON",lessonId,null,placementId.toString());
            return new Created(placementId,"DRAFT",0);
        });
    }

    /** All translations are inserted and audited in one transaction.
     * No route or table name comes from client input.
     */
    public void translate(String subject, String kind, UUID nodeId,
                          String locale, String title) {
        actor(subject);
        if (!List.of("kk-KZ", "ru-RU", "en-US").contains(locale))
            throw new IllegalArgumentException("Unsupported locale");
        if (title == null || title.isBlank() || title.length() > 160)
            throw new IllegalArgumentException("Invalid title");
        String table = switch (kind) {
            case "WORLD" -> "world_translation";
            case "AREA" -> "area_translation";
            case "SUBJECT" -> "subject_translation";
            case "LESSON" -> "lesson_translation";
            default -> throw new IllegalArgumentException("Unknown catalog node kind");
        };
        String column = kind.toLowerCase(java.util.Locale.ROOT) + "_id";
        transact(db -> {
            UUID world = worldForNode(db, kind, nodeId);
            authorized(db, subject, world, "LESSON".equals(kind));
            try (var s = db.prepareStatement("INSERT INTO content." + table
                    + "(" + column + ",locale,title) VALUES (?,?,?) "
                    + "ON CONFLICT (" + column + ",locale) DO UPDATE SET title=EXCLUDED.title")) {
                s.setObject(1, nodeId);
                s.setString(2, locale);
                s.setString(3, title.strip());
                s.executeUpdate();
            }
            audit(db, subject, "TRANSLATE", kind, nodeId, null, locale);
            return null;
        });
    }

    private static UUID worldForNode(Connection db, String kind, UUID nodeId) throws SQLException {
        return switch (kind) {
            case "WORLD" -> {
                try (var s = db.prepareStatement("SELECT world_id FROM content.catalog_world WHERE world_id=?")) {
                    s.setObject(1, nodeId);
                    try (var rows = s.executeQuery()) {
                        if (!rows.next()) throw new Conflict("Unknown World");
                        yield (UUID) rows.getObject(1);
                    }
                }
            }
            case "AREA" -> parentWorld(db, nodeId);
            case "SUBJECT" -> worldForSubject(db, nodeId);
            case "LESSON" -> {
                try (var s = db.prepareStatement("SELECT DISTINCT world_id "
                        + "FROM content.lesson_placement WHERE lesson_id=?")) {
                    s.setObject(1, nodeId);
                    try (var rows = s.executeQuery()) {
                        if (!rows.next()) throw new Conflict("Lesson without placement");
                        UUID world = (UUID) rows.getObject(1);
                        if (rows.next()) throw new SecurityException(
                                "Multi-world lesson localization requires platform workflow");
                        yield world;
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown type");
        };
    }

    public Change transition(String subject, String kind, UUID id, String requested,
                             long expectedVersion) {
        actor(subject);
        if (!List.of("WORLD", "AREA", "SUBJECT", "LESSON").contains(kind))
            throw new IllegalArgumentException("Unknown type");
        if (!List.of("PUBLISHED", "ARCHIVED", "READY_FOR_REVIEW").contains(requested)
                || ("LESSON".equals(kind) && !"READY_FOR_REVIEW".equals(requested))
                || (!"LESSON".equals(kind) && "READY_FOR_REVIEW".equals(requested)))
            throw new Conflict("Uncertified lessons cannot be directly published");
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid version");
        return transact(db -> {
            UUID world = worldForNode(db, kind, id);
            authorized(db, subject, world, false);
            String table = switch(kind) {
                case "WORLD" -> "catalog_world";
                case "AREA" -> "catalog_area";
                case "SUBJECT" -> "catalog_subject";
                default -> "catalog_lesson";
            };
            String column = kind.toLowerCase(java.util.Locale.ROOT) + "_id";
            String statusColumn = "LESSON".equals(kind) ? "state" : "status";
            String previous;
            long version;
            try (var s = db.prepareStatement("SELECT " + statusColumn + ",version FROM content."
                    + table + " WHERE " + column + "=? FOR UPDATE")) {
                s.setObject(1, id);
                try (var rows = s.executeQuery()) {
                    if (!rows.next()) throw new Conflict("Catalog node missing");
                    previous = rows.getString(1);
                    version = rows.getLong(2);
                }
            }
            if (version != expectedVersion
                    || !("DRAFT".equals(previous)
                        || (!"LESSON".equals(kind) && "PUBLISHED".equals(previous)
                            && "ARCHIVED".equals(requested))))
                throw new Conflict("Stale version or invalid lifecycle transition");
            if ("PUBLISHED".equals(requested)) {
                // A published child must not outlive an unpublished parent.
                if ("AREA".equals(kind)) {
                    try (var s=db.prepareStatement("SELECT w.status FROM content.catalog_area a "
                            + "JOIN content.catalog_world w ON w.world_id=a.world_id WHERE a.area_id=?")) {
                        s.setObject(1,id);
                        try(var rows=s.executeQuery()) {
                            if (!rows.next() || !"PUBLISHED".equals(rows.getString(1)))
                                throw new Conflict("Parent World must be published first");
                        }
                    }
                }
                if ("SUBJECT".equals(kind)) {
                    try (var s=db.prepareStatement("SELECT a.status FROM content.catalog_subject t "
                            + "JOIN content.catalog_area a ON a.area_id=t.area_id WHERE t.subject_id=?")) {
                        s.setObject(1,id);
                        try(var rows=s.executeQuery()) {
                            if (!rows.next() || !"PUBLISHED".equals(rows.getString(1)))
                                throw new Conflict("Parent Area must be published first");
                        }
                    }
                }
                String translationTable = switch (kind) {
                    case "WORLD" -> "world_translation";
                    case "AREA" -> "area_translation";
                    case "SUBJECT" -> "subject_translation";
                    default -> throw new IllegalArgumentException("Unsupported publication");
                };
                try (var s = db.prepareStatement("SELECT count(*) FROM content."
                        + translationTable + " WHERE " + column + "=?")) {
                    s.setObject(1, id);
                    try (var rows = s.executeQuery()) {
                        rows.next();
                        if (rows.getInt(1) != 3)
                            throw new Conflict("All three localized titles required");
                    }
                }
            }
            if ("ARCHIVED".equals(requested)) {
                // Prevent hiding active published descendants from catalog discovery.
                String descendants = switch (kind) {
                    case "WORLD" -> "SELECT 1 FROM content.catalog_area "
                            + "WHERE world_id=? AND status='PUBLISHED' LIMIT 1";
                    case "AREA" -> "SELECT 1 FROM content.catalog_subject "
                            + "WHERE area_id=? AND status='PUBLISHED' LIMIT 1";
                    case "SUBJECT" -> "SELECT 1 FROM content.lesson_placement p "
                            + "JOIN content.catalog_lesson l ON l.lesson_id=p.lesson_id "
                            + "WHERE p.subject_id=? AND l.state='PUBLISHED' LIMIT 1";
                    default -> throw new Conflict("Only taxonomy nodes may be archived");
                };
                try (var s=db.prepareStatement(descendants)) {
                    s.setObject(1,id);
                    try (var rows=s.executeQuery()) {
                        if (rows.next()) throw new Conflict("Published descendants block archive");
                    }
                }
            }
            try (var s = db.prepareStatement("UPDATE content." + table + " SET "
                    + statusColumn + "=?,version=version+1 WHERE " + column
                    + "=? AND version=?")) {
                s.setString(1, requested);
                s.setObject(2, id);
                s.setLong(3, version);
                if (s.executeUpdate() != 1) throw new Conflict("Stale version");
            }
            audit(db, subject, "TRANSITION", kind, id, previous, requested);
            return new Change(id, requested, version + 1);
        });
    }
}

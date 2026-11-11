package academy.vrtraining.platform.content.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="VRTA_TEST_PG_JDBC_URL", matches=".+")
class PostgresContentAdministrationServiceIntegrationTest {
    private final String url = System.getenv("VRTA_TEST_PG_JDBC_URL");
    private final String user = System.getenv("VRTA_TEST_PG_USER");
    private final String password = System.getenv("VRTA_TEST_PG_PASSWORD");

    private PostgresContentAdministrationService service() {
        return new PostgresContentAdministrationService(url,user,password);
    }

    private void migrate() throws Exception {
        for (var path : new String[]{"db/migrations/R5_1__content_catalog_foundation.sql",
                                     "db/migrations/R5_2__content_administration.sql"}) {
            String sql = Files.readString(Path.of(path)).lines()
                    .map(line -> line.stripLeading().startsWith("--") ? "" : line)
                    .collect(java.util.stream.Collectors.joining("\n"));
            try (var db = DriverManager.getConnection(url,user,password);
                 var statement = db.createStatement()) {
                for (var command : sql.split(";"))
                    if (!command.isBlank()) statement.execute(command);
            }
        }
    }

    // Role enrollment is trusted DB bootstrap, NEVER exposed by the Admin API.
    private void grant(String subject, String role, UUID world, UUID organization) throws Exception {
        try (var db=DriverManager.getConnection(url,user,password);
             var stmt=db.prepareStatement("INSERT INTO content.admin_membership "
                  + "(membership_id,subject,role,scope_world_id,organization_id,granted_by) "
                  + "VALUES (?,?,?,?,?,'test-bootstrap')")) {
            stmt.setObject(1,UUID.randomUUID());
            stmt.setString(2,subject);
            stmt.setString(3,role);
            stmt.setObject(4,world);
            stmt.setObject(5,organization);
            assertEquals(1,stmt.executeUpdate());
        }
    }

    private long auditCount(UUID id) throws Exception {
        try (var db=DriverManager.getConnection(url,user,password);
             var stmt=db.prepareStatement("SELECT count(*) FROM content.admin_audit WHERE object_id=?")) {
            stmt.setObject(1,id);
            try (var rows=stmt.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    @Test void scopedAdminAndAuthorCanCreateExactTreeAndActionsAreAudited() throws Exception {
        migrate();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String root="platform-"+suffix, admin="editor-"+suffix, author="writer-"+suffix;
        grant(root,"PLATFORM_ADMIN",null,null);
        var svc=service();
        var world=svc.createWorld(root,"WORLD_R52_"+suffix,10);
        grant(admin,"CONTENT_ADMIN",world.id(),null);
        grant(author,"CONTENT_AUTHOR",world.id(),null);
        assertTrue(svc.worlds(admin).stream().anyMatch(x->x.id().equals(world.id())));
        assertFalse(svc.worlds(author).stream().noneMatch(x->x.id().equals(world.id())));
        assertEquals(0,svc.worlds("stranger-"+suffix).size());
        var area=svc.createArea(admin,world.id(),"AREA_PHYS_"+suffix,2);
        var subject=svc.createSubject(admin,area.id(),"SUBJECT_PHYS_"+suffix,2);
        var lesson=svc.createLesson(author,subject.id(),"PHY_001_"+suffix,1);
        assertTrue(svc.areas(author,world.id()).stream().anyMatch(x->x.id().equals(area.id())));
        assertTrue(svc.subjects(author,area.id()).stream().anyMatch(x->x.id().equals(subject.id())));
        assertEquals(lesson.id(),svc.lessons(author,subject.id()).getFirst().id());
        assertThrows(PostgresContentAdministrationService.Conflict.class,
                ()->svc.reorder(admin,"AREA",area.id(),7,99));
        assertEquals(1,svc.reorder(admin,"AREA",area.id(),7,0).version());

        assertEquals("DRAFT",lesson.state());
        assertEquals(1,auditCount(world.id()));
        assertEquals(2,auditCount(area.id()));
        assertEquals(1,auditCount(subject.id()));
        assertEquals(1,auditCount(lesson.id()));

        svc.translate(admin,"WORLD",world.id(),"ru-RU","Школа");
        svc.translate(admin,"WORLD",world.id(),"kk-KZ","Мектеп");
        assertThrows(PostgresContentAdministrationService.Conflict.class,
                ()->svc.transition(admin,"WORLD",world.id(),"PUBLISHED",0),
                "Incomplete translations forbid publication");
        svc.translate(admin,"WORLD",world.id(),"en-US","School");
        var published=svc.transition(admin,"WORLD",world.id(),"PUBLISHED",0);
        assertEquals(1,published.version());
        assertEquals("PUBLISHED",published.state());
        assertThrows(PostgresContentAdministrationService.Conflict.class,
                ()->svc.transition(admin,"WORLD",world.id(),"ARCHIVED",0),
                "Optimistic version/lifecycle prevents stale overwrite");
        assertThrows(PostgresContentAdministrationService.Conflict.class,
                ()->svc.transition(admin,"LESSON",lesson.id(),"PUBLISHED",0),
                "Lesson must not publish without native certification");
        assertEquals("READY_FOR_REVIEW",
                svc.transition(admin,"LESSON",lesson.id(),"READY_FOR_REVIEW",0).state());
        assertEquals(5,auditCount(world.id()));
        assertEquals(2,auditCount(lesson.id()));
    }

    @Test void forgedRolesWrongWorldOrganizationScopedMembersAndBadParentCannotWrite() throws Exception {
        migrate();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String root="platform-"+suffix, editor="editor-"+suffix, author="author-"+suffix;
        grant(root,"PLATFORM_ADMIN",null,null);
        var svc=service();
        var worldA=svc.createWorld(root,"WORLD_A_"+suffix,1);
        var worldB=svc.createWorld(root,"WORLD_B_"+suffix,2);
        grant(editor,"CONTENT_ADMIN",worldA.id(),null);
        grant(author,"CONTENT_AUTHOR",worldA.id(),UUID.randomUUID());
        var areaB=svc.createArea(root,worldB.id(),"AREA_B_"+suffix,1);
        assertThrows(SecurityException.class, ()->svc.createArea(editor,worldB.id(),"ESCAPE_"+suffix,1));
        assertThrows(SecurityException.class, ()->svc.createLesson(author,
                svc.createSubject(root,areaB.id(),"SUBJECT_B_"+suffix,1).id(),
                "FORGED_"+suffix,1));
        assertThrows(SecurityException.class, ()->svc.createWorld(editor,"WORLD_EVIL_"+suffix,1));
        assertThrows(SecurityException.class, ()->svc.createWorld("rogue-"+suffix,"WORLD_EVIL2_"+suffix,1));
        assertThrows(SecurityException.class, ()->svc.createArea("rogue-"+suffix,worldA.id(),"AREA_EVIL_"+suffix,1));
        assertThrows(SecurityException.class, ()->svc.translate(editor,"WORLD",worldB.id(),"ru-RU","Чужой мир"));
        assertThrows(SecurityException.class, ()->svc.areas(editor,worldB.id()));
        assertTrue(svc.worlds(editor).stream().noneMatch(x->x.id().equals(worldB.id())));
        assertThrows(PostgresContentAdministrationService.Conflict.class,
                ()->svc.createSubject(root,UUID.randomUUID(),"ORPHAN_"+suffix,1));
        assertEquals(0,auditCount(worldA.id())-1,
                "Failed attempts must not add audit entries");
    }

    @Test void roleRevocationIsImmediatelyEffectiveAndRollbackPreservesAtomicAudit() throws Exception {
        migrate();
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String root="root-"+suffix, editor="editor-"+suffix;
        grant(root,"PLATFORM_ADMIN",null,null);
        var svc=service();
        var world=svc.createWorld(root,"WORLD_LOCK_"+suffix,1);
        grant(editor,"CONTENT_ADMIN",world.id(),null);
        var activeArea=svc.createArea(editor,world.id(),"AREA_DUP_"+suffix,1);
        var activeSubject=svc.createSubject(editor,activeArea.id(),"SUBJECT_ACTIVE_"+suffix,1);
        assertThrows(PostgresContentAdministrationService.Conflict.class,
                ()->svc.createArea(editor,world.id(),"AREA_DUP_"+suffix,1));
        try (var db=DriverManager.getConnection(url,user,password);
             var stmt=db.prepareStatement(
                     "UPDATE content.admin_membership SET active=false WHERE subject=?")) {
            stmt.setString(1,editor);
            assertEquals(1,stmt.executeUpdate());
        }
        assertThrows(SecurityException.class,
                ()->svc.createArea(editor,world.id(),"AREA_AFTER_REVOKE_"+suffix,1));
        assertThrows(SecurityException.class, ()->svc.createLesson(editor,activeSubject.id(),
                "LESSON_DENIED_"+suffix,1));
        assertEquals(1,auditCount(world.id()));
    }
}

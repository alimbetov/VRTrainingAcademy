package academy.vrtraining.platform.content.upload;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="VRTA_TEST_PG_JDBC_URL",matches=".+")
class LessonUploadSessionsIntegrationTest {
    private final String url=System.getenv("VRTA_TEST_PG_JDBC_URL");
    private final String user=System.getenv("VRTA_TEST_PG_USER");
    private final String password=System.getenv("VRTA_TEST_PG_PASSWORD");

    private void migrations() throws Exception {
        for(var file:new String[]{
                "db/migrations/R5_1__content_catalog_foundation.sql",
                "db/migrations/R5_2__content_administration.sql",
                "db/migrations/R5_4__lesson_upload_session.sql"}){
            var sql=Files.readString(Path.of(file)).lines()
                  .map(line->line.stripLeading().startsWith("--")?"":line)
                  .collect(java.util.stream.Collectors.joining("\n"));
            try(var db=DriverManager.getConnection(url,user,password);
                var stmt=db.createStatement()){
                for(var part:sql.split(";"))if(!part.isBlank())stmt.execute(part);
            }
        }
    }
    private void grant(String actor,UUID world) throws Exception {
        try(var db=DriverManager.getConnection(url,user,password);
            var stmt=db.prepareStatement("INSERT INTO content.admin_membership "
                    +"(membership_id,subject,role,scope_world_id,granted_by) "
                    +"VALUES (?,?,'CONTENT_AUTHOR',?,'integration-test')")){
            stmt.setObject(1,UUID.randomUUID());stmt.setString(2,actor);
            stmt.setObject(3,world);stmt.executeUpdate();
        }
    }
    private record Fixture(String author,UUID world,UUID subject,UUID lesson){}
    private Fixture setup() throws Exception {
        var repo=new academy.vrtraining.platform.content.catalog.PostgresContentCatalogStore(url,user,password);
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var world=repo.createWorld("WORLD_UPLOAD_"+suffix,1);
        var area=repo.createArea(world.id(),"AREA_UPLOAD_"+suffix,1);
        var subject=repo.createSubject(area.id(),"SUBJECT_UPLOAD_"+suffix,1);
        var lesson=repo.createLessonInSubject("LESSON_UPLOAD_"+suffix,"author-"+suffix,
                 world.id(),area.id(),subject.id(),1);
        grant("author-"+suffix,world.id());
        return new Fixture("author-"+suffix,world.id(),subject.id(),lesson.lessonId());
    }
    private static String hash(byte[] bytes)throws Exception{
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static LessonUploadSessions.ArtifactRequest metadata(byte[] bytes)throws Exception {
        return new LessonUploadSessions.ArtifactRequest("pakchunk1001-Android_ASTC.pak",
                bytes.length,hash(bytes),"0.1.0-foundation",
                "/Game/Lessons/Physics/L_Electricity");
    }
    @Test void trustedSubjectAndLessonPlacementAreBoundAndIdempotent()throws Exception {
        migrations();
        var f=setup();
        var writes=new AtomicInteger();
        var service=new LessonUploadSessions(url,user,password,(file,key)->{
            writes.incrementAndGet();
            assertTrue(key.startsWith("quarantine/quest-lessons/"+f.lesson()+"/"));
            assertEquals("pak payload",Files.readString(file));
        });
        String idempotency="upload-key-12345678";
        var session=service.create(f.author(),f.lesson(),f.subject(),idempotency);
        assertEquals("CREATED",session.state());
        assertEquals(session.uploadId(),service.create(f.author(),f.lesson(),f.subject(),idempotency).uploadId());
        assertThrows(SecurityException.class,()->service.status("forged",session.uploadId()));
        assertThrows(SecurityException.class,()->service.create("forged",f.lesson(),f.subject(),"different-key-123456"));
        assertThrows(LessonUploadSessions.Conflict.class,()->service.create(
                f.author(),f.lesson(),UUID.randomUUID(),"another-key-123456"));
        byte[] bytes="pak payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var staged=service.stage(f.author(),session.uploadId(),metadata(bytes),
                new ByteArrayInputStream(bytes));
        assertEquals("STAGED",staged.state());
        assertEquals(f.lesson(),staged.lessonId());
        assertEquals("STAGED",service.status(f.author(),session.uploadId()).state());
        assertEquals(1,writes.get());
        assertThrows(LessonUploadSessions.Conflict.class,()->service.stage(
                f.author(),session.uploadId(),metadata(bytes),new ByteArrayInputStream(bytes)));
        assertEquals(1,writes.get());
        try(var db=DriverManager.getConnection(url,user,password);
            var s=db.prepareStatement("SELECT count(*) FROM content.admin_audit "
                    +"WHERE object_id=? AND action='UPLOAD_STAGED'")){
            s.setObject(1,session.uploadId());
            try(var rows=s.executeQuery()){rows.next();assertEquals(1,rows.getInt(1));}
        }
    }
    @Test void corruptedPayloadNeverBecomesStagedAndRemainsQuarantined()throws Exception{
        migrations();var f=setup();
        var writes=new AtomicInteger();
        var service=new LessonUploadSessions(url,user,password,(file,key)->writes.incrementAndGet());
        var session=service.create(f.author(),f.lesson(),f.subject(),"hash-failure-key-12345");
        byte[] bytes={1,2,3,4};
        assertThrows(IllegalArgumentException.class,()->service.stage(f.author(),
                session.uploadId(),metadata(bytes),new ByteArrayInputStream(new byte[]{1,2,3,5})));
        assertEquals(0,writes.get());
        assertEquals("UPLOADING",service.status(f.author(),session.uploadId()).state(),
               "Failed transfer must not be reported as STAGED or certified");
    }
}

package academy.vrtraining.platform.content.upload;

import academy.vrtraining.platform.content.QuestLessonUploadService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * R5.4 durable, subject-bound upload. A client never supplies lessonId,
 * organizationId, collectionId or releaseId in the artifact metadata.
 * All content remains quarantined; publication is a separate trust gate.
 */
public final class LessonUploadSessions {
    @FunctionalInterface public interface QuarantineStore {
        void store(Path verifiedArtifact, String key) throws IOException;
    }
    public record Session(UUID uploadId, UUID lessonId, UUID subjectId, String state) {}
    public record ArtifactRequest(String artifactName, long sizeBytes, String sha256Hex,
                                  String runtimeBuildId, String entryLevel) {}
    public record Staged(UUID uploadId, UUID lessonId, String state,
                         long sizeBytes, String sha256Hex) {}
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {super(message);}
    }
    private final String url, user, password;
    private final QuarantineStore store;

    public LessonUploadSessions(String url, String user, String password,
                                QuarantineStore store) {
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL required");
        this.url=url;
        this.user=Objects.requireNonNull(user);
        this.password=Objects.requireNonNull(password);
        this.store=Objects.requireNonNull(store);
    }
    private Connection db() throws SQLException {
        return DriverManager.getConnection(url,user,password);
    }
    private static String actor(String who) {
        if(who==null || who.isBlank() || who.length()>255)
            throw new SecurityException("Verified author required");
        return who;
    }
    private static void authorized(Connection db, String who, UUID world) throws SQLException {
        try (var s=db.prepareStatement(
                "SELECT 1 FROM content.admin_membership WHERE subject=? "
                +"AND active=true AND organization_id IS NULL AND "
                +"((role='PLATFORM_ADMIN' AND scope_world_id IS NULL) "
                +"OR (role IN ('CONTENT_ADMIN','CONTENT_AUTHOR') AND scope_world_id=?)) LIMIT 1")) {
            s.setString(1,who);s.setObject(2,world);
            try(var rows=s.executeQuery()){
                if(!rows.next())throw new SecurityException("Author not authorized for catalog World");
            }
        }
    }
    private static void audit(Connection db, String who, UUID id, String action, String value)
            throws SQLException {
        try(var s=db.prepareStatement("INSERT INTO content.admin_audit "
                +"(event_id,actor_subject,action,object_type,object_id,after_state) "
                +"VALUES (?, ?, ?, 'UPLOAD', ?, ?)")){
            s.setObject(1,UUID.randomUUID());s.setString(2,who);
            s.setString(3,action);s.setObject(4,id);s.setString(5,value);
            s.executeUpdate();
        }
    }

    public Session create(String subject, UUID lessonId, UUID selectedSubjectId,
                          String idempotencyKey) {
        actor(subject);
        Objects.requireNonNull(lessonId);Objects.requireNonNull(selectedSubjectId);
        if(idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9._-]{16,128}"))
            throw new IllegalArgumentException("Idempotency-Key must be 16..128 safe chars");
        try(var db=db()){
            db.setAutoCommit(false);
            try {
                UUID world;
                try(var s=db.prepareStatement(
                    "SELECT p.world_id FROM content.lesson_placement p "
                    +"JOIN content.catalog_lesson l ON l.lesson_id=p.lesson_id "
                    +"WHERE p.lesson_id=? AND p.subject_id=? AND l.state='DRAFT' "
                    +"FOR SHARE OF p,l")){
                    s.setObject(1,lessonId);s.setObject(2,selectedSubjectId);
                    try(var rows=s.executeQuery()){
                        if(!rows.next())throw new Conflict("Lesson has no DRAFT placement in selected Subject");
                        world=(UUID)rows.getObject(1);
                    }
                }
                authorized(db,subject,world);
                UUID uploadId=UUID.randomUUID();
                try(var s=db.prepareStatement(
                    "INSERT INTO content.lesson_upload_session "
                    +"(upload_id,lesson_id,subject_id,world_id,created_by_subject,idempotency_key) "
                    +"VALUES (?,?,?,?,?,?) ON CONFLICT (created_by_subject,lesson_id,idempotency_key) "
                    +"DO NOTHING")){
                    s.setObject(1,uploadId);s.setObject(2,lessonId);s.setObject(3,selectedSubjectId);
                    s.setObject(4,world);s.setString(5,subject);s.setString(6,idempotencyKey);
                    s.executeUpdate();
                }
                try(var s=db.prepareStatement(
                    "SELECT upload_id,subject_id,state FROM content.lesson_upload_session "
                    +"WHERE created_by_subject=? AND lesson_id=? AND idempotency_key=?")){
                    s.setString(1,subject);s.setObject(2,lessonId);s.setString(3,idempotencyKey);
                    try(var rows=s.executeQuery()){
                        if(!rows.next())throw new IllegalStateException("Upload session was not created");
                        UUID selected=(UUID)rows.getObject(2);
                        if(!selectedSubjectId.equals(selected))
                            throw new Conflict("Idempotency-Key is already bound to a different Subject");
                        uploadId=(UUID)rows.getObject(1);
                        String state=rows.getString(3);
                        if("CREATED".equals(state))audit(db,subject,uploadId,"UPLOAD_CREATED","CREATED");
                        db.commit();
                        return new Session(uploadId,lessonId,selected,state);
                    }
                }
            }catch(SQLException|RuntimeException e){
                try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}
                throw e;
            }
        }catch(SQLException e){throw new IllegalStateException("Upload session unavailable",e);}
    }

    private record Locked(UUID lessonId, UUID subjectId, UUID worldId, String state) {}
    private Locked locked(Connection db, String subject, UUID id) throws SQLException {
        try(var s=db.prepareStatement("SELECT lesson_id,subject_id,world_id,state "
                 +"FROM content.lesson_upload_session WHERE upload_id=? "
                 +"AND created_by_subject=? FOR UPDATE")){
            s.setObject(1,id);s.setString(2,subject);
            try(var rows=s.executeQuery()){
                if(!rows.next())throw new SecurityException("Unknown upload or unauthorized owner");
                UUID world=(UUID)rows.getObject(3);
                authorized(db,subject,world);
                return new Locked((UUID)rows.getObject(1),(UUID)rows.getObject(2),
                        world,rows.getString(4));
            }
        }
    }
    public Session status(String subject, UUID id) {
        actor(subject);Objects.requireNonNull(id);
        try(var db=db()){
            var item=locked(db,subject,id);
            return new Session(id,item.lessonId(),item.subjectId(),item.state());
        }catch(SQLException e){throw new IllegalStateException("Upload status unavailable",e);}
    }

    public Staged stage(String subject, UUID id, ArtifactRequest metadata,
                        InputStream stream) throws IOException {
        actor(subject);Objects.requireNonNull(id);Objects.requireNonNull(stream);
        validate(metadata);
        // Reserve the idempotent upload session BEFORE receiving artifact bytes.
        UUID lessonId;
        try(var db=db()){
            db.setAutoCommit(false);
            try{
                var item=locked(db,subject,id);
                if(!"CREATED".equals(item.state()))
                    throw new Conflict("Upload must be CREATED, not "+item.state());
                lessonId=item.lessonId();
                try(var s=db.prepareStatement("UPDATE content.lesson_upload_session "
                         +"SET state='UPLOADING',updated_at=now() WHERE upload_id=?")){
                    s.setObject(1,id);s.executeUpdate();
                }
                db.commit();
            }catch(SQLException|RuntimeException e){
                try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}
                throw e;
            }
        }catch(SQLException e){throw new IllegalStateException("Unable to reserve upload",e);}

        Path temp=Files.createTempFile("vrta-upload-session-", ".part");
        try {
            var sha=MessageDigest.getInstance("SHA-256");
            long total=0;
            try(OutputStream out=Files.newOutputStream(temp)){
                byte[] chunk=new byte[64*1024];
                int count;
                while((count=stream.read(chunk))!=-1){
                    total=Math.addExact(total,count);
                    if(total>metadata.sizeBytes() || total>QuestLessonUploadService.MAX_ARTIFACT_BYTES)
                        throw new IllegalArgumentException("Artifact exceeds declared size");
                    sha.update(chunk,0,count);out.write(chunk,0,count);
                }
            }
            String digest=HexFormat.of().formatHex(sha.digest());
            if(total!=metadata.sizeBytes() || !MessageDigest.isEqual(
                    HexFormat.of().parseHex(digest),HexFormat.of().parseHex(metadata.sha256Hex())))
                throw new IllegalArgumentException("Artifact size/SHA-256 mismatch");
            String key="quarantine/quest-lessons/"+lessonId+"/"+id+"/"+metadata.artifactName();
            store.store(temp,key);
            // S3 object is not published and becomes visible only after durable STAGED receipt.
            try(var db=db()){
                db.setAutoCommit(false);
                try{
                    var item=locked(db,subject,id);
                    if(!"UPLOADING".equals(item.state()))
                        throw new Conflict("Session state changed during artifact upload");
                    try(var s=db.prepareStatement("UPDATE content.lesson_upload_session "
                         +"SET state='STAGED',artifact_name=?,artifact_sha256=?, "
                         +"artifact_size_bytes=?,object_key=?,runtime_build_id=?,entry_level=?,"
                         +"updated_at=now() WHERE upload_id=? AND state='UPLOADING'")){
                        s.setString(1,metadata.artifactName());s.setString(2,digest);
                        s.setLong(3,total);s.setString(4,key);
                        s.setString(5,metadata.runtimeBuildId());s.setString(6,metadata.entryLevel());
                        s.setObject(7,id);
                        if(s.executeUpdate()!=1)throw new Conflict("Lost upload reservation");
                    }
                    audit(db,subject,id,"UPLOAD_STAGED",digest);
                    db.commit();
                }catch(SQLException|RuntimeException e){
                    try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}
                    throw e;
                }
            }catch(SQLException e){throw new IllegalStateException("Cannot finalize staged upload",e);}
            return new Staged(id,lessonId,"STAGED",total,digest);
        }catch(java.security.NoSuchAlgorithmException e){
            throw new IllegalStateException(e);
        }finally{
            Files.deleteIfExists(temp);
        }
    }

    public static void validate(ArtifactRequest r){
        if(r==null || r.artifactName()==null
           || !r.artifactName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.pak")
           || r.artifactName().contains("..")
           || r.sizeBytes()<=0 || r.sizeBytes()>QuestLessonUploadService.MAX_ARTIFACT_BYTES
           || r.sha256Hex()==null || !r.sha256Hex().matches("[0-9a-f]{64}")
           || !"0.1.0-foundation".equals(r.runtimeBuildId())
           || r.entryLevel()==null || !r.entryLevel().matches(
               "/Game/Lessons/(?:[A-Za-z][A-Za-z0-9_]*/)*[A-Za-z][A-Za-z0-9_]*"))
           throw new IllegalArgumentException("Invalid Quest artifact metadata");
    }
}

package academy.vrtraining.education.classroom;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Classroom feature is disabled by default. No device endpoint is published.
 * Before enabling any HTTP controller, replace the fail-closed auth adapter
 * with verified teacher membership and cryptographic device authentication.
 */
@Configuration
public class ClassroomRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(name="vr.classroom.persistence-enabled", havingValue="true")
    ClassroomStore postgresClassroomStore(
            @Value("${vr.classroom.jdbc-url:}") String url,
            @Value("${vr.classroom.jdbc-user:}") String user,
            @Value("${vr.classroom.jdbc-password:}") String password) {
        return new PostgresClassroomStore(url, user, password);
    }

    @Bean
    @ConditionalOnMissingBean(ClassroomAuthorization.class)
    ClassroomAuthorization denyClassroomAccess() {
        return new ClassroomAuthorization() {
            public boolean teacherMayCreateClassroom(String teacher, String tenant) { return false; }
            public boolean teacherMayManage(String teacher, String tenant, java.util.UUID classroom) {
                return false;
            }
            public boolean deviceMayAttend(java.util.UUID device, String tenant, java.util.UUID session) {
                return false;
            }
        };
    }

    @Bean
    @ConditionalOnBean(ClassroomStore.class)
    ClassroomSessionService classroomSessionService(ClassroomStore store, ClassroomAuthorization authorization) {
        return new ClassroomSessionService(store, authorization, Clock.systemUTC());
    }
}

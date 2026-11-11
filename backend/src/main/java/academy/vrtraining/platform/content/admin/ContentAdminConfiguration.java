package academy.vrtraining.platform.content.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OFF unless admin membership migration and trusted OIDC are configured. */
@Configuration
@ConditionalOnProperty(name = "vr.content.admin.enabled", havingValue = "true")
public class ContentAdminConfiguration {
    @Bean
    PostgresContentAdministrationService postgresContentAdministrationService(
            @Value("${vr.content.admin.jdbc-url:}") String url,
            @Value("${vr.content.admin.jdbc-user:}") String user,
            @Value("${vr.content.admin.jdbc-password:}") String password) {
        return new PostgresContentAdministrationService(url, user, password);
    }
}

package academy.vrtraining.platform.licensing;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feature OFF until database migration, email_verified-capable OIDC and
 * trusted platform admin membership are deployed.
 */
@Configuration
@ConditionalOnProperty(name="vr.licensing.persistence-enabled", havingValue="true")
public class LicensingRuntimeConfiguration {
    @Bean
    LicenseStore postgresLicenseStore(
            @Value("${vr.licensing.jdbc-url:}") String url,
            @Value("${vr.licensing.jdbc-user:}") String user,
            @Value("${vr.licensing.jdbc-password:}") String password) {
        return new PostgresLicenseStore(url, user, password);
    }

    @Bean
    @ConditionalOnMissingBean(LicensingAuthorization.class)
    LicensingAuthorization denyAllPlatformAdministration() {
        return subject -> false;
    }

    @Bean
    @ConditionalOnMissingBean(LicensableCollectionCatalog.class)
    LicensableCollectionCatalog denyUnpublishedCollections() { return ids -> false; }

    @Bean
    OrganizationLicensingService organizationLicensingService(
            LicenseStore store, LicensingAuthorization auth, LicensableCollectionCatalog catalog) {
        return new OrganizationLicensingService(store, auth, catalog, Clock.systemUTC());
    }
}

package academy.vrtraining.platform.licensing;

import academy.vrtraining.platform.delivery.grant.DownloadEntitlementPort;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * No published Content Domain adapter means no licensed download.
 * Explicit opt-in requires organization licensing persistence as well.
 */
@Configuration
public class LicensedDownloadRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(PublishedContentCollectionPort.class)
    PublishedContentCollectionPort denyUnpublishedContentCollections() {
        return contentId -> Optional.empty();
    }

    @Bean
    @ConditionalOnProperty(name="vr.licensing.download-authorization-enabled",havingValue="true")
    DownloadEntitlementPort licensedOwnerDownloadEntitlement(
            LicenseStore licenses, PublishedContentCollectionPort published,
            @Value("${vr.licensing.persistence-enabled:false}") boolean licensingEnabled) {
        if (!licensingEnabled)
            throw new IllegalStateException("Download authorization requires organization licensing");
        return new LicensedOwnerDownloadEntitlement(licenses, published, Clock.systemUTC());
    }
}

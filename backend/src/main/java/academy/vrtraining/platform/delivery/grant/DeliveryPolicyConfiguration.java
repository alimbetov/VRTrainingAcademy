package academy.vrtraining.platform.delivery.grant;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/** No persistent release/entitlement adapter means no download entitlement. */
@Configuration
public class DeliveryPolicyConfiguration {
    @Bean @ConditionalOnMissingBean(PublishedReleasePort.class)
    PublishedReleasePort denyUnpublishedReleases() {
        return (contentId, platform, runtimeBuildId) -> Optional.empty();
    }
    @Bean @ConditionalOnMissingBean(DownloadEntitlementPort.class)
    DownloadEntitlementPort denyAllDownloads() { return (subject, tenant, content) -> false; }
}

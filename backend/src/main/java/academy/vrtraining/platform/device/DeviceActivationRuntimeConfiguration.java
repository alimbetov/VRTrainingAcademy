package academy.vrtraining.platform.device;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import academy.vrtraining.platform.licensing.LicenseStore;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in device service wiring. Deny all organization approval unless provided. */
@Configuration
@ConditionalOnProperty(name="vr.device.activation-enabled", havingValue="true")
public class DeviceActivationRuntimeConfiguration {
    @Bean
    PostgresDeviceActivationStore postgresDeviceActivationStore(
            @Value("${vr.device.jdbc-url:}") String url,
            @Value("${vr.device.jdbc-user:}") String user,
            @Value("${vr.device.jdbc-password:}") String password,
            @Value("${vr.licensing.persistence-enabled:false}") boolean licensingEnabled) {
        if (!licensingEnabled)
            throw new IllegalStateException("Device activation requires licensed organization persistence");
        return new PostgresDeviceActivationStore(url, user, password, true);
    }

    @Bean
    @ConditionalOnMissingBean(DeviceActivationService.AdministratorAuthorization.class)
    DeviceActivationService.AdministratorAuthorization organizationOwnerActivationAuthority(
            ObjectProvider<LicenseStore> licenseStore) {
        return new DeviceActivationService.AdministratorAuthorization() {
            private LicenseStore store() { return licenseStore.getIfAvailable(); }
            private UUID ownerOrganization(String subject, String tenant) {
                if (subject == null || tenant == null || store() == null) return null;
                try {
                    UUID orgId = UUID.fromString(tenant);
                    return store().organization(orgId)
                            .filter(o -> subject.equals(o.ownerSubject()))
                            .map(o -> o.id()).orElse(null);
                } catch (IllegalArgumentException e) { return null; }
            }
            @Override public boolean mayActivateDevice(String subject, String tenant) {
                UUID orgId = ownerOrganization(subject, tenant);
                return orgId != null && store().entitlements(orgId, Clock.systemUTC().instant()).active();
            }
            @Override public boolean mayRevokeDevice(String subject, String tenant) {
                return ownerOrganization(subject, tenant) != null;
            }
        };
    }

    @Bean
    DeviceActivationService deviceActivationService(
            PostgresDeviceActivationStore store,
            DeviceActivationService.AdministratorAuthorization authorization,
            @Value("${vr.device.activation-pepper-b64:}") String pepperB64) {
        byte[] pepper = Base64.getDecoder().decode(pepperB64);
        return new DeviceActivationService(store, authorization, Clock.systemUTC(),
                new SecureRandom(), pepper);
    }
}

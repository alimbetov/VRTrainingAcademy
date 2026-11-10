package academy.vrtraining.platform.device;

import java.security.SecureRandom;
import java.time.Clock;
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
            @Value("${vr.device.jdbc-password:}") String password) {
        return new PostgresDeviceActivationStore(url, user, password);
    }

    @Bean
    @ConditionalOnMissingBean(DeviceActivationService.AdministratorAuthorization.class)
    DeviceActivationService.AdministratorAuthorization denyDeviceAdministration() {
        return new DeviceActivationService.AdministratorAuthorization() {
            @Override public boolean mayActivateDevice(String subject, String tenant) { return false; }
            @Override public boolean mayRevokeDevice(String subject, String tenant) { return false; }
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

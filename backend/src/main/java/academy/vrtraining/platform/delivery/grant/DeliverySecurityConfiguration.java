package academy.vrtraining.platform.delivery.grant;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class DeliverySecurityConfiguration {
    @Bean SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.GET, "/api/v1/public/catalog").permitAll()
                    .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                    .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> {}))
                .build();
    }

    /**
     * If an issuer/audience is missing, all bearer tokens fail closed while
     * the public catalog remains available for standalone R0 development.
     */
    @Bean JwtDecoder jwtDecoder(
            @Value("${vr.auth.issuer-uri:}") String issuer,
            @Value("${vr.auth.audience:}") String audience) {
        if (issuer.isBlank() || audience.isBlank())
            return token -> { throw new JwtException("OIDC issuer and audience not configured"); };
        var decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> base = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(jwt -> {
            var result = base.validate(jwt);
            if (result.hasErrors()) return result;
            List<String> audiences = jwt.getAudience();
            return audiences != null && audiences.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Invalid token audience", null));
        });
        return decoder;
    }
}

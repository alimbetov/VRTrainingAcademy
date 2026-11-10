package academy.vrtraining.platform.licensing;

import academy.vrtraining.platform.licensing.LicenseContracts.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-service organization enrollment after verified OIDC account creation.
 * No password, tenantId or unverified email is accepted in request body.
 */
@RestController
@ConditionalOnProperty(name="vr.licensing.persistence-enabled", havingValue="true")
public class OrganizationLicensingController {
    private final OrganizationLicensingService service;

    public OrganizationLicensingController(OrganizationLicensingService service) {
        this.service = service;
    }

    public record Register(@NotBlank @Size(max=120) String organizationName) {}
    public record RequestCommercial(@Min(1) @Max(10000) int deviceLimit,
                                    @NotEmpty Set<@NotBlank String> collections) {}
    public record Approve(@Min(1) @Max(10000) int deviceLimit,
                          @NotEmpty Set<@NotBlank String> collections,
                          @NotNull Instant expiresAt) {}

    @PostMapping("/api/v1/organizations/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Organization register(@AuthenticationPrincipal Jwt jwt,
                                 @Valid @RequestBody Register request) {
        Jwt verified = requireJwt(jwt);
        return service.register(verified.getSubject(), verified.getClaimAsString("email"),
                Boolean.TRUE.equals(verified.getClaim("email_verified")), request.organizationName());
    }

    @GetMapping("/api/v1/organizations/me")
    public Organization me(@AuthenticationPrincipal Jwt jwt) {
        return service.myOrganization(requireJwt(jwt).getSubject());
    }

    @GetMapping("/api/v1/organizations/me/entitlements")
    public Entitlements entitlements(@AuthenticationPrincipal Jwt jwt) {
        return service.myEntitlements(requireJwt(jwt).getSubject());
    }

    @PostMapping("/api/v1/organizations/me/license-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LicenseRequest request(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody RequestCommercial body) {
        return service.requestCommercial(requireJwt(jwt).getSubject(),
                body.deviceLimit(), body.collections());
    }

    @GetMapping("/api/v1/platform-admin/license-requests/{requestId}")
    public LicenseRequest adminRequest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
        return service.getRequestForAdmin(requireJwt(jwt).getSubject(), requestId);
    }

    @PostMapping("/api/v1/platform-admin/license-requests/{requestId}/approve")
    @ResponseStatus(HttpStatus.CREATED)
    public License approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId,
                           @Valid @RequestBody Approve body) {
        return service.grant(requireJwt(jwt).getSubject(), requestId,
                body.deviceLimit(), body.collections(), body.expiresAt());
    }

    private static Jwt requireJwt(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated account required");
        return jwt;
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void forbidden() {}

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalidInput() {}

    @ExceptionHandler(LicenseConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void conflict() {}
}

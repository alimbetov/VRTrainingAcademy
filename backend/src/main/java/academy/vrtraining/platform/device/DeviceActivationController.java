package academy.vrtraining.platform.device;

import academy.vrtraining.platform.licensing.LicenseStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device enrollment APIs. Public begin/complete use a one-time code plus
 * Ed25519 proof; approval requires authenticated organization OWNER.
 *
 * IMPORTANT: no authenticated device API is created by these endpoints.
 * The next stage must issue sender-constrained credentials for future calls.
 */
@RestController
@ConditionalOnProperty(name="vr.device.activation-enabled", havingValue="true")
public class DeviceActivationController {
    private final DeviceActivationService service;
    private final LicenseStore licenses;

    public DeviceActivationController(DeviceActivationService service, LicenseStore licenses) {
        this.service = service;
        this.licenses = licenses;
    }

    public record Begin(@NotBlank String publicKeySpkiBase64) {}
    public record Approve(@NotNull UUID flowId, @NotBlank String userCode) {}
    public record Complete(@NotBlank String signatureBase64) {}
    public record CompletedDevice(UUID deviceId, String organizationId, String state) {}

    @PostMapping("/api/v1/device-activations")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceActivationService.Activation begin(@Valid @RequestBody Begin request)
            throws GeneralSecurityException {
        byte[] encoded = decodeBounded(request.publicKeySpkiBase64(), 128);
        PublicKey publicKey = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(encoded));
        return service.begin(publicKey);
    }

    @PostMapping("/api/v1/organizations/me/device-activation-approvals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Approve request)
            throws GeneralSecurityException {
        if (jwt == null || jwt.getSubject() == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        UUID organizationId = licenses.byOwner(jwt.getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN))
                .id();
        service.approve(jwt.getSubject(), organizationId.toString(),
                request.flowId(), request.userCode());
    }

    @PostMapping("/api/v1/device-activations/{flowId}/complete")
    public CompletedDevice complete(@PathVariable UUID flowId,
            @Valid @RequestBody Complete request) throws GeneralSecurityException {
        byte[] proof = decodeBounded(request.signatureBase64(), 64);
        if (proof.length != 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid device proof");
        var device = service.complete(flowId, proof);
        return new CompletedDevice(device.deviceId(), device.tenantId(), device.state().name());
    }

    private static byte[] decodeBounded(String base64, int maxBytes) {
        if (base64 == null || base64.length() > maxBytes * 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid encoded field");
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            if (bytes.length == 0 || bytes.length > maxBytes)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid field size");
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid base64");
        }
    }

    @ExceptionHandler(GeneralSecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public void denied() {}
}

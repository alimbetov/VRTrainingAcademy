package academy.vrtraining.platform.delivery.grant;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Never accept a tenant ID or user identity from request parameters. */
@RestController
@RequestMapping("/api/v1/me/content")
public class DownloadGrantController {
    private final DownloadGrantService service;
    public DownloadGrantController(DownloadGrantService service) { this.service = service; }

    @PostMapping("/{contentId}/download-grants")
    public DownloadGrantService.Grant create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String contentId, @RequestParam String platform,
            @RequestParam String runtimeBuildId) {
        if (jwt == null || jwt.getSubject() == null || !jwt.hasClaim("tenant_id"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trusted tenant claim required");
        String tenant = jwt.getClaimAsString("tenant_id");
        return service.issue(jwt.getSubject(), tenant, contentId, platform, runtimeBuildId);
    }
}

package academy.vrtraining.platform.delivery.grant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import academy.vrtraining.VrTrainingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = VrTrainingApplication.class)
@AutoConfigureMockMvc
class DownloadGrantControllerSecurityTest {
    @Autowired MockMvc mvc;

    @Test void anonymousGrantRequestIsRejected() throws Exception {
        mvc.perform(post("/api/v1/me/content/physics/download-grants")
                        .param("platform", "quest").param("runtimeBuildId", "runtime-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test void missingTenantClaimIsRejected() throws Exception {
        mvc.perform(post("/api/v1/me/content/physics/download-grants")
                        .with(jwt().jwt(t -> t.subject("user-1")))
                        .param("platform", "quest").param("runtimeBuildId", "runtime-1"))
                .andExpect(status().isForbidden());
    }

    @Test void tenantClaimCannotBypassDefaultDenyEntitlement() throws Exception {
        mvc.perform(post("/api/v1/me/content/physics/download-grants")
                        .with(jwt().jwt(t -> t.subject("user-1").claim("tenant_id", "tenant-1")))
                        .param("platform", "quest").param("runtimeBuildId", "runtime-1"))
                .andExpect(status().isForbidden());
    }

    @Test void anonymousCatalogStillWorks() throws Exception {
        mvc.perform(get("/api/v1/public/catalog").param("locale", "kk-KZ"))
                .andExpect(status().isOk());
    }

    @Test void authenticatedProxyModeIsNotExposedInDirectMode() throws Exception {
        mvc.perform(get("/api/v1/me/content/physics/artifacts/world.pak")
                        .with(jwt().jwt(t -> t.subject("user-1").claim("tenant_id", "tenant-1")))
                        .param("platform", "quest").param("runtimeBuildId", "runtime-1")
                        .header("Range", "bytes=0-100"))
                .andExpect(status().isNotFound());
    }

    @Test void publicCatalogDoesNotPermitPost() throws Exception {
        mvc.perform(post("/api/v1/public/catalog"))
                .andExpect(status().isUnauthorized());
    }

    @Test void proxyEndpointNotPublicEvenWhenDisabled() throws Exception {
        mvc.perform(get("/api/v1/me/content/physics/artifacts/world.pak")
                        .param("platform", "quest").param("runtimeBuildId", "runtime-1"))
                .andExpect(status().isUnauthorized());
    }
}

package academy.vrtraining.platform.licensing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="vr.licensing.persistence-enabled=false")
@AutoConfigureMockMvc
class OrganizationLicensingApiFailClosedTest {
    @Autowired MockMvc mvc;

    @Test void anonymousRegistrationRejected() throws Exception {
        mvc.perform(post("/api/v1/organizations/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"organizationName\":\"Example School\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test void jwtDoesNotEnableDisabledLicensingRoutes() throws Exception {
        mvc.perform(post("/api/v1/organizations/register")
            .with(jwt().jwt(x -> x.subject("owner").claim("email","owner@example.com")
                .claim("email_verified",true)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"organizationName\":\"Example School\"}"))
            .andExpect(status().isNotFound());
    }

    @Test void fakeAdminJwtDoesNotExposeIssueEndpointByDefault() throws Exception {
        mvc.perform(post("/api/v1/platform-admin/license-requests/" +
                  java.util.UUID.randomUUID() + "/approve")
            .with(jwt().jwt(x -> x.subject("owner").claim("roles", java.util.List.of("PLATFORM_ADMIN"))))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceLimit\":10,\"collections\":[\"PHYSICS\"],\"expiresAt\":\"2027-11-10T00:00:00Z\"}"))
            .andExpect(status().isNotFound());
    }
}

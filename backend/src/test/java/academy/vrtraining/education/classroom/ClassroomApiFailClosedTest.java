package academy.vrtraining.education.classroom;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Safety gate: until teacher membership/device identity adapters are installed,
 * there must be no accidental access to the classroom management APIs.
 */
@SpringBootTest(properties = "vr.classroom.persistence-enabled=false")
@AutoConfigureMockMvc
class ClassroomApiFailClosedTest {
    @Autowired MockMvc mvc;

    @Test void anonymousCannotCreateClassroom() throws Exception {
        mvc.perform(post("/api/v1/classrooms").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"7A\"}")).andExpect(status().isUnauthorized());
    }

    @Test void forgedTenantClaimDoesNotEnableDisabledFeature() throws Exception {
        mvc.perform(post("/api/v1/classrooms")
                .with(jwt().jwt(t -> t.subject("teacher-1").claim("tenant_id", "school-1")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"7A\"}")).andExpect(status().isNotFound());
    }

    @Test void genericUserJwtCannotBecomeDeviceCredential() throws Exception {
        mvc.perform(get("/api/v1/device/sessions/" + java.util.UUID.randomUUID() + "/roster")
                .with(jwt().jwt(t -> t.subject("user-1").claim("tenant_id", "school-1"))))
                .andExpect(status().isNotFound());
    }
}

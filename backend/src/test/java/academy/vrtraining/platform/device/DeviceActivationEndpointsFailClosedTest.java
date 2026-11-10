package academy.vrtraining.platform.device;

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
 * Routes stay closed when activation is disabled, including public key
 * enrollment. Owner approval and revoke never accept anonymous callers.
 */
@SpringBootTest(properties="vr.device.activation-enabled=false")
@AutoConfigureMockMvc
class DeviceActivationEndpointsFailClosedTest {
    @Autowired MockMvc mvc;

    @Test void anonymousDeviceCannotEnrollWhenFeatureOff() throws Exception {
        mvc.perform(post("/api/v1/device-activations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicKeySpkiBase64\":\"AA==\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void anonymousOwnerApprovalIsRejected() throws Exception {
        mvc.perform(post("/api/v1/organizations/me/device-activation-approvals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowId\":\"" + java.util.UUID.randomUUID() +
                        "\",\"userCode\":\"ABCD-EFGH\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void ordinaryJwtCannotUseDisabledActivationOrRevocation() throws Exception {
        mvc.perform(post("/api/v1/organizations/me/device-activation-approvals")
                .with(jwt().jwt(t -> t.subject("owner")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flowId\":\"" + java.util.UUID.randomUUID() +
                        "\",\"userCode\":\"ABCD-EFGH\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/organizations/me/devices/" + java.util.UUID.randomUUID())
                .with(jwt().jwt(t -> t.subject("owner"))))
                .andExpect(status().isNotFound());
    }
}

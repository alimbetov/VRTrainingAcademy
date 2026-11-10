package academy.vrtraining.platform.device;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import academy.vrtraining.platform.licensing.LicenseContracts.Organization;
import academy.vrtraining.platform.licensing.LicenseStore;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import academy.vrtraining.platform.delivery.grant.DeliverySecurityConfiguration;

/**
 * MVC + Spring Security contract for the opt-in activation endpoints.
 * The activation store is mocked: real PostgreSQL seat and key-proof tests
 * are in PostgresClassroomStoreIntegrationTest / DeviceActivationServiceTest.
 */
@WebMvcTest(value = DeviceActivationController.class, properties = {
        "vr.device.activation-enabled=true",
        "vr.device.activation-http-enabled=true"})
@Import(DeliverySecurityConfiguration.class)
class DeviceActivationHttpContractTest {
    @Autowired MockMvc mvc;
    @MockBean DeviceActivationService activation;
    @MockBean LicenseStore licenses;

    @Test void publicQuestCanBeginWithEd25519Spki() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var flow = UUID.randomUUID();
        var expiry = Instant.parse("2026-11-10T13:00:00Z");
        when(activation.begin(any())).thenReturn(
                new DeviceActivationService.Activation(flow, "ABCD-EFGH", new byte[32], expiry));
        mvc.perform(post("/api/v1/device-activations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicKeySpkiBase64\":\"" +
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flowId").value(flow.toString()))
                .andExpect(jsonPath("$.userCode").value("ABCD-EFGH"));
        verify(activation).begin(any());
    }

    @Test void invalidKeyCannotCreateActivationFlow() throws Exception {
        mvc.perform(post("/api/v1/device-activations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicKeySpkiBase64\":\"%%invalid%%\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activation);
    }

    @Test void ownerApprovalRequiresJwtAndOwnOrganization() throws Exception {
        var flow = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var body = "{\"flowId\":\"" + flow + "\",\"userCode\":\"ABCD-EFGH\"}";
        mvc.perform(post("/api/v1/organizations/me/device-activation-approvals")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/organizations/me/device-activation-approvals")
                .with(jwt().jwt(x -> x.subject("unknown-owner")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        when(licenses.byOwner("owner")).thenReturn(Optional.of(
                new Organization(orgId, "School", "owner@example.org", "owner",
                        Instant.parse("2026-11-10T12:00:00Z"))));
        mvc.perform(post("/api/v1/organizations/me/device-activation-approvals")
                .with(jwt().jwt(x -> x.subject("owner")))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        verify(activation).approve("owner", orgId.toString(), flow, "ABCD-EFGH");
    }

    @Test void completionRequiresExactSizeSignatureAndReturnsOnlyReceipt() throws Exception {
        var flow = UUID.randomUUID();
        var device = UUID.randomUUID();
        var org = UUID.randomUUID();
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        when(activation.complete(eq(flow), any())).thenReturn(
                new DeviceActivationService.Device(device, org.toString(), pair.getPublic(),
                        DeviceActivationService.State.ACTIVE));
        mvc.perform(post("/api/v1/device-activations/" + flow + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signatureBase64\":\"AA==\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(activation);
        mvc.perform(post("/api/v1/device-activations/" + flow + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signatureBase64\":\"" +
                        Base64.getEncoder().encodeToString(new byte[64]) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(device.toString()))
                .andExpect(jsonPath("$.organizationId").value(org.toString()))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.publicKey").doesNotExist());
    }

    @Test void wrongKeyProofDeniedOnCompletion() throws Exception {
        var flow = UUID.randomUUID();
        when(activation.complete(eq(flow), any())).thenThrow(
                new GeneralSecurityException("Invalid device proof"));
        mvc.perform(post("/api/v1/device-activations/" + flow + "/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signatureBase64\":\"" +
                        Base64.getEncoder().encodeToString(new byte[64]) + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void onlyOwnerCanRevokeRegisteredQuest() throws Exception {
        var device = UUID.randomUUID();
        mvc.perform(delete("/api/v1/organizations/me/devices/" + device))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/organizations/me/devices/" + device)
                .with(jwt().jwt(x -> x.subject("stranger"))))
                .andExpect(status().isForbidden());
        var org = UUID.randomUUID();
        when(licenses.byOwner("owner")).thenReturn(Optional.of(
                new Organization(org, "School", "owner@example.org", "owner",
                        Instant.parse("2026-11-10T12:00:00Z"))));
        mvc.perform(delete("/api/v1/organizations/me/devices/" + device)
                .with(jwt().jwt(x -> x.subject("owner"))))
                .andExpect(status().isNoContent());
        verify(activation).revoke("owner", org.toString(), device);
    }
}

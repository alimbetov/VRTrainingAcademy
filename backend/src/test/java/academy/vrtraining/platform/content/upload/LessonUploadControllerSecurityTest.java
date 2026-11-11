package academy.vrtraining.platform.content.upload;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="vr.content.upload-sessions.enabled=true")
@AutoConfigureMockMvc
class LessonUploadControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean LessonUploadSessions sessions;

    @Test void anonymousCannotCreateOrInspectAnUploadSession()throws Exception {
        UUID lesson=UUID.randomUUID(),subject=UUID.randomUUID();
        mvc.perform(post("/api/v1/admin/content/lessons/"+lesson+"/uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key","trusted-key-123456789")
                .content("{\"subjectId\":\""+subject+"\"}"))
             .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/content/uploads/"+UUID.randomUUID()))
             .andExpect(status().isUnauthorized());
        verifyNoInteractions(sessions);
    }
    @Test void requestCannotImpersonateAnotherOwnerOrPickArbitraryLesson()throws Exception {
        UUID lesson=UUID.randomUUID(),subject=UUID.randomUUID();
        when(sessions.create("attacker",lesson,subject,"trusted-key-123456789"))
             .thenThrow(new SecurityException("Not a scoped author"));
        mvc.perform(post("/api/v1/admin/content/lessons/"+lesson+"/uploads")
                 .with(jwt().jwt(j->j.subject("attacker").claim("role","PLATFORM_ADMIN")))
                 .header("Idempotency-Key","trusted-key-123456789")
                 .contentType(MediaType.APPLICATION_JSON)
                 .content("{\"subjectId\":\""+subject+"\",\"organizationId\":\"forged\",\"role\":\"ADMIN\"}"))
              .andExpect(status().isForbidden());
        verify(sessions).create("attacker",lesson,subject,"trusted-key-123456789");
    }
    @Test void authenticatedAuthorSeesCreatedSessionOnly()throws Exception {
        UUID lesson=UUID.randomUUID(),subject=UUID.randomUUID(),id=UUID.randomUUID();
        when(sessions.create("author",lesson,subject,"trusted-key-123456789"))
             .thenReturn(new LessonUploadSessions.Session(id,lesson,subject,"CREATED"));
        mvc.perform(post("/api/v1/admin/content/lessons/"+lesson+"/uploads")
                 .with(jwt().jwt(j->j.subject("author")))
                 .header("Idempotency-Key","trusted-key-123456789")
                 .contentType(MediaType.APPLICATION_JSON)
                 .content("{\"subjectId\":\""+subject+"\"}"))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.uploadId").value(id.toString()))
              .andExpect(jsonPath("$.state").value("CREATED"));
    }
}

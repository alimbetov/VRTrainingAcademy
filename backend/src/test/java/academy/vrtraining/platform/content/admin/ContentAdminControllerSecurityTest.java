package academy.vrtraining.platform.content.admin;

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

@SpringBootTest(properties="vr.content.admin.enabled=true")
@AutoConfigureMockMvc
class ContentAdminControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockBean PostgresContentAdministrationService service;

    @Test void anonymousAdminRequestIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/admin/content/worlds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"WORLD_SCHOOL\",\"sortOrder\":1}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void jwtSubjectIsOnlyTrustedActorAndClientRoleCannotEscalate() throws Exception {
        when(service.createWorld(eq("oidc-subject"),eq("WORLD_SCHOOL"),eq(1)))
                .thenThrow(new SecurityException("No trusted platform role"));
        mvc.perform(post("/api/v1/admin/content/worlds")
                .with(jwt().jwt(j -> j.subject("oidc-subject").claim("role","PLATFORM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"WORLD_SCHOOL\",\"sortOrder\":1}"))
                .andExpect(status().isForbidden());
        verify(service).createWorld("oidc-subject","WORLD_SCHOOL",1);
    }

    @Test void scopedAuthorizedCreationAndSubjectLessonPathAreServerChecked() throws Exception {
        UUID subjectId=UUID.randomUUID(), lessonId=UUID.randomUUID();
        when(service.createLesson("verified-writer",subjectId,"PHY_001",10))
                .thenReturn(new PostgresContentAdministrationService.Created(lessonId,"DRAFT",0));
        mvc.perform(post("/api/v1/admin/content/subjects/"+subjectId+"/lessons")
                .with(jwt().jwt(j -> j.subject("verified-writer")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"PHY_001\",\"sortOrder\":10,\"role\":\"PLATFORM_ADMIN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(lessonId.toString()))
                .andExpect(jsonPath("$.state").value("DRAFT"));
        verify(service).createLesson("verified-writer",subjectId,"PHY_001",10);
    }

    @Test void invalidTranslationAndUncertifiedLessonPublicationAreRejected() throws Exception {
        UUID node=UUID.randomUUID();
        mvc.perform(put("/api/v1/admin/content/WORLD/"+node+"/translations/ru-RU")
                .with(jwt().jwt(j -> j.subject("admin")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locale\":\"kk-KZ\",\"title\":\"Школа\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);

        when(service.transition("admin","LESSON",node,"PUBLISHED",0))
                .thenThrow(new PostgresContentAdministrationService.Conflict(
                        "Cannot publish uncertified lesson"));
        mvc.perform(post("/api/v1/admin/content/LESSON/"+node+"/transitions")
                .with(jwt().jwt(j -> j.subject("admin")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"PUBLISHED\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
    }
}

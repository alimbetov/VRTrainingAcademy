package academy.vrtraining.platform.catalog;

import academy.vrtraining.VrTrainingApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = VrTrainingApplication.class)
@AutoConfigureMockMvc
class PublicCatalogControllerTest {
    @Autowired MockMvc mvc;

    @Test void returnsThreeFreeLessonsInRussian() throws Exception {
        mvc.perform(get("/api/v1/public/catalog").param("locale", "ru-RU"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locale").value("ru-RU"))
            .andExpect(jsonPath("$.worlds[0].lessons[0].code").value("ASTRO-001"))
            .andExpect(jsonPath("$.worlds[0].lessons[1].code").value("PHY-001"))
            .andExpect(jsonPath("$.worlds[1].lessons[0].code").value("SAFE-001"))
            .andExpect(jsonPath("$.worlds[0].lessons[0].free").value(true));
    }

    @Test void supportsKazakh() throws Exception {
        mvc.perform(get("/api/v1/public/catalog").param("locale", "kk-KZ"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.worlds[0].lessons[0].title").value("Күн жүйесі"));
    }

    @Test void rejectsUnsupportedLocale() throws Exception {
        mvc.perform(get("/api/v1/public/catalog").param("locale", "unknown"))
            .andExpect(status().isBadRequest());
    }
}

package com.medreminder.medicationbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import com.medreminder.medicationbackend.util.JwtUtil;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.medreminder.medicationbackend.mapper.UserMapper;
import com.medreminder.medicationbackend.entity.User;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"reminder.scheduling.enabled=false", "notification.delivery.enabled=false", "app.initialize-admin=false", "app.initialize-console=false"})
@AutoConfigureMockMvc
class MedicationBackendApplicationTests {
    @Autowired
    MockMvc mvc;
    @Autowired
    JwtUtil jwt;
    @MockitoBean UserMapper users;

    @BeforeEach void activeUser() {
        var user=new User(); user.setId(1L); user.setStatus(1); user.setRole("USER");
        when(users.selectById(1L)).thenReturn(user);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void unauthenticatedBusinessRequestIsRejected() throws Exception {
        mvc.perform(get("/api/reminders/today"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        mvc.perform(get("/api/plans")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ordinaryUserCannotRunGlobalSystemJobs() throws Exception {
        String token = jwt.generate(1L, "USER");
        for (String path : List.of("/api/reminders/generate-today", "/api/reminders/scan-expired")) {
            mvc.perform(post(path)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

}

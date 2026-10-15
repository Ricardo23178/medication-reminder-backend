package com.medreminder.medicationbackend;

import com.medreminder.medicationbackend.entity.User;
import com.medreminder.medicationbackend.mapper.UserMapper;
import com.medreminder.medicationbackend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_MYSQL_INTEGRATION_TESTS", matches="true")
@SpringBootTest(properties={"reminder.scheduling.enabled=false", "notification.delivery.enabled=false",
        "app.initialize-admin=false", "app.initialize-console=true"})
@AutoConfigureMockMvc
@Transactional
class AdminApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @Autowired JwtUtil jwt;
    @Autowired JdbcTemplate db;

    @Test void administratorCanLoadIdentityAndRealDatabaseOverview() throws Exception {
        var admin=user("ADMIN");
        mvc.perform(get("/api/admin/me").header("Authorization",token(admin)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(admin.getId()))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(get("/api/admin/overview").header("Authorization",token(admin)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalUsers").isNumber())
                .andExpect(jsonPath("$.data.openEvents").isNumber())
                .andExpect(jsonPath("$.data.pendingNotifications").isNumber())
                .andExpect(jsonPath("$.data.totalTasks").isNumber());
    }

    @Test void ordinaryCannotAccessAdminNamespace() throws Exception {
        var user = user("USER");
        for (String path : new String[]{"me","overview","users","settings","events","notifications","audit-logs"}) {
            mvc.perform(get("/api/admin/"+path).header("Authorization", token(user)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        }
    }
    @Test void administratorClaimCannotElevateAnOrdinaryDatabaseUser() throws Exception {
        var user = user("USER");
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer "+jwt.generate(user.getId(),"ADMIN")))
                .andExpect(status().isForbidden());
    }
    @Test void matrixParameterDoesNotBypassAdministratorCheck() throws Exception {
        var user=user("USER");
        mvc.perform(get("/api/admin;v=1/me").header("Authorization",token(user)))
                .andExpect(status().isForbidden());
    }
    @Test void disabledAndDeletedAccountsLoseExistingTokens() throws Exception {
        var user = user("USER"); String token = token(user);
        user.setStatus(0); users.updateById(user);
        mvc.perform(get("/api/notifications/unread-count").header("Authorization",token))
                .andExpect(status().isUnauthorized());
        users.deleteById(user.getId());
        mvc.perform(get("/api/notifications/unread-count").header("Authorization",token))
                .andExpect(status().isUnauthorized());
    }
    @Test void userStatusChangeIsAuditedAndRevokesTheOldSession() throws Exception {
        var admin=user("ADMIN"); var target=user("USER"); String oldToken=token(target);
        mvc.perform(patch("/api/admin/users/{id}/status",target.getId()).header("Authorization",token(admin))
                        .contentType("application/json").content("{\"status\":0,\"reason\":\"测试禁用账号\"}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(0,users.selectById(target.getId()).getStatus());
        assertEquals(1,db.queryForObject("select count(*) from admin_audit_log where operator_id=? and target_id=? and action='USER_STATUS'",Integer.class,admin.getId(),target.getId().toString()));
        mvc.perform(get("/api/notifications/unread-count").header("Authorization",oldToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization",token(admin)).param("action","USER_STATUS"))
                .andExpect(jsonPath("$.code").value(200));
    }
    @Test void administratorsCannotBeDisabledAndInvalidStatusCannotBeWritten() throws Exception {
        var admin=user("ADMIN");
        mvc.perform(patch("/api/admin/users/{id}/status",admin.getId()).header("Authorization",token(admin))
                        .contentType("application/json").content("{\"status\":0,\"reason\":\"不允许禁用管理员\"}"))
                .andExpect(jsonPath("$.code").value(409));
        var target=user("USER");
        mvc.perform(patch("/api/admin/users/{id}/status",target.getId()).header("Authorization",token(admin))
                        .contentType("application/json").content("{\"status\":2,\"reason\":\"非法状态\"}"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(1,users.selectById(target.getId()).getStatus());
    }
    @Test void userProjectionExcludesPhoneAndPasswordAndSupportsFiltering() throws Exception {
        var admin=user("ADMIN"); var target=user("USER");
        mvc.perform(get("/api/admin/users").header("Authorization",token(admin)).param("keyword",target.getAccount()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].phone").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());
    }
    @Test void configurationUpdatesRequireCurrentVersionAndAreAudited() throws Exception {
        var admin=user("ADMIN");
        var row=db.queryForMap("select id,version from admin_setting where code='delivery.batch-size'");
        var id=((Number)row.get("id")).longValue(); var version=((Number)row.get("version")).intValue();
        String body="{\"value\":\"25\",\"version\":"+version+",\"reason\":\"调整本批投递数量\"}";
        mvc.perform(put("/api/admin/settings/{id}",id).header("Authorization",token(admin))
                        .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(200));
        mvc.perform(put("/api/admin/settings/{id}",id).header("Authorization",token(admin))
                        .contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(409));
        assertEquals("25",db.queryForObject("select setting_value from admin_setting where id=?",String.class,id));
        assertEquals(1,db.queryForObject("select count(*) from admin_audit_log where operator_id=? and action='SETTING_UPDATE'",Integer.class,admin.getId()));
    }
    @Test void invalidConfigurationAndMissingReasonCannotMutateOrAudit() throws Exception {
        var admin=user("ADMIN");
        var row=db.queryForMap("select id,version from admin_setting where code='delivery.batch-size'");
        for(String value:new String[]{"0","101","oops"}) {
            mvc.perform(put("/api/admin/settings/{id}",row.get("id")).header("Authorization",token(admin))
                    .contentType("application/json").content("{\"value\":\""+value+"\",\"version\":"+row.get("version")+",\"reason\":\"无效配置测试\"}"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(patch("/api/admin/users/{id}/status",user("USER").getId()).header("Authorization",token(admin))
                        .contentType("application/json").content("{\"status\":0,\"reason\":\" \"}"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(0,db.queryForObject("select count(*) from admin_audit_log where operator_id=?",Integer.class,admin.getId()));
    }
    @Test void projectionsExposeOnlyOperationalEventAndNotificationFields() throws Exception {
        var admin=user("ADMIN");
        for(String path:new String[]{"events","notifications"}) {
            String json=mvc.perform(get("/api/admin/"+path).header("Authorization",token(admin)))
                    .andExpect(jsonPath("$.code").value(200)).andReturn().getResponse().getContentAsString();
            for(String secret:new String[]{"medicineName","content","receiverId","userId","passwordHash","phone"})
                assertFalse(json.contains("\""+secret+"\":"),json);
        }
    }
    @Test void boundedPaginationAndStatusFiltersRejectBadInputs() throws Exception {
        var admin=user("ADMIN");
        for(String path:new String[]{"users","events","notifications","audit-logs"}) {
            mvc.perform(get("/api/admin/"+path).header("Authorization",token(admin)).param("pageSize","500"))
                    .andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(get("/api/admin/events").header("Authorization",token(admin)).param("status","' OR 1=1"))
                .andExpect(jsonPath("$.code").value(400));
    }
    User user(String role) {
        var user=new User(); user.setAccount("admin_it_"+UUID.randomUUID().toString().substring(0,20));
        user.setNickname("后台测试"); user.setPhone(null); user.setPasswordHash("test-not-a-login-password");
        user.setRole(role); user.setStatus(1); users.insert(user); return user;
    }
    String token(User user) { return "Bearer "+jwt.generate(user.getId(),user.getRole()); }
}

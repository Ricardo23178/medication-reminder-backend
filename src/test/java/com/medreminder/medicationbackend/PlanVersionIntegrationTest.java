package com.medreminder.medicationbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medreminder.medicationbackend.dto.ConfirmRequest;
import com.medreminder.medicationbackend.dto.PlanRequest;
import com.medreminder.medicationbackend.entity.*;
import com.medreminder.medicationbackend.mapper.*;
import com.medreminder.medicationbackend.service.PlanService;
import com.medreminder.medicationbackend.service.ReminderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MySQL only: ordinary fixtures roll back; committed interleaving fixtures use explicit cleanup. */
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_INTEGRATION_TESTS", matches = "true")
@SpringBootTest(properties = {"reminder.scheduling.enabled=false", "notification.delivery.enabled=false",
        "app.initialize-admin=false", "app.initialize-console=false"})
@Transactional
class PlanVersionIntegrationTest {
    @Autowired PlanService plans;
    @Autowired ReminderService reminders;
    @Autowired UserMapper users;
    @Autowired MedicineMapper medicines;
    @Autowired PlanTimeMapper times;
    @Autowired RemindTaskMapper tasks;
    @Autowired MedicationRecordMapper records;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void newPlanStartsAtVersionOne() {
        Fixture f = fixture();
        assertEquals(1, json.valueToTree(plans.getOwned(f.userId, f.planId)).path("planVersion").asInt());
    }

    @Test
    void editInvalidatesOldTasksAndPreservesCompletedVersionAndTime() {
        Fixture f = fixture();
        RemindTask pending = task(f, LocalDate.now().plusDays(1).atTime(8, 0));
        RemindTask completed = task(f, LocalDate.now().atTime(0, 0));
        completed.setStatus("CONFIRMED"); tasks.updateById(completed);
        MedicationRecord record = new MedicationRecord();
        record.setTaskId(completed.getId()); record.setUserId(f.userId);
        record.setAction("TAKEN"); record.setActionTime(LocalDateTime.now()); record.setSource("APP");
        records.insert(record);

        PlanRequest edit = request(f.medicineId);
        edit.setRemindTimes(null); edit.setDosagePerTime(new BigDecimal("2.00"));
        assertEquals(2, json.valueToTree(plans.update(f.userId, f.planId, edit)).path("planVersion").asInt());
        assertEquals("CANCELLED", tasks.selectById(pending.getId()).getStatus());
        assertEquals(4, reminders.confirm(f.userId, pending.getId(), taken()));
        assertEquals("CONFIRMED", tasks.selectById(completed.getId()).getStatus());
        assertEquals(completed.getScheduledAt(), tasks.selectById(completed.getId()).getScheduledAt());
        assertEquals(1, version(completed.getId()));
        assertEquals("TAKEN", records.selectById(record.getId()).getAction());
        assertEquals(1, reminders.confirm(f.userId, completed.getId(), taken()));
    }

    @Test
    void resumeCreatesNewVersionInsteadOfRevivingOldTask() {
        Fixture f = fixture();
        RemindTask old = task(f, LocalDate.now().atTime(LocalTime.MAX.withNano(0)));
        assertEquals(2, json.valueToTree(plans.pause(f.userId, f.planId)).path("planVersion").asInt());
        assertEquals(3, json.valueToTree(plans.resume(f.userId, f.planId)).path("planVersion").asInt());
        assertEquals("CANCELLED", tasks.selectById(old.getId()).getStatus());
        reminders.listToday(f.userId);
        reminders.listToday(f.userId);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM remind_task WHERE plan_id=? AND plan_version=3", Integer.class, f.planId));
        assertEquals(1, version(old.getId()));
        assertEquals(4, reminders.confirm(f.userId, old.getId(), taken()));
    }

    @Test
    void staleWaitingTaskCannotBeConfirmedOrPublishTimeoutEvent() {
        Fixture f = fixture();
        RemindTask old = task(f, LocalDateTime.now().minusHours(3).withNano(0));
        old.setExpireAt(LocalDateTime.now().plusHours(1)); tasks.updateById(old);
        // Simulate an old queued request whose cancellation state has not been refreshed.
        jdbc.update("UPDATE medication_plan SET plan_version=2 WHERE id=?", f.planId);
        assertEquals(4, reminders.confirm(f.userId, old.getId(), taken()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM medication_record WHERE task_id=?", Integer.class, old.getId()));
        jdbc.update("UPDATE remind_task SET expire_at=? WHERE id=?", LocalDateTime.now().minusMinutes(1), old.getId());
        reminders.scanExpiredTasks();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM abnormal_event WHERE task_id=?", Integer.class, old.getId()));
    }

    @Test
    void pausedPlanRejectsWaitingTaskEvenWhenVersionsMatch() {
        Fixture f = fixture();
        RemindTask task = task(f, LocalDateTime.now().withNano(0));
        jdbc.update("UPDATE medication_plan SET status='PAUSED' WHERE id=?", f.planId);
        assertEquals(4, reminders.confirm(f.userId, task.getId(), taken()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM medication_record WHERE task_id=?", Integer.class, task.getId()));
    }

    @Test
    void resumingPlanStartingTomorrowDoesNotGenerateTodayTasks() {
        Fixture f = fixture();
        plans.pause(f.userId, f.planId);
        PlanRequest edit = request(f.medicineId); edit.setStartDate(LocalDate.now().plusDays(1));
        edit.setRemindTimes(null); plans.update(f.userId, f.planId, edit);
        plans.resume(f.userId, f.planId);
        reminders.listToday(f.userId);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM remind_task WHERE plan_id=?", Integer.class, f.planId));
    }

    @Test
    void legacyCompletedTaskIsNotRegeneratedAsBaselineVersionOne() {
        Fixture f = fixture();
        RemindTask legacy = task(f, LocalDate.now().atTime(LocalTime.MAX.withNano(0)));
        jdbc.update("UPDATE remind_task SET status='CONFIRMED',plan_version=NULL WHERE id=?", legacy.getId());
        reminders.listToday(f.userId);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM remind_task WHERE plan_id=?", Integer.class, f.planId));
        assertNull(jdbc.queryForObject("SELECT plan_version FROM remind_task WHERE id=?", Integer.class, legacy.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void generatorUsesRulesCommittedAfterItsSnapshotWasCreated() {
        requireIsolatedDatabase();
        Fixture f = requiresNew().execute(status -> fixture());
        try {
            jdbc.update("UPDATE plan_time SET remind_time='12:00:00' WHERE id=?", f.timeId);
            repeatableRead().execute(status -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM plan_time WHERE plan_id=?", Integer.class, f.planId);
                requiresNew().execute(inner -> {
                    jdbc.update("UPDATE medication_plan SET plan_version=2,update_time=? WHERE id=?", LocalDate.now().atStartOfDay(), f.planId);
                    jdbc.update("DELETE FROM plan_time WHERE plan_id=?", f.planId);
                    PlanTime current = new PlanTime(); current.setPlanId(f.planId);
                    current.setRemindTime(LocalTime.MAX.withNano(0)); current.setEnabled(1); times.insert(current);
                    return null;
                });
                var generated = reminders.listToday(f.userId);
                assertEquals(1, generated.size());
                assertEquals(LocalTime.of(23, 59, 59), generated.get(0).getScheduledAt().toLocalTime());
                assertEquals(2, generated.get(0).getPlanVersion());
                return null;
            });
        } finally { cleanup(f); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmationUsesDelayRecordCommittedAfterItsSnapshotWasCreated() {
        requireIsolatedDatabase();
        Fixture f = requiresNew().execute(status -> fixture());
        try {
            RemindTask task = task(f, LocalDateTime.now().withNano(0));
            int result = repeatableRead().execute(status -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM medication_record WHERE task_id=?", Integer.class, task.getId());
                requiresNew().execute(inner -> {
                    ConfirmRequest delay = new ConfirmRequest(); delay.setAction("DELAYED"); delay.setDelayMinutes(15);
                    assertEquals(0, reminders.confirm(f.userId, task.getId(), delay));
                    return null;
                });
                return reminders.confirm(f.userId, task.getId(), taken());
            });
            assertEquals(0, result);
            assertEquals("CONFIRMED", tasks.selectById(task.getId()).getStatus());
            assertEquals("TAKEN", jdbc.queryForObject("SELECT action FROM medication_record WHERE task_id=?", String.class, task.getId()));
        } finally { cleanup(f); }
    }

    private TransactionTemplate repeatableRead() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private void requireIsolatedDatabase() {
        assertTrue(jdbc.queryForObject("SELECT DATABASE()", String.class).startsWith("medication_version_test_"),
                "These committed interleaving fixtures must use an isolated medication_version_test_ database");
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM medication_record WHERE user_id=?", f.userId);
        jdbc.update("DELETE FROM remind_task WHERE user_id=?", f.userId);
        jdbc.update("DELETE FROM plan_time WHERE plan_id=?", f.planId);
        jdbc.update("DELETE FROM medication_plan WHERE id=? AND user_id=?", f.planId, f.userId);
        jdbc.update("DELETE FROM medicine WHERE id=? AND user_id=?", f.medicineId, f.userId);
        jdbc.update("DELETE FROM sys_user WHERE id=?", f.userId);
    }

    private Fixture fixture() {
        User user = new User(); user.setAccount("version_" + UUID.randomUUID().toString().substring(0, 22));
        user.setPasswordHash("test-only-not-a-login-password"); user.setRole("USER"); user.setStatus(1);
        users.insert(user);
        Medicine medicine = new Medicine(); medicine.setUserId(user.getId()); medicine.setName("版本测试虚构药品");
        medicines.insert(medicine);
        Long planId = plans.create(user.getId(), request(medicine.getId())).getId();
        PlanTime time = times.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PlanTime>()
                .eq(PlanTime::getPlanId, planId)).get(0);
        // A future point on the current day independent of the hour the test runs.
        time.setRemindTime(LocalTime.MAX.withNano(0)); times.updateById(time);
        return new Fixture(user.getId(), medicine.getId(), planId, time.getId());
    }

    private PlanRequest request(Long medicineId) {
        PlanRequest req = new PlanRequest(); req.setMedicineId(medicineId); req.setStartDate(LocalDate.now());
        req.setDosagePerTime(BigDecimal.ONE); req.setRemindTimes(List.of("23:59")); return req;
    }

    private RemindTask task(Fixture f, LocalDateTime at) {
        RemindTask task = new RemindTask(); task.setUserId(f.userId); task.setPlanId(f.planId);
        task.setPlanTimeId(f.timeId); task.setScheduledAt(at); task.setExpireAt(at.plusHours(2));
        task.setStatus("WAITING"); task.setPlanVersion(1); tasks.insert(task);
        return task;
    }

    private int version(Long taskId) {
        return jdbc.queryForObject("SELECT plan_version FROM remind_task WHERE id=?", Integer.class, taskId);
    }

    private ConfirmRequest taken() { ConfirmRequest req = new ConfirmRequest(); req.setAction("TAKEN"); return req; }
    private record Fixture(Long userId, Long medicineId, Long planId, Long timeId) { }
}

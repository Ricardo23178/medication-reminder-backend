package com.medreminder.medicationbackend.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.medreminder.medicationbackend.dto.*;
import com.medreminder.medicationbackend.entity.*;
import com.medreminder.medicationbackend.mapper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class CurrentProgressRegressionTest {
    @Test
    void pauseCancelsPendingTasksInSameServiceCall() {
        PlanMapper plans = mock(PlanMapper.class);
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationPlan plan = new MedicationPlan();
        plan.setId(2L);
        plan.setStatus("ACTIVE");
        when(plans.selectOne(any(Wrapper.class))).thenReturn(plan);
        PlanService service = new PlanService(plans, mock(PlanTimeMapper.class), mock(MedicineMapper.class), tasks);
        assertEquals("PAUSED", service.pause(1L, 2L).getStatus());
        verify(tasks).update(isNull(), argThat((Wrapper<RemindTask> wrapper) ->
                wrapper.getSqlSegment().contains("user_id") && wrapper.getSqlSegment().contains("plan_id")
                        && wrapper.getSqlSegment().contains("status IN")));
        assertNotNull(plan.getUpdateTime());
    }

    @Test
    void resumeDoesNotReviveCancelledTasks() {
        PlanMapper plans = mock(PlanMapper.class);
        PlanTimeMapper times = mock(PlanTimeMapper.class);
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationPlan plan = new MedicationPlan();
        plan.setId(2L);
        plan.setStatus("PAUSED");
        plan.setStartDate(LocalDate.now().plusDays(1));
        plan.setEndDate(LocalDate.now().plusDays(2));
        when(plans.selectOne(any(Wrapper.class))).thenReturn(plan);
        PlanService service = new PlanService(plans, times, mock(MedicineMapper.class), tasks);
        service.resume(1L, 2L);
        verifyNoInteractions(tasks);
        assertEquals(2, plan.getPlanVersion());
    }

    @Test
    void todayQueryIncludesOpenReminderFromPreviousDay() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        ReminderService service = new ReminderService(tasks, mock(MedicationRecordMapper.class),
                mock(PlanMapper.class), mock(PlanTimeMapper.class), mock(MedicineMapper.class),
                mock(AbnormalEventMapper.class), mock(NotificationService.class));
        service.listToday(1L);
        verify(tasks).selectList(argThat((Wrapper<RemindTask> wrapper) ->
                wrapper.getSqlSegment().contains(" OR ") && wrapper.getSqlSegment().contains("expire_at >=")
                        && wrapper.getSqlSegment().contains("status IN")));
    }

    @Test
    void repeatedExpiryScanProducesOneEventAndOneNotification() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        AbnormalEventMapper events = mock(AbnormalEventMapper.class);
        NotificationService notifications = mock(NotificationService.class);
        RemindTask task = new RemindTask();
        task.setId(2L);
        task.setUserId(1L);
        task.setPlanVersion(1);
        task.setExpireAt(LocalDateTime.now().minusMinutes(1));
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(1, 0);
        doAnswer(call -> { ((AbnormalEvent) call.getArgument(0)).setId(5L); return 1; })
                .when(events).insert(any(AbnormalEvent.class));
        ReminderService service = new ReminderService(tasks, records, activePlanMapper(),
                mock(PlanTimeMapper.class), mock(MedicineMapper.class), events, notifications);
        service.scanExpiredTasks();
        service.scanExpiredTasks();
        verify(events, times(1)).insert(any(AbnormalEvent.class));
        verify(notifications, times(1)).queueAbnormalEvent(argThat((AbnormalEvent e) -> e.getId().equals(5L)));
        verify(records, times(1)).insert(argThat((MedicationRecord r) -> "UNCONFIRMED".equals(r.getAction())));
    }
    @BeforeAll
    static void initializeMapperMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(RemindTask.class, MedicationPlan.class, PlanTime.class,
                FamilyRelation.class, MedicationRecord.class, AbnormalEvent.class, Medicine.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
    @Test
    void delayedResponseKeepsTaskOpenForLaterConfirmation() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        RemindTask task = new RemindTask();
        task.setStatus("WAITING");
        task.setPlanVersion(1);
        task.setExpireAt(LocalDateTime.now().plusHours(1));
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ReminderService service = new ReminderService(tasks, records, activePlanMapper(),
                mock(PlanTimeMapper.class), mock(MedicineMapper.class), mock(AbnormalEventMapper.class),
                mock(NotificationService.class));
        ConfirmRequest request = new ConfirmRequest();
        request.setAction("DELAYED");
        request.setDelayMinutes(15);
        assertEquals(0, service.confirm(1L, 2L, request));
        assertEquals("TRIGGERED", task.getStatus());
        verify(tasks).update(isNull(), argThat((Wrapper<RemindTask> wrapper) ->
                wrapper.getSqlSet().contains("expire_at=") && wrapper.getSqlSet().contains("last_trigger_at=")));
    }

    @Test
    void takenAfterDelayUpdatesExistingRecord() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        RemindTask task = new RemindTask();
        task.setStatus("TRIGGERED");
        task.setPlanVersion(1);
        task.setExpireAt(LocalDateTime.now().plusHours(1));
        MedicationRecord record = new MedicationRecord();
        record.setId(10L);
        record.setAction("DELAYED");
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        when(tasks.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(records.selectOne(any(Wrapper.class))).thenReturn(record);
        ReminderService service = new ReminderService(tasks, records, activePlanMapper(),
                mock(PlanTimeMapper.class), mock(MedicineMapper.class), mock(AbnormalEventMapper.class),
                mock(NotificationService.class));
        ConfirmRequest request = new ConfirmRequest();
        request.setAction("TAKEN");
        assertEquals(0, service.confirm(1L, 2L, request));
        verify(records).updateById(record);
        verify(records, never()).insert(any(MedicationRecord.class));
        assertEquals("TAKEN", record.getAction());
    }
    @Test
    void cancelledTaskCannotBeConfirmed() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        RemindTask task = new RemindTask();
        task.setStatus("CANCELLED");
        task.setExpireAt(LocalDateTime.now().plusHours(1));
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        ReminderService service = new ReminderService(tasks, records, activePlanMapper(),
                mock(PlanTimeMapper.class), mock(MedicineMapper.class), mock(AbnormalEventMapper.class),
                mock(NotificationService.class));
        ConfirmRequest request = new ConfirmRequest();
        request.setAction("TAKEN");
        assertEquals(4, service.confirm(1L, 2L, request));
        verifyNoInteractions(records);
    }

    @Test
    void invalidActionMustNotBecomeTaken() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        RemindTask task = new RemindTask();
        task.setStatus("WAITING");
        task.setPlanVersion(1);
        task.setExpireAt(LocalDateTime.now().plusHours(1));
        when(tasks.selectOne(any(Wrapper.class))).thenReturn(task);
        ReminderService service = new ReminderService(tasks, records, activePlanMapper(),
                mock(PlanTimeMapper.class), mock(MedicineMapper.class), mock(AbnormalEventMapper.class),
                mock(NotificationService.class));
        ConfirmRequest request = new ConfirmRequest();
        request.setAction("UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> service.confirm(1L, 2L, request));
        verifyNoInteractions(records);
    }

    @Test
    void updatingPlanCannotUseAnotherUsersMedicine() {
        PlanMapper plans = mock(PlanMapper.class);
        MedicineMapper medicines = mock(MedicineMapper.class);
        MedicationPlan plan = new MedicationPlan();
        plan.setId(2L);
        plan.setStatus("ACTIVE");
        when(plans.selectOne(any(Wrapper.class))).thenReturn(plan);
        PlanService service = new PlanService(plans, mock(PlanTimeMapper.class), medicines, mock(RemindTaskMapper.class));
        PlanRequest request = validPlan();
        assertThrows(IllegalArgumentException.class, () -> service.update(1L, 2L, request));
        verify(medicines).selectOne(argThat((Wrapper<Medicine> wrapper) -> wrapper.getSqlSegment().contains("user_id")));
        verify(plans, never()).updateById(any(MedicationPlan.class));
    }

    @Test
    void duplicateTimePointsAreRejectedBeforeWriting() {
        PlanMapper plans = mock(PlanMapper.class);
        MedicineMapper medicines = mock(MedicineMapper.class);
        when(medicines.selectOne(any(Wrapper.class))).thenReturn(new Medicine());
        PlanService service = new PlanService(plans, mock(PlanTimeMapper.class), medicines, mock(RemindTaskMapper.class));
        PlanRequest request = validPlan();
        request.setRemindTimes(List.of("08:00", "08:00"));
        assertThrows(IllegalArgumentException.class, () -> service.create(1L, request));
        verify(plans, never()).insert(any(MedicationPlan.class));
    }

    @Test
    void familyWithOnlyPlanScopeCannotReadAbnormalEvents() {
        FamilyRelationMapper relations = mock(FamilyRelationMapper.class);
        AbnormalEventMapper events = mock(AbnormalEventMapper.class);
        FamilyRelation relation = new FamilyRelation();
        relation.setOwnerUserId(1L);
        relation.setScope("PLAN_STATUS");
        when(relations.selectList(any(Wrapper.class))).thenReturn(List.of(relation));
        FamilyService service = new FamilyService(relations, mock(UserMapper.class), events,
                mock(RemindTaskMapper.class), mock(PlanMapper.class), mock(MedicineMapper.class), mock(NotificationMapper.class));
        assertTrue(service.listAuthorizedAbnormalEvents(2L).isEmpty());
        verifyNoInteractions(events);
    }

    @Test
    void midnightConfirmationBelongsToScheduledDayAndCancelledTasksDoNotCount() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        LocalDate day = LocalDate.of(2026, 10, 13);
        RemindTask task = new RemindTask();
        task.setId(1L);
        task.setStatus("CONFIRMED");
        task.setScheduledAt(day.atTime(23, 30));
        RemindTask cancelled = new RemindTask();
        cancelled.setId(2L);
        cancelled.setStatus("CANCELLED");
        cancelled.setScheduledAt(day.atTime(20, 0));
        MedicationRecord record = new MedicationRecord();
        record.setTaskId(1L);
        record.setAction("TAKEN");
        record.setActionTime(day.plusDays(1).atTime(0, 15));
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(task, cancelled));
        when(records.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        var stat = new StatisticsService(tasks, records).calendar(1L, day, day.plusDays(1));
        assertEquals(1, stat.getSummary().getTotalShould());
        assertEquals(1, stat.getDays().get(0).getTakenCount());
        assertEquals(0, stat.getDays().get(1).getTakenCount());
        assertEquals(100.0, stat.getSummary().getCompletionRate());
    }

    private PlanMapper activePlanMapper() {
        PlanMapper plans = mock(PlanMapper.class);
        MedicationPlan plan = new MedicationPlan();
        plan.setId(2L); plan.setUserId(1L); plan.setStatus("ACTIVE");
        when(plans.selectOne(any(Wrapper.class))).thenReturn(plan);
        return plans;
    }

    private PlanRequest validPlan() {
        PlanRequest request = new PlanRequest();
        request.setMedicineId(3L);
        request.setStartDate(LocalDate.now());
        request.setRemindTimes(List.of("08:00"));
        return request;
    }

    @Test
    void delayedIsNotCompletedAndStillNeedsFinalConfirmation() {
        RemindTaskMapper tasks = mock(RemindTaskMapper.class);
        MedicationRecordMapper records = mock(MedicationRecordMapper.class);
        LocalDate day = LocalDate.now();
        RemindTask task = new RemindTask();
        task.setId(1L);
        task.setStatus("TRIGGERED");
        task.setScheduledAt(day.atTime(8, 0));
        MedicationRecord record = new MedicationRecord();
        record.setTaskId(1L);
        record.setAction("DELAYED");
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        when(records.selectList(any(Wrapper.class))).thenReturn(List.of(record));
        var stat = new StatisticsService(tasks, records).calendar(1L, day, day);
        assertEquals(0.0, stat.getSummary().getCompletionRate());
        assertEquals(1, stat.getSummary().getTotalUnconfirmed());
        assertEquals(1, stat.getSummary().getTotalDelayed());
    }
}

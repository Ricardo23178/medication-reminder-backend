package com.medreminder.medicationbackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medreminder.medicationbackend.entity.RemindTask;
import org.apache.ibatis.annotations.Mapper;
import java.time.LocalDateTime;

@Mapper
public interface RemindTaskMapper extends BaseMapper<RemindTask> {
    /** 调用方持有计划行锁；这里只记服务端触发，不表示终端已收到通知。 */
    default int triggerDueTasks(Long userId, Long planId, Integer version, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<RemindTask>()
                .eq(RemindTask::getUserId, userId).eq(RemindTask::getPlanId, planId)
                .eq(RemindTask::getPlanVersion, version).eq(RemindTask::getStatus, "WAITING")
                .le(RemindTask::getScheduledAt, now)
                .set(RemindTask::getStatus, "TRIGGERED").set(RemindTask::getTriggerCount, 1)
                .set(RemindTask::getLastTriggerAt, now));
    }
}

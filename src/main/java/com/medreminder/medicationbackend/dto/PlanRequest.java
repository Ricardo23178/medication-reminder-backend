package com.medreminder.medicationbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 新增 / 修改用药计划的入参。
 * 方案 1：创建计划时，每日提醒时间点一起提交（remindTimes）。
 */
@Data
public class PlanRequest {

    /** 关联药品档案 id，必填 */
    @NotNull(message = "必须指定药品")
    private Long medicineId;

    /** 单次服用量，选填 */
    private BigDecimal dosagePerTime;

    /** 服用量单位，选填 */
    private String dosageUnit;

    /** 开始日期，必填 */
    @NotNull(message = "开始日期不能为空")
    private LocalDate startDate;

    /** 结束日期，null=长期用药 */
    private LocalDate endDate;

    /** 0=无关 1=餐前 2=餐后 3=晨起空腹 4=睡前 */
    private Integer mealRelation;

    /** 备注 */
    private String remark;

    /** 每日提醒时间点，格式 "HH:mm"，如 ["08:00","20:00"]。新增时必填，修改时选填 */
    private List<String> remindTimes;
}

package com.medreminder.medicationbackend.vo;

import com.medreminder.medicationbackend.entity.PlanTime;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用药计划详情返回对象（VO）。
 * 相比实体，多了药品名称（medicineName）和时间点列表（times），
 * 前端一次拿到完整信息，不用再发多个请求。
 */
@Data
public class PlanVO {

    private Long id;

    private Integer planVersion;

    private Long medicineId;

    private String medicineName;

    private BigDecimal dosagePerTime;

    private String dosageUnit;

    private LocalDate startDate;

    private LocalDate endDate;

    private Integer mealRelation;

    private String status;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 每日提醒时间点列表 */
    private List<PlanTime> times;
}

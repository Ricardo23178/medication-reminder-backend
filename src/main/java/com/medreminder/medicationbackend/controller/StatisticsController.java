package com.medreminder.medicationbackend.controller;

import com.medreminder.medicationbackend.common.Result;
import com.medreminder.medicationbackend.common.UserContext;
import com.medreminder.medicationbackend.service.StatisticsService;
import com.medreminder.medicationbackend.vo.CalendarStatVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 用药日历与状态统计接口 —— 导师方案接口清单第 10 个：GET /api/statistics/calendar。
 * 查询个人在日期范围内的用药完成情况（应完成/已服/稍后/跳过/未确认/完成率）。
 * 越权：只返回当前登录用户自己的统计（user_id 取自 token）。
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    /**
     * 查询个人用药日历与状态统计。
     * 参数（均可选）：
     *   startDate 起始日期，格式 yyyy-MM-dd，默认今天往前 29 天
     *   endDate   结束日期，格式 yyyy-MM-dd，默认今天
     * 示例：GET /api/statistics/calendar?startDate=2026-09-01&endDate=2026-09-30
     */
    @GetMapping("/calendar")
    public Result<CalendarStatVO> calendar(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.ok(statisticsService.calendar(UserContext.userId(), startDate, endDate));
    }
}

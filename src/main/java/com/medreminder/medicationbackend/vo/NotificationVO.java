package com.medreminder.medicationbackend.vo;

import lombok.Data;
import java.time.LocalDateTime;

/** 只向客户端提供已发布消息；重试信息留在服务端。 */
@Data
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private String bizType;
    private Long bizId;
    private Integer readFlag;
    private LocalDateTime createTime;
    private boolean familyEvent;
}

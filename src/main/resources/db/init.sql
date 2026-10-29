-- =============================================================================
--  基于 HarmonyOS 与 Spring Boot 的用药提醒与家属协同系统
--  数据库初始化脚本  init.sql   （可重复执行 / 从零重建）
-- -----------------------------------------------------------------------------
--  DBMS    : MySQL 8.0+ (InnoDB / utf8mb4)
--  用法    : mysql --default-character-set=utf8mb4 -uroot -p < init.sql
--
--  ★ 必须带 --default-character-set=utf8mb4
--    Windows 下 mysql.exe 默认按系统代码页（简体中文环境为 GBK）解码脚本文件，
--    中文注释和 admin_setting 里的中文 JSON 会变成乱码，通知模板直接解析失败
--    （报「配置内容必须是合法JSON」）。用 Navicat / Workbench 导入时同理，
--    要确认连接字符集为 utf8mb4。
--  说明    : 本脚本由 schema.sql（10 张业务表）与三份增量迁移脚本合并而成，
--            合并后的结构等于「schema.sql → plan-version.sql → admin-console.sql
--            → task-history-snapshot.sql」全部执行完的最终状态。
--            因此新环境不需要再手动执行那三份迁移脚本。
--
--  设计原则：谁的数据由谁拥有 —— 涉及个人用药信息的表一律带 user_id，
--            接口层再按 user_id / family_relation.scope 做数据级过滤。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS medication_system
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE medication_system;

-- 全库统一：服务端时间以 Asia/Shanghai 存储，DATETIME 不带时区；客户端只做展示转换
SET time_zone = '+08:00';


-- =============================================================================
-- 1. sys_user  账户与角色
-- -----------------------------------------------------------------------------
-- 设计说明：role 只区分「是否平台管理员」。家属身份不写死在这里，而由
-- family_relation 动态决定 —— 因为同一个自然人可能既是患者又是家属
-- （自己服药、同时照看父母），单一 role 字段会冲突。
-- =============================================================================
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    account       VARCHAR(64)     NOT NULL                COMMENT '登录账号（手机号或用户名）',
    password_hash VARCHAR(100)    NOT NULL                COMMENT '密码哈希（BCrypt，禁止明文）',
    nickname      VARCHAR(64)              DEFAULT NULL   COMMENT '昵称',
    phone         VARCHAR(20)              DEFAULT NULL   COMMENT '手机号（脱敏展示）',
    role          VARCHAR(16)     NOT NULL DEFAULT 'USER' COMMENT 'USER=普通用户, ADMIN=平台管理员',
    status        TINYINT         NOT NULL DEFAULT 1      COMMENT '1=启用, 0=禁用（管理员可停用）',
    last_login_at DATETIME                 DEFAULT NULL   COMMENT '最近登录时间',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除 0=正常 1=已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account, deleted),
    KEY idx_role_status (role, status)
) ENGINE = InnoDB COMMENT ='账户与角色';


-- =============================================================================
-- 2. medicine  用户药品档案
-- -----------------------------------------------------------------------------
-- 合规边界：只存用户自己录入的信息（名称/规格/备注/图片），
-- 不存适应症、用法用量建议、不良反应等药学知识库内容 —— 系统不做药事顾问。
-- =============================================================================
CREATE TABLE IF NOT EXISTS medicine (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '药品档案ID',
    user_id       BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    name          VARCHAR(128)    NOT NULL                COMMENT '药品名称（用户按药盒/处方录入）',
    specification VARCHAR(64)              DEFAULT NULL   COMMENT '规格，如 5mg×30片',
    dosage        VARCHAR(64)              DEFAULT NULL   COMMENT '单次剂量，如 1片',
    unit          VARCHAR(16)              DEFAULT NULL   COMMENT '剂量单位',
    remark        VARCHAR(255)             DEFAULT NULL   COMMENT '用户备注（自由文本）',
    image_url     VARCHAR(255)             DEFAULT NULL   COMMENT '药盒照片',
    enabled       TINYINT         NOT NULL DEFAULT 1      COMMENT '1=启用, 0=停用（停用不影响历史记录）',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_user_enabled (user_id, enabled),
    CONSTRAINT fk_medicine_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE = InnoDB COMMENT ='用户药品档案';


-- =============================================================================
-- 3. medication_plan  用药计划主表
-- -----------------------------------------------------------------------------
-- 状态机：DRAFT（草稿）→ ACTIVE（执行中）⇄ PAUSED（暂停）→ FINISHED（自然结束）
--                                                      ↘ CANCELLED（取消）
-- 方案第六节明确：计划与提醒任务分开建模，不把状态堆在一张表里。
--
-- plan_version（原 plan-version.sql 迁移项）：计划规则每次变更 +1，
-- 生成提醒任务时把当时版本写进 remind_task.plan_version，
-- 从而做到「改规则不影响已生成的旧任务，也不误判旧任务的确认状态」。
-- =============================================================================
CREATE TABLE IF NOT EXISTS medication_plan (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '计划ID',
    user_id         BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    medicine_id     BIGINT UNSIGNED NOT NULL                COMMENT '关联药品档案',
    dosage_per_time DECIMAL(8, 2)           DEFAULT NULL    COMMENT '单次服用量',
    dosage_unit     VARCHAR(16)             DEFAULT NULL    COMMENT '服用量单位',
    start_date      DATE            NOT NULL                COMMENT '开始日期',
    end_date        DATE                    DEFAULT NULL    COMMENT '结束日期，NULL=长期用药',
    meal_relation   TINYINT         NOT NULL DEFAULT 0      COMMENT '0=无关 1=餐前 2=餐后 3=晨起空腹 4=睡前',
    status          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT'
                                    COMMENT 'DRAFT/ACTIVE/PAUSED/FINISHED/CANCELLED',
    plan_version    INT             NOT NULL DEFAULT 1      COMMENT '计划规则版本，每次规则变更 +1',
    remark          VARCHAR(255)             DEFAULT NULL   COMMENT '备注（用户自定义，如处方来源）',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                            ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_medicine (medicine_id),
    KEY idx_active_scan (status, end_date) COMMENT '扫描到期需自然结束的计划',
    CONSTRAINT fk_plan_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_plan_medicine FOREIGN KEY (medicine_id) REFERENCES medicine (id)
) ENGINE = InnoDB COMMENT ='用药计划主表';


-- =============================================================================
-- 4. plan_time  一个计划的多个每日提醒时间点
-- -----------------------------------------------------------------------------
-- 方案原文用途：「一个计划的多个每日提醒时间点」。
-- 与 remind_task 的区别：plan_time 是「规则」（每天几点），
-- remind_task 是「实例」（2026-09-30 08:00 这一剂），规则改动不影响已生成的实例。
-- =============================================================================
CREATE TABLE IF NOT EXISTS plan_time (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '时间点ID',
    plan_id     BIGINT UNSIGNED NOT NULL                COMMENT '所属计划',
    remind_time TIME            NOT NULL                COMMENT '每日提醒时刻 HH:mm:ss',
    sequence_no INT UNSIGNED    NOT NULL DEFAULT 1      COMMENT '当日第几剂（用于排序与展示）',
    enabled     TINYINT         NOT NULL DEFAULT 1      COMMENT '1=启用 0=停用（不改计划整体状态）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_time (plan_id, remind_time) COMMENT '同一计划同一时刻不重复',
    KEY idx_plan (plan_id, enabled),
    CONSTRAINT fk_plan_time_plan FOREIGN KEY (plan_id) REFERENCES medication_plan (id)
) ENGINE = InnoDB COMMENT ='计划的每日提醒时间点';


-- =============================================================================
-- 5. remind_task  具体提醒任务
-- -----------------------------------------------------------------------------
-- 状态机：WAITING（等待）→ TRIGGERED（已触发）→ CONFIRMED（已确认）
--                                              ↘ EXPIRED（超时）→ 产生 abnormal_event
--                     ↘ CANCELLED（计划暂停/修改时同步取消）
--
-- 唯一键演进（重要，勿回退）：
--   原 uk_plantime_scheduled (plan_time_id, scheduled_at) 在「计划改规则后重新生成
--   同一时刻任务」的场景下会撞键。现改为
--   uk_plan_version_scheduled (plan_id, plan_version, scheduled_at)：
--   不同版本各自独立生成任务，旧版本任务保持历史原样。
--
-- snapshot_* 字段（原 task-history-snapshot.sql 迁移项）：任务生成时对药品资料的
-- 快照。计划后来换药/改剂量时，历史记录仍展示当时吃的是什么，不借用当前计划回填。
-- NULL 表示迁移前的旧历史，未留存快照。
-- =============================================================================
CREATE TABLE IF NOT EXISTS remind_task (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    plan_id                  BIGINT UNSIGNED NOT NULL                COMMENT '所属计划',
    plan_time_id             BIGINT UNSIGNED NOT NULL                COMMENT '来源时间点规则',
    plan_version             INT                      DEFAULT NULL    COMMENT '生成时的计划版本；NULL=迁移前旧历史，无法追溯',
    user_id                  BIGINT UNSIGNED NOT NULL                COMMENT '冗余归属用户，按人查当天任务免联表',
    scheduled_at             DATETIME        NOT NULL                COMMENT '计划触发时间',
    expire_at                DATETIME        NOT NULL                COMMENT '容忍窗口截止时间（超此即判超时）',
    status                   VARCHAR(16)     NOT NULL DEFAULT 'WAITING'
                                             COMMENT 'WAITING/TRIGGERED/CONFIRMED/EXPIRED/CANCELLED',
    trigger_count            INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '已触发次数（防止一次提醒反复通知）',
    last_trigger_at          DATETIME                 DEFAULT NULL    COMMENT '最近触发时间',
    snapshot_medicine_id     BIGINT UNSIGNED          DEFAULT NULL    COMMENT '生成时的药品ID快照',
    snapshot_medicine_name   VARCHAR(128)             DEFAULT NULL    COMMENT '生成时的药品名称快照',
    snapshot_dosage_per_time DECIMAL(8, 2)            DEFAULT NULL    COMMENT '生成时的单次剂量快照',
    snapshot_dosage_unit     VARCHAR(16)              DEFAULT NULL    COMMENT '生成时的剂量单位快照',
    snapshot_meal_relation   TINYINT                  DEFAULT NULL    COMMENT '生成时的餐前餐后关系快照',
    create_time              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_version_scheduled (plan_id, plan_version, scheduled_at)
        COMMENT '★幂等：同一计划同一版本同一时刻只生成一个任务，定时任务重复执行不会产生重复提醒',
    KEY idx_scan_due (status, scheduled_at)   COMMENT '扫描：待触发',
    KEY idx_scan_expire (status, expire_at)   COMMENT '扫描：待判超时',
    KEY idx_user_day (user_id, scheduled_at)  COMMENT '查询当天提醒清单',
    KEY idx_plan_status (plan_id, status)     COMMENT '暂停计划时批量取消未执行任务',
    CONSTRAINT fk_task_plan FOREIGN KEY (plan_id) REFERENCES medication_plan (id),
    CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE = InnoDB COMMENT ='具体提醒任务';


-- =============================================================================
-- 6. medication_record  确认与执行记录
-- -----------------------------------------------------------------------------
-- 方案第十一节第 2 条（幂等与重复操作）：用户可能连续点击「已服」，网络重试
-- 也可能导致重复请求。这里的实现是「业务先查 + 唯一索引兜底」双保险：
--   uk_task 保证一个任务最多一条记录 —— 第 1 次写入成功，第 2 次直接命中唯一键冲突，
--   接口捕获后返回首次结果（幂等返回 200 而非报错），不会产生重复记录。
-- =============================================================================
CREATE TABLE IF NOT EXISTS medication_record (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    task_id           BIGINT UNSIGNED NOT NULL                COMMENT '关联提醒任务',
    user_id           BIGINT UNSIGNED NOT NULL                COMMENT '确认人（数据主体）',
    action            VARCHAR(16)     NOT NULL                COMMENT 'TAKEN已服/DELAYED稍后/SKIPPED跳过/UNCONFIRMED超时未确认',
    action_time       DATETIME        NOT NULL                COMMENT '实际操作时间（以服务端时间为准）',
    delay_minutes     INT                      DEFAULT NULL    COMMENT '「稍后」顺延的分钟数',
    skip_reason       VARCHAR(64)              DEFAULT NULL    COMMENT '「跳过」原因（用户自报，系统不作医学解释）',
    source            VARCHAR(16)     NOT NULL DEFAULT 'APP'  COMMENT 'APP/CARD卡片/SYSTEM系统判定/TASK',
    client_request_id VARCHAR(64)              DEFAULT NULL    COMMENT '客户端幂等键（弱网重传去重）',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task (task_id)
        COMMENT '★幂等核心：一个提醒任务只允许一条执行记录，重复提交由唯一索引兜底',
    KEY idx_user_time (user_id, action_time)                  COMMENT '个人记录与日历查询',
    KEY idx_action_time (action, action_time)                 COMMENT '统计各类动作占比',
    CONSTRAINT fk_record_task FOREIGN KEY (task_id) REFERENCES remind_task (id),
    CONSTRAINT fk_record_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE = InnoDB COMMENT ='确认与执行记录';


-- =============================================================================
-- 7. family_relation  家属绑定与授权范围
-- -----------------------------------------------------------------------------
-- 方向约定（重要）：
--   owner_user_id  = 被照护方（数据主体，用药的人）
--   family_user_id = 家属（查看的人）
-- 授权权在 owner 手里：绑定必须经 owner 确认，解绑 owner 可单方发起。
-- 这正面回应方案第十五节的表述 ——「用户主动授权的家庭关系模型」。
-- =============================================================================
CREATE TABLE IF NOT EXISTS family_relation (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关系ID',
    owner_user_id  BIGINT UNSIGNED NOT NULL                COMMENT '被照护方（数据主体）',
    family_user_id BIGINT UNSIGNED DEFAULT NULL            COMMENT '家属用户（邀请阶段未定，接受后回填）',
    relation_type  VARCHAR(32)              DEFAULT NULL    COMMENT '关系称谓（父/母/子女/配偶，用户自选）',
    relation_status VARCHAR(16)    NOT NULL DEFAULT 'PENDING'
                                   COMMENT 'PENDING待确认/ACTIVE生效/REJECTED拒绝/REVOKED已解除',
    scope          VARCHAR(255)    NOT NULL DEFAULT 'PLAN_STATUS,ABNORMAL_EVENT'
                                   COMMENT '授权范围，逗号分隔：PLAN_STATUS计划状态/ABNORMAL_EVENT异常事件/RECORD_DETAIL明细/STATISTICS统计',
    invite_code    VARCHAR(32)              DEFAULT NULL    COMMENT '邀请码（仅 PENDING 期间有效）',
    invite_expire_at DATETIME               DEFAULT NULL    COMMENT '邀请过期时间',
    bind_time      DATETIME                 DEFAULT NULL    COMMENT '关系生效时间',
    unbind_time    DATETIME                 DEFAULT NULL    COMMENT '解除时间（保留记录，不物理删除）',
    create_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_family (owner_user_id, family_user_id) COMMENT '同一对关系只保留一条',
    KEY idx_family_status (family_user_id, relation_status)    COMMENT '家属查自己的协同对象',
    KEY idx_owner_status (owner_user_id, relation_status)      COMMENT '患者查自己的授权列表',
    KEY idx_invite_code (invite_code)                          COMMENT '邀请码查询',
    CONSTRAINT fk_relation_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_relation_family FOREIGN KEY (family_user_id) REFERENCES sys_user (id)
) ENGINE = InnoDB COMMENT ='家属绑定与授权范围';


-- =============================================================================
-- 8. abnormal_event  超时/漏服等异常事件
-- -----------------------------------------------------------------------------
-- 状态机：OPEN（已创建）→ NOTIFIED（已通知家属）→ ACKNOWLEDGED（家属已知晓）→ CLOSED
-- 口径澄清（方案第十一节第 3 条）：系统只判断「在规定窗口内是否完成确认」，
-- 不宣称医学意义上的真实漏服 —— 因此事件类型命名用 UNCONFIRMED（未确认）而非 MISSED（漏服）。
-- =============================================================================
CREATE TABLE IF NOT EXISTS abnormal_event (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '事件ID',
    user_id         BIGINT UNSIGNED NOT NULL                COMMENT '归属用户',
    task_id         BIGINT UNSIGNED NOT NULL                COMMENT '关联提醒任务',
    type            VARCHAR(32)     NOT NULL
                                    COMMENT 'UNCONFIRMED_TIMEOUT未确认超时/CONSECUTIVE_UNCONFIRMED连续未确认/PLAN_ENDED_UNFINISHED计划结束未完成',
    status          VARCHAR(16)     NOT NULL DEFAULT 'OPEN'
                                    COMMENT 'OPEN/NOTIFIED/ACKNOWLEDGED/CLOSED',
    occurred_at     DATETIME        NOT NULL                COMMENT '事件发生时间（判定时刻）',
    notified_at     DATETIME                DEFAULT NULL    COMMENT '通知发出时间',
    acknowledged_by BIGINT UNSIGNED         DEFAULT NULL    COMMENT '确认知晓的家属用户',
    acknowledged_at DATETIME                DEFAULT NULL    COMMENT '知晓时间',
    closed_at       DATETIME                DEFAULT NULL    COMMENT '事件关闭时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                            ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_type (task_id, type)
        COMMENT '★防重复告警：同一任务的同一类异常只建一条，定时任务重复扫描不会重复通知',
    KEY idx_user_status (user_id, status),
    KEY idx_status_occurred (status, occurred_at) COMMENT '待通知事件扫描',
    CONSTRAINT fk_event_task FOREIGN KEY (task_id) REFERENCES remind_task (id),
    CONSTRAINT fk_event_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE = InnoDB COMMENT ='超时/未确认等异常事件';


-- =============================================================================
-- 9. notification  消息中心与通知记录
-- -----------------------------------------------------------------------------
-- 方案第十一节第 6 条（通知可靠性）：「通知发送失败需要记录状态，可采用有限次数重试；
-- 答辩中重点展示『业务事件已落库，即使通知失败也可追查』。」
-- 本表的 send_status / retry_count 就是这句话的落地：
--   业务事件先落库（abnormal_event）→ 再生成通知记录 → 发送结果回写本表，
--   发送失败不丢事件，运维可查可补发。
-- =============================================================================
CREATE TABLE IF NOT EXISTS notification (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    receiver_id   BIGINT UNSIGNED NOT NULL                COMMENT '接收人',
    type          VARCHAR(32)     NOT NULL                COMMENT 'REMIND用药提醒/FAMILY_ALERT家属告警/SYSTEM系统消息',
    title         VARCHAR(128)    NOT NULL                COMMENT '标题',
    content       VARCHAR(512)             DEFAULT NULL   COMMENT '正文（不得写入敏感明文）',
    biz_type      VARCHAR(32)              DEFAULT NULL   COMMENT '关联业务类型：REMIND_TASK/ABNORMAL_EVENT',
    biz_id        BIGINT UNSIGNED          DEFAULT NULL   COMMENT '关联业务ID（点击跳转用）',
    read_flag     TINYINT         NOT NULL DEFAULT 0      COMMENT '0=未读 1=已读',
    send_status   VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
                                  COMMENT 'PENDING待发送/SENT已发送/FAILED发送失败',
    retry_count   INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '重试次数（上限由配置控制）',
    sent_at       DATETIME                 DEFAULT NULL   COMMENT '发送成功时间',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_receiver_read (receiver_id, read_flag)        COMMENT '消息中心未读数',
    KEY idx_send_status (send_status, retry_count)        COMMENT '失败通知补偿扫描',
    CONSTRAINT fk_notification_receiver FOREIGN KEY (receiver_id) REFERENCES sys_user (id)
) ENGINE = InnoDB COMMENT ='消息中心与通知记录';


-- =============================================================================
-- 10. audit_log  敏感操作审计
-- -----------------------------------------------------------------------------
-- 方案第三节权限边界：「管理员不默认浏览用户敏感明细；敏感操作需留痕。」
-- 记录对象：管理员查看用药明细、导出数据、停用账号、解除家属关系等。
-- detail 中禁止写入完整手机号、完整用药明细等敏感明文。
-- =============================================================================
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    operator_id   BIGINT UNSIGNED NOT NULL                COMMENT '操作人',
    operator_role VARCHAR(16)              DEFAULT NULL   COMMENT '操作时的角色',
    action        VARCHAR(64)     NOT NULL                COMMENT 'VIEW_RECORD查看明细/EXPORT导出/DISABLE_USER停用账号/UNBIND解除关系...',
    target_type   VARCHAR(32)              DEFAULT NULL   COMMENT '目标类型：USER/MEDICINE/PLAN/RELATION',
    target_id     BIGINT UNSIGNED          DEFAULT NULL   COMMENT '目标ID',
    ip            VARCHAR(45)              DEFAULT NULL   COMMENT '来源IP（IPv6 需 45 位）',
    detail        VARCHAR(512)             DEFAULT NULL   COMMENT '补充说明（不含敏感明文）',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_operator_time (operator_id, created_at),
    KEY idx_action_time (action, created_at),
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB COMMENT ='敏感操作审计';


-- =============================================================================
-- 11. admin_setting  管理端配置（字典 / 通知模板 / 投递参数）
-- -----------------------------------------------------------------------------
-- 原 admin-console.sql 迁移项。category 区分用途：
--   DICT     字典配置
--   TEMPLATE 通知模板
--   PARAM    系统参数
-- version 用于乐观锁：配置页并发修改时版本号不一致则拒绝覆盖。
-- 说明：只影响之后新入队的通知，不改写历史消息。
-- =============================================================================
CREATE TABLE IF NOT EXISTS admin_setting (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    category      VARCHAR(16)  NOT NULL                COMMENT 'DICT/TEMPLATE/PARAM',
    code          VARCHAR(80)  NOT NULL                COMMENT '配置项编码',
    name          VARCHAR(80)  NOT NULL                COMMENT '展示名称',
    setting_value TEXT         NOT NULL                COMMENT '配置值（JSON 或标量）',
    description   VARCHAR(300) NOT NULL                COMMENT '说明',
    version       INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_setting_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT ='管理端配置项';


-- =============================================================================
-- 12. admin_audit_log  管理端敏感操作审计
-- -----------------------------------------------------------------------------
-- 原 admin-console.sql 迁移项。与 audit_log 分开：本表记录「改了什么值」（before/after），
-- 更贴近后台运维追溯；audit_log 记录业务侧敏感行为。
-- 所有敏感修改必须填写 reason。
-- =============================================================================
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    operator_id  BIGINT       NOT NULL                COMMENT '操作管理员',
    action       VARCHAR(40)  NOT NULL                COMMENT '动作编码',
    target_type  VARCHAR(40)  NOT NULL                COMMENT '目标类型',
    target_id    VARCHAR(80)  NOT NULL                COMMENT '目标ID',
    reason       VARCHAR(200) NOT NULL                COMMENT '修改原因（必填）',
    before_value TEXT         NOT NULL                COMMENT '修改前',
    after_value  TEXT         NOT NULL                COMMENT '修改后',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_admin_audit_created (create_time),
    KEY idx_admin_audit_action (action, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT ='管理端敏感操作审计';


-- =============================================================================
--  初始化数据
-- =============================================================================

-- 管理端默认配置（原 admin-console.sql 的 INSERT IGNORE，可重复执行）
INSERT IGNORE INTO admin_setting(category,code,name,setting_value,description) VALUES
('DICT','event.labels','异常类型名称','{"UNCONFIRMED_TIMEOUT":"超时未确认服药","CONSECUTIVE_UNCONFIRMED":"连续多次未确认","PLAN_ENDED_UNFINISHED":"计划结束未完成"}','管理端异常列表的类型名称；仅修改展示，不修改业务状态'),
('TEMPLATE','notification.owner','本人异常通知','{"title":"用药未确认提醒","content":"您有用药提醒超时未确认，系统已记录异常。这不代表医学意义上的漏服。"}','仅影响之后新入队的本人通知，已有消息保持原文'),
('TEMPLATE','notification.family','家属异常通知','{"title":"家属用药未确认提醒","content":"您关注的家人有用药提醒超时未确认，请查看授权范围内的异常事件。"}','仅影响之后新入队的授权家属通知，已有消息保持原文'),
('PARAM','delivery.paused','暂停站内消息发布','false','true暂停后续投递批次，已执行中的批次继续完成；不会停止异常入队'),
('PARAM','delivery.batch-size','每批消息投递数量','100','1至100之间的整数，下一个投递批次生效');

-- 平台管理员账号不在脚本里建，由后端 DataInitializer 在首次启动时按配置生成，
-- 避免把口令哈希硬编码进仓库。（见 README「首次启动」一节）


-- =============================================================================
--  可扩展项（对应方案第十二节 C 级「选做加分」，有余力再加，不影响主流程）
-- -----------------------------------------------------------------------------
--  sys_dict          字典表（方案第四节第 11 条「字典配置」）
--  plan_time_rule    按周几服药的复杂周期规则（当前仅支持每日固定时刻）
--  medicine_stock    剩余药量估算与配药提醒（按消耗速率外推）
--  notification_log  通知发送明细流水（当前合并进 notification.send_status）
-- =============================================================================

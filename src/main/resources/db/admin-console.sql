-- Additive and repeatable: never rewrites existing configuration or business records.
CREATE TABLE IF NOT EXISTS admin_setting (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  category VARCHAR(16) NOT NULL,
  code VARCHAR(80) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  setting_value TEXT NOT NULL,
  description VARCHAR(300) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS admin_audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  action VARCHAR(40) NOT NULL,
  target_type VARCHAR(40) NOT NULL,
  target_id VARCHAR(80) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  before_value TEXT NOT NULL,
  after_value TEXT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_admin_audit_created (create_time),
  INDEX idx_admin_audit_action (action, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO admin_setting(category,code,name,setting_value,description) VALUES
('DICT','event.labels','异常类型名称','{"UNCONFIRMED_TIMEOUT":"超时未确认服药","CONSECUTIVE_UNCONFIRMED":"连续多次未确认","PLAN_ENDED_UNFINISHED":"计划结束未完成"}','管理端异常列表的类型名称；仅修改展示，不修改业务状态'),
('TEMPLATE','notification.owner','本人异常通知','{"title":"用药未确认提醒","content":"您有用药提醒超时未确认，系统已记录异常。这不代表医学意义上的漏服。"}','仅影响之后新入队的本人通知，已有消息保持原文'),
('TEMPLATE','notification.family','家属异常通知','{"title":"家属用药未确认提醒","content":"您关注的家人有用药提醒超时未确认，请查看授权范围内的异常事件。"}','仅影响之后新入队的授权家属通知，已有消息保持原文'),
('PARAM','delivery.paused','暂停站内消息发布','false','true暂停后续投递批次，已执行中的批次继续完成；不会停止异常入队'),
('PARAM','delivery.batch-size','每批消息投递数量','100','1至100之间的整数，下一个投递批次生效');

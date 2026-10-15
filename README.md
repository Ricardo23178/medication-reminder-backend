# 用药提醒与家属协同系统 — 后端

> 《基于 HarmonyOS 与 Spring Boot 的用药提醒与家属协同系统设计与实现》服务端，本科毕业设计项目。

## 项目简介

为「用药提醒与家属协同系统」提供 RESTful API 服务，支撑 HarmonyOS 客户端的登录、药品管理、用药计划、服药提醒、家属协同、异常告警、日历统计等核心功能。

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.16 | 整体框架 |
| MyBatis-Plus | 3.5.x | ORM 持久层 |
| MySQL | 8.0 | 数据库 |
| JWT (jjwt) | 0.12 | 无状态登录认证 |
| Spring Security Crypto | — | BCrypt 密码加密 |
| Lombok | — | 减少样板代码 |

## 核心特性

- **JWT 无状态认证**：登录签发 token，`AuthInterceptor` 拦截校验，`ThreadLocal` 传递当前用户
- **越权防护**：所有数据操作按 `user_id` 过滤（`id + user_id` 双条件），防止横向越权
- **状态机建模**：计划 / 提醒任务 / 服药记录 / 异常事件 四套状态机
- **定时任务**：`@Scheduled` 每 5 分钟自动生成当日提醒、扫描超时未确认任务；变更计划会取消待执行任务
- **家属协同**：owner 发起邀请码 → family 凭码接受，授权权在数据主体手里，解绑后权限即时失效
- **幂等设计**：确认服药、生成任务、异常事件均有唯一键兜底，防重复
- **Web管理后台**：`admin-web/` 提供Vue 3管理端，支持用户状态、字典、通知模板、投递参数、异常与通知状态和操作审计，见[后台说明](docs/admin-console.md)。
- **实时账号校验**：禁用账号的旧JWT后续请求会被拒绝；管理员权限以数据库当前角色为准。

## 核心接口概览

| 模块 | 方法 | 路径 |
|------|------|------|
| 认证 | POST | `/api/auth/register` |
| 认证 | POST | `/api/auth/login` |
| 药品 | GET/POST/PUT/DELETE | `/api/medicines` |
| 计划 | GET/POST | `/api/plans` |
| 计划 | POST | `/api/plans/{id}/pause` `/resume` |
| 提醒 | GET | `/api/reminders/today` |
| 提醒 | POST | `/api/reminders/{id}/confirm` |
| 家属 | POST | `/api/family/invitations` `/invitations/accept` |
| 家属 | GET/DELETE | `/api/family/relations` `/relations/{id}` |
| 家属 | GET | `/api/family/abnormal-events` |
| 统计 | GET | `/api/statistics/calendar` |
| 消息 | GET/POST | `/api/notifications` 及详情、已读接口，见[消息说明](docs/family-collaboration-and-inbox.md) |
| 后台 | GET/PATCH/PUT | `/api/admin/**`，见[后台接口与权限](docs/admin-console.md) |

> 另有 2 个测试辅助接口：`POST /api/reminders/generate-today`、`POST /api/reminders/scan-expired`（仅 ADMIN 可手动触发）。普通用户查询今日提醒时会按需生成本人任务。

## 目录结构

```
src/main/java/com/medreminder/medicationbackend/
├── common/      # 统一返回 Result、全局异常处理、用户上下文 UserContext
├── config/      # 拦截器 AuthInterceptor、定时任务 ReminderScheduler、数据初始化
├── controller/  # 移动端接口层
├── admin/       # 管理端接口、配置与审计
├── dto/         # 请求体
├── entity/      # 数据库实体
├── mapper/      # MyBatis-Plus Mapper
├── service/     # 业务逻辑
├── util/        # JwtUtil
└── vo/          # 返回体
```

## 如何运行

### 前置条件

- JDK 17+（以 `pom.xml` 的 Java 17 编译目标为准；本次使用现有 JDK 23 验证）
- MySQL 8.0（数据库 `medication_system`，账号 `root`）

### 1. 建库建表

```sql
CREATE DATABASE medication_system DEFAULT CHARACTER SET utf8mb4;
```

当前交付目录未附建表脚本，原先说明中的 `project/db/schema.sql` 路径不存在。已有数据库可继续使用；新环境需要先取得并核对原始建表脚本，尤其是提醒任务、服药记录、异常事件的唯一键。仅建空数据库不能直接运行业务。

### 2. 修改数据库配置

`src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/medication_system?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: root123456
```

### 3. 启动

```bash
# Windows (IDEA 直接运行 MedicationBackendApplication 即可)
./mvnw spring-boot:run
```

服务默认监听 `http://localhost:8080`，接口前缀 `/api`。

## 默认测试账号

> 首次运行会通过 `DataInitializer` 初始化管理员账号（如需），普通用户通过 `/api/auth/register` 注册。

`DataInitializer` 只处理已存在且密码为占位符的 admin 记录，不会自动创建管理员。普通用户不能调用全库调试接口。

## 当前阶段优化与测试

- “稍后”默认15分钟，保持待确认；后续已服/跳过更新同一条记录。超时生成 `UNCONFIRMED` 记录。
- 日历按提醒原定日期统计；取消任务不计入应完成数，完成率为“已服 / 应完成”。稍后仍需最终确认。
- 家属异常查询要求生效关系和 `ABNORMAL_EVENT` 授权；邀请码通过条件更新领取一次。
- 数据库配置支持 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`；令牌密钥支持 `JWT_SECRET`。本地默认值保留，部署时通过环境变量覆盖。
- 默认关闭 SQL 明细日志；调试时可设置 `MYBATIS_LOG_IMPL=org.apache.ibatis.logging.stdout.StdOutImpl`。

执行 `./mvnw test` 运行回归测试和 Spring MVC/JWT 鉴权测试。测试通过属性关闭调度与管理员初始化，不修改现有数据库数据。数据库并发和客户端真机流程仍需联调验证。

上一阶段通知仅创建待发送记录。2026-10-15 已补齐站内消息中心、有限失败重试和家属知晓/关闭流程，详见[家属协同与消息中心](docs/family-collaboration-and-inbox.md)。`SENT` 仅表示消息已发布至站内消息中心，手机系统推送和后台管理仍未接入。

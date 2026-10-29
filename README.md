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

完整建表脚本已随仓库提供：[`src/main/resources/db/init.sql`](src/main/resources/db/init.sql)，共 12 张表（10 张业务表 + `admin_setting` + `admin_audit_log`）和 5 条管理端默认配置。

```bash
# 注意必须带 --default-character-set=utf8mb4
mysql --default-character-set=utf8mb4 -uroot -p < src/main/resources/db/init.sql
```

**字符集必须显式指定。** Windows 下 `mysql.exe` 默认按系统代码页（简体中文环境为 GBK）解码脚本，中文会变乱码，`admin_setting` 里的中文 JSON 通知模板随即解析失败（启动后台接口报「配置内容必须是合法JSON」）。用 Navicat / Workbench 导入时同样要确认连接字符集为 `utf8mb4`。

脚本可重复执行（`CREATE DATABASE IF NOT EXISTS` + `CREATE TABLE IF NOT EXISTS` + `INSERT IGNORE`），但**不会**修正已存在的旧表结构 —— 旧库升级仍需按下面的迁移顺序执行增量脚本。

该脚本已是最终结构，等于依次执行完 `schema.sql` → `plan-version.sql` → `admin-console.sql` → `task-history-snapshot.sql` 的结果，新环境**不需要**再单独跑那三份迁移。

已有数据库升级到计划版本功能时，先停止后端及定时任务、备份数据库，再在目标库执行一次 [`src/main/resources/db/plan-version.sql`](src/main/resources/db/plan-version.sql)，核对成功后启动新代码。该文件是增量迁移，不能替代完整建表脚本，也不能重复执行。迁移只为当前有效的未完成任务建立基线版本，无法追溯的旧历史版本保留为 `NULL`。

新计划从版本 1 开始，修改、暂停、恢复时递增。修改和暂停先在计划行锁内登记已到时点任务的服务端触发，只取消尚未触发的未来任务；已触发任务继续按自身截止时点正常确认或超时，已超时及已完成历史保留原版本和原定时点。服务端触发不代表系统通知已经送达终端。删除计划仍取消其待处理任务。

2026-10-22 新增任务生成时的药名、剂量、单位和餐时快照，提醒、个人记录与家属异常查询均读取该快照。已有数据库需在停止后端并备份后执行一次 [`src/main/resources/db/task-history-snapshot.sql`](src/main/resources/db/task-history-snapshot.sql)，前提是已完成计划版本迁移。不能重复执行。迁移只为已知且与当前计划版本一致的任务建立升级时基线；未知或旧版本的历史不推测回填，显示“历史药品信息未留存”，原记录不删除。


本阶段没有实现迟到补确认与追加更正；当前确认窗口仍为120分钟，稍后次数尚未限制。导师新要求的30分钟可配置窗口、最多一次稍后及迟到结果保留将作为下一阶段统一实现。

### 2. 配置数据库口令

口令**不再写进仓库**。复制模板文件并填写：

```bash
cp application-local.yml.example application-local.yml
# 编辑 application-local.yml，把 spring.datasource.password 改成你的 MySQL 口令
```

`application-local.yml` 已被 `.gitignore` 忽略，由 `application.yml` 中的 `spring.config.import: optional:file:./application-local.yml` 自动加载，不需要切换 profile。部署环境改用环境变量：`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`，令牌密钥用 `JWT_SECRET`。

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

### 关于集成测试（重要）

三个集成测试类挂在 `@EnabledIfEnvironmentVariable(named="RUN_MYSQL_INTEGRATION_TESTS")` 上，共 69 项：

| 测试类 | 数量 | 不设环境变量时 |
|--------|------|----------------|
| `AdminApiIntegrationTest` | 12 | 跳过 |
| `CollaborationApiIntegrationTest` | 14 | 跳过 |
| `PlanVersionIntegrationTest` | 20 | 跳过 |
| `MedicationBackendApplicationTests` | 4 | 照常执行 |
| `CurrentProgressRegressionTest` | 13 | 照常执行 |
| `NotificationDispatcherTest` | 6 | 照常执行 |

也就是说默认的 `./mvnw test` 只跑 23 项，**输出仍然是 BUILD SUCCESS**，很容易误判成全部通过。要跑真实数据库测试：

```bash
RUN_MYSQL_INTEGRATION_TESTS=true ./mvnw test
```

此时 69 项都会执行，需要能连上 `medication_system` 库。另外 `PlanVersionIntegrationTest` 里有 4 个用例带库名护栏，要求库名以 `medication_version_test_` 开头（防止误改真实库），指向普通库名时会直接失败 —— 这是保护，不是缺陷。建议单独建一个该前缀的空库跑这 4 项。


上一阶段通知仅创建待发送记录。2026-10-15 已补齐站内消息中心、有限失败重试和家属知晓/关闭流程，详见[家属协同与消息中心](docs/family-collaboration-and-inbox.md)。`SENT` 仅表示消息已发布至站内消息中心，手机系统推送和后台管理仍未接入。

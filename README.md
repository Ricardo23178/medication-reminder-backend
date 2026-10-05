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
- **定时任务**：`@Scheduled` 每 5 分钟自动生成当日提醒、扫描超时未确认任务
- **家属协同**：owner 发起邀请码 → family 凭码接受，授权权在数据主体手里，解绑后权限即时失效
- **幂等设计**：确认服药、生成任务、异常事件均有唯一键兜底，防重复

## 接口清单（10 个业务接口）

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

> 另有 2 个测试辅助接口：`POST /api/reminders/generate-today`、`POST /api/reminders/scan-expired`（手动触发定时任务，调试用）。

## 目录结构

```
src/main/java/com/medreminder/medicationbackend/
├── common/      # 统一返回 Result、全局异常处理、用户上下文 UserContext
├── config/      # 拦截器 AuthInterceptor、定时任务 ReminderScheduler、数据初始化
├── controller/  # 接口层（8 个 Controller）
├── dto/         # 请求体
├── entity/      # 数据库实体
├── mapper/      # MyBatis-Plus Mapper
├── service/     # 业务逻辑
├── util/        # JwtUtil
└── vo/          # 返回体
```

## 如何运行

### 前置条件

- JDK 21+（Spring Boot 3.5 要求）
- MySQL 8.0（数据库 `medication_system`，账号 `root`）

### 1. 建库建表

```sql
CREATE DATABASE medication_system DEFAULT CHARACTER SET utf8mb4;
```

然后执行建表脚本（项目根目录 `project/db/schema.sql`）。

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

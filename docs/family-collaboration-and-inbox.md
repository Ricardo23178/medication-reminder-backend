# 家属协同与消息中心

本阶段依据毕业设计方案中的家属协同、消息中心、异常状态与通知可靠性要求，实现“超时异常—通知入队—站内消息—家属知晓—关闭事件”的流程。消息投递使用现有数据库表，无需执行表结构变更。

## 状态和权限

- 超时扫描在同一事务中创建未确认记录、异常事件、本人消息和授权家属消息。同一事件通过行锁串行入队，对同一接收人去重。
- 家属必须具有生效中的关系和 `ABNORMAL_EVENT` 授权。绑定时间晚于事件发生时间的家属不会收到历史告警补发。
- `PENDING` 为待投递，`SENT` 表示已发布至站内消息中心，`FAILED` 为投递失败，`CANCELLED` 表示授权失效或协同事件关闭等原因取消。`SENT` 不代表手机收到系统推送，也不代表家属已读。
- 调度器默认每30秒处理至多100条待投递或失败消息。每条消息独立处理；记录3次失败后停止自动尝试。一条失败不阻断后续消息；数据库不可用而无法记录失败时，原待发送消息保留，恢复后继续处理。
- 本人消息发布不将事件改为已通知家属。授权家属消息发布成功才将 `OPEN` 事件改为 `NOTIFIED`，不会覆盖后来的已知晓或关闭状态。
- 消息已读和家属已知晓为独立操作。事件只允许由授权家属确认知晓，重复知晓保留首位操作者与时间。关闭前必须已知晓；重复关闭不改变首次关闭时间，也不改变服药记录。
- 列表、未读数、详情、单条已读和全部已读均在SQL中重新校验关系与授权。解绑后历史家属消息即时不可访问；重新绑定不会恢复旧关系期间的消息。

## 接口

接口均需 `Authorization: Bearer <token>`，沿用项目统一响应 `{code,message,data}`。业务拒绝由响应体 `code` 表示；缺少或无效凭证返回HTTP 401。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/notifications` | 本人可见消息，支持 `unreadOnly`、`beforeId`、`limit` |
| GET | `/api/notifications/unread-count` | 当前可见消息的未读总数 |
| GET | `/api/notifications/{id}` | 消息详情，不自动标记已读 |
| POST | `/api/notifications/{id}/read` | 幂等标记单条已读 |
| POST | `/api/notifications/read-all` | 将当前可见已发布消息全部标记已读 |
| GET | `/api/family/abnormal-events` | 默认返回未关闭异常；`includeClosed=true` 包含关闭历史 |
| POST | `/api/family/abnormal-events/{id}/acknowledge` | 家属确认知晓 |
| POST | `/api/family/abnormal-events/{id}/close` | 家属关闭已知晓事件 |

列表默认每页20条，允许1至50条，按消息ID倒序使用游标分页。返回 `items`、`unreadCount`、`hasMore`、`nextBeforeId`；读取下一页时将 `nextBeforeId` 作为 `beforeId`。未读总数不受当前分页或筛选范围限制。无法访问的消息或事件返回403；事件状态不允许操作返回409；非法分页参数返回400。

## 配置与运行

前后端启动步骤见各项目README。消息投递默认启用：

```properties
notification.delivery.enabled=true
notification.delivery.interval-ms=30000
```

测试通过属性关闭提醒调度、消息投递调度和管理员初始化，避免测试触发已有用户的任务。已有可关联的本人待发送异常通知会在投递时补齐当时已授权的家属接收人；缺少业务关联或授权失效的消息不会对家属展示。

## 验证

默认单元测试及鉴权测试：

```powershell
.\mvnw.cmd test
```

真实MySQL接口与投递测试需显式开启，先确认数据库连接指向可用于测试的本地数据库、表结构与项目实体一致：

```powershell
$env:RUN_MYSQL_INTEGRATION_TESTS = "true"
.\mvnw.cmd test
Remove-Item Env:RUN_MYSQL_INTEGRATION_TESTS
```

`CollaborationApiIntegrationTest` 的测试数据、接口写入与服务写入都在测试事务结束时回滚。覆盖本人消息隔离、游标分页、未读筛选、幂等已读、批量已读边界、解绑权限、授权范围、知晓关闭、通知去重、迟到绑定、投递状态和失败次数上限。`NotificationDispatcherTest` 模拟投递或失败记账异常，检查单条失败不阻断批次。

调度器启用后观察消息表中的状态与失败次数，并核对家属消息可见范围。若出现越权消息、重复接收或异常状态倒退，应先关闭 `notification.delivery.enabled`，保留异常与通知记录定位原因。尚未接入厂商推送、短信、后台管理端；消息中心可通过进入页面和手动刷新获取最新消息。

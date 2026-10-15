# 会话与并发回归证据

日期：2026-10-15，Node.js v26.7.0。

1. 先写 `test/session.test.js` 的四个行为测试，执行 `node --test test/session.test.js`：退出码 1，`ERR_MODULE_NOT_FOUND` 指向尚未实现的 `src/api.js`，1 个测试文件失败。
2. 实现 `AdminClient` 与 `ViewGuard` 后执行同一命令：退出码 0，4 项通过。覆盖旧会话迟到 401、服务器 `/me` 独立权限检查、并发登录、旧视图失效和保存互斥。
3. 后续补充当前会话非 JSON 401、恢复凭证权限与实际配置边界测试。执行 `node --test test/*.test.js`：退出码 0，8 项测试全部通过。本文件不推断未执行的构建或浏览器结果。

测试通过 fake fetch 的延迟响应与可控存储驱动真实导出 helper；未用生产数据库、未使用有效账号口令或真实令牌。

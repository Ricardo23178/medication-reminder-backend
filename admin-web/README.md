# 用药协同管理后台

Vue 3 + Element Plus 独立 Web 管理端。开发与部署入口均为 `/admin/`，普通用户账号无权进入后台；前端显示内容前必须通过 `GET /api/admin/me` 验证 `ADMIN`，后端仍负责每个接口的鉴权。

## 本地运行

前置条件：Node.js 22.12+（开发验证环境为 26.7），npm，以及运行在 `127.0.0.1:8080` 的 Spring Boot 后端。使用已有管理员账号；页面不预填口令。

```powershell
cd D:\HuaweiMoveData\Users\86186\Desktop\毕业设计\medication-backend\admin-web
npm ci
npm run dev
```

访问 `http://127.0.0.1:5173/admin/`。Vite 仅监听 loopback，端口占用会直接报错，不会悄悄切换端口。`/api` 由开发服务器代理到 `http://127.0.0.1:8080`。

## 构建与部署

```powershell
npm test
npm run build
npm run preview
```

构建产物只在本目录 `dist/`。预览地址为 `http://127.0.0.1:4173/admin/`；预览服务器不提供开发代理，需要相同来源的 `/api` 服务才能联调。正式环境将 `dist/` 的内容挂载到 `/admin/`，并将同源 `/api/` 反向代理到后端。Vite 开发服务器和 preview 仅用于本地调试。

Nginx 配置示意（替换静态文件绝对路径）：

```nginx
location /admin/ {
    alias /srv/medication/admin-web/dist/;
    index index.html;
}
location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

页面使用内部视图切换，没有浏览器路径路由；访问 `/admin/` 即可。对外部署应在反向代理配置 HTTPS。

## 页面与数据

- 运行概览：用户、待处理异常、待投递/失败通知及提醒任务指标。
- 用户管理：按账号/昵称与状态查询，管理员账号保护，启用/禁用须填写 3～200 字原因。
- 字典配置：`event.labels`，固定三个异常编码对应 1～40 字展示名。异常列表读取这项配置；缺少配置时使用内置中文名称。
- 通知模板：`notification.owner`、`notification.family`，JSON 对象包含 `title`（1～80 字）与 `content`（1～500 字）。保存后仅影响新入队消息，不改写已有通知。模板为纯文本，不执行表达式或占位符替换。
- 系统参数：`delivery.paused` 为 `true`/`false`；`delivery.batch-size` 为 1～100 整数，实际控制后端站内通知投递。
- 异常任务：只读状态列表，后台不代替授权家属知晓/关闭异常。
- 通知投递：只读投递状态、重试次数与时间，不展示接收人和消息正文。
- 操作审计：成功的用户状态和配置变更，展开查看变更前后值。

预设配置只能编辑值，不提供新增、删除。配置保存使用版本号；409 冲突会关闭旧编辑窗口并重新加载，必须基于新版本重新编辑，不自动覆盖。

## 会话、并发与验证

令牌仅保存在当前浏览器页的 `sessionStorage`。刷新后重新请求 `/admin/me`；当前会话 401 清除令牌、数据与编辑内容并回登录；403 展示权限拒绝。退出同样清空业务内容。页面不使用 `v-html`，审计值和正文按文本转义。

API helper 使用会话 generation 拒绝旧响应（包含旧会话的迟到 401/403），视图读取使用独立版本号。变更提交期间禁止导航、分页、重复提交和退出，直到保存与当前页刷新完成。切换页面或发起新查询后，旧读取不能覆盖新页面。

`npm test` 使用 Node 内置测试运行器，无浏览器依赖，覆盖旧 401 不能清除新会话、登录/恢复均独立校验管理员权限、并发登录隔离、非 JSON 401 清凭证、旧视图失效及保存互斥，以及配置真实值格式与边界。开发时先运行测试取得缺少 API helper 的失败结果，再实现 helper 得到通过结果；执行记录见同目录 `test/evidence.md`。这些单元测试不能代替后端权限验证或真实浏览器交互验收。

本地启动开发服务器后，可用已安装的Chrome运行真实页面验收：

```powershell
node test/browser-smoke.mjs
```

默认使用Windows标准Chrome安装路径；可用 `CHROME_PATH` 与 `ADMIN_URL` 环境变量覆盖。脚本启动隐藏的独立浏览器配置目录，拦截API并返回独立验收夹具，不操作真实账号或配置，结束后仅清理本次临时浏览器目录。覆盖用户状态/原因/审计、配置保存与409、字典、401清理、普通用户拒绝及390px页面宽度。服务器权限和数据库语义仍须运行后端集成测试。

当前已通过生产构建；全量Element Plus产生约1MB的JS分块（gzip约339KB），构建仍有分块大小提示。未配置单独ESLint命令；编译、Node检查和浏览器验收不等同于完整lint。

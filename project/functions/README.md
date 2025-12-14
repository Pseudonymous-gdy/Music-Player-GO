# Cloud Functions：Firestore → 个人服务器转发

此目录提供一个 `forwardUserLogs` 云函数：监听 Firestore `user_logs` 集合 `onCreate` 事件，把新文档通过 HTTPS 转发到你的个人服务器。

## 前置条件
- 安装 Firebase CLI：`npm install -g firebase-tools`
- 已在本机登录：`firebase login`
- 确保根目录存在你的 Firebase 项目配置（`firebase.json`/`.firebaserc`），或部署时加 `--project <projectId>`。

## 配置转发目标
支持两种方式，函数会优先读取 Functions Config：
```
firebase functions:config:set forward.server_url="https://your-server.com/api/logs" \
  forward.api_key="YOUR_TOKEN" \
  forward.timeout_ms="5000"
```
如不想用 Config，可在部署环境设置环境变量 `FORWARD_SERVER_URL` / `FORWARD_API_KEY` / `FORWARD_TIMEOUT_MS`。

## 部署
在 `project/functions` 下安装依赖并部署：
```
cd project/functions
npm install
firebase deploy --only functions:forwardUserLogs
```

## 工作流程
1) Firestore `user_logs/{docId}` 新建文档时触发。  
2) 取出文档内容并附加 `document_id`、`firestore_event_time`、`forwarded_at`。  
3) 使用 Node 18 的原生 `fetch` POST 到你的 `server_url`，可选 `Authorization: Bearer <api_key>`。  
4) 超时（默认 5s）或 HTTP 非 2xx 会在 Functions 日志中记录错误。

## 个人服务器期望的接口格式
- 方法：`POST`
- 头：`Content-Type: application/json`，可选 `Authorization: Bearer <token>`
- 体：原 Firestore 文档字段 + `document_id`、`firestore_event_time`、`forwarded_at`

## 安全建议
- 不要把私钥放进仓库；`serviceAccountKey.json` 已在 `.gitignore`。
- 使用 HTTPS、短期 token 或签名校验请求体，服务器端限制频率/IP。 

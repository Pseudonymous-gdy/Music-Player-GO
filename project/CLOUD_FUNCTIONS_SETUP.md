# Cloud Functions 配置和部署指南

本文档提供完整的 Firebase Cloud Functions 配置和部署步骤，用于将 Firestore 数据转发到你的个人服务器。

## 📋 目录
1. [前置准备](#前置准备)
2. [安装 Firebase CLI](#安装-firebase-cli)
3. [配置转发目标](#配置转发目标)
4. [部署 Cloud Functions](#部署-cloud-functions)
5. [修改 Android 应用](#修改-android-应用)
6. [测试验证](#测试验证)
7. [监控和调试](#监控和调试)

---

## 前置准备

### 确认 Firebase 项目信息
- **项目 ID**: `device-streaming-656221ac`
- **项目名称**: device-streaming
- **Firestore 数据库**: 已创建 (default)

### 需要准备的信息
1. 你的个人服务器 URL（例如：`https://your-server.com/api/logs`）
2. API 密钥/Token（可选，用于服务器验证）
3. 超时时间（可选，默认 5000ms）

---

## 安装 Firebase CLI

### 1. 安装 Node.js（如果还没有）
```bash
# 检查是否已安装
node --version
npm --version

# 如果未安装，访问 https://nodejs.org/ 下载安装
```

### 2. 安装 Firebase CLI
```bash
# 全局安装
npm install -g firebase-tools

# 验证安装
firebase --version
```

### 3. 登录 Firebase
```bash
firebase login

# 这会打开浏览器，使用你的 Google 账号登录
```

### 4. 验证项目连接
```bash
cd /Users/lzh/Downloads/Music-Player-GO/project
firebase projects:list

# 应该能看到 device-streaming-656221ac 项目
```

---

## 配置转发目标

### 方法 1：使用 Firebase Functions Config（推荐）

```bash
cd /Users/lzh/Downloads/Music-Player-GO/project

# 设置服务器 URL（必需）
firebase functions:config:set forward.server_url="https://your-server.com/api/logs"

# 设置 API 密钥（可选）
firebase functions:config:set forward.api_key="YOUR_SECRET_TOKEN"

# 设置超时时间（可选，默认 5000ms）
firebase functions:config:set forward.timeout_ms="10000"

# 查看当前配置
firebase functions:config:get
```

**示例输出：**
```json
{
  "forward": {
    "server_url": "https://your-server.com/api/logs",
    "api_key": "YOUR_SECRET_TOKEN",
    "timeout_ms": "10000"
  }
}
```

### 方法 2：使用环境变量
如果不想用 Functions Config，也可以在 `.env` 文件中设置：
```bash
FORWARD_SERVER_URL=https://your-server.com/api/logs
FORWARD_API_KEY=YOUR_SECRET_TOKEN
FORWARD_TIMEOUT_MS=10000
```

---

## 部署 Cloud Functions

### 1. 安装依赖
```bash
cd /Users/lzh/Downloads/Music-Player-GO/project/functions
npm install
```

### 2. 部署 Firestore 规则（首次）
```bash
cd /Users/lzh/Downloads/Music-Player-GO/project
firebase deploy --only firestore:rules
```

### 3. 部署 Cloud Function
```bash
cd /Users/lzh/Downloads/Music-Player-GO/project

# 部署单个函数
firebase deploy --only functions:forwardUserLogs

# 或者使用 npm script
cd functions
npm run deploy
```

### 4. 等待部署完成
部署成功后会显示：
```
✔  functions[forwardUserLogs(us-central1)] Successful create operation.
Function URL (forwardUserLogs(us-central1)): https://...
✔  Deploy complete!
```

---

## 修改 Android 应用

当前应用将数据上传到 `user_behaviors` 集合，但 Cloud Function 监听的是 `user_logs` 集合。你有两个选择：

### 选项 1：修改 Cloud Function 监听 `user_behaviors`

编辑 `functions/index.js`，将第 23 行：
```javascript
.firestore.document("user_logs/{docId}")
```
改为：
```javascript
.firestore.document("user_behaviors/{docId}")
```

然后重新部署：
```bash
firebase deploy --only functions:forwardUserLogs
```

### 选项 2：修改 Android 应用上传到 `user_logs`（推荐）

这样可以分离原始数据和转发数据：
- `user_behaviors`: 保留原始用户行为数据
- `user_logs`: 专门用于转发到个人服务器的数据

**需要修改的文件：** `app/src/main/java/com/example/musicplayergo/utils/FirestoreLogger.kt`

将第 13 行的常量改为：
```kotlin
private const val COLLECTION_USER_BEHAVIORS = "user_logs"
```

然后重新构建和部署 APK。

---

## 测试验证

### 1. 本地测试（使用 Firestore 模拟器）
```bash
cd /Users/lzh/Downloads/Music-Player-GO/project
firebase emulators:start --only functions,firestore
```

### 2. 手动测试转发

在 Firebase Console 中手动添加一条测试数据：

1. 打开 [Firebase Console](https://console.firebase.google.com/)
2. 选择项目 `device-streaming-656221ac`
3. 进入 **Firestore Database**
4. 点击 **启动集合**
5. 集合 ID: `user_logs`（或 `user_behaviors`，取决于你的配置）
6. 文档 ID: 自动生成
7. 添加字段：
   ```
   eventType: "test"
   userId: "test_user_123"
   timestamp: 1234567890
   songTitle: "Test Song"
   ```
8. 点击**保存**

### 3. 检查 Cloud Functions 日志

```bash
# 实时查看日志
firebase functions:log --only forwardUserLogs

# 或在 Firebase Console 查看
# Functions → forwardUserLogs → 日志
```

**成功的日志示例：**
```
Forwarded user_logs doc { docId: 'abc123', status: 200 }
```

**失败的日志示例：**
```
Forwarding failed { status: 404, statusText: 'Not Found', body: '...' }
```

### 4. 检查你的服务器

确认服务器收到了 POST 请求，请求体格式：
```json
{
  "eventType": "test",
  "userId": "test_user_123",
  "timestamp": 1234567890,
  "songTitle": "Test Song",
  "document_id": "abc123",
  "firestore_event_time": "2025-01-15T10:30:00.000Z",
  "forwarded_at": "2025-01-15T10:30:01.234Z"
}
```

请求头：
```
Content-Type: application/json
Authorization: Bearer YOUR_SECRET_TOKEN  (如果配置了 api_key)
```

---

## 监控和调试

### 查看 Functions 日志
```bash
# 实时日志
firebase functions:log --only forwardUserLogs

# 最近的 100 条日志
firebase functions:log --only forwardUserLogs --limit 100
```

### Firebase Console 监控
1. 打开 [Firebase Console](https://console.firebase.google.com/)
2. 选择项目
3. 进入 **Functions**
4. 点击 `forwardUserLogs`
5. 查看：
   - **调用次数**
   - **执行时间**
   - **错误率**
   - **详细日志**

### 常见问题

#### 1. 配置未生效
```bash
# 删除旧配置
firebase functions:config:unset forward

# 重新设置
firebase functions:config:set forward.server_url="https://..."

# 重新部署
firebase deploy --only functions:forwardUserLogs
```

#### 2. 超时错误
增加超时时间：
```bash
firebase functions:config:set forward.timeout_ms="15000"
firebase deploy --only functions:forwardUserLogs
```

#### 3. 权限错误
检查 Firestore 规则：
```bash
firebase deploy --only firestore:rules
```

#### 4. 服务器未收到请求
- 检查服务器 URL 是否正确
- 检查服务器是否在运行
- 检查防火墙/安全组设置
- 查看 Cloud Functions 日志

---

## 服务器端实现示例

### Node.js + Express
```javascript
const express = require('express');
const app = express();

app.use(express.json());

app.post('/api/logs', (req, res) => {
  // 验证 API 密钥
  const token = req.headers.authorization?.replace('Bearer ', '');
  if (token !== 'YOUR_SECRET_TOKEN') {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  // 处理数据
  const data = req.body;
  console.log('Received log:', data);

  // 存储到数据库或其他处理
  // ...

  res.json({ success: true });
});

app.listen(3000, () => {
  console.log('Server running on port 3000');
});
```

### Python + Flask
```python
from flask import Flask, request, jsonify

app = Flask(__name__)

@app.route('/api/logs', methods=['POST'])
def receive_logs():
    # 验证 API 密钥
    token = request.headers.get('Authorization', '').replace('Bearer ', '')
    if token != 'YOUR_SECRET_TOKEN':
        return jsonify({'error': 'Unauthorized'}), 401

    # 处理数据
    data = request.json
    print('Received log:', data)

    # 存储到数据库或其他处理
    # ...

    return jsonify({'success': True})

if __name__ == '__main__':
    app.run(port=3000)
```

---

## 安全建议

1. **使用 HTTPS**: 确保服务器 URL 使用 HTTPS
2. **API 密钥管理**:
   - 不要将密钥硬编码在代码中
   - 定期轮换密钥
   - 使用环境变量或 Firebase Config
3. **限流**: 在服务器端实施请求频率限制
4. **IP 白名单**: 只允许 Google Cloud Functions 的 IP 范围
5. **请求签名**: 考虑使用 HMAC 签名验证请求完整性

---

## 成本优化

### Cloud Functions 免费额度
- **调用次数**: 每月 200 万次
- **计算时间**: 每月 40 万 GB-秒
- **出站流量**: 每月 5GB

### 监控用量
```bash
# 查看当前用量
firebase projects:addfirebase device-streaming-656221ac
firebase use device-streaming-656221ac
gcloud functions list
```

### 降低成本建议
1. 批量处理：不是每次都转发，而是批量发送
2. 过滤数据：只转发重要事件
3. 使用更便宜的区域：`us-central1` 是最便宜的

---

## 快速参考命令

```bash
# 1. 安装依赖
cd /Users/lzh/Downloads/Music-Player-GO/project/functions
npm install

# 2. 配置转发
firebase functions:config:set forward.server_url="https://your-server.com/api/logs"
firebase functions:config:set forward.api_key="YOUR_TOKEN"

# 3. 部署
firebase deploy --only functions:forwardUserLogs

# 4. 查看日志
firebase functions:log --only forwardUserLogs

# 5. 测试（在 Firestore Console 手动添加数据）
```

---

## 下一步

1. ✅ 安装 Firebase CLI
2. ✅ 配置转发 URL 和 API 密钥
3. ✅ 部署 Cloud Function
4. ✅ 部署 Firestore 规则
5. ⬜ 修改 Android 应用（选项 1 或 2）
6. ⬜ 测试转发功能
7. ⬜ 监控运行状况

如有问题，请查看 Cloud Functions 日志或联系支持。

# 🚀 快速开始：部署 Cloud Functions

## 📝 概览

当 Android 应用上传用户行为到 Firestore 的 `user_behaviors` 集合时，Cloud Function 会自动将数据转发到你的个人服务器。

**数据流程：**
```
Android App → Firestore (user_behaviors) → Cloud Function → 你的服务器
```

---

## ⚡ 5 分钟快速部署

### 1️⃣ 安装 Firebase CLI

```bash
# 安装
npm install -g firebase-tools

# 登录（会打开浏览器）
firebase login

# 验证（应该能看到 device-streaming-656221ac）
firebase projects:list
```

### 2️⃣ 配置转发目标

```bash
cd /Users/lzh/Downloads/Music-Player-GO/project

# 设置你的服务器 URL（必需）
firebase functions:config:set forward.server_url="https://your-server.com/api/logs"

# 设置 API 密钥（可选，推荐）
firebase functions:config:set forward.api_key="YOUR_SECRET_TOKEN"

# 设置超时时间（可选，默认 5 秒）
firebase functions:config:set forward.timeout_ms="10000"

# 验证配置
firebase functions:config:get
```

**示例输出：**
```json
{
  "forward": {
    "server_url": "https://api.example.com/logs",
    "api_key": "sk_test_1234567890",
    "timeout_ms": "10000"
  }
}
```

### 3️⃣ 安装依赖

```bash
cd /Users/lzh/Downloads/Music-Player-GO/project/functions
npm install
```

### 4️⃣ 部署

```bash
cd /Users/lzh/Downloads/Music-Player-GO/project

# 部署 Firestore 规则
firebase deploy --only firestore:rules

# 部署 Cloud Function
firebase deploy --only functions:forwardUserLogs
```

**成功部署后会显示：**
```
✔  functions[forwardUserLogs(us-central1)] Successful create operation.
✔  Deploy complete!

Project Console: https://console.firebase.google.com/project/device-streaming-656221ac/overview
```

### 5️⃣ 测试

**方法 1：在 Firestore Console 手动添加测试数据**

1. 打开 https://console.firebase.google.com/project/device-streaming-656221ac/firestore
2. 点击 **启动集合**（如果还没有数据）
3. 集合 ID: `user_behaviors`
4. 文档 ID: 自动生成
5. 添加字段：
   ```
   eventType: "test"
   songId: 12345
   songTitle: "Test Song"
   userId: "test_user"
   timestamp: 1736923200000
   ```
6. 点击 **保存**

**方法 2：运行 Android 应用**

在应用中播放/暂停/收藏歌曲，数据会自动上传。

### 6️⃣ 查看日志

```bash
# 实时查看转发日志
firebase functions:log --only forwardUserLogs

# 或在 Firebase Console 查看
# https://console.firebase.google.com/project/device-streaming-656221ac/functions/logs
```

**成功转发的日志：**
```
✅ Forwarded user_behaviors doc { docId: 'abc123', status: 200 }
```

**失败的日志：**
```
❌ Forwarding failed { status: 404, statusText: 'Not Found' }
```

---

## 🖥️ 服务器端需要实现

你的服务器需要接收 POST 请求：

### 接口规格

- **URL**: 你配置的 `forward.server_url`
- **方法**: `POST`
- **Content-Type**: `application/json`
- **Authorization**: `Bearer YOUR_API_KEY`（如果配置了）

### 请求体示例

```json
{
  "userId": "device_abc123",
  "sessionId": "session_xyz789",
  "sequence": 42,
  "eventType": "play",
  "timestamp": 1736923200000,
  "songId": 12345,
  "songTitle": "Song Name",
  "artist": "Artist Name",
  "album": "Album Name",
  "duration": 180000,
  "document_id": "firestore_doc_id",
  "firestore_event_time": "2025-01-15T10:00:00.000Z",
  "forwarded_at": "2025-01-15T10:00:01.234Z"
}
```

### Node.js 示例服务器

```javascript
const express = require('express');
const app = express();

app.use(express.json());

app.post('/api/logs', (req, res) => {
  // 验证 API 密钥
  const authHeader = req.headers.authorization;
  const token = authHeader?.replace('Bearer ', '');

  if (token !== 'YOUR_SECRET_TOKEN') {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  // 接收数据
  const data = req.body;
  console.log('📥 Received:', {
    eventType: data.eventType,
    songTitle: data.songTitle,
    timestamp: new Date(data.timestamp).toISOString()
  });

  // 这里可以存储到数据库
  // await db.logs.insert(data);

  // 返回成功
  res.json({ success: true, received_at: new Date().toISOString() });
});

app.listen(3000, () => {
  console.log('🚀 Server listening on port 3000');
});
```

---

## 🔍 常见问题

### Q1: 如何修改服务器 URL？

```bash
# 更新配置
firebase functions:config:set forward.server_url="https://new-server.com/api/logs"

# 重新部署
firebase deploy --only functions:forwardUserLogs
```

### Q2: 数据没有转发到服务器？

**检查清单：**
1. ✅ Cloud Function 是否部署成功？
2. ✅ 配置中的 `server_url` 是否正确？
3. ✅ 服务器是否在运行？
4. ✅ 服务器是否返回 2xx 状态码？
5. ✅ 查看 Cloud Functions 日志

```bash
# 查看详细日志
firebase functions:log --only forwardUserLogs --limit 50
```

### Q3: 如何查看已配置的设置？

```bash
firebase functions:config:get
```

### Q4: 如何删除配置？

```bash
# 删除所有转发配置
firebase functions:config:unset forward

# 删除单个配置
firebase functions:config:unset forward.api_key
```

### Q5: 部署失败怎么办？

```bash
# 查看详细错误
firebase deploy --only functions:forwardUserLogs --debug

# 检查 Firebase 项目
firebase use

# 检查依赖
cd functions
npm install
```

---

## 📊 监控和维护

### 查看 Cloud Functions 使用情况

1. 打开 https://console.firebase.google.com/project/device-streaming-656221ac/functions
2. 点击 `forwardUserLogs`
3. 查看：
   - 📈 调用次数
   - ⏱️ 平均执行时间
   - ❌ 错误率
   - 💰 估计费用

### 免费额度

- ✅ 调用次数：**200 万次/月**
- ✅ 计算时间：**40 万 GB-秒/月**
- ✅ 出站流量：**5 GB/月**

超出免费额度后才会收费。

---

## 🎯 下一步

1. ✅ 完成上面的 5 步部署
2. ⬜ 实现你的服务器接收接口
3. ⬜ 测试转发功能
4. ⬜ 在 Android 应用中触发用户行为
5. ⬜ 验证服务器收到数据

---

## 📚 更多帮助

- 📖 完整文档：`CLOUD_FUNCTIONS_SETUP.md`
- 🔧 Cloud Functions 代码：`functions/index.js`
- 📝 Firestore 规则：`firestore.rules`
- 🌐 Firebase Console: https://console.firebase.google.com/project/device-streaming-656221ac

---

## 💡 提示

- 🔐 **安全**: 一定要设置 `api_key` 来保护你的服务器
- 📊 **监控**: 定期查看 Cloud Functions 日志和使用情况
- 💰 **成本**: 正常使用完全在免费额度内
- 🐛 **调试**: 遇到问题先查看 `firebase functions:log`

有问题随时查看日志或文档！🚀

# Firebase Analytics 用户行为追踪指南

## 📋 概述

本项目已完整集成 Firebase Analytics 用于追踪正常用户的使用行为。本文档说明如何验证和监控分析事件。

---

## ✅ 已实现的改进

### 1. **代码优化**
- ✅ 移除重复的 Firebase 初始化（只在 `GoApp.onCreate()` 中初始化一次）
- ✅ 添加详细的本地日志，方便实时验证事件触发
- ✅ 添加 ProGuard 规则，确保 Release 版本不会混淆 Firebase 代码
- ✅ 改进错误处理和状态日志

### 2. **已追踪的用户行为**

| 事件名称 | 触发时机 | 参数 |
|---------|---------|------|
| `screen_view` | 打开 MainActivity | screen_name, screen_class |
| `select_content` | 点击播放按钮 | song_title, artist_name |
| `song_complete` | 歌曲播放完成 | song_id, song_title, listen_duration |
| `habit_listen` | 歌曲播放完成 | 同上 |
| `tab_view` | 切换标签页 | tab, index |
| `tab_duration` | 离开标签页 | tab, duration_ms |
| `search` | 搜索操作 | screen, query |
| `recommend_click` | 点击推荐歌曲 | song_id, title, artist, position |
| `recommend_refresh` | 刷新推荐 | source |
| `song_selected` | 选择歌曲 | song_id, title, artist, source |
| `prediction_result` | 预测结果 | source, count |
| `favorite_action` | 收藏操作 | song_id, title, artist, action |

所有事件都包含：
- `session_id` - 会话 ID（每12小时自动刷新）
- `seq` - 事件序列号
- `timestamp` - 事件时间戳

---

## 🔍 验证方法

### 方法 1: 使用自动化测试脚本（推荐）

```bash
cd /Users/lzh/Downloads/Music-Player-GO/project
./test_analytics.sh
```

这个脚本会：
1. ✅ 检查设备连接
2. ✅ 验证应用安装
3. ✅ 启动应用
4. ✅ 实时显示所有 Analytics 事件

**示例输出：**
```
🚀 Initializing Analytics...
   ✓ Firebase Analytics initialized successfully
   Session ID: 7f3a9b2e...
   Sequence: 0

📊 Event: screen_view | Session: 7f3a9b2e... | Seq: 1
   Params: screen_name=MainActivity, screen_class=MainActivity
   ✓ Sent to Firebase

📊 Event: tab_view | Session: 7f3a9b2e... | Seq: 2
   Params: tab=Artists, index=0
   ✓ Sent to Firebase

📊 Event: select_content | Session: 7f3a9b2e... | Seq: 3
   Params: song_title=Song Name, artist_name=Artist Name
   ✓ Sent to Firebase
```

### 方法 2: 手动查看 Logcat

```bash
# 过滤 Analytics 相关日志
adb logcat -s AnalyticsLogger:* Firebase:* FA:*

# 或者更详细的过滤
adb logcat | grep -E "AnalyticsLogger|Firebase|📊"
```

### 方法 3: Firebase Console 查看

**重要提示：**
- ⏰ **正常模式下，事件会延迟 1-24 小时才在 Firebase Console 显示**
- 💡 如果需要实时查看，请使用 Debug 模式（见下文）

**查看步骤：**
1. 访问 [Firebase Console](https://console.firebase.google.com/)
2. 选择项目 `device-streaming-656221ac`
3. 进入 Analytics → Events
4. 查看事件统计

---

## 🐛 Debug 模式（可选）

如果需要在 Firebase Console 中实时查看事件：

```bash
# 1. 启用 debug 模式
adb shell setprop debug.firebase.analytics.app com.example.musicplayergo

# 2. 重启应用
adb shell am force-stop com.example.musicplayergo
adb shell am start -n com.example.musicplayergo/.ui.MainActivity

# 3. 在 Firebase Console 查看
# Analytics → DebugView → 实时事件流

# 4. 测试完成后关闭 debug 模式
adb shell setprop debug.firebase.analytics.app .none.
```

---

## 📱 完整测试流程

### 步骤 1: 准备环境
```bash
# 连接设备或启动模拟器
adb devices

# 构建并安装应用
./gradlew installDebug
```

### 步骤 2: 启动监控
```bash
./test_analytics.sh
```

### 步骤 3: 执行用户操作

在应用中执行以下操作，观察日志输出：

1. **启动应用** → 应看到 `screen_view` 事件
2. **切换标签页** → 应看到 `tab_view` 和 `tab_duration` 事件
3. **播放音乐** → 应看到 `select_content` 事件
4. **等待歌曲播放完成** → 应看到 `song_complete` 事件
5. **搜索歌曲** → 应看到 `search` 事件
6. **点击推荐** → 应看到 `recommend_click` 事件

### 步骤 4: 验证结果

**本地日志（实时）：**
- ✅ 每个操作都应生成对应的日志
- ✅ 日志应显示 `✓ Sent to Firebase`
- ❌ 如果显示 `✗ Firebase not initialized`，检查初始化错误

**Firebase Console（延迟）：**
- ⏰ 等待 1-24 小时
- 📊 在 Analytics → Events 中查看累计数据
- 👥 在 Analytics → Users 中查看用户数据

---

## ⚠️ 常见问题排查

### Q1: 为什么 Firebase Console 看不到数据？

**可能原因：**
1. **数据延迟** - 正常模式下延迟 1-24 小时，这是正常的
2. **设备没有 Google Play Services** - 某些国产手机或模拟器
3. **网络问题** - 检查设备网络连接
4. **初始化失败** - 查看 Logcat 错误日志

**解决方法：**
```bash
# 检查初始化状态
adb logcat | grep "AnalyticsLogger\|Firebase"

# 启用 debug 模式实时查看
adb shell setprop debug.firebase.analytics.app com.example.musicplayergo
```

### Q2: Logcat 显示 "Firebase not initialized"

**可能原因：**
- Google Play Services 未安装或版本过旧
- `google-services.json` 配置错误
- 包名不匹配

**检查步骤：**
```bash
# 1. 验证包名
adb shell pm list packages | grep musicplayergo

# 2. 查看详细错误
adb logcat -s Firebase:* GooglePlayServicesUtil:*

# 3. 检查 Google Play Services 版本
adb shell dumpsys package com.google.android.gms | grep versionName
```

### Q3: 本地日志显示事件，但 Firebase Console 没有

这是**正常现象**！
- 本地日志是实时的
- Firebase 上报是批量的、延迟的
- 即使本地显示成功，数据也需要 1-24 小时才能在控制台显示

---

## 📊 数据分析建议

### 1. 用户留存分析
- 查看 `screen_view` 事件频率
- 分析 `session_id` 的生命周期

### 2. 内容偏好分析
- `song_complete` - 哪些歌曲被完整播放
- `listen_duration` - 平均听歌时长
- `recommend_click` - 推荐系统效果

### 3. 功能使用分析
- `tab_view` 和 `tab_duration` - 哪些功能最受欢迎
- `search` - 用户搜索行为
- `favorite_action` - 收藏偏好

---

## 🔐 隐私说明

所有 Analytics 数据：
- ✅ 不包含个人身份信息（PII）
- ✅ Session ID 每 12 小时自动刷新
- ✅ 遵守 Firebase 隐私政策
- ✅ 用户可以在设备设置中关闭 Analytics

---

## 📞 技术支持

如果遇到问题：
1. 运行 `./test_analytics.sh` 查看实时日志
2. 检查 `adb logcat` 中的错误信息
3. 验证 `google-services.json` 配置
4. 确保设备有 Google Play Services

**日志文件位置：**
- `AnalyticsLogger.kt` - 主要日志来源
- `BehaviorReporter.kt` - 行为上报日志

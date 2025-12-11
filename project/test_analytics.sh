#!/bin/bash
# Firebase Analytics 正常用户行为验证脚本

echo "🔍 Firebase Analytics 用户行为监控工具"
echo "========================================"
echo ""

PACKAGE="com.example.musicplayergo"

# 检查设备连接
echo "1️⃣ 检查设备连接..."
DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)
if [ $DEVICES -eq 0 ]; then
    echo "❌ 没有检测到连接的设备"
    echo "   请连接 Android 设备或启动模拟器"
    exit 1
fi
echo "✅ 发现 $DEVICES 个设备"
echo ""

# 检查应用是否安装
echo "2️⃣ 检查应用安装..."
if adb shell pm list packages | grep -q "$PACKAGE"; then
    echo "✅ 应用已安装"
else
    echo "❌ 应用未安装"
    echo "   运行: ./gradlew installDebug"
    exit 1
fi
echo ""

# 清除旧日志
echo "3️⃣ 清除旧日志..."
adb logcat -c
echo "✅ 日志已清除"
echo ""

# 启动应用
echo "4️⃣ 启动应用..."
adb shell am force-stop $PACKAGE
sleep 1
adb shell am start -n $PACKAGE/.ui.MainActivity
echo "✅ 应用已启动"
echo ""

echo "5️⃣ 开始监控 Analytics 事件..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "📝 监控以下事件："
echo "   - Firebase 初始化"
echo "   - 用户行为事件（播放、切换标签等）"
echo "   - 事件上报状态"
echo ""
echo "💡 提示："
echo "   - 现在开始使用应用（播放音乐、切换标签等）"
echo "   - 你会实时看到所有被记录的事件"
echo "   - 按 Ctrl+C 停止监控"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

# 实时监控日志
adb logcat -s AnalyticsLogger:* Firebase:* FA:* FA-SVC:* | while read line; do
    # 高亮显示重要信息
    if echo "$line" | grep -q "Initializing Analytics"; then
        echo "🚀 $line"
    elif echo "$line" | grep -q "📊 Event"; then
        echo "$line"
    elif echo "$line" | grep -q "✓ Sent to Firebase"; then
        echo "$line"
    elif echo "$line" | grep -q "✗"; then
        echo "⚠️  $line"
    elif echo "$line" | grep -q "Error"; then
        echo "❌ $line"
    else
        echo "$line"
    fi
done

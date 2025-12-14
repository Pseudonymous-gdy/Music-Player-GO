#!/bin/bash
# 快速验证脚本

echo "🔍 验证包名重构完成度"
echo "======================================"
echo ""

# 1. 检查旧包名引用
echo "1️⃣ 检查是否有遗漏的旧包名..."
OLD_REFS=$(grep -r "com\.iven\.musicplayergo" app/src --include="*.kt" --include="*.xml" 2>/dev/null | wc -l)
if [ "$OLD_REFS" -eq 0 ]; then
    echo "   ✅ 无旧包名引用"
else
    echo "   ❌ 发现 $OLD_REFS 处旧包名引用"
    grep -r "com\.iven\.musicplayergo" app/src --include="*.kt" --include="*.xml" | head -5
fi
echo ""

# 2. 检查新目录结构
echo "2️⃣ 检查目录结构..."
if [ -d "app/src/main/java/com/example/musicplayergo" ]; then
    echo "   ✅ 新目录结构存在"
else
    echo "   ❌ 新目录结构不存在"
fi

if [ -d "app/src/main/java/com/iven" ]; then
    echo "   ❌ 旧目录仍然存在"
else
    echo "   ✅ 旧目录已删除"
fi
echo ""

# 3. 检查配置文件
echo "3️⃣ 检查配置文件..."
if grep -q "namespace 'com.example.musicplayergo'" app/build.gradle; then
    echo "   ✅ build.gradle namespace 正确"
else
    echo "   ❌ build.gradle namespace 不正确"
fi

if grep -q 'package="com.example.musicplayergo"' app/src/main/AndroidManifest.xml; then
    echo "   ✅ AndroidManifest package 正确"
else
    echo "   ❌ AndroidManifest package 不正确"
fi
echo ""

# 4. 尝试构建
echo "4️⃣ 验证构建..."
./gradlew assembleDebug --quiet > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "   ✅ 构建成功"
    APK_SIZE=$(du -h app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | cut -f1)
    echo "   📦 APK 大小: $APK_SIZE"
else
    echo "   ❌ 构建失败"
fi
echo ""

# 5. 检查 Firebase 配置
echo "5️⃣ 检查 Firebase 配置..."
if grep -q '"package_name": "com.example.musicplayergo"' app/google-services.json; then
    echo "   ✅ google-services.json 包名正确"
else
    echo "   ❌ google-services.json 包名不匹配"
fi
echo ""

echo "======================================"
echo "✅ 验证完成！"
echo ""
echo "📱 下一步："
echo "   ./gradlew installDebug    # 安装应用"
echo "   ./test_analytics.sh       # 测试 Analytics"

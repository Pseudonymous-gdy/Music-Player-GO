#!/bin/bash

# Cloud Functions 部署脚本
# 使用方法：./deploy-functions.sh [config|deploy|logs|test]

set -e

PROJECT_DIR="/Users/lzh/Downloads/Music-Player-GO/project"
FUNCTIONS_DIR="$PROJECT_DIR/functions"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 打印带颜色的消息
print_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

print_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

print_error() {
    echo -e "${RED}❌ $1${NC}"
}

# 检查 Firebase CLI
check_firebase_cli() {
    if ! command -v firebase &> /dev/null; then
        print_error "Firebase CLI 未安装"
        echo ""
        echo "请运行以下命令安装："
        echo "  npm install -g firebase-tools"
        echo ""
        exit 1
    fi
    print_success "Firebase CLI 已安装"
}

# 检查登录状态
check_login() {
    if ! firebase projects:list &> /dev/null; then
        print_error "未登录 Firebase"
        echo ""
        echo "请运行以下命令登录："
        echo "  firebase login"
        echo ""
        exit 1
    fi
    print_success "已登录 Firebase"
}

# 配置转发目标
configure() {
    print_info "开始配置转发目标..."
    echo ""

    # 读取服务器 URL
    read -p "请输入服务器 URL (例如: https://api.example.com/logs): " SERVER_URL
    if [ -z "$SERVER_URL" ]; then
        print_error "服务器 URL 不能为空"
        exit 1
    fi

    # 读取 API 密钥（可选）
    read -p "请输入 API 密钥 (可选，直接回车跳过): " API_KEY

    # 读取超时时间（可选）
    read -p "请输入超时时间（毫秒，默认 5000）: " TIMEOUT_MS
    TIMEOUT_MS=${TIMEOUT_MS:-5000}

    echo ""
    print_info "配置信息："
    echo "  Server URL: $SERVER_URL"
    echo "  API Key: ${API_KEY:-<未设置>}"
    echo "  Timeout: ${TIMEOUT_MS}ms"
    echo ""

    read -p "确认配置？(y/n): " CONFIRM
    if [ "$CONFIRM" != "y" ]; then
        print_warning "已取消配置"
        exit 0
    fi

    cd "$PROJECT_DIR"

    # 设置配置
    firebase functions:config:set forward.server_url="$SERVER_URL"

    if [ -n "$API_KEY" ]; then
        firebase functions:config:set forward.api_key="$API_KEY"
    fi

    firebase functions:config:set forward.timeout_ms="$TIMEOUT_MS"

    echo ""
    print_success "配置完成！"
    echo ""
    print_info "当前配置："
    firebase functions:config:get
}

# 安装依赖
install_deps() {
    print_info "安装依赖..."
    cd "$FUNCTIONS_DIR"
    npm install
    print_success "依赖安装完成"
}

# 部署
deploy() {
    print_info "开始部署..."
    echo ""

    cd "$PROJECT_DIR"

    # 检查是否已配置
    if ! firebase functions:config:get forward.server_url &> /dev/null; then
        print_warning "尚未配置转发目标"
        read -p "是否现在配置？(y/n): " DO_CONFIG
        if [ "$DO_CONFIG" = "y" ]; then
            configure
        else
            print_error "必须先配置转发目标"
            exit 1
        fi
    fi

    # 安装依赖
    if [ ! -d "$FUNCTIONS_DIR/node_modules" ]; then
        install_deps
    fi

    # 部署 Firestore 规则
    print_info "部署 Firestore 规则..."
    firebase deploy --only firestore:rules

    # 部署 Cloud Function
    print_info "部署 Cloud Function..."
    firebase deploy --only functions:forwardUserLogs

    echo ""
    print_success "部署完成！"
    echo ""
    print_info "下一步："
    echo "  1. 运行 Android 应用触发用户行为"
    echo "  2. 查看日志: ./deploy-functions.sh logs"
    echo "  3. 检查服务器是否收到数据"
}

# 查看日志
view_logs() {
    print_info "查看 Cloud Functions 日志..."
    echo ""
    cd "$PROJECT_DIR"
    firebase functions:log --only forwardUserLogs
}

# 测试配置
test_config() {
    print_info "测试配置..."
    echo ""

    cd "$PROJECT_DIR"

    print_info "当前配置："
    firebase functions:config:get

    echo ""
    print_info "Cloud Function 状态："
    firebase functions:list 2>/dev/null || print_warning "函数可能尚未部署"

    echo ""
    print_info "Firestore 规则状态："
    firebase firestore:rules:list 2>/dev/null || print_warning "规则可能尚未部署"
}

# 显示帮助
show_help() {
    echo "Cloud Functions 部署脚本"
    echo ""
    echo "使用方法："
    echo "  ./deploy-functions.sh [命令]"
    echo ""
    echo "命令："
    echo "  config    配置转发目标（服务器 URL、API 密钥等）"
    echo "  deploy    部署 Cloud Functions 和 Firestore 规则"
    echo "  logs      查看 Cloud Functions 日志"
    echo "  test      测试配置和部署状态"
    echo "  help      显示此帮助信息"
    echo ""
    echo "示例："
    echo "  ./deploy-functions.sh config   # 配置转发目标"
    echo "  ./deploy-functions.sh deploy   # 部署函数"
    echo "  ./deploy-functions.sh logs     # 查看日志"
}

# 主函数
main() {
    echo ""
    echo "═══════════════════════════════════════════"
    echo "   Cloud Functions 部署脚本"
    echo "═══════════════════════════════════════════"
    echo ""

    check_firebase_cli
    check_login

    echo ""

    case "${1:-help}" in
        config)
            configure
            ;;
        deploy)
            deploy
            ;;
        logs)
            view_logs
            ;;
        test)
            test_config
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            print_error "未知命令: $1"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

main "$@"

#!/bin/bash
# ============================================================================
# 上课通 Linux 开机自启动安装脚本（systemd 服务）
# 用法：以 root 执行  sudo bash install-linux.sh
# 前置：已安装 JDK17+、MySQL、且本目录已 mvn package 生成 target/skt-server-1.0.0.jar
# ============================================================================
set -e

APP_DIR="/opt/skt-server"
JAR_NAME="skt-server-1.0.0.jar"
SERVICE_FILE="/etc/systemd/system/skt-server.service"
ENV_FILE="/etc/skt-server/skt.env"
SRC_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== 上课通 systemd 服务安装 ==="

# 1. 部署目录与日志目录
mkdir -p "$APP_DIR"
mkdir -p /var/log/skt-server

# 2. 拷贝 jar 与 service 单元
if [ ! -f "$SRC_DIR/target/$JAR_NAME" ]; then
  echo "[错误] 未找到 $SRC_DIR/target/$JAR_NAME，请先在项目根目录执行: mvn -DskipTests package"
  exit 1
fi
cp -f "$SRC_DIR/target/$JAR_NAME" "$APP_DIR/$JAR_NAME"
cp -f "$SRC_DIR/deploy/skt-server.service" "$SERVICE_FILE"

# 3. 生成/校验密钥环境文件（不覆盖已有，防止覆盖真实密钥）
mkdir -p /etc/skt-server
if [ ! -f "$ENV_FILE" ]; then
  cp "$SRC_DIR/deploy/skt.env.example" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "[重要] 已生成 $ENV_FILE，请编辑填入真实密钥：  sudo nano $ENV_FILE"
  echo "        至少填入 AI_API_KEY / DB_PASSWORD / JWT_SECRET 后再启动服务"
  exit 0
fi
chmod 600 "$ENV_FILE"

# 4. 创建运行用户（如不存在）
if ! id -u skt >/dev/null 2>&1; then
  useradd -r -s /sbin/nologin skt
fi
chown -R skt:skt "$APP_DIR" /var/log/skt-server

# 5. 注册并启用开机自启动
systemctl daemon-reload
systemctl enable skt-server.service
systemctl restart skt-server.service

echo "=== 安装完成 ==="
echo "状态: systemctl status skt-server"
echo "日志: tail -f /var/log/skt-server/app.log"
echo "停止: sudo systemctl stop skt-server"

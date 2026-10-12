#!/bin/sh
# 统一 Docker Compose 入口，要求显式选择 dev 或 prod，缺失变量或命令失败即退出。
set -eu
DEPLOY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
case "${1:-}" in dev|prod) APP_ENV=$1; shift ;; *) echo 'Usage: compose.sh dev|prod <compose arguments>' >&2; exit 2 ;; esac
ENV_FILE="$DEPLOY_DIR/.env.$APP_ENV"
[ -f "$ENV_FILE" ] || { echo "Run: python3 $DEPLOY_DIR/scripts/init-env.py $APP_ENV" >&2; exit 1; }
grep -qx "APP_ENV=$APP_ENV" "$ENV_FILE" || { echo 'Environment file does not match target.' >&2; exit 1; }
# 合并公共配置与所选环境配置，使用独立项目名隔离容器、网络和数据卷。
exec docker compose --project-name "xmut-$APP_ENV" \
  --env-file "$DEPLOY_DIR/images.env" --env-file "$ENV_FILE" \
  -f "$DEPLOY_DIR/compose.yaml" -f "$DEPLOY_DIR/compose.$APP_ENV.yaml" "$@"

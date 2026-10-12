#!/bin/sh
# 仅连接 dev 基础设施运行集成测试；先确保对应 Docker 服务已启动。
set -eu
REPO_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$REPO_DIR"
# 将私密配置导出给 Maven 子进程，文件内的密码不会作为命令行参数传递。
set -a
. ./deployment/secrets/dev/backend.env
set +a
# 显式开启基础设施测试，验证数据库、Redis、RabbitMQ 和 Nacos 的实际连接。
export XMUT_INFRA_TEST=true
exec ./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp -pl GraduationProject -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InfrastructureIT test

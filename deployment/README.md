# Docker 基础服务：dev / prod

本目录管理 MySQL、Redis、RabbitMQ、Nacos。当前仅在本机启动 **dev**；**prod 已准备配置与独立凭据，尚未部署**。这里的 prod 是单机部署模板，业务容器、HTTPS 网关、备份恢复和高可用仍需在上线前完成。

## 环境隔离

| 项目 | dev | prod（待部署） |
| --- | --- | --- |
| Compose 项目 | `xmut-dev` | `xmut-prod` |
| 私密变量文件 | `.env.dev` | `.env.prod` |
| 私密账号记录 | `secrets/dev/accounts.md` | `secrets/prod/accounts.md` |
| 主机 Java 环境变量 | `secrets/dev/backend.env` | `secrets/prod/backend.env` |
| MySQL | `127.0.0.1:3306` | `127.0.0.1:13306` |
| Redis | `127.0.0.1:6379` | `127.0.0.1:16379` |
| RabbitMQ AMQP | `127.0.0.1:5672` | `127.0.0.1:15673` |
| RabbitMQ 控制台 | <http://127.0.0.1:15672> | <http://127.0.0.1:25672> |
| Nacos API / gRPC | `8848 / 9848` | `18848 / 19848` |
| Nacos 控制台 | <http://127.0.0.1:8088> | <http://127.0.0.1:18088> |
| RabbitMQ vhost | `xmut_dev` | `xmut_prod` |

所有映射端口绑定 `127.0.0.1`。线上管理员通过 SSH 隧道访问，未来业务容器通过同项目网络的 `mysql:3306`、`redis:6379`、`rabbitmq:5672`、`nacos:8848` 连接。主机进程专用的 `backend.env` 不能直接作为容器连接配置。

网络与五个命名卷由 Compose 项目名隔离，不指定跨环境共享卷或容器名称。MySQL 分别建立 `identity_db`、`booking_db`、`ai_db`、`notification_db`；每个库使用独立的 `identity_dev` / `identity_prod` 等账号，只授权本库。root 仅允许容器本地登录。Nacos 为开启鉴权的单机 Derby 持久化部署，两环境使用不同的密码、签名密钥和服务端身份值。

镜像通过 [images.env](images.env) 中的 manifest digest 锁定。dev 本次实际版本：MySQL 8.4.11、Redis 7.4.11、RabbitMQ 4.3.6、Nacos 3.1.1。

## 首次配置

以下命令在仓库根目录执行；需要 Docker Compose 和 Python 3.9+。

```sh
python3 deployment/scripts/init-env.py dev
python3 deployment/scripts/init-env.py prod
```

本机这一步已经完成。生成器为每个服务生成独立随机密码，并保存账号记录；已有文件时拒绝覆盖，避免重启后密码与数据库不一致。账号文件、环境文件权限为 `600`，`secrets` 目录为 `700`，并被 Git 忽略。另存到密码管理器或加密备份后再迁移机器；Git 克隆不会包含这些文件。

`.env.example` 只展示变量名，实际部署应使用生成器。不要运行普通 `docker compose config` 后公开输出，因为其完整展开结果包含密码；验证语法使用 `config --quiet`。

## 启动本机 dev

```sh
deployment/scripts/compose.sh dev up -d --wait --wait-timeout 240
python3 deployment/scripts/bootstrap-nacos.py dev
deployment/scripts/compose.sh dev ps
```

Nacos 初始化脚本使用已保存的管理员密码，首次初始化后发布 `GraduationProject.properties` 中的 `platform.environment=dev`。重复运行会验证登录和已有标记，不覆盖已有业务配置。Nacos 默认管理员名为 `nacos`；密码见私密账号记录。

MySQL 初始化只在**空数据卷**运行。健康检查会验证 booking 账号能查询自己的库，而不只是数据库进程存活。调整 `.env` 不会自动修改已有 MySQL、RabbitMQ 或 Nacos 账号；轮换密码需要先修改对应服务，再同步环境文件、账号记录与 Java 配置文件。

## Java 接入与验证

```sh
# 验证 dev/prod 的配置隔离，不打印密码。
python3 deployment/scripts/check-environments.py

# 真实 dev 集成测试，自动读取私密 backend.env。
deployment/scripts/verify-dev.sh

# 启动业务工程（dev profile 自动启用 nacos）。
set -a
. deployment/secrets/dev/backend.env
set +a
./GraduationProject/mvnw -s .mvn/settings.xml -pl GraduationProject spring-boot:run
```

集成测试验证 MySQL/InnoDB 回滚、Redis 读写、RabbitMQ 消息收发、Nacos 配置导入与服务注册。测试只临时创建自己的表、key 和独占队列，结束后清理。业务 Flyway 迁移尚未编写；Flyway 能连接真实数据库不表示业务表已完成。

`InfrastructureIT` 默认不执行，需要 `verify-dev.sh` 设置 `XMUT_INFRA_TEST=true`；该脚本固定为 dev，不对 prod 执行业务写入测试。普通 `mvn verify` 继续运行不依赖 Docker 的原有 5 项测试。

## 停止、重启与数据

```sh
deployment/scripts/compose.sh dev stop
deployment/scripts/compose.sh dev start
deployment/scripts/compose.sh dev restart
deployment/scripts/compose.sh dev down
```

`down` 保留命名卷；`down --volumes` 会删除数据库、消息、Redis 和 Nacos 的持久数据，不作为日常操作。更换电脑需同时迁移私密凭据和经过备份的数据，不能只复制 Compose 文件。

## prod 部署准备

将仓库及 `deployment/.env.prod`、`deployment/secrets/prod/` 安全传到目标服务器，保持权限和文件相对位置。使用 prod 专属项目名启动，不能复制 dev 密码或把 dev 数据卷当作线上数据卷。目标机器需运行支持该 manifest 架构的 Docker。

以下是未来在目标服务器执行的命令，**本次未执行**：

```sh
deployment/scripts/compose.sh prod config --quiet
deployment/scripts/compose.sh prod up -d --wait --wait-timeout 240
python3 deployment/scripts/bootstrap-nacos.py prod
```

生产业务容器需要使用内部服务地址、生产 JWT/模型凭据及 HTTPS 配置。此模板为单机持久化部署，不提供多节点容错，也未在真实线上主机验证。

## 参考与验证记录

[MySQL 官方初始化规则](https://hub.docker.com/_/mysql)、[Nacos 单机部署](https://nacos.io/en/docs/v3.0/manual/admin/deployment/deployment-standalone/)、[Nacos 3.1 快速开始](https://nacos.io/en/docs/v3.1/quickstart/quick-start/)。执行结果见 [Docker 环境验证记录](../开发文档/docs/development/10-Docker环境验证记录.md)。

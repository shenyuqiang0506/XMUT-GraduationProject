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

Nacos 初始化脚本使用已保存的管理员密码，首次初始化后发布各服务 Data ID（booking-service、identity-service、notification-service、ai-service、gateway 的 `.properties`，并保留旧 GraduationProject）的 `platform.environment=dev`。重复运行会验证登录和已有标记，不覆盖已有业务配置。Nacos 默认管理员名为 `nacos`；密码见私密账号记录。

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
java -jar GraduationProject/target/GraduationProject-0.0.1-SNAPSHOT.jar
```

集成测试验证 MySQL/InnoDB 回滚、Redis 读写、RabbitMQ 消息收发、Nacos 配置导入与服务注册。测试只临时创建自己的表、key 和独占队列，结束后清理。M2 身份与目录 Flyway 迁移已完成；基础设施测试与正式 API 验收分别执行。

`InfrastructureIT` 默认不执行，需要 `verify-dev.sh` 设置 `XMUT_INFRA_TEST=true`；该脚本固定为 dev，不对 prod 执行业务写入测试。普通 `mvn verify` 执行工具、时间窗口、异常分类、身份调用及网关保护等单元与上下文测试。

## AI 配置与历史验证

AI 服务保留 Spring AI 依赖、模型配置与身份接入骨架，正式 AI 查询业务待实现。旧演示接口、Basic 认证、`m1` profile、凭据生成器及专用冒烟脚本已于 2026-10-12 移除；不再生成或加载演示账号。

已有 `deployment/.env.ai` 继续作为本机私密配置保留，包含 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY`，权限 600 且 Git 忽略。不要将真实密钥写入公开文件。历史调用结果保存在 `开发文档/docs/verification/2026-10-11/m1/`，只说明当时通过了技术验证，不是现行操作指令。

正式业务通过 `dev,business` 启动并使用 JWT 认证。回归执行 `auth-smoke.py`、`catalog-smoke.py`、`availability-smoke.py` 与 `applications-smoke.py`；新报告按上海日期写入 `开发文档/docs/verification/<日期>/business/`，不覆盖历史阶段记录。

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

## M2 正式身份、目录与 Web

新机器在基础凭据生成后执行以下命令（本机已完成；已有文件拒绝覆盖）：

```sh
python3 deployment/scripts/init-auth.py dev
python3 deployment/scripts/init-auth.py prod
python3 deployment/scripts/prepare-messaging.py dev
python3 deployment/scripts/prepare-messaging.py prod
```

每套环境生成 RSA 3072 私钥/公钥、`auth.env`、`identity.env`，权限 600。`auth.env` 保存公钥路径、issuer、audience、key ID 与私密导入目录；身份私钥只在 identity 环境中加载。dev 的 `business-accounts.md` 留存初始化账号与原始密码，数据库只保存 BCrypt。prod 没有 dev 初始化账号，首次管理员需在上线前通过受控配置流程建立；本次未启动 prod。

`run-service.py` 分别加载身份库与目录库凭据，启用 `dev,business`。身份首次启动可使用 `--bootstrap-admin`，只允许 dev，已有账号不重置。手工在四个终端启动：

```sh
python3 deployment/scripts/run-service.py dev identity-service --bootstrap-admin
python3 deployment/scripts/run-service.py dev booking-service
python3 deployment/scripts/run-service.py dev notification-service
python3 deployment/scripts/run-service.py dev gateway
```

推荐根目录运行 `python3 deployment/scripts/dev-workspace.py start` 启动四个服务与 Web。它记录 PID、启动时间与所属进程组；`stop` 只停止本脚本创建且身份匹配的进程及同组子进程。端口占用时不接管其他进程，日志与记录保存在 `secrets/dev/`。

IDEA 调试可先在仓库根目录执行 `python3 deployment/scripts/prepare-idea.py`，然后选 **后端总启动（dev）**。它同时启动 identity、booking、notification、gateway，分服务配置也可独立运行。准备脚本从现有 dev 私密文件生成 `secrets/dev/idea/*.properties`，权限 600；身份签名私钥和初始化管理员凭据只进入身份服务配置。Spring 通过 VM 参数 `spring.config.additional-location` 加载文件；新电脑需先完成上面的基础环境和 `init-auth.py dev`，轮换凭据后需重新生成。共享 `.run` XML 不含密码；当前内层 IDEA 工程的适配 XML 保存在被忽略的 `.idea/runConfigurations/`。此配置仅用于 dev。

可在根目录 Maven `verify` 后运行 `python3 deployment/scripts/verify-idea.py` 验证：清除继承的服务环境变量，以同样的私密 properties 启动四个 JAR，检查 readiness 与网关未认证拒绝。8081–8083、8085 已占用时退出，不接管现有进程；结束后关闭本次启动的进程。日志保存在私密 `secrets/dev/idea-verification/`。不要同时使用 IDEA 和命令行启动器占用同一组端口。

Flyway 已执行的版本迁移保持原始内容，后续结构变更新增迁移。不要对历史 `V*.sql` 自动格式化，也不要关闭迁移校验来绕过 checksum mismatch。

Web 安装/构建在 `web-admin` 执行 `npm ci && npm run build`。本次运行环境 Node 26.10.0、npm 11.19.1；直接依赖固定版本，`package-lock.json` 锁定传递依赖。TypeScript 固定 5.9.3，当前 vue-tsc 3.3.13 与 TypeScript 7 不兼容。Node 版本需满足 Vite 的 `engines`。

导入原文件保存在 `secrets/<环境>/imports/`，目录 700、文件 600。客户端只提交文件与映射，不提供服务器路径。最多 2 MB、2,000 行，仅支持目录；预览保存 SHA-256，确认重新读取并核验。错误整批阻断，事务失败不部分写入。重复导入按来源系统、来源 ID、REAL/DEMO 更新，不删除缺失记录，不产生完整覆盖证明。本机路径在多实例上线前需替换为受控共享持久存储。

正式验证：

```sh
python3 deployment/scripts/check-environments.py
python3 deployment/scripts/auth-smoke.py
python3 deployment/scripts/catalog-smoke.py
# 浏览器验收需已安装锁定 Web 依赖和 Playwright Chromium。
XMUT_WEB_TEST=1 python3 deployment/scripts/catalog-smoke.py
```

测试进程使用独立 Nacos group；结束关闭自身进程，清理本轮随机前缀用户、资源、批次与文件，保留迁移和管理员。日志私密，公开摘要在 `开发文档/docs/verification/2026-10-11/m2/`。不要公开私密日志或完整 Compose 配置。

## M3 可用性基础

已有环境升级后端保护时，先执行 `python3 deployment/scripts/migrate-fingerprint-key.py dev`，prod 配置准备使用同命令的 `prod` 参数，再重新执行 `prepare-idea.py`。新环境无需迁移，`init-auth.py` 会生成独立密码指纹密钥。网关现在需要本环境 Redis 凭据；启动器和 IDEA 准备脚本已适配。账户密码不变，密钥与环境文件仍被 Git 忽略。详见 [后端可靠性与网关保护](../开发文档/docs/development/18-后端可靠性与网关保护.md)。

```sh
# 先停止已经运行的 Java / Web，再执行带浏览器的独立 dev 验收。
XMUT_WEB_TEST=1 python3 deployment/scripts/availability-smoke.py
XMUT_WEB_TEST=1 python3 deployment/scripts/applications-smoke.py
```

脚本使用真实 MySQL / Redis / Nacos，临时创建自己的来源、资源、账号和明确标记的测试策略，检查覆盖、开放、占用、组合与事务回滚；结束后清理本轮资料，恢复原生效策略并停止自己启动的进程。新执行的公开结果位于 `开发文档/docs/verification/<日期>/business/`，原阶段证据保留在 `2026-10-11/m3/`，私密日志不进 Git。

M3 的 Flyway V2 已在本机 dev 执行，不得重新排版；以后结构变更新增迁移。新环境策略为空，需系统管理员依据已确认制度显式配置 REAL，页面默认 REAL，规则缺失时留空并由管理员依据制度填写。V3 已在本机 dev 建立联合申请、整组暂占、审批、到期补偿与 Outbox。V3 同样必须保持已执行原文，后续结构变更新增版本。查询不预留资源，提交后才暂占，授权批准后方可使用；Outbox 投递与通知属于 M4。预约写事务 READ COMMITTED，读取快照 REPEATABLE READ。详细接口、固定协调行的串行化边界、私密备份及恢复说明见 [M3 联合预约闭环](../开发文档/docs/development/19-M3联合预约闭环.md)。

## M4 升级与通知服务

已有 M3 私密凭据后执行 `prepare-messaging.py dev` 与 `prepare-messaging.py prod`，为两个环境生成独立服务签名密钥及 notification.env，已有账号密码不变。通知仅使用自己的分库凭据。执行 `bootstrap-nacos.py dev` 添加通知服务环境标记，再执行 `prepare-idea.py`；不启动 prod。后端总启动新增 notification 8085，命令行启动器同样适配。

预约 V4、通知 V1 已执行，历史迁移不可编辑；此前已备份两库至私密 backups 目录。默认不自动接管 legacy 事件，维护者备份审查后才显式接管。SENT 表示发布确认，用户已读另有状态。

```sh
XMUT_WEB_TEST=1 python3 deployment/scripts/waitlists-messaging-smoke.py
```

验收会短暂暂停并恢复 dev RabbitMQ，须先停止其他业务进程；清理独立 DEMO 数据和队列，不删除数据卷。失败事件和死信需系统管理员检查原因后带说明重试。首版仅 IN_APP 站内通知，单节点 RabbitMQ 不具备多节点容错。完整参数、接口与边界见 [M4 文档](../开发文档/docs/development/20-M4候补与消息.md)。

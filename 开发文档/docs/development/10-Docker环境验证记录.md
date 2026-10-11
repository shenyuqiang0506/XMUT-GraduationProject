# Docker 环境验证记录

日期：2026-10-11（Asia/Shanghai）。本机 macOS / Apple Silicon，Docker Engine 29.7.2，Compose v5.4.0。

## 本次范围

本机部署 dev 的 MySQL、Redis、RabbitMQ、Nacos，准备 prod 独立配置和凭据。prod 未启动、未部署到线上；线上主机尚未指定。业务 API、网关业务路由、模型调用和移动端仍未完成。

两个 Compose 项目分别为 `xmut-dev`、`xmut-prod`，各自使用独立网络、五个命名卷、主机端口、数据库用户与密码、RabbitMQ vhost、Nacos 密码和签名密钥。端口仅绑定 `127.0.0.1`。

## 服务与凭据

| 服务 | 本机 dev 版本 | dev 地址 |
| --- | --- | --- |
| MySQL | 8.4.11 | 127.0.0.1:3306 |
| Redis | 7.4.11 | 127.0.0.1:6379 |
| RabbitMQ | 4.3.6 | AMQP 5672；管理 15672 |
| Nacos | 3.1.1 | API 8848；gRPC 9848；管理 8088 |

镜像摘要锁定在 `deployment/images.env`。MySQL 已建立四个业务库及分库账号，root 限定 localhost。Nacos 开启鉴权，管理员初始化后发布不含密钥的 `platform.environment=dev` 配置；Java dev/prod profile 自动启用 Nacos。

真实密码分别保存在 `deployment/.env.dev`、`deployment/.env.prod` 和对应的 `deployment/secrets/{dev,prod}/accounts.md`；主机 Java 接入配置保存在 `backend.env`。文件权限 600，私密目录权限 700，全部被 Git 忽略，不进入测试证据。两环境的随机密码与签名密钥已检查互不相同。账号生成器拒绝覆盖现有凭据。

## 已执行验证

| 检查 | 实际结果 |
| --- | --- |
| dev/prod Compose 语法与隔离 | 通过；项目、网络、卷、端口、凭据不共用 |
| dev 四服务健康检查 | 全部 healthy |
| MySQL 业务权限 | 四个账号能登录自己的库；不能访问其余业务库及 mysql 库 |
| MySQL JDBC / InnoDB | 真实 8.4 数据库，READ COMMITTED，事务插入回滚后记录数为零 |
| Redis | 使用保存的密码连接；临时 key 写入、读取与清理通过 |
| RabbitMQ | 使用 dev 账号和 vhost 连接；独占临时队列发送、接收与清理通过 |
| Nacos | 管理员登录、配置发布/读取、Java 配置导入和服务注册通过 |
| dev 重启 | 四服务重新 healthy；Nacos 原凭据登录及配置仍有效，MySQL 四账号登录及范围校验通过 |
| 全新 MySQL 数据卷初始化 | 临时项目 xmut-bootstrap-check 通过，四库和账号正确；临时容器和卷已清理 |
| Java 基础回归 | 原有 5 项测试全部通过，verify 打包成功 |

真实服务集成测试：4 项，失败 0、错误 0、跳过 0，`BUILD SUCCESS`。本次测试不是预约业务并发、消息恢复或完整 M1 验收。

证据：[真实服务集成测试](../verification/2026-10-11/docker-dev/InfrastructureIT.txt)、[账号权限](../verification/2026-10-11/docker-dev/mysql-account-scopes.txt)、[环境隔离](../verification/2026-10-11/docker-dev/environment-isolation.txt)、[基础回归](../verification/2026-10-11/docker-dev/regression-summary.txt)、[重启健康](../verification/2026-10-11/docker-dev/restart-summary.txt)。

## 修复与使用说明

首次 MySQL 初始化遭遇 Docker Desktop 对挂载脚本执行位的误判。已改为先复制脚本到容器内部、设置 0644，再由官方入口加载；已在全新数据卷验证修复。健康检查现在要求 booking 账号能查询业务库。

RabbitMQ 4 拒绝非独占的临时非持久队列，集成测试改用独占临时队列；不修改 broker 的兼容开关。

日常运行、停止、prod 准备和凭据迁移见 [部署说明](../../../deployment/README.md)。prod 为单机持久化模板，尚未验证线上服务器、HTTPS、备份恢复或高可用。

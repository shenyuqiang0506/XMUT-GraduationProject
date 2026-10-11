# XMUT-GraduationProject

校园活动与资源智能预约平台毕业设计：计划提供场地与设备联合预约、审批、候补、通知及 AI 查询，覆盖 Web 管理端和 iOS/Android 学生端。

目前已建立 M1 后端独立服务、网关路由、Feign 与 AI 只读工具验证工程，本机 dev 基础服务已部署。业务 API 和前端尚未实现。完整设计见 [开发文档](开发文档/开发文档.md)。

## 工程结构

- `GraduationProject/`：booking-service，保留原目录和制品名；MVC、数据库、消息与只读演示资源。
- `identity-service/`：身份服务骨架，提供开发验证端点。
- `ai-service/`：Spring AI 模型适配，通过只读工具和 Feign 查询演示资源。
- `gateway/`：独立 WebFlux 网关，通过 Nacos/LoadBalancer 路由开发验证请求。
- `common-contracts/`：统一响应与 traceId 契约。
- `service-support/`：MVC traceId、开发验证认证、Feign 请求头和错误处理。
- `pom.xml`：统一版本管理并聚合六个模块。
- `开发文档/`：需求、架构、数据库、接口与验收设计。
- `deployment/`：dev/prod 独立 Docker 配置、凭据生成与真实服务验证脚本。

## 构建与验证

需要 JDK 21 和网络连接。Maven Wrapper 会自动下载 Maven 3.10.0，无需全局安装 Maven。在仓库根目录执行：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp clean verify
```

`.mvn/settings.xml` 将依赖仓库统一到 Maven Central，避免第三方 POM 附带的私有仓库导致认证失败。全部版本使用正式发布版；完整解析树可用以下命令生成：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp dependency:tree
```

测试配置仅用于本地依赖冒烟：H2 验证 Spring 数据源装配，关闭 Nacos、模型调用及外部健康探测。H2 不能证明 MySQL 的事务和锁行为。真实基础服务验证使用 `deployment/scripts/verify-dev.sh`；M1 HTTP 调用链使用 `deployment/scripts/m1-smoke.py` 验证，操作见下方部署说明。

## 运行配置

已部署的本机 dev 优先使用 [Docker 部署说明](deployment/README.md) 和 `deployment/secrets/dev/backend.env`。手工接入其他服务时可参考 [.env.example](.env.example)，在终端或 IDE 运行配置中设置环境变量。Spring Boot 不会自动读取 `.env`；不要提交真实密码、模型密钥和证书。

业务工程需要准备 MySQL 数据库及 `DB_USERNAME`、`DB_PASSWORD`，并设置 RabbitMQ 凭据；Redis/RabbitMQ 默认使用本机端口。数据库迁移、用户体系和业务表尚未实现。

各服务打包后可以使用 `java -jar <服务>/target/<制品>.jar` 启动。本机端口为 booking 8082、identity 8083、AI 8084、gateway 8081，默认绑定回环地址。先加载私密环境变量；新增模块依赖根工程，验证使用根目录的 Maven reactor。

可选 Spring profiles：

- `dev` / `prod`：启用对应环境标记和 Nacos；配置中心 Data ID 使用各服务的 `spring.application.name`。
- `m1`：仅允许同时启用 `dev` 或 `test`，加载私密 M1 验证凭据，拒绝 `prod`。使用开发 Basic 认证和 DEMO 数据，不提供 JWT 或业务权限验收。
- `ai`：仅在 ai-service 启用模型，需要 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY`。未启用时不会调用模型；调用可能产生费用。

自动验证器使用独立 Nacos 注册 group，临时启动四个 Java 进程并在完成后停止自己创建的进程。Docker dev 基础服务继续运行。具体命令、密钥文件和日志位置见 [部署说明](deployment/README.md)。Actuator 仅暴露 health/info。

## 验证记录

具体版本、执行结果和未覆盖项见 [依赖验证记录](开发文档/docs/development/09-依赖验证记录.md)。Docker dev 已部署，prod 仅准备配置，详见 [部署说明](deployment/README.md)。后端调用链结果见 [M1 后端验证记录](开发文档/docs/development/12-M1后端验证记录.md)。iOS/Android 基础运行按用户要求暂缓；业务阶段继续按设计推进，本次不代表整个平台验收完成。

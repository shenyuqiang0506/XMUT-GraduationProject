# XMUT-GraduationProject

校园活动与资源智能预约平台毕业设计：计划提供场地与设备联合预约、审批、候补、通知及 AI 查询，覆盖 Web 管理端和 iOS/Android 学生端。

目前已完成后端依赖准备并部署本机 dev 基础服务，业务 API 和前端尚未实现。完整设计见 [开发文档](开发文档/开发文档.md)。

## 工程结构

- `GraduationProject/`：保留已有 Java 工程，准备 MVC、数据库、权限、消息、AI 等依赖；后续按设计拆分业务服务。
- `gateway/`：独立 Spring Cloud Gateway WebFlux 工程，不引入 MVC/JDBC；目前没有业务路由。
- `pom.xml`：聚合两个模块，统一执行验证。
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

测试配置仅用于本地依赖冒烟：H2 验证 Spring 数据源装配，关闭 Nacos、模型调用及外部健康探测。H2 不能证明 MySQL 的事务和锁行为。真实基础服务验证使用 `deployment/scripts/verify-dev.sh`；网关转发、Feign 调用和模型工具调用仍需后续验收。

## 运行配置

已部署的本机 dev 优先使用 [Docker 部署说明](deployment/README.md) 和 `deployment/secrets/dev/backend.env`。手工接入其他服务时可参考 [.env.example](.env.example)，在终端或 IDE 运行配置中设置环境变量。Spring Boot 不会自动读取 `.env`；不要提交真实密码、模型密钥和证书。

业务工程需要准备 MySQL 数据库及 `DB_USERNAME`、`DB_PASSWORD`，并设置 RabbitMQ 凭据；Redis/RabbitMQ 默认使用本机端口。数据库迁移、用户体系和业务表尚未实现。

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -pl GraduationProject spring-boot:run
./GraduationProject/mvnw -s .mvn/settings.xml -pl gateway spring-boot:run
```

业务端口默认为 8080，网关为 8081。保留 Spring Security 默认访问保护；尚未实现 JWT 发行、issuer/audience 校验配置及业务权限，不能作为生产认证方案。

可选 Spring profiles：

- `nacos`：配置 `NACOS_SERVER_ADDR`、`NACOS_USERNAME`、`NACOS_PASSWORD`，在 Nacos 准备 `GraduationProject.properties` 后启用。
- `ai`：配置 `AI_API_KEY`、`AI_MODEL`，可选配置兼容服务的 `AI_BASE_URL`。模型调用可能产生费用。

通过 `SPRING_PROFILES_ACTIVE=nacos,ai` 启用两项；未启用时不连接 Nacos 或调用模型。设置 `API_DOCS_ENABLED=true` 可启用 OpenAPI/Swagger UI，仍受默认安全规则保护。Actuator 仅暴露 health/info。

## 验证记录

具体版本、执行结果和未覆盖项见 [依赖验证记录](开发文档/docs/development/09-依赖验证记录.md)。Docker dev 已部署，prod 仅准备配置，详见 [部署说明](deployment/README.md)。Web、uni-app 和业务服务拆分仍按原开发文档推进，本次不代表 M1 整体完成。

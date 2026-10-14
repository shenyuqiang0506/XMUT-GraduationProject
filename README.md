# 校园资源预约

校园资源预约毕业设计：计划提供场地与设备联合预约、审批、候补、通知及 AI 查询，覆盖 Web 管理端和 iOS/Android 学生端。

已完成 M1 后端调用链与 M2 身份、资源目录、Excel/JSON 导入、Web 基础管理页，本机 dev 基础服务已部署。M3 已补齐来源覆盖、开放规则、策略、可用组合查询与联合预约、审批、取消和冲突复核闭环，移动端按用户要求暂缓。完整设计见 [开发文档](开发文档/开发文档.md)，M4 已新增固定组合候补、可靠 Outbox、站内通知和投递运维入口，当前范围见 [M4 候补与消息](开发文档/docs/development/20-M4候补与消息.md)。

## 工程结构

- `GraduationProject/`：booking-service，保留原目录和制品名；正式资源目录、导入、可用性、联合申请与整组占用、审批、开放占用管理、审批组与管理范围。
- `notification-service/`：独立通知库、消费去重、站内收件箱和死信恢复。
- `identity-service/`：本地账号、BCrypt、JWT/刷新会话、角色与账号启停。
- `ai-service/`：保留 Spring AI 依赖、模型配置和身份接入骨架；演示接口已移除，正式 AI 业务待实现。
- `gateway/`：WebFlux 网关、JWT 校验和 Nacos/LoadBalancer 路由。
- `common-contracts/`：统一响应、身份与 traceId 契约。
- `security-core/`：共享 RSA 密钥读取与 JWT 校验，不向网关引入 MVC/JDBC。
- `service-support/`：MVC traceId、实时身份检查、Feign 请求头和错误处理。
- `web-admin/`：Vue 3 + TypeScript + Element Plus，登录、目录、可用时段、联合申请、审批、开放占用管理、导入和账号页。
- `pom.xml`：统一版本管理并聚合八个 Java 模块。
- `开发文档/`：需求、架构、数据库、接口与验收设计。
- `deployment/`：dev/prod 独立 Docker 配置、私密凭据生成与真实服务验证。

## IDEA 导入

在 IDEA 中打开仓库根目录 `pom.xml`，导入整个 Maven 工程。Maven 面板应包含 common-contracts、security-core、service-support、identity-service、booking-service、notification-service、ai-service 和 gateway。`org.shen.platform` 来自公共模块，单独导入内层 `GraduationProject/pom.xml` 会缺少这些模块。

已有单模块窗口可通过 Maven 面板的“添加 Maven 项目”选择外层 `pom.xml`，然后同步。若需独立打开 booking 工程，先在仓库根目录安装公共模块，再同步 IDEA：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp -pl service-support -am install -DskipTests
```

公共模块修改后，单模块工程需重新执行该命令；完整工程导入可直接使用模块源码。

本机 dev 的后端总启动配置为 **后端总启动（dev）**，同时运行身份服务（8083）、预约服务（8082）、通知服务（8085）与网关（8081）。在私密环境文件已准备、Docker dev 已启动后，先在仓库根目录执行一次：

```sh
python3 deployment/scripts/prepare-messaging.py dev
python3 deployment/scripts/prepare-idea.py
```

随后在 IDEA 右上角选“后端总启动（dev）”并运行，也可单独选“身份服务（dev）”“预约服务（dev）”“通知服务（dev）”“网关（dev）”。共享配置在 `.run/`，VM 参数加载各服务专属的 `deployment/secrets/dev/idea/*.properties`，启用 `dev,business`；密码与私钥不写入共享 XML。旧的无配置 Application 启动项不会自动读取 `.env`，可能出现 `${DB_USERNAME}` 未解析。

凭据变更后重新执行准备脚本；已有内层 IDEA 工程会生成对应的本机运行配置。总启动包含四个后端服务，Web 仍在 `web-admin` 执行 `npm run dev`。停止时需停止四项后端进程，Docker 独立运行。

共享配置以 `$MODULE_WORKING_DIR$` 作为工作目录，从服务模块通过 `file:../deployment/secrets/dev/idea/<服务名>.properties` 加载私密配置。因此打开仓库或内层 `GraduationProject` 工程时都指向同一份文件。若旧配置报错路径含 `GraduationProject/GraduationProject/deployment`，重新执行 `prepare-idea.py` 并重新打开运行配置；不要添加 `optional:` 跳过必须的数据库与身份配置。`verify-idea.py` 会读取实际 XML 校验两种工程上下文并启动验证。

已执行的 Flyway `V*.sql` 必须保持原始内容，包括排版；表结构变更应新增版本迁移。否则格式化也可能导致启动时 checksum mismatch。

本机验证已覆盖配置文件独立加载和 IDEA 总启动四服务就绪，见 [M4 验证记录](开发文档/docs/verification/2026-10-13/business/messages-build.json)及 [2026-10-14 内外层工程路径修复验证](开发文档/docs/verification/2026-10-14/business/idea-path-fix.json)。

## 构建与验证

需要 JDK 21 和网络连接。Maven Wrapper 自动下载 Maven 3.10.0，无需全局安装 Maven。在仓库根目录执行：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp clean verify
```

`.mvn/settings.xml` 统一到 Maven Central，避免第三方 POM 附带的私有仓库导致认证失败。依赖使用正式发布版，完整解析树可运行 `dependency:tree` 查看。

八个 Java 模块统一引入 Lombok（由 Boot 管理，当前 1.18.46）和 Hutool 全量包（5.8.47）。Lombok 使用 `provided` 范围并配置注解处理器，排除出可执行 JAR；Hutool 可直接使用 `cn.hutool.*`。IDEA 同步 Maven 后会导入依赖与处理器配置。纯依赖注入类使用 `@RequiredArgsConstructor`；包内构造器保留 `AccessLevel.PACKAGE`，依赖字段顺序保持原构造器参数顺序。带配置参数、校验或初始化逻辑的构造器仍显式编写，DTO 继续使用 `record`，Hutool 后续按需使用。

普通 `verify` 执行 Java 单元及装配测试（本轮 60 项，涵盖基础装配、工具、时间边界、身份与网关规则），包含 Lombok 生成构造器/getter 与 Hutool 调用验证；H2 只能验证装配，不能证明 MySQL 事务与锁行为。真实基础服务验证使用 `deployment/scripts/verify-dev.sh`；正式身份、目录与 Web 使用下面的验收脚本。详细操作见 [部署说明](deployment/README.md)。

## 本机运行

本机私密文件已生成，正式管理员账号与原始密码在 `deployment/secrets/dev/business-accounts.md`；这与 Nacos 管理账号不同。新电脑先按部署说明生成文件、启动 Docker dev，再执行：

```sh
# 根目录：构建 Java 八模块。
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp verify
# web-admin 中：安装锁定依赖、检查生产构建。
cd web-admin
npm ci
npm run build
cd ..
# 启动 identity、booking、notification、gateway、Web 五个本机进程。
python3 deployment/scripts/dev-workspace.py start
# 查看/停止本脚本记录的进程，不影响 Docker。
python3 deployment/scripts/dev-workspace.py status
python3 deployment/scripts/dev-workspace.py stop
```

访问 <http://127.0.0.1:5173>；Vite 将 `/api/v1` 转发给 8081 网关。凭据与导入原文件保存在被忽略的私密目录。启动前检查 8081–8083、8085、5173 空闲；不要在服务运行时重建或覆盖 JAR。启动器用于本机 dev，线上 Web/HTTPS 部署仍待完成。

真实验收（先停止上述启动器，脚本自行启动服务并清理本轮测试资料）：

```sh
python3 deployment/scripts/auth-smoke.py
python3 deployment/scripts/catalog-smoke.py
# 首次浏览器验证：先在 web-admin 执行 npx playwright install chromium。
XMUT_WEB_TEST=1 python3 deployment/scripts/catalog-smoke.py
```

## M3 可用性基础

Web 新增“可用时段查询”“开放与占用管理”；资料导入支持来源占用快照和已确认空闲区间。新环境不会自动启用规则：系统管理员先配置 REAL 参数与制度依据，再维护开放时段并导入已核实的覆盖。页面新增、导入、查询与策略默认 REAL，不预填演示规则。用户后续提供的数据集均按真实来源处理；真实目录不自动证明占用覆盖完整或资源可预约。DEMO 标记仅保留用于隔离自动化测试资料和识别既有演示记录，不将其转换为真实资料。

```sh
XMUT_WEB_TEST=1 python3 deployment/scripts/availability-smoke.py
XMUT_WEB_TEST=1 python3 deployment/scripts/applications-smoke.py
```

覆盖缺失、部分或过期不视为空闲；完整快照只替换声明窗口，空闲导入只证明文件实际区间。查询结果不会暂占资源，提交申请会在重新校验后暂占整组资源，授权审批后才转为批准占用。新增“我的申请”“预约审批”页面。详见 [联合预约接口与验收说明](开发文档/docs/development/19-M3联合预约闭环.md)。

## M4 候补与站内通知

新增“我的候补”“站内通知”和系统管理员“消息投递管理”。确认候补后仍需审批，Redis 缓存不作为可用性或授权依据。新环境先执行 `prepare-messaging.py dev`，prod 只准备独立配置。

```sh
XMUT_WEB_TEST=1 python3 deployment/scripts/waitlists-messaging-smoke.py
```

脚本会短暂暂停并恢复 dev RabbitMQ，先停止已有 Java/Web。接口与边界见 [M4 文档](开发文档/docs/development/20-M4候补与消息.md)。

## 运行配置

本机 dev 优先使用 [部署说明](deployment/README.md) 和分服务私密环境文件。Spring Boot 不自动读取 `.env`；使用 `run-service.py` 加载对应配置，避免将身份私钥分发给网关或其他服务。

端口：booking 8082、identity 8083、AI 8084、notification 8085、gateway 8081，默认回环地址。M2 Flyway 迁移分别位于身份服务与目录服务，分库账号只访问自己的库。

可选 Spring profiles：

- `dev` / `prod`：对应环境标记和 Nacos；Data ID 使用服务的 `spring.application.name`。
- `business`：正式业务 API，加载对应环境的 JWT、数据库配置。
- `bootstrap-admin`：仅 dev/test 身份服务初始化空库管理员，已有账号不覆盖；prod 禁止。
- `ai`：仅在 ai-service 加载模型配置，需要私密 AI_BASE_URL、AI_MODEL、AI_API_KEY；当前没有对外 AI 查询接口。

自动验证使用独立 Nacos 注册 group，完成后停止自己创建的 Java 进程，Docker dev 继续运行。Actuator 仅暴露 health/info。prod 已准备独立配置与凭据，尚未部署。

2026-10-12 已移除旧演示控制器、路由、Basic 认证、`m1` profile 和专用验证脚本。历史 M1 记录保留为当时的技术验证证据，不代表当前仍提供这些接口。

## 验证记录

- [M4 候补与消息](开发文档/docs/development/20-M4候补与消息.md)
- [M3 联合预约闭环](开发文档/docs/development/19-M3联合预约闭环.md)
- [后端可靠性与网关保护](开发文档/docs/development/18-后端可靠性与网关保护.md)

- [演示接口下线与真实数据约定](开发文档/docs/development/15-演示接口下线与真实数据约定.md)

- [依赖验证记录](开发文档/docs/development/09-依赖验证记录.md)
- [Docker 环境验证](开发文档/docs/development/10-Docker环境验证记录.md)
- [M1 验收核对](开发文档/docs/development/11-M1验收核对.md)与[后端调用链](开发文档/docs/development/12-M1后端验证记录.md)
- [M2 身份、目录与 Web 验收](开发文档/docs/development/13-M2身份目录验收.md)

iOS/Android 基础运行按用户要求暂缓；本次不代表整个平台验收完成。

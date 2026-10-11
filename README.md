# 校园资源预约

校园资源预约毕业设计：计划提供场地与设备联合预约、审批、候补、通知及 AI 查询，覆盖 Web 管理端和 iOS/Android 学生端。

已完成 M1 后端调用链与 M2 身份、资源目录、Excel/JSON 导入、Web 基础管理页，本机 dev 基础服务已部署。联合预约与审批从 M3 开始，移动端按用户要求暂缓。完整设计见 [开发文档](开发文档/开发文档.md)，当前实现范围见 [M2 验收记录](开发文档/docs/development/13-M2身份目录验收.md)。

## 工程结构

- `GraduationProject/`：booking-service，保留原目录和制品名；正式资源目录、导入、审批组与管理范围。
- `identity-service/`：本地账号、BCrypt、JWT/刷新会话、角色与账号启停。
- `ai-service/`：Spring AI 模型适配，M1 只读工具与 Feign 演示验证；正式 AI 在 M5 实现。
- `gateway/`：WebFlux 网关、JWT 校验和 Nacos/LoadBalancer 路由。
- `common-contracts/`：统一响应、身份与 traceId 契约。
- `security-core/`：共享 RSA 密钥读取与 JWT 校验，不向网关引入 MVC/JDBC。
- `service-support/`：MVC traceId、实时身份检查、Feign 请求头和错误处理。
- `web-admin/`：Vue 3 + TypeScript + Element Plus，登录、目录、导入和账号页。
- `pom.xml`：统一版本管理并聚合七个 Java 模块。
- `开发文档/`：需求、架构、数据库、接口与验收设计。
- `deployment/`：dev/prod 独立 Docker 配置、私密凭据生成与真实服务验证。

## IDEA 导入

在 IDEA 中打开仓库根目录 `pom.xml`，导入整个 Maven 工程。Maven 面板应包含 common-contracts、security-core、service-support、identity-service、booking-service、ai-service 和 gateway。`org.shen.platform` 来自公共模块，单独导入内层 `GraduationProject/pom.xml` 会缺少这些模块。

已有单模块窗口可通过 Maven 面板的“添加 Maven 项目”选择外层 `pom.xml`，然后同步。若需独立打开 booking 工程，先在仓库根目录安装公共模块，再同步 IDEA：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp -pl service-support -am install -DskipTests
```

公共模块修改后，单模块工程需重新执行该命令；完整工程导入可直接使用模块源码。

本机 dev 的后端总启动配置为 **后端总启动（dev）**，同时运行身份服务（8083）、预约服务（8082）与网关（8081）。在私密环境文件已准备、Docker dev 已启动后，先在仓库根目录执行一次：

```sh
python3 deployment/scripts/prepare-idea.py
```

随后在 IDEA 右上角选“后端总启动（dev）”并运行，也可单独选“身份服务（dev）”“预约服务（dev）”“网关（dev）”。共享配置在 `.run/`，VM 参数加载各服务专属的 `deployment/secrets/dev/idea/*.properties`，启用 `dev,business`；密码与私钥不写入共享 XML。旧的无配置 Application 启动项不会自动读取 `.env`，可能出现 `${DB_USERNAME}` 未解析。

凭据变更后重新执行准备脚本；已有内层 IDEA 工程会生成对应的本机运行配置。总启动包含三个后端服务，Web 仍在 `web-admin` 执行 `npm run dev`。停止时需停止三项后端进程，Docker 独立运行。

已执行的 Flyway `V*.sql` 必须保持原始内容，包括排版；表结构变更应新增版本迁移。否则格式化也可能导致启动时 checksum mismatch。

本机验证已覆盖配置文件独立加载和 IDEA 总启动三服务就绪，见 [IDEA 启动验证记录](开发文档/docs/verification/2026-10-11/m2/idea-startup.json)。

## 构建与验证

需要 JDK 21 和网络连接。Maven Wrapper 自动下载 Maven 3.10.0，无需全局安装 Maven。在仓库根目录执行：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp clean verify
```

`.mvn/settings.xml` 统一到 Maven Central，避免第三方 POM 附带的私有仓库导致认证失败。依赖使用正式发布版，完整解析树可运行 `dependency:tree` 查看。

普通 `verify` 执行原有 5 项基础测试；H2 只能验证装配，不能证明 MySQL 事务与锁行为。真实基础服务验证使用 `deployment/scripts/verify-dev.sh`；正式身份、目录与 Web 使用下面的验收脚本。详细操作见 [部署说明](deployment/README.md)。

## M2 本机运行

本机私密文件已生成，正式管理员账号与原始密码在 `deployment/secrets/dev/business-accounts.md`；这与 Nacos、M1 验证账号不同。新电脑先按部署说明生成文件、启动 Docker dev，再执行：

```sh
# 根目录：构建 Java 七模块。
./GraduationProject/mvnw -s .mvn/settings.xml -B -ntp verify
# web-admin 中：安装锁定依赖、检查生产构建。
cd web-admin
npm ci
npm run build
cd ..
# 启动 identity、booking、gateway、Web 四个本机进程。
python3 deployment/scripts/dev-workspace.py start
# 查看/停止本脚本记录的进程，不影响 Docker。
python3 deployment/scripts/dev-workspace.py status
python3 deployment/scripts/dev-workspace.py stop
```

访问 <http://127.0.0.1:5173>；Vite 将 `/api/v1` 转发给 8081 网关。凭据与导入原文件保存在被忽略的私密目录。启动前检查 8081–8083、5173 空闲；不要在服务运行时重建或覆盖 JAR。启动器用于本机 dev，线上 Web/HTTPS 部署仍待完成。

真实验收（先停止上述启动器，脚本自行启动服务并清理本轮测试资料）：

```sh
python3 deployment/scripts/auth-smoke.py
python3 deployment/scripts/catalog-smoke.py
# 首次浏览器验证：先在 web-admin 执行 npx playwright install chromium。
XMUT_WEB_TEST=1 python3 deployment/scripts/catalog-smoke.py
```

目录返回 `UNKNOWN` 不表示可预约；学校占用、完整覆盖、时段搜索、预约事务与审批尚待 M3。

## 运行配置

本机 dev 优先使用 [部署说明](deployment/README.md) 和分服务私密环境文件。Spring Boot 不自动读取 `.env`；使用 `run-service.py` 加载对应配置，避免将身份私钥分发给网关或其他服务。

端口：booking 8082、identity 8083、AI 8084、gateway 8081，默认回环地址。M2 Flyway 迁移分别位于身份服务与目录服务，分库账号只访问自己的库。

可选 Spring profiles：

- `dev` / `prod`：对应环境标记和 Nacos；Data ID 使用服务的 `spring.application.name`。
- `business`：正式 M2 API，加载对应环境的 JWT、数据库配置；不要与 `m1` 组合。
- `bootstrap-admin`：仅 dev/test 身份服务初始化空库管理员，已有账号不覆盖；prod 禁止。
- `m1`：仅允许 dev/test，开发 Basic 认证与 DEMO 数据；拒绝 prod。
- `ai`：仅在 ai-service 启用模型，需要私密 AI_BASE_URL、AI_MODEL、AI_API_KEY；调用可能产生费用。

自动验证使用独立 Nacos 注册 group，完成后停止自己创建的 Java 进程，Docker dev 继续运行。Actuator 仅暴露 health/info。prod 已准备独立配置与凭据，尚未部署。

## 验证记录

- [依赖验证记录](开发文档/docs/development/09-依赖验证记录.md)
- [Docker 环境验证](开发文档/docs/development/10-Docker环境验证记录.md)
- [M1 验收核对](开发文档/docs/development/11-M1验收核对.md)与[后端调用链](开发文档/docs/development/12-M1后端验证记录.md)
- [M2 身份、目录与 Web 验收](开发文档/docs/development/13-M2身份目录验收.md)

iOS/Android 基础运行按用户要求暂缓；本次不代表整个平台验收完成。

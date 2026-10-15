# 前后端代码复审报告

审阅日期：2026-10-14。基线提交：`c5161b7`。审阅包含当前工作区内容；原有 `gateway/src/main/resources/application.properties` 的注释修改保留。

结论：确认 8 个问题，其中 P1（优先修复）2 个、P2（正常修复）6 个。未发现阻止编译的 Java、TypeScript 或 Vue 语法错误。业务源码和数据库迁移未修改。

范围包括 Vue 页面、API 客户端、会话与路由；预约服务的目录、导入、可用性、申请、审批、候补与 Outbox；身份服务、通知服务、网关及公共安全和响应契约。AI 服务当前为骨架，没有正式查询接口，按现有实现审阅。

## 1. P1：快速切换资源可导致规则写入另一资源

- 位置：[Rules.vue:81](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/Rules.vue:81>)，相关占用追加位于第 97 行，保存目标选择位于第 146 行。
- 类型：前端逻辑缺陷、数据流异常。
- 触发：选择资源 A，A 的详情请求未完成时选择 B；B 先返回，A 后返回。
- 原因：`loadResource()` 不设置读取期间的 `busy`，也不记录请求序号或校验响应所属资源。迟到的 A 直接覆盖 `detail` 和 `rules`；后续 `loadOccupancies()` 又读取当前 `resourceId`（B），并向已有占用数组追加。
- 影响：下拉框显示 B，规则标题与内容变成 A，占用列表却为 B，还可能重复出现。保存规则使用 `detail.id`，最终请求写入 A；版本检查无法识别这种前端选错对象的行为，因为携带的是 A 的有效版本。登记外部占用使用 B，进一步使页面中不同操作指向不同资源。
- 验证：Chromium 模拟请求乱序，确认当前选择为 `2`、详情为 `1`，B 的占用出现两次；点击保存实际调用 `/admin/resources/1/opening-rules`。
- 建议：冻结或捕获资源 ID，详情与占用一起提交页面状态；只接受最新请求且仍属于当前资源的结果，保存前核对选择与详情一致。

## 2. P1：经网关登录的用户共享同一个来源限流额度

- 位置：[AuthController.java:40](</Users/shen/大学内容/2026-2027 学年/GraduationProject/identity-service/src/main/java/org/shen/identity/controller/AuthController.java:40>)；[LoginThrottle.java:36](</Users/shen/大学内容/2026-2027 学年/GraduationProject/identity-service/src/main/java/org/shen/identity/infrastructure/LoginThrottle.java:36>)。
- 类型：后端逻辑缺陷、跨服务数据传递不匹配。
- 触发：多个用户通过同一个网关实例调用 `/api/v1/auth/login`。
- 原因：身份服务用 `http.getRemoteAddr()` 作为来源地址。当前配置没有可信代理地址解析，该地址表示直连身份服务的网关。`LoginThrottle` 对同一个来源设置 60 秒最多 30 次的额度，而且成功登录也计数。
- 影响：不同终端、不同账号共同消耗网关地址的额度；第 31 次新账号登录也被返回 `429 LOGIN_RATE_LIMITED`。集中登录时误伤所有经该网关的新登录，持续请求可以持续占满额度。
- 验证：核对网关转发链与身份配置；用独立计数器执行原 `LoginThrottle.check()`，30 个不同账号使用同一网关地址后，第 31 个全新账号收到 429。没有进行真实网络流量压测。
- 建议：在可信入口识别客户端地址并绑定严格的代理信任范围，或把来源限流放到能识别客户端的网关；保留独立账号额度。

## 3. P2：占用重新启用后再次拆分会产生重复键异常

- 位置：[TemporalImports.java:254](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/TemporalImports.java:254>)；重新启用入口见 [AvailabilityMaintenance.java:135](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/AvailabilityMaintenance.java:135>)。
- 类型：后端逻辑缺陷、潜在运行时错误。
- 触发：已有 08:00–12:00 来源占用；确认 10:00–11:00 的完整快照后生成左右片段；随后通过更新接口重新启用原占用，再确认相同范围的新快照。
- 原因：片段键仅由原记录 ID 与片段区间决定，使用普通 `INSERT`。第一次生成的片段仍保留，第二次拆分生成完全相同的键，与 `uq_external_source` 唯一约束冲突。
- 影响：合法导入确认抛出 `DuplicateKeyException`；`TemporalImports.commit()` 未将其处理为可恢复的片段复用，最终走公共异常处理的 500，整批导入事务回滚。相关资源的来源更新、覆盖更新与申请冲突复核无法提交。
- 验证：在独立 H2 内存库建立同等唯一约束，调用原 `replaceOccupancy()`；第一次拆分成功，重新启用原记录后的第二次拆分稳定抛出 `DuplicateKeyException`。此验证证明键生成冲突，不代替 MySQL 事务集成测试。
- 建议：明确重复片段的复用、重新启用或新版本标识规则，避免相同合法业务序列再次插入已存在的片段键。

## 4. P2：设备类型的目录与搜索契约不一致

- 位置：[CatalogCommands.java:34](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/domain/CatalogCommands.java:34>)；[AvailabilityCommands.java:79](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/domain/AvailabilityCommands.java:79>)。前端输入见 [Resources.vue:285](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/Resources.vue:285>)。
- 类型：接口契约不匹配、数据流异常。
- 触发：管理员将设备类型填写为“话筒”、带空格的名称等，或导入这类类型。
- 原因：目录只限制 `equipmentType` 最大 32 字符，应用层仅检查非空；搜索 `EquipmentRequirement.type` 却限定为 `[A-Za-z0-9_-]{1,32}`。创建、导入和搜索没有共用的类型代码规范。
- 影响：设备能成功入库，但同类型的搜索请求被 400 拒绝，无法按类型找到对应配套设备。Web 编辑还将设备类型设为只读，普通编辑流程无法修正。
- 验证：使用原 Jakarta Validator，完整目录 DTO 的“话筒”类型通过校验，相同值的搜索需求 DTO 校验失败。
- 建议：统一类型代码与显示名称；创建、导入、编辑和搜索使用同一规范，并为已有不符合规范的数据提供修正路径。

## 5. P2：候补冲突后的前端入口可能没有可成功提交的路径

- 位置：[Availability.vue:128](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/Availability.vue:128>)；[Waitlists.java:95](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/Waitlists.java:95>)；[WaitlistPriority.java:16](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/WaitlistPriority.java:16>)。
- 类型：前后端行为契约不匹配、逻辑缺陷。
- 触发：同组合已有有效 `WAITING`，资源此时全部空闲，但队首因配额不足等原因暂未取得 offer。
- 原因：普通申请因有效队列返回 `WAITLIST_PRIORITY`，前端据此展示“按此组合加入候补”；后端登记候补却要求至少一个资源为 `BUSY`，此时返回 `WAITLIST_NOT_NEEDED`，提示提交普通申请。
- 影响：新用户在“提交普通申请”和“加入候补”之间反复被拒绝，页面给出的解决入口无法解决当前错误；直到队列状态改变前均不能完成登记。
- 验证：独立 H2 队列中建立有效 WAITING，原 `WaitlistPriority` 返回 `WAITLIST_PRIORITY`；让共用资源检查返回 AVAILABLE 后，调用原 `Waitlists.create()`，收到 `WAITLIST_NOT_NEEDED`。资源检查和资格等外围依赖为测试替身。
- 建议：明确空闲但有队列时的产品规则，使前端入口与后端条件一致；如保持“必须 BUSY”的规则，就应展示等待现有队列处理的提示，而不能承诺可以按原组合加入候补。

## 6. P2：目录写入口遗漏 DEMO 的环境限制

- 位置：[ResourceCatalog.java:41](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/ResourceCatalog.java:41>)，创建入口第 181 行；[CatalogImports.java:106](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/CatalogImports.java:106>)。既有环境规则见 [BookingPolicies.java:44](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/BookingPolicies.java:44>)。
- 类型：后端逻辑缺陷、环境数据隔离不一致。
- 触发：prod 环境的授权管理员创建或导入 `dataMode=DEMO` 的目录资源。
- 原因：目录 DTO 和目录导入仅检查 REAL/DEMO 枚举，没有调用环境校验；`ResourceCatalog.validate()`、创建调用链和目录导入确认调用链均缺少 `BookingPolicies.mode()` 等同等约束。时间导入、可用性和申请则执行 dev/test 限制。
- 影响：生产库可以写入并通过目录列表显示 DEMO 资源，后续日历、查询或预约却返回 403；不同入口对同一数据模式给出矛盾结果。
- 验证：原目录校验接受合法 DEMO DTO；结合创建、目录预览与确认调用链确认环境约束缺失。本次没有启动 prod，也没有写入生产库。
- 建议：在共享目录写校验及目录导入入口统一执行环境限制，避免只在后续预约阶段拒绝。

## 7. P2：旧列表请求可以覆盖新筛选结果

- 位置：[Resources.vue:48](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/Resources.vue:48>)；同类实现见 [Users.vue:22](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/Users.vue:22>)。
- 类型：前端逻辑缺陷、数据流异常。
- 触发：连续执行不同筛选或翻页，新请求先完成，旧请求后完成。
- 原因：`load()` 没有防止并发、取消旧请求或核验请求序号；筛选按钮和分页仍可发起新请求。每个响应无条件写入 `rows`、`total`，任一请求的 `finally` 还会提前解除另一请求的加载状态。
- 影响：筛选控件和当前页显示新条件，表格却显示旧条件的资源或账号；管理员可能基于错误筛选结果进入后续编辑、启停或角色操作。
- 验证：Chromium 连续查询校区 A、B，让 B 先返回后再释放 A；最终筛选框为 B，列表显示 A。Users 页的相同代码路径经静态核对，未另作浏览器复现。
- 建议：用单调递增的请求序号或取消机制，仅允许当前查询更新列表、总数和加载状态。

## 8. P2：刷新服务暂时故障会清空有效会话

- 位置：[session.ts:90](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/stores/session.ts:90>)；跳转联动见 [App.vue:12](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/App.vue:12>)。
- 类型：前端错误处理缺陷、会话数据流异常。
- 触发：访问令牌需要刷新，而 `/auth/refresh` 因身份服务或网关暂不可用返回 503。
- 原因：刷新 `catch` 对所有异常执行 `clear()`，未区分明确的凭据撤销与临时依赖故障。随后 `signedIn=false` 触发 App 跳转登录页。
- 影响：短暂服务故障使当前身份、访问令牌和刷新令牌全部丢失，用户被迫重新登录；打开的编辑状态随页面卸载丢失。
- 验证：Chromium 模拟业务请求 401、刷新请求 503；客户端错误仍为 `SERVICE_UNAVAILABLE`，但页面已跳转 `/login`。
- 建议：按确定性撤销、明确未完成的服务故障和结果不明的刷新分别处理。对于一次性刷新令牌的超时或响应丢失，不应简单无条件重试；需结合服务器是否可能已轮换来决定恢复路径。

## 验证结果与边界

| 检查 | 本次结果 | 能证明的范围 |
| --- | --- | --- |
| 八个 Java 模块 `mvnw -o ... test` | 构建成功，60 项测试通过 | Java 编译及现有单元、装配检查 |
| `npm run build` | TypeScript、Vue 类型检查及 Vite 构建成功 | 前端编译通过；存在包体积提示，与本报告逻辑缺陷分开 |
| `npm run test:frontend` | 16 项通过 | 项目现有前端回归场景 |
| 隔离 Chromium 复现 | 3 项通过 | 规则页资源混用、目录列表乱序、刷新 503 清会话 |
| 独立 Java 检查 | 5 项预期行为得到确认 | 类型校验不一致、目录模式校验缺失、重复片段键、候补冲突入口、共享来源额度 |

Java 普通测试中，notification-service 与 ai-service 没有独立测试用例；60 项通过不代表通知消费和死信恢复已做本轮真实服务验收。新增复现使用模拟 HTTP、内存数据库或外围替身，没有运行会暂停 RabbitMQ、修改 dev 业务资料的集成验收脚本，也没有执行 prod 变更。

证据摘要见 [code-review.json](</Users/shen/大学内容/2026-2027 学年/GraduationProject/开发文档/docs/verification/2026-10-14/code-review.json>)。临时复现代码和日志位于 `/private/tmp/graduation-code-review/`；长期结论及触发步骤以本报告为准。

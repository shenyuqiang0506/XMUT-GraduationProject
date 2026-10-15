# 第二轮修复后续验收

日期：2026-10-15。延续 [第二轮修复记录](../2026-10-14/code-review-round2-fixes.md)。上一轮完成了代码修复和模拟测试，本轮补齐真实服务链路、浏览器验收、重新打包及开发环境启动，并补修条件增量中的否定追加缺口。

原 7 项修复已完成后续验收，最终 dev 已运行，可访问 [Web 登录页](http://127.0.0.1:5173)。

| 范围 | 实际验证 |
| --- | --- |
| 设施与设备增量 | 正式会话依次追加白板、取消白板、追加电脑、取消电脑，原投影和话筒条件保留；真实资源组合随条件变化。条件、版本和 conditions-v2 提示版本已写入 MySQL 并从历史接口恢复。 |
| 数字时刻与复合时长 | 下午 3:00 保存为 15:00；两个半小时保存为 150 分钟，结束为 17:30。相同窗口用于真实可用性查询与普通申请。 |
| 新建会话的目标一致性 | 浏览器实际创建新会话，随后对列表 GET 注入 503；下一条消息写入新会话，离开再进入后仍能从服务器读取新条件。 |
| 读取失败保留条件 | 对现有会话读取注入 503，保留人数 8、上海时刻及需求，旧方案撤下；普通查询正确接收 15:00–17:30 和人数 8。浏览器时区设为洛杉矶。发送成功后读取失败的最新条件/版本路径另由前端回归覆盖。 |
| 说明长度契约 | 浏览器绕过输入限制填写 2,001 字符时，前端拦截且不发送申请；2,000 字符实际创建普通申请并通过页面取消。正式后端另确认 2,001 字符返回 400，2,000 字符可保存并读取。 |
| 列表模式隔离 | dev 的正式接口能读取本轮 DEMO 申请和候补。另用同一 dev 凭据启动未启用 dev/test 的临时业务进程，执行 DataModes 禁止 DEMO 分支：本人列表总数及两页结果均为 0，管理列表总数与 MySQL 中 REAL 数量一致，DEMO 详情返回 403。prod 优先于 dev/test 及审批组范围由 Java 回归覆盖。没有启动 prod。 |

复核发现的剩余缺口在 [ConditionNormalizer.java:94](</Users/shen/大学内容/2026-2027 学年/GraduationProject/ai-service/src/main/java/org/shen/ai/domain/ConditionNormalizer.java:94>)：仅识别“再加”会错误接受“不用再加网络”。新增测试先复现失败，再加入否定追加与混合追加/取消的拒绝校验；正式会话收到错误 ADD 输出时返回 AI_UNGROUNDED_CONDITION，全部旧条件保留。该校验同时覆盖新操作对象和旧数组输入。

验收使用 [ai-smoke.py](</Users/shen/大学内容/2026-2027 学年/GraduationProject/deployment/scripts/ai-smoke.py>) 和新增 [ai-live.spec.ts](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/tests/ai-live.spec.ts>)。浏览器除指定的读取故障外，登录、会话、查询、申请和取消均访问真实 dev 服务。模型响应来自本地 OpenAI 兼容测试服务；本轮未调用付费模型，也不据此声明真实模型的语义准确率。

| 最终检查 | 结果 |
| --- | --- |
| Maven verify，八个模块编译、测试和打包 | 108 项测试通过，0 失败、0 错误、0 跳过 |
| MySQL 事务、Redis、RabbitMQ、Nacos 实际连接 | 4 项通过 |
| Vue/TypeScript、测试代码类型检查及 Vite 构建 | 通过；保留既有包体积提示 |
| 完整前端回归 | 31 项通过 |
| 真实 Chromium 流程 | 2 项通过 |
| AI 会话、只读工具、申请和故障恢复链路 | 17 组通过 |
| IDEA 五个后端实际 XML 配置启动 | 通过，验证进程已退出 |
| dev/prod 配置隔离与补丁空白检查 | 通过 |

测试使用随机资料、独立服务端口及 Nacos 分组，结束后清理本轮进程与资料。另只读核对 AI 验收命名空间，资源、申请、候补、导入、用户、消息这 6 类主记录剩余数均为 0。历史 Flyway 迁移保持原文。

最终运行状态：命令行启动器已启动 identity 8083、booking 8082、gateway 8081、notification 8085、ai 8084、Web 5173；六个健康检查均为 200，网关未登录 AI 请求返回 401。dev 基础容器保持运行。日常服务由 dev-workspace.py 记录和管理；本轮最终健康核查没有发送模型请求。

可复跑命令：

```sh
./GraduationProject/mvnw -s .mvn/settings.xml -o -B -ntp verify
deployment/scripts/verify-dev.sh
npm --prefix web-admin run build
npm --prefix web-admin run test:frontend
# 真实浏览器脚本使用 5173；先通过启动器停止本次已启动的日常开发服务。
python3 deployment/scripts/dev-workspace.py stop
XMUT_WEB_TEST=1 python3 deployment/scripts/ai-smoke.py
python3 deployment/scripts/verify-idea.py
python3 deployment/scripts/dev-workspace.py start
```

证据：[完成摘要](code-review-round2-completion.json)、[17 组链路](business/ai/mock-model.json)、[真实浏览器](business/ai/browser.json)、[清理核对](business/ai/cleanup.json)、[最终运行状态](business/ai/runtime.json)。

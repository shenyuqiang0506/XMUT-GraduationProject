# 第二轮复审修复与验证

日期：2026-10-14。基线 `6c03de2`。第二轮报告的 7 项问题均已修复，新增 15 项 Java 测试和 4 项前端回归。改动保留在当前工作区；原有网关排版与配置注释修改未覆盖，历史 Flyway 迁移未修改。

| 问题 | 修复位置 | 最终行为与验证 |
| --- | --- | --- |
| 设施、设备增量覆盖旧需求 | [ConditionNormalizer.java:94](</Users/shen/大学内容/2026-2027 学年/GraduationProject/ai-service/src/main/java/org/shen/ai/domain/ConditionNormalizer.java:94>)；[ConditionExtractor.java:24](</Users/shen/大学内容/2026-2027 学年/GraduationProject/ai-service/src/main/java/org/shen/ai/infrastructure/ConditionExtractor.java:24>) | 提取使用明确操作并校验原文；追加白板保留投影，追加电脑保留话筒，取消白板仅删除白板。同类设备追加累加数量，设定数量保留其他类型；整体替换、全部清除须有对应证据。兼容旧数组，合并超限阻止查询。测试同时验证模型 JSON 转换到正式归一化方法。 |
| 数字时刻忽略时段 | [ConditionNormalizer.java:190](</Users/shen/大学内容/2026-2027 学年/GraduationProject/ai-service/src/main/java/org/shen/ai/domain/ConditionNormalizer.java:190>) | 数字与中文时刻共用时段转换，下午 3:00 为 15:00；上午 3:00 保持 03:00，凌晨 12:00 为 00:00；矛盾时段、多个时刻拒绝作为明确条件。 |
| 复合半小时时长缩短 | [ConditionNormalizer.java:222](</Users/shen/大学内容/2026-2027 学年/GraduationProject/ai-service/src/main/java/org/shen/ai/domain/ConditionNormalizer.java:222>) | 先解析整小时加半小时，再处理单独半小时。两个半小时为 150 分钟，14:00 开始得到 16:30；验证一个半、两小时半及小时加分钟。 |
| 新建后的列表故障导致发往旧会话 | [IntelligentQuery.vue:53](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/IntelligentQuery.vue:53>) | 创建返回后原子切换当前会话，先读取新会话再刷新列表；发送前核对目标。浏览器模拟创建 2、列表 503，确认旧方案清除且消息实际发往 2。 |
| 刷新失败丢失已确认条件 | [IntelligentQuery.vue:36](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/IntelligentQuery.vue:36>) | 同一会话保留条件和历史文字，刷新时撤下旧方案；切换会话清除旧上下文。另覆盖发送成功、历史读取失败：保留新人数和版本，下一条消息使用新版本，普通查询沿用条件。海外浏览器保持上海时刻。 |
| 活动说明长度契约不一致 | [IntelligentQuery.vue:103](</Users/shen/大学内容/2026-2027 学年/GraduationProject/web-admin/src/pages/IntelligentQuery.vue:103>) | 输入最多 2,000 字符，提交前再次校验。浏览器绕过输入限制填写 2,001 字符仍不发送；2,000 字符正常提交普通申请。后端契约保持原值。 |
| prod 列表遗漏 DEMO 过滤 | [Applications.java:165](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/Applications.java:165>)；[Waitlists.java:174](</Users/shen/大学内容/2026-2027 学年/GraduationProject/GraduationProject/src/main/java/org/shen/graduationproject/application/Waitlists.java:174>) | 不允许 DEMO 的环境在总数、分页前筛选 REAL；候补检查所属组模式。H2 混合记录验证本人、系统管理员、资源管理员审批组范围、状态筛选和分页；dev/test 仍可显示两种模式，prod 与 dev/test 同时存在仍过滤。 |

提示版本记录及未来 ai-smoke 输出同步为 `conditions-v2`；既有历史验收报告保持原版本。接口存储仍使用归一化条件数组，无需数据库迁移。

验证结果：

| 检查 | 结果 |
| --- | --- |
| 八个 Java 模块编译及测试 | 107 项通过，0 失败、0 错误、0 跳过 |
| Vue、TypeScript 与测试代码类型检查及 Vite 构建 | 通过；仍有既有包体积提示 |
| 完整前端回归 | 31 项通过，0 失败、0 跳过 |
| ai-smoke 脚本语法及补丁空白检查 | 通过 |

本轮模型边界测试使用模拟 Spring AI 响应，浏览器使用模拟 HTTP，列表测试使用独立 H2 内存库及正式服务方法/SQL。没有调用付费模型、修改真实业务库、启动 prod 或重跑真实 MySQL/Redis/消息中间件链路。测试通过不替代真实模型的语义评估。已有 Java 服务需重新构建并重启后才能加载修复，本次没有重启正在使用的服务。

结构化证据：[code-review-round2-fixes.json](</Users/shen/大学内容/2026-2027 学年/GraduationProject/开发文档/docs/verification/2026-10-14/code-review-round2-fixes.json>)。原复审报告：[code-review-round2.md](</Users/shen/大学内容/2026-2027 学年/GraduationProject/开发文档/docs/verification/2026-10-14/code-review-round2.md>)。

2026-10-15 后续已补齐真实链路、浏览器和开发服务启动，并补修否定追加边界。最终结果见[后续验收记录](../2026-10-15/code-review-round2-completion.md)；本页保留上一轮的验证范围与结果。

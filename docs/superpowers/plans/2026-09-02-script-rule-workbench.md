# 脚本规则工作台 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零搭出一个 Web 版「脚本规则工作台」：Groovy 规则脚本的编写、一键校验（语法 + 占位符提取 + 大模型 CR）、填值沙箱运行，以及 AI 对话辅助编写。

**Architecture:** 前后端分离。`script_front/`（Vue 3 + Vite，开发期代理 `/api` → `localhost:8080`）+ `script_back/`（Spring Boot 3.4.5，端口 8080）。后端分四层：`controller`（全 POST，统一 `{code,message,data}`）→ `service`（规则/引擎/AI/用例编排）→ `groovy`（词法区域扫描、占位符替换、AST 类型推断、编译期安全检查）→ `entity`/`repository`（MySQL 4 张表，JPA `ddl-auto=update`）。AI 走 Spring AI Alibaba DashScope（qwen-plus），对话用 SSE（POST + `Flux<ServerSentEvent>`）。

**Tech Stack:** JDK 17 · Spring Boot 3.4.5 · Spring AI 1.0.0 · Spring AI Alibaba 1.0.0.3 · Groovy 4.0.24 · MySQL 8.0.46 · Spring Data JPA · JUnit 5 · Vue 3.4 · Vite 5 · Element Plus 2 · CodeMirror 6（`@codemirror/legacy-modes` groovy 模式）· Vitest 1

**Spec:** `docs/superpowers/specs/2026-09-01-script-rule-workbench-技术方案.md`（技术实现依据）与 `docs/superpowers/specs/2026-09-01-script-rule-workbench-需求文档.md`（需求依据）。两份文档都要读，计划中的所有取舍都以它们为准。UI 参考稿：`docs/ui-mockups/style3-gradient.html`。

## Global Constraints

以下约束对**每一个任务**都生效，任务正文不再重复。

**接口与响应**
- 所有接口一律 **POST**，路径 `/api/<模块>/<动作>`（如 `/api/rule/create`、`/api/chat/send`）
- 统一响应体 `{code, message, data}`，`code=0` 表示成功；业务失败也用 HTTP 200 + 非 0 `code`
- 返回前端的错误信息必须是**可读中文**，严禁出现原始异常堆栈、英文异常类名
- AI 对话 `/api/chat/send` 用 SSE 流式返回（后端 POST 返回 `Flux<ServerSentEvent<String>>`，前端 `fetch` + `ReadableStream` 逐段读；**不使用 EventSource**，它只支持 GET）

**脚本规则（核心业务）**
- 仅支持 **Groovy**，不支持 Python
- 占位符语法固定 `${变量名}`，变量名匹配 `\w+`
- 校验为**同步**流程：语法校验 → 提取占位符 → 大模型 CR，三者全部完成且语法通过才解锁运行
- 脚本内容被修改后，校验结果作废，运行按钮重新锁定
- Groovy 执行必须走沙箱：编译期禁 `import`/禁包声明 + 危险类型拦截 + 接收者黑名单；运行期独立线程池 5 秒超时中断
- 类型只归一为五种：`int` / `long` / `double` / `boolean` / `String`；推断不到一律按 `String`

**测试与 TDD（对每个任务都生效，并行分发的子代理同样适用）**
- **一律先红后绿**：写失败测试 → 跑一遍确认它真的失败（编译不过、类不存在、断言失败都是**预期的红**，别当成 bug 去改测试断言或删 import）→ 写实现 → 再跑确认通过。任务 **2–12、14–20** 共 18 个任务都走这个顺序，不要在哪个任务里改成「先实现再补测试」
- **Step 列表里没单列「跑测试确认失败」的，执行时自己补跑一次**。任务 2、3、4、5、6、7 已经把「红」写成了独立 Step；任务 **8、9、10、11、12、14、15、16、17、18、19、20** 是「写失败测试 → 直接写实现 → 跑测试」，中间那次红没显式跑过。执行到这 12 个任务时，写完失败测试**先单独跑一次**再往下写实现：后端 `mvn -q test -Dtest=<刚写的测试类>`，前端 `npx vitest run <刚写的 spec 文件>`。一次不到一分钟，能挡住「测试其实空过、永远绿」这类最难查的问题
- **前端只给有分支逻辑的纯 JS 模块写 Vitest 单测**：`http.js`（任务 3）、`api/rule.js`（任务 6）、`useValidationState.js`（任务 14）、`messageBlocks.js` 与 SSE 解析器（任务 19）、`testCaseParams.js`（任务 20）。Vue 组件靠 `npm run build` + 任务里写明的手工验收步骤把关
- **不给纯装配型组件写单测是刻意取舍**，完整理由见任务 13 Step 5：得先引入 `jsdom` + `@vue/test-utils` + CodeMirror 的 DOM mock，成本高而收益低（任务 3 的 `vitest.config.js` 配的是 `environment: 'node'`，devDependencies 里也确实没有 jsdom）。要在某个任务里破例，先在该任务里写明理由
- **跳过测试必须写明理由**，不能默默跳过 —— 任务 13 Step 5 是正面例子（组件是纯装配、没有可独立验证的分支）
- ⚠️ **「薄封装所以不用测」这个判断要逐个文件看代码，别照抄上游结论**：任务 6 的 `src/api/rule.js` 起初被当成薄封装而跳过单测，实际它有 `listRules` 的 `name || null` 归一和 `updateRule` 的三个 `if (x !== undefined)`（实现「部分更新：只把非 undefined 的字段发出去」），已补 `rule.spec.js` 9 条。最易错的是 `scriptContent: ''`：用户清空脚本时空串是**合法值必须发出去**，写成 `if (scriptContent)` 就会静默丢掉这个操作，而这种 bug 手工验收很难撞到。反过来，任务 12 的 `api/script.js` 确实是薄封装（两个函数都是一行转发），不测是对的
- 不适用 TDD 的只有三类：**任务 1**（工具链落地）、**21**（端到端联调）、**22**（收尾文档）。**任务 13** 是第四种情况：有代码但刻意不写单测，理由写在它自己的 Step 5。除此之外每个任务都必须有测试
- **大模型调用不进单测**：后端测试统一以 `app.ai.enabled=false` 跑降级分支（真调会烧额度、会因网络抖动让测试变红），真实调用只在任务 16 / 18 / 21 的手工验收里做
- **修 bug 先补一条能复现它的测试，再动手修**。任务 21 发现缺陷时按此办理：就地修 → 回对应任务补一条测试 → 回来重验
- 跑测试必须先 `source tools/env.sh`，否则没有 JDK / Maven / Node
- **测试规模基线**（各任务的验收预期都对齐这些数字，跑完对不上就是删多了或漏写了）：
  - 后端 **234** 个 —— 任务 12→143、16→176、17→200、18→218、20→234
  - 前端 **101** 个 —— 任务 3→6、6→15、14→52、15→63、19→92、20→101

**版本锁定（不得随意升降）**
- JDK 17（Temurin）、Maven 3.9.9、Node 20.18.1
- Spring Boot `3.4.5`、`spring-ai-bom` `1.0.0`、`spring-ai-alibaba-bom` `1.0.0.3`
  - 已核实：`spring-ai-alibaba-autoconfigure-dashscope:1.0.0.3` 正是针对 Spring Boot 3.4.5 + Spring AI 1.0.0 构建的，三者必须成套
- Groovy `org.apache.groovy:groovy:4.0.24`
- MySQL 8.0.46（本机已运行，`localhost:3306`，客户端 `/usr/local/mysql/bin/mysql`）

**命名与配置**
- 后端 Java 包名根：`com.xd.rulescript`；Maven 坐标 `com.xd:script-back:0.0.1-SNAPSHOT`
- 数据库名 `script_workbench`；四张表 `rule` / `conversation` / `message` / `test_case`
- 大模型统一 `qwen-plus`（对话与 CR 同模型）
- **百炼接入方式已实测定案**（2026-09-02，用真实 Key curl 验证，均返回 HTTP 200）：
  - 业务空间专属端点 `https://ws-q6x7mi6uitc06swd.cn-beijing.maas.aliyuncs.com` 的兼容模式（`/compatible-mode/v1/chat/completions`，`stream:true` 时 SSE 分片与 `data: [DONE]` 正常）
  - 同一专属端点的原生 DashScope 路径（`/api/v1/services/aigc/text-generation/generation`）
  - **公共端点 `https://dashscope.aliyuncs.com` 的原生 DashScope 路径**
  - 结论：`sk-ws-` 业务空间 Key 在公共端点上也能用，所以**技术方案原来的选型（Spring AI Alibaba dashscope starter）不需要改**，`base-url` 用默认值即可；仍把 `base-url` 暴露成环境变量作为逃生门
- 凭据一律走环境变量，**严禁写死进代码库**（Key 只能放在 gitignore 掉的 `tools/ai-secret.env` 里）：
  - `DB_URL`（默认 `jdbc:mysql://localhost:3306/script_workbench?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false`）
  - `DB_USER`（默认 `root`）、`DB_PASS`（默认空串）
  - `AI_DASHSCOPE_API_KEY`（默认 `not-configured`）、`AI_BASE_URL`（默认 `https://dashscope.aliyuncs.com`）、`AI_CHAT_MODEL`（默认 `qwen-plus`）、`AI_ENABLED`（默认 `true`）
  - 真实凭据只存在 `tools/local-secret.env`（gitignore），由 `tools/env.sh` 自动 `source`。任何代码、配置、文档、提交信息里都不得出现真实密码或 Key

**版本控制（git）**
- 本机 `/usr/bin/git` **是坏的空壳，不能用**：CommandLineTools 自 2019 年起就残缺（`/Library/Developer/CommandLineTools/usr/bin/` 下只剩一个 `stapler`，没有 `xcrun`），一调就报 `xcrun: error: invalid active developer path`。Homebrew 本身也已损坏（`/usr/local/bin/brew` 指向的目录不完整）
- 真正可用的是 **`/usr/local/bin/git`（Homebrew git 2.20.1）**，实测 `--version`/`status`/`log`/`branch`/`pull`/`grep`/`check-ignore` 全部正常。但部分执行环境的 `PATH` 只有 `tools/node/bin:/usr/bin:/bin`，**不含 `/usr/local/bin`**，于是会先撞上坏的那个，症状是 `which git` 返回 `/usr/bin/git` 且任何 git 命令都报 xcrun 错误
- 对策：`tools/env.sh` 的 `PATH` 里**显式列出 `/usr/local/bin`**（任务 1 Step 6 已这么写）。**任何跑 git 的步骤都要先 `source tools/env.sh`**；若在没 source 的裸 shell 里，直接用绝对路径 `/usr/local/bin/git`
- git 版本是 **2.20.1**，`git switch` / `git restore` **不存在**（需 2.23+，已实测确认不可用）。本计划全程只用 `checkout/add/commit/status/log/grep/check-ignore/branch`，都在 2.20.1 的可用范围内，不要去用新命令
- 仓库有远端 `origin`（GitHub `yuantong22/zd_test`，`.git/config` 里配了本机代理 `http://127.0.0.1:7897`），当前分支 `master` 跟踪 `origin/master`，`git pull` 已实测可通（fast-forward 到 `232afb1`）。推送前确认代理进程在跑
- **`.gitignore` 至今还不存在**（任务 1 Step 1 未执行），`tools/`（含整个便携 Node）与 `docs/superpowers/plans/` 都处于 untracked 状态。在任务 1 Step 1 完成之前**严禁执行 `git add -A` / `git add .`**

**环境与联调查库**
- 本机 MySQL 8.0.46 跑在 `127.0.0.1:3306`，`root` 有密码，库 `script_workbench` 已建（utf8mb4）。CLI 在 `/usr/local/mysql/bin/mysql`
- **已配 MySQL MCP**（`~/.qoder-cn/mcp.json`，server 名 `mysql`，实际进程名 `sql_exec`，已握手冒烟测通）。四个工具：
  - `list_tables` —— 无入参，看当前库里有哪些表（验 `ddl-auto=update` 有没有真的建出表）
  - `get_table_structure` —— 入参 `tables`（字符串，多个表逗号分隔），验字段/类型/注释对不对
  - `exec_sql` —— 入参 `sql` + 可选 `params`（数组，与 `?` 占位符一一对应）。验数据真的落库了
  - `ddl_exec` —— 入参同上，但只接受 DDL
- **两个 SQL 工具是互斥的，别用错**（已读包源码 `dist/db.js` 的 `assertNoDDL` / `assertValidDDL` 确认，判定只看 SQL 的**首个关键字**）：
  - `exec_sql` 跑 `CREATE/ALTER/DROP/TRUNCATE/RENAME` 会被**直接拒绝**，即使 `MYSQL_ALLOW_WRITE=true` 也一样 —— 建表必须走 `ddl_exec`
  - `ddl_exec` 跑 `SELECT/INSERT/UPDATE/DELETE` 同样被拒 —— 查数据必须走 `exec_sql`
  - `exec_sql` 的只读白名单是 `SELECT/SHOW/EXPLAIN/DESCRIBE/DESC`；`MYSQL_ALLOW_WRITE=true` 时不做只读校验，DML 全放行
- 权限开关有两个且**默认都是 true**：`MYSQL_ALLOW_WRITE`、`MYSQL_ALLOW_DDL`（`config.js` 里 `parseBool(env, true)`）。配置里已显式写 `MYSQL_ALLOW_WRITE=true`；`MYSQL_ALLOW_DDL` 未写但默认开启，所以 `ddl_exec` 可用
- **用户明确要求：后续联调时主动用这个 MCP 自己去查库核对，不要只看接口返回的 JSON**。接口说写成功不等于真的写进去了，尤其是字段截断、时区偏移、级联删除这类问题，只有查库才能发现
- MCP 未加载时（`CallMcpTool` 报找不到 `mysql` 服务）退化为 `mysql` CLI，不要因此卡住任务；改过 `mcp.json` 需重启 Qoder 或在 MCP 面板刷新才生效

**前端**
- UI 采用风格三（现代渐变风）：主色 `#5b5fc7 → #8b5cf6`、大圆角（面板 16px、按钮 20px）、柔和阴影 `0 4px 24px rgba(80,90,180,.08)`
- 列表页与工作台页视觉统一；所有色值/圆角/阴影集中在 `src/styles/theme.css` 的 CSS 变量里，组件内不写死颜色

**通用**
- 代码注释、提交信息使用中文
- 提交信息格式 `<类型>: <简述>`，类型取 `docs`/`feat`/`fix`/`refactor`/`test`/`chore`
- 每个任务结束前必须跑通验证命令：后端 `source ../tools/env.sh && mvn -q test`（在 `script_back/` 下），前端 `npm run test:unit && npm run build`（在 `script_front/` 下）

## 与技术方案的必要修正

技术方案写作时参照的是较早的 Spring AI API，实现时按下述修正执行，**语义不变**：

1. **技术方案 3.3 写的 `InMemoryChatMemory` 在 Spring AI 1.0.0 GA 已被移除。** 等价替换为
   `MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(40).build()`
   —— 同样是「进程内、按 conversationId 隔离、滑动窗口」，`MessageChatMemoryAdvisor` 与 `chatMemory.clear(conversationId)` 的用法完全一致。
2. **技术方案 3.3 只写了内存记忆，但需求文档非功能要求 #5 要求「服务重启后历史消息不丢」。**
   因此增加一步：发起对话前，若该会话的内存记忆为空而 MySQL 有历史，则先把 MySQL 历史灌入 `ChatMemory`。这样重启后多轮上下文也能续上。
3. **技术方案 3.3 / 7 假设的是公共百炼账号，实际拿到的是业务空间专属 Key（`sk-ws-` 前缀）。**
   已实测三种端点均通（见上方 Global Constraints），因此**保留 dashscope starter 不换 OpenAI 兼容 starter**，只额外把 `base-url` 与 `model` 做成环境变量。保留「无 Key / Key 失效时降级为中文提示」的代码路径，因为它是非功能要求 #1（不能把工具搞挂）的一部分，且单测需要它来保持确定性。
4. **单测一律不得真调大模型**（会烧额度、会因网络抖动变红）。凡涉及 `validate` 的后端测试，统一加 `@SpringBootTest(properties = "app.ai.enabled=false")` 走降级分支；真实调用只在手工验收步骤里做。

另外补充一处技术方案未展开但必须定的细节：**占位符替换必须区分「代码区」与「字符串区」**。
UI 参考稿里的示例脚本同时出现了两种写法：

```groovy
int age = ${age}              // 代码区：要换成字面量 28
String level = "${level}"     // 双引号串内：要换成 vip（不能再套一层引号）
```

若一律做文本字面量替换，第二行会变成 `String level = "'vip'"`（值多了引号）。因此引入 `GroovyLexScanner` 做区域扫描，替换策略按区域分派。见任务 7、8、11。

## 文件结构

### 后端 `script_back/`

```
script_back/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/xd/rulescript/
    │   │   ├── ScriptBackApplication.java          启动类
    │   │   ├── common/
    │   │   │   ├── ApiResponse.java                统一响应 {code,message,data}
    │   │   │   ├── BizException.java               业务异常（带 code）
    │   │   │   └── GlobalExceptionHandler.java     兜底异常 → 中文提示
    │   │   ├── config/
    │   │   │   ├── GroovySandboxConfig.java        CompilerConfiguration（沙箱）bean
    │   │   │   ├── ScriptExecutorConfig.java       脚本执行线程池 bean
    │   │   │   └── AiConfig.java                   ChatMemory bean
    │   │   ├── groovy/
    │   │   │   ├── GroovyLexScanner.java           字符级区域扫描（代码/单引号串/双引号串）
    │   │   │   ├── PlaceholderSubstitutor.java     ${x} → 标记 / 假值 / 真值
    │   │   │   ├── PlaceholderTypeInferer.java     AST 遍历推断占位符类型
    │   │   │   ├── ScriptSafetyCustomizer.java     编译期危险类型拦截（自定义）
    │   │   │   └── ScriptSecurityException.java    安全拦截异常（消息即中文）
    │   │   ├── entity/                             Rule / Conversation / ChatMessage / TestCase
    │   │   ├── repository/                         4 个 JpaRepository
    │   │   ├── dto/                                请求响应 record（见任务 5）
    │   │   ├── service/
    │   │   │   ├── GroovyEngineService.java        提取占位符 / 语法校验 / 沙箱运行
    │   │   │   ├── RuleService.java                规则 CRUD + validate + run 编排
    │   │   │   ├── AiService.java                  可用性判定 / 同步 CR / 流式对话 / 记忆
    │   │   │   ├── ChatService.java                会话与消息编排
    │   │   │   └── TestCaseService.java            测试用例 CRUD（params ↔ JSON）
    │   │   └── controller/
    │   │       ├── HealthController.java           /api/health
    │   │       ├── RuleController.java             /api/rule/*
    │   │       ├── ChatController.java             /api/chat/*
    │   │       └── TestCaseController.java         /api/testcase/*
    │   └── resources/application.yml
    └── test/java/com/xd/rulescript/
        ├── ScriptBackApplicationTests.java
        ├── groovy/GroovyLexScannerTest.java
        ├── groovy/PlaceholderSubstitutorTest.java
        ├── groovy/PlaceholderTypeInfererTest.java
        ├── service/GroovyEngineSyntaxTest.java
        ├── service/GroovyEngineSandboxTest.java
        ├── service/GroovyEngineRunTest.java
        ├── service/AiServiceAvailabilityTest.java
        ├── service/AiServiceCodeBlockTest.java
        └── service/RuleServiceTest.java
```

单文件职责边界：`groovy/` 包里每个类只做一件事且都是**纯静态或无状态**的，因此可以脱离 Spring 上下文做单元测试（快、稳）；`GroovyEngineService` 只做编排 + 异常翻译，不含算法。

### 前端 `script_front/`

```
script_front/
├── package.json
├── vite.config.js                                  插件 + @ 别名 + /api 代理
├── vitest.config.js                                单测配置（node 环境）
├── index.html
└── src/
    ├── main.js                                     挂 Vue + Element Plus + router
    ├── App.vue                                     <router-view/>
    ├── router/index.js                             / → 列表页；/rule/:id → 工作台
    ├── styles/theme.css                            风格三全部色值/圆角/阴影 + CodeMirror 定制
    ├── api/
    │   ├── http.js                                 fetch 封装，解包 {code,message,data}
    │   ├── rule.js                                 规则 + 校验 + 运行
    │   ├── chat.js                                 历史 / 清空 / SSE 流式发送
    │   ├── testcase.js                             用例增删查
    │   └── __tests__/http.spec.js
    ├── composables/
    │   ├── useValidationState.js                   校验状态机（脏标记 + 运行锁）
    │   └── __tests__/useValidationState.spec.js
    ├── components/
    │   ├── ScriptEditor.vue                        CodeMirror 6（groovy 高亮、行号、错误行标红、占位符高亮、Ctrl+S）
    │   ├── TopBar.vue                              渐变顶栏 + 保存/校验/运行
    │   ├── AiReviewCard.vue                        AI 审查卡片 + [应用到编辑器]
    │   ├── ParamsForm.vue                          占位符填值表单（按类型渲染控件）
    │   ├── RunResultCard.vue                       返回值 / 错误 / 超时
    │   ├── TestCaseBar.vue                         用例保存 / 回填 / 删除
    │   └── ChatPanel.vue                           AI 对话（流式、代码块应用、清空、回显）
    └── views/
        ├── RuleListView.vue                        列表 + 搜索 + 分页 + 新建/改名/删除
        └── RuleWorkbenchView.vue                   工作台总装
```

前端只把「有分支逻辑」的两个纯 JS 模块（`http.js` 解包、`useValidationState.js` 状态机）纳入单测；Vue 组件靠 `npm run build` + 明确的浏览器手工验收步骤把关。这与技术方案第 8 节的测试计划一致。

---

## 任务总览（22 个，编号已锁定）

按依赖顺序排列。前 7 个已展开写完，后 15 个待展开。**执行时不得重排编号**，正文里的交叉引用（如「任务 12 补上」）全依赖这张表。

| # | 任务 | 层 | 关键产出 | 依赖 |
|---|---|---|---|---|
| 1 | 本机工具链落地 | 环境 | `tools/env.sh`、`tools/local-secret.env`、`.gitignore`、JDK17+Maven | — |
| 2 | 后端脚手架 + 健康检查 | 后端 | `pom.xml`、`application.yml`、`ApiResponse`、`BizException`、`GlobalExceptionHandler`、`/api/health` | 1 |
| 3 | 前端脚手架 + 代理 + 风格三主题 | 前端 | `vite.config.js`、`theme.css`、`http.js`、router、两个占位页 | 1,2 |
| 4 | 数据模型（4 张表） | 后端 | `Rule`/`Conversation`/`ChatMessage`/`TestCase` 实体 + 4 个 Repository | 2 |
| 5 | DTO 全集 + 规则 CRUD 后端 | 后端 | `dto/` 全部 record、`RuleService` CRUD、`/api/rule/list\|create\|detail\|update\|delete` | 4 |
| 6 | 规则列表页前端 | 前端 | `RuleListView.vue`、`api/rule.js` | 3,5 |
| 7 | GroovyLexScanner | 后端 | 字符级区域扫描（代码区/单引号串/双引号串） | — |
| 8 | PlaceholderSubstitutor | 后端 | 按区域分派的 `${}` 替换（代码区→字面量，串内→裸值） | 7 |
| 9 | PlaceholderTypeInferer | 后端 | AST 推断占位符类型，归一为 5 种，推不出则 String | 7,8 |
| 10 | 语法校验 + 编译期安全检查 | 后端 | `GroovySandboxConfig`、`ScriptSafetyCustomizer`、`checkSyntax`、错误行号提取 | 7,8,9 |
| 11 | 沙箱运行 + 5 秒超时 | 后端 | `GroovyEngineService.run`、独立线程池、超时中断、运行期拦截 | 8,10 |
| 12 | validate / run 接口 | 后端 | `RuleService.validate\|run`、`/api/rule/validate`、`/api/rule/run` | 5,9,11 |
| 13 | 工作台页面骨架 + ScriptEditor | 前端 | `RuleWorkbenchView.vue`、`ScriptEditor.vue`（CodeMirror 6 groovy） | 3,5 |
| 14 | 校验状态机 + 填值表单 + 运行结果 | 前端 | `useValidationState.js`、`TopBar.vue`、`ParamsForm.vue`、`RunResultCard.vue` | 12,13 |
| 15 | AI 审查卡片 | 前端 | `AiReviewCard.vue` + 「应用到编辑器」 | 14 |
| 16 | **AiService（CR）** | 后端 | `AiService.reviewScript`、提示词、代码块提取、降级与超时 | 2 |
| 17 | 会话/消息持久化 + ChatMemory | 后端 | `ChatMemoryService`、`MessageWindowChatMemory`、MySQL 历史回灌 | 4,16 |
| 18 | **`/api/chat/send` SSE 后端** | 后端 | `ChatController`、`Flux<ServerSentEvent>`、`/api/chat/history\|clear` | 17 |
| 19 | ChatPanel 前端 | 前端 | `api/chat.js`、`ChatPanel.vue`（流式渲染/代码块应用/清空/回显） | 18,13 |
| 20 | 测试用例 | 全栈 | `/api/testcase/*`、`api/testcase.js`、`TestCaseBar.vue` | 12,14 |
| 21 | 端到端联调 + 浏览器验收 | 全栈 | 跑通全链路，**用 MySQL MCP 查库核对**，截图存证 | 1–20 |
| 22 | 收尾 | 文档 | 同步需求/技术方案差异、README、最终提交 | 21 |

粗体的 16、18 是本次拿到百炼 Key 后**从「降级占位」变成「真实可调」**的两个任务，它们的手工验收步骤会真的发起大模型调用。

可并行分组（无共享状态、无顺序依赖）：
- **组 A**：任务 7 → 8 → 9 → 10 → 11（纯后端算法，不依赖 Spring 上下文）
- **组 B**：任务 16（AiService，只依赖任务 2）
- **组 C**：任务 13（前端工作台骨架，只依赖 3、5）

组 A/B/C 可同时开工；任务 12 需等组 A 完成，14/15 需等 12+13，17 需等 16，18 需等 17，19 需等 18+13。

---

## Task 1: 本机工具链落地（JDK 17 / Maven / Node）

**背景（必读）：** 本机现状是 JDK 1.8、无 Maven，且 Homebrew 已损坏（任何 `brew` 命令都因无法识别 macOS 13.7.8 而抛 `TypeError`）。所以**不能**用 `brew install`。

**2026-09-02 已就位的部分（不要重复做）：**
- `tools/node/` 已存在，实测 `node -v` = `v20.18.1`、`npx -v` = `10.8.2` —— **Step 4 直接跳过**
- MySQL 8.0.46 在跑，`root` 密码已拿到且实测可连，库 `script_workbench` 已建好（utf8mb4 / utf8mb4_general_ci）—— **Step 7 只需复核**
- MySQL MCP 已配到 `~/.qoder-cn/mcp.json`（server 名 `mysql`），工具：`list_tables` / `get_table_structure` / `exec_sql` / `ddl_exec`，已握手冒烟测通。**`exec_sql` 不接受 DDL、`ddl_exec` 不接受 DML**，本任务的建库走 CLI（或 `ddl_exec`），别拿 `exec_sql` 跑 `CREATE`

本任务只需补齐 JDK 17 + Maven，并产出 `tools/env.sh` 与 `tools/local-secret.env`，后续所有任务的命令都以 `source tools/env.sh` 开头。

**Files:**
- Create: `.gitignore`
- Create: `tools/env.sh`（入库）
- Create: `tools/local-secret.env`（**gitignore，不入库**，只放 MySQL 密码与百炼 Key）
- 下载解压产物（不入库）：`tools/jdk17/`、`tools/maven/`

**Interfaces:**
- Consumes: 无
- Produces:
  - `tools/env.sh`，导出 `JAVA_HOME`、`MAVEN_HOME`、`PATH`、`DB_URL`、`DB_USER`、`DB_PASS`、`AI_DASHSCOPE_API_KEY`、`AI_BASE_URL`、`AI_CHAT_MODEL`、`AI_ENABLED`；开头会自动 `source tools/local-secret.env`（存在则读）。后续每个任务的验证命令都依赖它。
  - `tools/local-secret.env`，格式为若干行 `export KEY=value`，是**唯一**允许出现真实凭据的地方。

- [ ] **Step 1: 建 .gitignore（先做，防止把 Node 和密钥提交上去）**

⚠️ 当前 `tools/` 处于 untracked 状态且**没有** `.gitignore`，一旦有人 `git add .` 会把整个 Node 目录提上去。本步骤必须最先执行。

注意：不能写成 `tools/`（那样连 `tools/env.sh` 都会被忽略，`git add tools/env.sh` 会报错）。要按子项精确忽略：

```gitignore
# 本机工具链（JDK / Maven / Node）与下载缓存，不入库
tools/jdk17/
tools/maven/
tools/node/
tools/tmp-*/
tools/*.tar.gz

# 本机凭据（MySQL 密码、百炼 API Key），绝不入库
tools/local-secret.env

# 构建产物
target/
dist/
node_modules/

# 系统与日志
.DS_Store
*.log
```

写完立即校验（四条路径都必须有输出，否则说明规则写错）：

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git check-ignore -v tools/node/bin/node tools/local-secret.env tools/jdk17/Contents/Home tools/maven/bin/mvn
git status --short
```

预期：`check-ignore` 把**四条路径全部**匹配到；`git status --short` 里出现 `?? .gitignore`，**不再出现** `?? tools/`。
`tools/jdk17/` 与 `tools/maven/` 此刻还没下载、目录不存在也没关系 —— `check-ignore` 只拿路径比对模式，不要求文件真存在，所以能在下载前就把规则验掉。

> ⚠️ **规则行开头绝对不能有空格**。gitignore 的前导空格是模式的一部分，git 不会忽略它：写成 ` tools/jdk17/` 只会匹配名字真以一个空格开头的目录，规则静默失效，几百 MB 的 JDK 会在本任务 Step 9 的 `git add` 里被整个提交上去（已实测确认：带前导空格时 `check-ignore` 匹配不到，`git status` 里 `tools/` 仍为待添加）。行尾空格同样会被 git 忽略，除非用 `\` 转义 —— 两边都不要留。

- [ ] **Step 2: 下载并解压 JDK 17（Temurin，x64）**

本机是 Intel Mac（Homebrew 在 `/usr/local`、MySQL 是 x86_64 包），所以下 x64 构建。

```bash
cd /Users/zhoudingyan/workspace/zdy_test
curl -fL --retry 3 -o tools/jdk17.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jdk/hotspot/normal/eclipse"
mkdir -p tools/tmp-jdk
tar -xzf tools/jdk17.tar.gz -C tools/tmp-jdk
mv tools/tmp-jdk/*/ tools/jdk17
rmdir tools/tmp-jdk
rm -f tools/jdk17.tar.gz
```

`tools/jdk17/Contents/Home/bin/java` 应当存在（Temurin 的 macOS 包内层就是 `Contents/Home`）。

- [ ] **Step 3: 下载并解压 Maven 3.9.9**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
curl -fL --retry 3 -o tools/maven.tar.gz \
  "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
mkdir -p tools/tmp-mvn
tar -xzf tools/maven.tar.gz -C tools/tmp-mvn
mv tools/tmp-mvn/*/ tools/maven
rmdir tools/tmp-mvn
rm -f tools/maven.tar.gz
```

- [ ] **Step 4: Node —— 已就位，跳过**

`tools/node/` 已由用户手动安装（v20.18.1），**不要重新下载**。只需确认：

```bash
/Users/zhoudingyan/workspace/zdy_test/tools/node/bin/node -v   # 预期 v20.18.1
/Users/zhoudingyan/workspace/zdy_test/tools/node/bin/npx -v    # 预期 10.8.2
```

若输出版本号不符或缺文件，才回退到下载 `node-v20.18.1-darwin-x64.tar.gz` 解压进 `tools/node`。

> 已知坑：手动装的 Node 不会自动进 PATH，所以 `tools/env.sh` 里必须把 `$_TOOLS_DIR/node/bin` 拼进 `PATH`；同理 MySQL MCP 的配置里 `command` 与 `PATH` 也写的是绝对路径。

- [ ] **Step 5: 写 `tools/local-secret.env`（凭据，不入库）**

```bash
cat > /Users/zhoudingyan/workspace/zdy_test/tools/local-secret.env <<'EOF'
# 本机真实凭据，已被 .gitignore 排除，切勿提交、切勿贴进任何文档
export DB_PASS='<本机 MySQL root 密码>'
export AI_DASHSCOPE_API_KEY='<百炼业务空间 Key，sk-ws- 开头>'
EOF
chmod 600 /Users/zhoudingyan/workspace/zdy_test/tools/local-secret.env
git check-ignore -v /Users/zhoudingyan/workspace/zdy_test/tools/local-secret.env
```

> ⚠️ **两个尖括号必须换成真实值，但真实值绝不能出现在本计划、代码注释或提交信息里** —— 本文件是要入库的文档，远端是 GitHub，写进去就等于公开泄漏（Global Constraints 已定死这条）。
> 真实值的来源，执行时去这两处取：
> - `DB_PASS` —— 本机 MySQL 8.0.46 的 root 密码，用户在 MySQL MCP 配置里提供过
> - `AI_DASHSCOPE_API_KEY` —— 用户提供的 `/Users/zhoudingyan/Downloads/默认业务空间-apiKey-7024685.csv` 里那条 `sk-ws-` 前缀的 Key
>
> 填完**不要回显**：别 `cat` 这个文件、别把值贴回对话或文档。任务 22 Step 6 ⑤ 会做一次全库泄漏扫描兜底，那一步用的也是变量而非字面值。

预期：`git check-ignore` 输出命中 `.gitignore` 里的 `tools/local-secret.env` 规则。**若这一步没输出，立刻停下修 `.gitignore`，不要继续**。

- [ ] **Step 6: 写 `tools/env.sh`（入库，不含任何真实凭据）**

```bash
# 本机工具链与运行期环境变量
# 用法：source tools/env.sh   （在仓库根目录执行；后续 mvn / npm / mysql 命令都靠它）
# 说明：本机 Homebrew 已损坏，JDK17 / Maven / Node 全部装在 tools/ 下，不污染系统环境。
#       真实凭据放在同目录 local-secret.env（已 gitignore），本文件只留默认值。

_TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

export JAVA_HOME="$_TOOLS_DIR/jdk17/Contents/Home"
export MAVEN_HOME="$_TOOLS_DIR/maven"
# /usr/local/bin 必须显式列出，不能只靠末尾的 $PATH 继承：
# 本机 /usr/bin/git 是坏的空壳（CommandLineTools 残缺，一调就报 xcrun: error: invalid active
# developer path），真正可用的 git 2.20.1 在 /usr/local/bin；而实测部分执行环境的 PATH 只有
# tools/node/bin:/usr/bin:/bin，光靠继承会让本计划里所有 git 命令失败。
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$_TOOLS_DIR/node/bin:/usr/local/mysql/bin:/usr/local/bin:$PATH"

# ---- 本机凭据（存在则加载，缺失不报错，方便新机器先跑起来）----
[ -f "$_TOOLS_DIR/local-secret.env" ] && source "$_TOOLS_DIR/local-secret.env"

# ---- MySQL（本机已装 8.0.46，服务在跑，库 script_workbench 已建）----
export DB_USER="${DB_USER:-root}"
export DB_PASS="${DB_PASS:-}"
export DB_URL="${DB_URL:-jdbc:mysql://localhost:3306/script_workbench?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}"

# ---- 阿里百炼 DashScope（业务空间专属 Key，sk-ws- 前缀）----
# 已实测：该 Key 在公共端点 dashscope.aliyuncs.com 上也能用，所以 base-url 保持默认即可；
# 若要改走业务空间专属 MaaS 端点，export AI_BASE_URL=https://ws-q6x7mi6uitc06swd.cn-beijing.maas.aliyuncs.com 后重启后端。
# AI_ENABLED=false 时后端自动降级：语法校验、占位符提取、沙箱运行照常可用，
# 只有 AI 审查 / AI 对话返回中文提示。单测统一走降级分支，不烧额度。
export AI_DASHSCOPE_API_KEY="${AI_DASHSCOPE_API_KEY:-not-configured}"
export AI_BASE_URL="${AI_BASE_URL:-https://dashscope.aliyuncs.com}"
export AI_CHAT_MODEL="${AI_CHAT_MODEL:-qwen-plus}"
export AI_ENABLED="${AI_ENABLED:-true}"
```

- [ ] **Step 7: 验证工具链**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
source tools/env.sh
java -version
mvn -v
node -v
npm -v
mysql --version
echo "DB_PASS 已加载: ${DB_PASS:+是}"      # 预期输出「是」
echo "AI Key 已加载: ${AI_DASHSCOPE_API_KEY:0:7}"  # 预期输出 sk-ws-
```

预期：
- `java -version` 输出 `openjdk version "17.` 开头
- `mvn -v` 输出 `Apache Maven 3.9.9`，且 `Java version: 17.`
- `node -v` 输出 `v20.18.1`，`npm -v` 输出 `10.x`
- `mysql --version` 输出 `Ver 8.0.46`

若 `java -version` 报「无法验证开发者」，执行 `xattr -dr com.apple.quarantine tools/jdk17` 后重试。

- [ ] **Step 8: 复核建库（已完成，只需确认）**

```bash
source /Users/zhoudingyan/workspace/zdy_test/tools/env.sh
mysql -h127.0.0.1 -P3306 -u"$DB_USER" -p"$DB_PASS" -e "SHOW DATABASES LIKE 'script_workbench';" 2>&1 | grep -v "Using a password"
```

预期：输出包含 `script_workbench`（2026-09-02 已建好）。若为空则执行：

```bash
mysql -h127.0.0.1 -P3306 -u"$DB_USER" -p"$DB_PASS" -e \
  "CREATE DATABASE IF NOT EXISTS script_workbench DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
```

> 联调查库的两种手段，后续任务任选其一：
> 1. **MySQL MCP**（server 名 `mysql`）：`list_tables` 看有哪些表、`get_table_structure` 看字段、`exec_sql` 跑查询与增删改、`ddl_exec` 跑建表改表。适合在对话里直接核对数据。
>    **两个 SQL 工具互斥**：`exec_sql` 不接受 DDL，`ddl_exec` 不接受 DML，用错会被直接拒（判定只看首关键字）。
> 2. **`mysql` CLI**：MCP 未加载时的退化方案。CLI 不受上述互斥限制，什么都能跑。
>
> MCP 改配置后 Qoder 不会热加载，需重启或在 MCP 面板刷新；若 `CallMcpTool` 报找不到 `mysql` 服务，先用 CLI 顶上，不要因此卡住任务。

- [ ] **Step 9: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add .gitignore tools/env.sh
git status --short
```

提交前必须确认 `git status --short` 里**没有** `tools/node`、`tools/jdk17`、`tools/maven`、`tools/local-secret.env`。确认无误再提交：

```bash
git commit -m "chore: 本机工具链脚本（JDK17/Maven 装进 tools/，凭据走 local-secret.env 不入库）"
```

---

## Task 2: 后端脚手架 + 健康检查

**Files:**
- Create: `script_back/pom.xml`
- Create: `script_back/src/main/resources/application.yml`
- Create: `script_back/src/main/java/com/xd/rulescript/ScriptBackApplication.java`
- Create: `script_back/src/main/java/com/xd/rulescript/common/ApiResponse.java`
- Create: `script_back/src/main/java/com/xd/rulescript/common/BizException.java`
- Create: `script_back/src/main/java/com/xd/rulescript/common/GlobalExceptionHandler.java`
- Create: `script_back/src/main/java/com/xd/rulescript/controller/HealthController.java`
- Test: `script_back/src/test/java/com/xd/rulescript/ScriptBackApplicationTests.java`

**Interfaces:**
- Consumes: `tools/env.sh`（任务 1）
- Produces:
  - `ApiResponse<T>` record：`static <T> ApiResponse<T> ok(T data)`、`static ApiResponse<Void> ok()`、`static <T> ApiResponse<T> fail(int code, String message)`；组件 `int code()`、`String message()`、`T data()`
  - `BizException(String message)`（code 默认 1000）、`BizException(int code, String message)`、`int getCode()`
  - Maven 工程 `script_back`，可用 `mvn test` / `mvn spring-boot:run`

- [ ] **Step 1: 写 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.5</version>
    <relativePath/>
  </parent>

  <groupId>com.xd</groupId>
  <artifactId>script-back</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <name>script-back</name>
  <description>脚本规则工作台后端</description>

  <properties>
    <java.version>17</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <groovy.version>4.0.24</groovy.version>
    <spring-ai.version>1.0.0</spring-ai.version>
    <spring-ai-alibaba.version>1.0.0.3</spring-ai-alibaba.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-bom</artifactId>
        <version>${spring-ai-alibaba.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <!-- Groovy 脚本引擎（沙箱编译 + 执行） -->
    <dependency>
      <groupId>org.apache.groovy</groupId>
      <artifactId>groovy</artifactId>
      <version>${groovy.version}</version>
    </dependency>

    <!-- 阿里百炼 DashScope（qwen-plus）。未配 Key 时后端自动降级，不影响启动 -->
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    </dependency>

    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

注意：**不要**加 `spring-boot-starter-webflux`。`Flux` 由 spring-ai 传递进来的 `reactor-core` 提供，Spring MVC 原生支持把 `Flux<ServerSentEvent>` 流式写出；同时引入 webflux 反而会让启动类型判定变复杂。

- [ ] **Step 2: 写 `application.yml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: script-back
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/script_workbench?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false}
    username: ${DB_USER:root}
    password: ${DB_PASS:}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
    properties:
      hibernate:
        format_sql: false
  ai:
    dashscope:
      # Key 从 tools/local-secret.env 经 env.sh 注入，不写死。未配时为 not-configured，AiService 会自行降级
      api-key: ${AI_DASHSCOPE_API_KEY:not-configured}
      # 已实测业务空间 Key 在公共端点上也能用，所以默认值不改；
      # 若后续必须走专属 MaaS 端点，export AI_BASE_URL=https://ws-q6x7mi6uitc06swd.cn-beijing.maas.aliyuncs.com
      base-url: ${AI_BASE_URL:https://dashscope.aliyuncs.com}
      chat:
        options:
          model: ${AI_CHAT_MODEL:qwen-plus}
  servlet:
    multipart:
      max-request-size: 2MB

# 业务自有配置
app:
  script:
    timeout-seconds: 5        # 单次脚本执行上限，超时中断
  ai:
    enabled: ${AI_ENABLED:true}      # 单测统一置 false 走降级分支，不烧额度
    cr-timeout-seconds: 60           # 同步 CR 的自身超时，超时降级为提示

logging:
  level:
    root: INFO
    com.xd.rulescript: DEBUG
```

- [ ] **Step 3: 写启动类**

`script_back/src/main/java/com/xd/rulescript/ScriptBackApplication.java`

```java
package com.xd.rulescript;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 脚本规则工作台后端启动类。
 */
@SpringBootApplication
public class ScriptBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScriptBackApplication.class, args);
    }
}
```

- [ ] **Step 4: 写统一响应与异常处理**

`common/ApiResponse.java`

```java
package com.xd.rulescript.common;

/**
 * 统一响应包装。code=0 表示成功，其余为业务/系统错误码。
 * 业务失败也走 HTTP 200，前端只根据 code 判断。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

`common/BizException.java`

```java
package com.xd.rulescript.common;

/**
 * 业务异常。message 必须是可直接展示给用户的中文提示。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(1000, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

`common/GlobalExceptionHandler.java`

```java
package com.xd.rulescript.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：任何异常都翻译成可读中文，绝不把堆栈或英文异常名抛给前端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException e) {
        log.warn("业务异常：{}", e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("；"));
        String message = detail.isBlank() ? "请求参数不合法" : "请求参数不合法：" + detail;
        return ApiResponse.fail(1001, message);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ApiResponse.fail(1999, "服务内部错误，请稍后重试；如反复出现请查看后端日志");
    }
}
```

- [ ] **Step 5: 写上下文加载测试（此刻编译不过，这就是预期的红）**

注意顺序：测试写在 `HealthController` **之前**。测试里 import 了还不存在的 `HealthController`，所以下一步必然编译失败 —— 这正是我们要看到的红。（原计划是先写接口再写测试，那样标题里的「应该失败」是假的，测试是否真的在验证东西就无从得知。）

`src/test/java/com/xd/rulescript/ScriptBackApplicationTests.java`

```java
package com.xd.rulescript;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载 + 健康检查冒烟测试。
 * 需要本机 MySQL 可用（库 script_workbench 已建）；DB_PASS 有值时先 export 再跑。
 */
@SpringBootTest
class ScriptBackApplicationTests {

    @Autowired
    private HealthController healthController;

    @Test
    void 上下文能加载且健康检查返回成功码() {
        assertThat(healthController.health().code()).isZero();
        assertThat(healthController.health().data()).containsEntry("status", "UP");
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：**COMPILATION ERROR**，报 `ScriptBackApplicationTests.java` 找不到符号 `com.xd.rulescript.controller.HealthController`。这是预期的红，**不要为了让它变绿去删 import 或改断言**，下一步补实现即可。
首次执行会下载大量依赖，耗时数分钟属正常。
若报的是别的错（比如 `Access denied`、`Unknown database`），说明环境问题先于红出现了，按 Step 8 的对策表处理。

- [ ] **Step 7: 写健康检查接口**

`controller/HealthController.java`

```java
package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查。用于确认后端起来了、前端代理通了。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @PostMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "app", "script-back"));
    }
}
```

- [ ] **Step 8: 跑测试确认通过**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，1 个测试通过。
常见失败与对策：
- `Access denied for user 'root'` → 本机 root 确实有密码，说明 `tools/local-secret.env` 没被加载或没写 `DB_PASS`。检查：`source ../tools/env.sh && echo "${DB_PASS:+已加载}"`，输出空则回任务 1 Step 5
- `Public Key Retrieval is not allowed` → `DB_URL` 里的 `allowPublicKeyRetrieval=true` 丢了，核对 `application.yml` 与 `env.sh` 两处的默认值是否一致
- `Unknown database 'script_workbench'` → 回任务 1 Step 8 复核建库

- [ ] **Step 9: 启动两次，分别确认「真实 Key 能起」与「降级也能起」**

启动前务必 `source ../tools/env.sh`，否则 `DB_PASS` 与 `AI_DASHSCOPE_API_KEY` 为空。

**第 1 次（真实配置，默认路径）：**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q spring-boot:run
```

另开一个终端：

```bash
curl -s -X POST http://localhost:8080/api/health
```

预期返回：`{"code":0,"message":"success","data":{"status":"UP","app":"script-back"}}`

验证完 `Ctrl+C` 停掉。

**第 2 次（强制降级，验证无 Key 时不会把应用搞挂）：**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
AI_ENABLED=false AI_DASHSCOPE_API_KEY=not-configured mvn -q spring-boot:run
```

同样调 `/api/health`，预期**仍然返回 code=0**。这是非功能要求 #1 的底线：AI 不可用时工具本体（语法校验 / 占位符提取 / 沙箱运行）必须照常可用。

验证完 `Ctrl+C` 停掉。

**两次都必须能起**。如果第 1 次因 DashScope 相关 bean 报错：
1. 先确认 `api-key` 不是空串（空串会让 starter 建 bean 失败，所以默认值给的是 `not-configured` 而不是空）
2. 再确认 `spring.ai.dashscope.base-url` 已生效（启动日志里搜 `dashscope`）
3. 仍失败则记录完整报错，任务 16 会据此调整 AI 装配方式（备选方案：改用 OpenAI 兼容模式 starter 指向 `/compatible-mode/v1`，该端点已实测可用）

> 此时还不要验证 AI 真的能调通（健康检查接口不碰大模型）。真实调用留到任务 16、18 的手工验收步骤，避免在脚手架阶段就烧额度。

- [ ] **Step 10: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 后端脚手架（Spring Boot 3.4.5 + 统一响应 + 全局异常 + 健康检查）"
```

---

## Task 3: 前端脚手架 + 代理 + 风格三主题

**Files:**
- Create: `script_front/package.json`
- Create: `script_front/vite.config.js`
- Create: `script_front/vitest.config.js`
- Create: `script_front/index.html`
- Create: `script_front/src/main.js`
- Create: `script_front/src/App.vue`
- Create: `script_front/src/router/index.js`
- Create: `script_front/src/styles/theme.css`
- Create: `script_front/src/api/http.js`
- Create: `script_front/src/api/__tests__/http.spec.js`
- Create: `script_front/src/views/RuleListView.vue`（本任务只放占位骨架，任务 6 填实）
- Create: `script_front/src/views/RuleWorkbenchView.vue`（本任务只放占位骨架，任务 13 填实）

**Interfaces:**
- Consumes: `tools/env.sh`（任务 1 的 Node 20）、后端 `/api/health`（任务 2）
- Produces:
  - `post(path, body)` → `Promise<data>`：POST JSON，HTTP 非 2xx 抛 `Error("服务异常（HTTP xxx），请确认后端已启动")`，`code !== 0` 抛 `Error(payload.message)`，成功返回 `payload.data`
  - `reportError(error)` → `string`：把错误转成中文文本并 `ElMessage.error` 弹出，返回该文本
  - 路由：`/` → `RuleListView`，`/rule/:id` → `RuleWorkbenchView`
  - CSS 变量（`theme.css`）：`--brand-from` `--brand-to` `--brand-gradient` `--page-bg` `--panel-bg` `--panel-radius` `--panel-shadow` `--text-main` `--text-muted` `--border-light` `--input-bg` `--chip-bg` `--chip-fg`

- [ ] **Step 1: 写 `package.json`**

```json
{
  "name": "script-front",
  "version": "0.0.1",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "test:unit": "vitest run"
  },
  "dependencies": {
    "@codemirror/commands": "^6.6.0",
    "@codemirror/language": "^6.10.0",
    "@codemirror/legacy-modes": "^6.4.0",
    "@codemirror/state": "^6.4.0",
    "@codemirror/view": "^6.28.0",
    "element-plus": "^2.7.0",
    "vue": "^3.4.0",
    "vue-router": "^4.3.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.3.0",
    "vitest": "^1.6.0"
  }
}
```

- [ ] **Step 2: 写 `vite.config.js`**

```js
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期把 /api 转发到后端 8080，后端因此不需要配跨域
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
```

- [ ] **Step 3: 写 `vitest.config.js`**

```js
import { defineConfig } from 'vitest/config'

// 只测纯逻辑模块，用 node 环境即可，不拉 jsdom
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/__tests__/*.spec.js']
  }
})
```

- [ ] **Step 4: 写 `index.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>脚本规则工作台</title>
</head>
<body>
  <div id="app"></div>
  <script type="module" src="/src/main.js"></script>
</body>
</html>
```

- [ ] **Step 5: 写风格三主题 `src/styles/theme.css`**

色值全部照抄参考稿 `docs/ui-mockups/style3-gradient.html`。

```css
/* 风格三：现代渐变风。所有色值/圆角/阴影集中在这里，组件内一律引用变量，不写死颜色 */
:root {
  --brand-from: #5b5fc7;
  --brand-to: #8b5cf6;
  --brand-gradient: linear-gradient(90deg, #5b5fc7, #8b5cf6);
  --brand-gradient-soft: linear-gradient(180deg, #f5f5ff, #fff);
  --page-bg: #eef1f8;
  --panel-bg: #ffffff;
  --panel-radius: 16px;
  --panel-shadow: 0 4px 24px rgba(80, 90, 180, .08);
  --topbar-shadow: 0 2px 12px rgba(91, 95, 199, .25);
  --text-main: #3d4260;
  --text-muted: #8a90a8;
  --border-light: #f0f2f8;
  --input-bg: #fafbff;
  --chip-bg: #eef0ff;
  --chip-fg: #5b5fc7;
  --danger: #ef4444;
  --danger-bg: #ffecec;
}

* { box-sizing: border-box; }

html, body, #app { height: 100%; }

body {
  margin: 0;
  background: var(--page-bg);
  color: var(--text-main);
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 14px;
}

/* 通用面板 */
.panel {
  background: var(--panel-bg);
  border-radius: var(--panel-radius);
  box-shadow: var(--panel-shadow);
  overflow: hidden;
}

.panel-title {
  padding: 12px 20px;
  font-size: 13px;
  color: var(--text-muted);
  border-bottom: 1px solid var(--border-light);
  font-weight: 500;
}

/* 渐变顶栏 */
.topbar {
  height: 60px;
  background: var(--brand-gradient);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 24px;
  box-shadow: var(--topbar-shadow);
  flex: none;
}

.topbar .logo { font-weight: 600; color: #fff; font-size: 17px; letter-spacing: 1px; }
.topbar .rule-name {
  font-size: 14px; color: rgba(255, 255, 255, .92);
  background: rgba(255, 255, 255, .16); padding: 4px 14px; border-radius: 14px;
}
.topbar .tag { font-size: 12px; color: #fff; background: rgba(255, 255, 255, .22); padding: 3px 10px; border-radius: 12px; }
.topbar .spacer { flex: 1; }

/* 类型小标签，如 int / String */
.type-chip {
  font-size: 11px; color: var(--chip-fg); background: var(--chip-bg);
  padding: 2px 8px; border-radius: 8px; margin-left: 6px;
}

/* Element Plus 主色对齐风格三 */
.el-button--primary {
  --el-button-bg-color: var(--brand-from);
  --el-button-border-color: var(--brand-from);
  --el-button-hover-bg-color: var(--brand-to);
  --el-button-hover-border-color: var(--brand-to);
}

/* ---- CodeMirror 定制 ---- */
.cm-editor { height: 100%; font-size: 13px; background: #fff; }
.cm-editor.cm-focused { outline: none; }
.cm-gutters { background: #fbfbff; border-right: 1px solid var(--border-light); color: #c3c8d9; }
.cm-activeLine { background: #f7f8ff; }
.cm-activeLineGutter { background: #f0f1ff; color: var(--chip-fg); }

/* 语法错误行整行标红 */
.cm-errorLine { background: var(--danger-bg) !important; }
.cm-placeholder { color: var(--brand-from); background: var(--chip-bg); border-radius: 4px; padding: 1px 2px; }
```

- [ ] **Step 6: 写 `http.js` 的失败测试**

顺序要点：测试写在实现**之前**。`http.js` 是本计划里第一个有分支逻辑的纯 JS 模块（网络异常 / HTTP 非 2xx / JSON 解析失败 / `code !== 0` 四条分支），先让测试红一次，才能确认它真的在验证这四条分支而不是空过。

`src/api/__tests__/http.spec.js`

```js
import { describe, it, expect, vi, afterEach } from 'vitest'

// http.js 顶层 import 了 element-plus，测试里把它替换成空实现，避免拉入样式与 DOM 依赖
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

const { post } = await import('../http.js')

function stubFetch(payload, { ok = true, status = 200 } = {}) {
  return vi.fn(async () => ({
    ok,
    status,
    json: async () => payload
  }))
}

describe('post 解包统一响应', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('code=0 时返回 data 字段', async () => {
    vi.stubGlobal('fetch', stubFetch({ code: 0, message: 'success', data: { id: 7 } }))
    await expect(post('/api/rule/detail', { ruleId: 7 })).resolves.toEqual({ id: 7 })
  })

  it('请求体是 JSON 且方法固定为 POST', async () => {
    const fetchMock = stubFetch({ code: 0, message: 'success', data: null })
    vi.stubGlobal('fetch', fetchMock)
    await post('/api/rule/delete', { ruleId: 3 })
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/rule/delete')
    expect(options.method).toBe('POST')
    expect(JSON.parse(options.body)).toEqual({ ruleId: 3 })
  })

  it('code 非 0 时抛出后端给的中文 message', async () => {
    vi.stubGlobal('fetch', stubFetch({ code: 1000, message: '规则不存在或已被删除', data: null }))
    await expect(post('/api/rule/detail', { ruleId: 999 }))
      .rejects.toThrow('规则不存在或已被删除')
  })

  it('HTTP 非 2xx 时抛服务异常提示', async () => {
    vi.stubGlobal('fetch', stubFetch({}, { ok: false, status: 502 }))
    await expect(post('/api/rule/list', {})).rejects.toThrow('服务异常（HTTP 502）')
  })

  it('网络不可达时提示确认后端已启动', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await expect(post('/api/rule/list', {})).rejects.toThrow('无法连接后端服务')
  })

  it('响应不是合法 JSON 时给可读提示', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => { throw new Error('bad json') } })))
    await expect(post('/api/rule/list', {})).rejects.toThrow('无法解析')
  })
})
```

- [ ] **Step 7: 装依赖并跑测试确认失败**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm install
npm run test:unit
```

预期：`npm install` 成功（首次会拉很多包，耗时几分钟属正常）；`npm run test:unit` 以 **`Cannot find module '../http.js'`** 失败，6 个用例一个都跑不起来 —— 这就是预期的红。
**不要建一个空的 `http.js` 或改测试里的 import 路径去让它变绿**，下一步写真正的实现。

- [ ] **Step 8: 写 `src/api/http.js`**

```js
// 统一请求封装：所有接口 POST，响应体固定 {code, message, data}
import { ElMessage } from 'element-plus'

/**
 * 发一个 POST 请求并解包 data。
 * @param {string} path 以 /api 开头的路径（开发期由 Vite 代理到 8080）
 * @param {object} body 请求体
 * @returns {Promise<any>} data 字段
 * @throws {Error} message 为可直接展示的中文提示
 */
export async function post(path, body = {}) {
  let response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
  } catch (networkError) {
    throw new Error('无法连接后端服务，请确认 script_back 已在 8080 端口启动')
  }
  if (!response.ok) {
    throw new Error(`服务异常（HTTP ${response.status}），请稍后重试`)
  }
  let payload
  try {
    payload = await response.json()
  } catch (parseError) {
    throw new Error('后端返回内容无法解析，请确认接口地址是否正确')
  }
  if (payload.code !== 0) {
    throw new Error(payload.message || '请求失败')
  }
  return payload.data
}

/**
 * 把异常翻译成中文文本，弹一次错误提示，并把文本返回给调用方用于界面展示。
 */
export function reportError(error) {
  const text = error instanceof Error && error.message ? error.message : '操作失败，请稍后重试'
  ElMessage.error(text)
  return text
}
```

- [ ] **Step 9: 跑测试确认通过**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：**6 个用例全部通过**（网络异常 / HTTP 500 / JSON 不可解析 / `code !== 0` / 正常解包 / `reportError`）。到不了 6 个就是实现漏了分支，别改断言凑数。

- [ ] **Step 10: 写 `src/main.js` 与 `src/App.vue`**

`src/main.js`

```js
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles/theme.css'
import App from './App.vue'
import router from './router'

createApp(App).use(ElementPlus, { locale: zhCn }).use(router).mount('#app')
```

`src/App.vue`

```vue
<template>
  <router-view />
</template>
```

`src/router/index.js`

```js
import { createRouter, createWebHistory } from 'vue-router'
import RuleListView from '@/views/RuleListView.vue'
import RuleWorkbenchView from '@/views/RuleWorkbenchView.vue'

const routes = [
  { path: '/', name: 'rule-list', component: RuleListView },
  { path: '/rule/:id', name: 'rule-workbench', component: RuleWorkbenchView, props: true }
]

export default createRouter({ history: createWebHistory(), routes })
```

- [ ] **Step 11: 写两个视图的占位骨架**

`src/views/RuleListView.vue`（任务 6 会整体替换）

```vue
<template>
  <div class="page">
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="spacer"></div>
    </div>
    <div class="body">规则列表页（待实现）</div>
  </div>
</template>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.body { flex: 1; padding: 20px; }
</style>
```

`src/views/RuleWorkbenchView.vue`（任务 13 会整体替换）

```vue
<template>
  <div class="page">
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="rule-name">工作台（待实现）</div>
      <div class="spacer"></div>
    </div>
    <div class="body">规则 ID：{{ id }}</div>
  </div>
</template>

<script setup>
defineProps({ id: { type: String, required: true } })
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.body { flex: 1; padding: 20px; }
</style>
```

- [ ] **Step 12: 构建验证**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
npm run build
```

预期：单测 6 个通过；`vite build` 成功产出 `dist/`，无报错。

- [ ] **Step 13: 代理连通性验证**

后端起着（任务 2 Step 9 的方式，另开终端），再开一个终端：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run dev
```

浏览器打开 `http://localhost:5173/`，应看到渐变顶栏 + 「规则列表页（待实现）」；打开 `http://localhost:5173/rule/1`，应看到「规则 ID：1」。
再在浏览器控制台执行：

```js
fetch('/api/health', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
  .then(r => r.json()).then(console.log)
```

预期打印 `{code: 0, message: "success", data: {...}}`——证明 Vite 代理通了。验证完 `Ctrl+C` 停掉 dev server。

- [ ] **Step 14: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_front
git status --short   # 确认 node_modules、dist 未被纳入
git commit -m "feat: 前端脚手架（Vue3+Vite+ElementPlus、/api 代理、风格三主题、请求封装）"
```

---

## Task 4: 数据模型（4 张表的实体与仓库）

对应技术方案第 4 节。建表交给 JPA `ddl-auto=update`，不写 SQL 脚本。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/entity/Rule.java`
- Create: `script_back/src/main/java/com/xd/rulescript/entity/Conversation.java`
- Create: `script_back/src/main/java/com/xd/rulescript/entity/ChatMessage.java`
- Create: `script_back/src/main/java/com/xd/rulescript/entity/TestCase.java`
- Create: `script_back/src/main/java/com/xd/rulescript/repository/RuleRepository.java`
- Create: `script_back/src/main/java/com/xd/rulescript/repository/ConversationRepository.java`
- Create: `script_back/src/main/java/com/xd/rulescript/repository/ChatMessageRepository.java`
- Create: `script_back/src/main/java/com/xd/rulescript/repository/TestCaseRepository.java`
- Test: `script_back/src/test/java/com/xd/rulescript/repository/RepositorySmokeTest.java`

**Interfaces:**
- Consumes: `ScriptBackApplication`（任务 2）
- Produces:
  - `Rule`：`Long getId()` / `String getName()` / `setName` / `String getDescription()` / `setDescription` / `String getScriptContent()` / `setScriptContent` / `LocalDateTime getCreatedAt()` / `getUpdatedAt()`
  - `Conversation`：`Long getId()` / `Long getRuleId()` / `setRuleId` / `String getTitle()` / `setTitle` / `getCreatedAt()` / `getUpdatedAt()` / `setUpdatedAt`
  - `ChatMessage`：`Long getId()` / `Long getConversationId()` / `setConversationId` / `String getRole()` / `setRole` / `String getContent()` / `setContent` / `getCreatedAt()`
  - `TestCase`：`Long getId()` / `Long getRuleId()` / `setRuleId` / `String getName()` / `setName` / `String getParamsJson()` / `setParamsJson` / `getCreatedAt()`
  - `RuleRepository`：`Page<Rule> findByNameContaining(String keyword, Pageable pageable)`
  - `ConversationRepository`：`Optional<Conversation> findByRuleId(Long ruleId)`
  - `ChatMessageRepository`：`List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId)`、`void deleteByConversationId(Long conversationId)`
  - `TestCaseRepository`：`List<TestCase> findByRuleIdOrderByIdAsc(Long ruleId)`、`void deleteByRuleId(Long ruleId)`

**说明：** 实体类名用 `ChatMessage` 而不是 `Message`，因为任务 18 会引入 `org.springframework.ai.chat.messages.Message`，同名会造成持续歧义。表名仍按技术方案叫 `message`。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/xd/rulescript/repository/RepositorySmokeTest.java`

```java
package com.xd.rulescript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.entity.Rule;
import com.xd.rulescript.entity.TestCase;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * 四张表的落库与查询冒烟测试。@Transactional 让每个用例结束后自动回滚，不留脏数据。
 * 前置：本机 MySQL 在跑，库 script_workbench 已建。
 */
@SpringBootTest
@Transactional
class RepositorySmokeTest {

    @Autowired private RuleRepository ruleRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private TestCaseRepository testCaseRepository;

    @Test
    void 规则能落库并自动填充两个时间戳() {
        Rule rule = new Rule();
        rule.setName("冒烟规则");
        rule.setDescription("用于测试");
        rule.setScriptContent("return 1");
        Rule saved = ruleRepository.save(rule);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(ruleRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void 规则能按名称模糊搜索并按更新时间倒序分页() {
        Rule a = new Rule(); a.setName("折扣判定"); a.setScriptContent(""); ruleRepository.save(a);
        Rule b = new Rule(); b.setName("风控判定"); b.setScriptContent(""); ruleRepository.save(b);

        Page<Rule> hit = ruleRepository.findByNameContaining("折扣",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")));
        assertThat(hit.getContent()).extracting(Rule::getName).contains("折扣判定");
        assertThat(hit.getContent()).extracting(Rule::getName).doesNotContain("风控判定");
    }

    @Test
    void 会话与规则一对一并能按规则ID查到() {
        Rule rule = new Rule(); rule.setName("带会话的规则"); rule.setScriptContent("");
        rule = ruleRepository.save(rule);

        Conversation conversation = new Conversation();
        conversation.setRuleId(rule.getId());
        conversation.setTitle(rule.getName());
        conversationRepository.save(conversation);

        Optional<Conversation> found = conversationRepository.findByRuleId(rule.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("带会话的规则");
    }

    @Test
    void 消息按时间升序返回并能按会话删除() {
        Conversation conversation = new Conversation();
        conversation.setRuleId(-1L);
        conversation.setTitle("临时");
        conversation = conversationRepository.save(conversation);

        ChatMessage first = new ChatMessage();
        first.setConversationId(conversation.getId());
        first.setRole("user");
        first.setContent("第一条");
        messageRepository.save(first);

        ChatMessage second = new ChatMessage();
        second.setConversationId(conversation.getId());
        second.setRole("assistant");
        second.setContent("第二条");
        messageRepository.save(second);

        List<ChatMessage> history =
                messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
        assertThat(history).extracting(ChatMessage::getContent).containsExactly("第一条", "第二条");

        messageRepository.deleteByConversationId(conversation.getId());
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId())).isEmpty();
    }

    @Test
    void 测试用例能按规则查询并能按规则删除() {
        TestCase testCase = new TestCase();
        testCase.setRuleId(-2L);
        testCase.setName("vip 成年");
        testCase.setParamsJson("{\"age\":\"28\",\"level\":\"vip\"}");
        testCaseRepository.save(testCase);

        assertThat(testCaseRepository.findByRuleIdOrderByIdAsc(-2L)).hasSize(1);
        testCaseRepository.deleteByRuleId(-2L);
        assertThat(testCaseRepository.findByRuleIdOrderByIdAsc(-2L)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：编译失败，报 `Rule`、`RuleRepository` 等符号找不到。

- [ ] **Step 3: 写 `Rule` 实体**

```java
package com.xd.rulescript.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 规则：一段 Groovy 脚本，决策引擎的最小执行单元。
 */
@Entity
@Table(name = "rule")
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    /** 不用 @Lob：Hibernate 6 对 String + @Lob 会映射成 LONGTEXT 且读写行为不一致，直接用 columnDefinition 指定 TEXT */
    @Column(name = "script_content", nullable = false, columnDefinition = "TEXT")
    private String scriptContent = "";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getScriptContent() { return scriptContent; }
    public void setScriptContent(String scriptContent) { this.scriptContent = scriptContent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: 写 `Conversation` 实体**

```java
package com.xd.rulescript.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 会话：与规则一对一（rule_id 唯一索引），创建规则时自动生成，不提供新建/删除入口。
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false, unique = true)
    private Long ruleId;

    @Column(length = 255)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 5: 写 `ChatMessage` 实体**

```java
package com.xd.rulescript.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 消息：对话历史。内存记忆负责给大模型提供上下文，这张表负责页面回显与重启后不丢。
 */
@Entity
@Table(name = "message")
public class ChatMessage {

    /** 角色常量 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: 写 `TestCase` 实体**

```java
package com.xd.rulescript.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 测试用例：一组占位符填值的快照。params 以 JSON 字符串存储，序列化由 service 层负责。
 */
@Entity
@Table(name = "test_case")
public class TestCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "params_json", nullable = false, columnDefinition = "TEXT")
    private String paramsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 7: 写四个仓库接口**

```java
package com.xd.rulescript.repository;

import com.xd.rulescript.entity.Rule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleRepository extends JpaRepository<Rule, Long> {

    /** 按名称模糊搜索，排序与分页由 Pageable 决定 */
    Page<Rule> findByNameContaining(String keyword, Pageable pageable);
}
```

```java
package com.xd.rulescript.repository;

import com.xd.rulescript.entity.Conversation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByRuleId(Long ruleId);
}
```

```java
package com.xd.rulescript.repository;

import com.xd.rulescript.entity.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 同一秒内落库的消息靠 id 兜底排序，保证回显顺序稳定 */
    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);

    void deleteByConversationId(Long conversationId);
}
```

```java
package com.xd.rulescript.repository;

import com.xd.rulescript.entity.TestCase;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {

    List<TestCase> findByRuleIdOrderByIdAsc(Long ruleId);

    void deleteByRuleId(Long ruleId);
}
```

- [ ] **Step 8: 跑测试确认通过**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 6 个测试通过（任务 2 的 1 个 + 本任务 5 个）。

- [ ] **Step 9: 确认表结构真的建出来了**

```bash
source /Users/zhoudingyan/workspace/zdy_test/tools/env.sh
mysql -u"$DB_USER" ${DB_PASS:+-p"$DB_PASS"} script_workbench -e "SHOW TABLES; DESC rule;"
```

预期：`SHOW TABLES` 列出 `conversation`、`message`、`rule`、`test_case`；`rule` 含 `id`/`name`/`description`/`script_content`(text)/`created_at`/`updated_at`。

- [ ] **Step 10: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 数据模型（rule/conversation/message/test_case 四表实体与仓库）"
```

---

## Task 5: DTO 全集 + 规则 CRUD 后端

本任务一次性把**全部** DTO record 定义完（包括校验/运行/对话/用例的），后续任务只写 service 与 controller，不再回头改 dto 包。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/dto/` 下 22 个 record（见 Step 1）
- Create: `script_back/src/main/java/com/xd/rulescript/service/RuleService.java`
- Create: `script_back/src/main/java/com/xd/rulescript/controller/RuleController.java`
- Test: `script_back/src/test/java/com/xd/rulescript/service/RuleServiceTest.java`

**Interfaces:**
- Consumes: 任务 4 的 4 个仓库与实体；任务 2 的 `BizException`
- Produces（后续任务依赖的完整签名）:
  - `PlaceholderInfo(String name, String type)`
  - `SyntaxCheckResult(boolean ok, Integer line, String message)` + `static pass()` / `static error(Integer, String)`（成功工厂叫 `pass()` 不叫 `ok()`：record 组件 `ok` 已生成同签名读取器 `ok()`，静态方法不能再叫 `ok()`，否则编译不过；读取仍用 `.ok()`）
  - `RunResult(boolean success, String value, String errorMessage, boolean timeout)`
  - `AiReviewResult(String text, String suggestedScript, boolean available)`
  - `ValidateRequest(String scriptContent)` / `ValidateResponse(boolean syntaxOk, Integer errorLine, String errorMessage, List<PlaceholderInfo> placeholders, AiReviewResult aiReview)`
  - `RunRequest(String scriptContent, Map<String,String> params)` / `RunResponse(boolean success, String value, String errorMessage, boolean timeout)`
  - `RuleListRequest(String name, Integer page, Integer size)` / `RuleItem(Long id, String name, String description, String updatedAt)` / `RulePageResponse(List<RuleItem> items, long total, int page, int size)`
  - `RuleCreateRequest(String name, String description)` / `RuleIdRequest(Long ruleId)` / `RuleDetailResponse(Long id, String name, String description, String scriptContent, String updatedAt)` / `RuleUpdateRequest(Long ruleId, String name, String description, String scriptContent)`
  - `ChatHistoryRequest(Long ruleId)` / `ChatMessageDto(String role, String content, String createdAt)` / `ChatSendRequest(Long ruleId, String message, String scriptContent)`
  - `TestCaseListRequest(Long ruleId)` / `TestCaseDto(Long id, String name, Map<String,String> params)` / `TestCaseSaveRequest(Long ruleId, String name, Map<String,String> params)` / `TestCaseIdRequest(Long testCaseId)`
  - `RuleService`：`RulePageResponse list(RuleListRequest)`、`RuleDetailResponse create(RuleCreateRequest)`、`RuleDetailResponse detail(Long ruleId)`、`RuleDetailResponse update(RuleUpdateRequest)`、`void delete(Long ruleId)`

**为什么用 record：** Spring Boot 3.4.5 自带 Jackson 2.18，对 record 的序列化/反序列化（含 `@RequestBody` 绑定与 `jakarta.validation` 约束）原生支持，无需无参构造与 setter，代码量最小。

- [ ] **Step 1: 写全部 DTO record**

每个 record 一个文件，放在 `script_back/src/main/java/com/xd/rulescript/dto/` 下。

```java
package com.xd.rulescript.dto;

/** 占位符及其推断类型。type 只会是 int / long / double / boolean / String 之一 */
public record PlaceholderInfo(String name, String type) {}
```

```java
package com.xd.rulescript.dto;

/** 语法校验结果。ok=false 时 line 为出错行号（可能为 null，表示无法定位到具体行），message 为中文原因 */
public record SyntaxCheckResult(boolean ok, Integer line, String message) {

    /** 成功工厂命名为 pass() 而非 ok()：record 组件 ok 已生成同签名的读取器 ok()，静态方法不能再叫 ok() */
    public static SyntaxCheckResult pass() {
        return new SyntaxCheckResult(true, null, null);
    }

    public static SyntaxCheckResult error(Integer line, String message) {
        return new SyntaxCheckResult(false, line, message);
    }
}
```

```java
package com.xd.rulescript.dto;

/** 沙箱运行结果。timeout=true 表示因超过 5 秒被中断 */
public record RunResult(boolean success, String value, String errorMessage, boolean timeout) {}
```

```java
package com.xd.rulescript.dto;

/**
 * 大模型审查结果。
 * @param text            审查意见全文（中文，可能包含 markdown）
 * @param suggestedScript 从审查意见里抽出的第一个代码块内容；没有则为 null
 * @param available       本次是否真的调通了大模型；false 表示走了降级提示
 */
public record AiReviewResult(String text, String suggestedScript, boolean available) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 校验请求。校验一律针对编辑器当前内容（含未保存修改），因此只传 scriptContent，不传 ruleId */
public record ValidateRequest(@NotNull(message = "不能为空") String scriptContent) {}
```

```java
package com.xd.rulescript.dto;

import java.util.List;

/** 校验响应：语法结果 + 占位符列表 + AI 审查 */
public record ValidateResponse(
        boolean syntaxOk,
        Integer errorLine,
        String errorMessage,
        List<PlaceholderInfo> placeholders,
        AiReviewResult aiReview) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 运行请求。params 的 key 是占位符名，value 一律是字符串，由后端按类型校验并转字面量 */
public record RunRequest(
        @NotNull(message = "不能为空") String scriptContent,
        Map<String, String> params) {}
```

```java
package com.xd.rulescript.dto;

/** 运行响应 */
public record RunResponse(boolean success, String value, String errorMessage, boolean timeout) {}
```

```java
package com.xd.rulescript.dto;

/** 规则列表查询。name 为空表示不过滤；page 从 1 开始 */
public record RuleListRequest(String name, Integer page, Integer size) {}
```

```java
package com.xd.rulescript.dto;

/** 列表行。updatedAt 已格式化为 yyyy-MM-dd HH:mm:ss 字符串，前端直接展示 */
public record RuleItem(Long id, String name, String description, String updatedAt) {}
```

```java
package com.xd.rulescript.dto;

import java.util.List;

/** 规则分页结果 */
public record RulePageResponse(List<RuleItem> items, long total, int page, int size) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建规则 */
public record RuleCreateRequest(
        @NotBlank(message = "规则名称不能为空") String name,
        String description) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 只带规则 ID 的请求（详情、删除、对话历史、清空对话、用例列表共用） */
public record RuleIdRequest(@NotNull(message = "规则 ID 不能为空") Long ruleId) {}
```

```java
package com.xd.rulescript.dto;

/** 规则详情（含脚本内容） */
public record RuleDetailResponse(
        Long id,
        String name,
        String description,
        String scriptContent,
        String updatedAt) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 更新规则。除 ruleId 外其余字段为 null 表示该项不改 */
public record RuleUpdateRequest(
        @NotNull(message = "规则 ID 不能为空") Long ruleId,
        String name,
        String description,
        String scriptContent) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 查询对话历史 */
public record ChatHistoryRequest(@NotNull(message = "规则 ID 不能为空") Long ruleId) {}
```

```java
package com.xd.rulescript.dto;

/** 对话消息（回显用） */
public record ChatMessageDto(String role, String content, String createdAt) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 发送对话。scriptContent 是编辑器当前内容，作为上下文一并发给大模型 */
public record ChatSendRequest(
        @NotNull(message = "规则 ID 不能为空") Long ruleId,
        @NotBlank(message = "消息内容不能为空") String message,
        String scriptContent) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 查询测试用例列表 */
public record TestCaseListRequest(@NotNull(message = "规则 ID 不能为空") Long ruleId) {}
```

```java
package com.xd.rulescript.dto;

import java.util.Map;

/** 测试用例（返回给前端） */
public record TestCaseDto(Long id, String name, Map<String, String> params) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 保存测试用例 */
public record TestCaseSaveRequest(
        @NotNull(message = "规则 ID 不能为空") Long ruleId,
        @NotBlank(message = "用例名称不能为空") String name,
        @NotNull(message = "参数不能为空") Map<String, String> params) {}
```

```java
package com.xd.rulescript.dto;

import jakarta.validation.constraints.NotNull;

/** 删除测试用例 */
public record TestCaseIdRequest(@NotNull(message = "用例 ID 不能为空") Long testCaseId) {}
```

- [ ] **Step 2: 写失败测试**

`src/test/java/com/xd/rulescript/service/RuleServiceTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则 CRUD 行为测试。
 */
@SpringBootTest
@Transactional
class RuleServiceTest {

    @Autowired private RuleService ruleService;
    @Autowired private ConversationRepository conversationRepository;

    @Test
    void 新建规则会同时生成一个会话并带默认脚本() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("VIP 折扣判定", "按年龄和会员等级判定"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("VIP 折扣判定");
        assertThat(created.scriptContent()).contains("${age}").contains("${level}");
        assertThat(conversationRepository.findByRuleId(created.id())).isPresent();
    }

    @Test
    void 名称为空时拒绝创建() {
        assertThatThrownBy(() -> ruleService.create(new RuleCreateRequest("   ", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规则名称不能为空");
    }

    @Test
    void 列表按名称模糊搜索且分页信息正确() {
        ruleService.create(new RuleCreateRequest("折扣规则甲", null));
        ruleService.create(new RuleCreateRequest("风控规则乙", null));

        RulePageResponse page = ruleService.list(new RuleListRequest("折扣", 1, 10));

        assertThat(page.items()).extracting("name").contains("折扣规则甲");
        assertThat(page.items()).extracting("name").doesNotContain("风控规则乙");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 列表页码缺省时按第一页处理() {
        ruleService.create(new RuleCreateRequest("缺省分页规则", null));
        RulePageResponse page = ruleService.list(new RuleListRequest(null, null, null));
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
    }

    @Test
    void 更新时null字段保持原值() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("原名", "原描述"));
        String originalScript = created.scriptContent();

        RuleDetailResponse updated = ruleService.update(new RuleUpdateRequest(created.id(), "新名", null, null));

        assertThat(updated.name()).isEqualTo("新名");
        assertThat(updated.description()).isEqualTo("原描述");
        assertThat(updated.scriptContent()).isEqualTo(originalScript);
    }

    @Test
    void 更新脚本内容会落库() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("脚本更新", null));
        RuleDetailResponse updated = ruleService.update(
                new RuleUpdateRequest(created.id(), null, null, "return '改过了'"));
        assertThat(updated.scriptContent()).isEqualTo("return '改过了'");
    }

    @Test
    void 详情不存在时给出中文提示() {
        assertThatThrownBy(() -> ruleService.detail(999999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规则不存在");
    }

    @Test
    void 删除规则会连带删除会话() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("待删除", null));
        ruleService.delete(created.id());

        assertThatThrownBy(() -> ruleService.detail(created.id()))
                .isInstanceOf(BizException.class);
        assertThat(conversationRepository.findByRuleId(created.id())).isEmpty();
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：编译失败，`RuleService` 找不到。

- [ ] **Step 4: 写 `RuleService`（本任务只实现 CRUD 部分）**

```java
package com.xd.rulescript.service;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleItem;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.entity.Rule;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import com.xd.rulescript.repository.RuleRepository;
import com.xd.rulescript.repository.TestCaseRepository;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则管理。负责 CRUD、校验编排、运行编排。
 * 校验与运行方法在任务 12 补上（依赖 GroovyEngineService）。
 */
@Service
public class RuleService {

    /** 对外统一的时间格式，前端直接展示，不再做时区/格式转换 */
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final RuleRepository ruleRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final TestCaseRepository testCaseRepository;

    public RuleService(RuleRepository ruleRepository,
                       ConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       TestCaseRepository testCaseRepository) {
        this.ruleRepository = ruleRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.testCaseRepository = testCaseRepository;
    }

    public RulePageResponse list(RuleListRequest request) {
        int page = request.page() == null || request.page() < 1 ? 1 : request.page();
        int size = request.size() == null || request.size() < 1 ? DEFAULT_PAGE_SIZE : Math.min(request.size(), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));

        String keyword = request.name() == null ? "" : request.name().trim();
        Page<Rule> result = keyword.isEmpty()
                ? ruleRepository.findAll(pageable)
                : ruleRepository.findByNameContaining(keyword, pageable);

        List<RuleItem> items = result.getContent().stream().map(RuleService::toItem).toList();
        return new RulePageResponse(items, result.getTotalElements(), page, size);
    }

    /** 新建规则：同时自动生成一对一的会话（需求 4.3.4：规则创建时自动建立会话） */
    @Transactional
    public RuleDetailResponse create(RuleCreateRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException("规则名称不能为空");
        }
        Rule rule = new Rule();
        rule.setName(name);
        rule.setDescription(request.description() == null ? "" : request.description().trim());
        rule.setScriptContent(defaultScript(name));
        rule = ruleRepository.save(rule);

        Conversation conversation = new Conversation();
        conversation.setRuleId(rule.getId());
        conversation.setTitle(rule.getName());
        conversationRepository.save(conversation);

        return toDetail(rule);
    }

    public RuleDetailResponse detail(Long ruleId) {
        return toDetail(requireRule(ruleId));
    }

    /** 部分更新：字段为 null 表示不改 */
    @Transactional
    public RuleDetailResponse update(RuleUpdateRequest request) {
        Rule rule = requireRule(request.ruleId());
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BizException("规则名称不能为空");
            }
            rule.setName(name);
        }
        if (request.description() != null) {
            rule.setDescription(request.description().trim());
        }
        if (request.scriptContent() != null) {
            rule.setScriptContent(request.scriptContent());
        }
        return toDetail(ruleRepository.save(rule));
    }

    /** 删除规则：级联清掉它的会话、消息、测试用例（需求 4.2） */
    @Transactional
    public void delete(Long ruleId) {
        Rule rule = requireRule(ruleId);
        conversationRepository.findByRuleId(ruleId).ifPresent(conversation -> {
            messageRepository.deleteByConversationId(conversation.getId());
            conversationRepository.delete(conversation);
        });
        testCaseRepository.deleteByRuleId(ruleId);
        ruleRepository.delete(rule);
    }

    private Rule requireRule(Long ruleId) {
        if (ruleId == null) {
            throw new BizException("规则 ID 不能为空");
        }
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BizException("规则不存在或已被删除"));
    }

    private static RuleItem toItem(Rule rule) {
        return new RuleItem(rule.getId(), rule.getName(), rule.getDescription(),
                rule.getUpdatedAt() == null ? "" : TIMESTAMP.format(rule.getUpdatedAt()));
    }

    private static RuleDetailResponse toDetail(Rule rule) {
        return new RuleDetailResponse(rule.getId(), rule.getName(), rule.getDescription(),
                rule.getScriptContent(),
                rule.getUpdatedAt() == null ? "" : TIMESTAMP.format(rule.getUpdatedAt()));
    }

    /** 新规则的初始脚本，与 UI 参考稿里的示例一致，用户建完就能直接点校验、填值、运行 */
    static String defaultScript(String ruleName) {
        return """
                // 规则：%s
                // ${变量名} 是占位符，运行前需要在下方填值
                int age = ${age}
                String level = "${level}"
                if (level == "vip" && age >= 18) {
                    return "享受8折优惠"
                }
                return "无折扣"
                """.formatted(ruleName);
    }
}
```

**注意 `defaultScript` 里的文本块**：Java 文本块中的 `${age}` 不是 Java 语法，会原样保留；但 `%s` 会被 `.formatted(ruleName)` 替换。脚本里如果出现 `%` 需要写成 `%%`——当前模板没有，不用管。

- [ ] **Step 5: 写 `RuleController`（本任务只挂 CRUD 五个接口）**

```java
package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleIdRequest;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.service.RuleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则接口。全部 POST，路径 /api/rule/<动作>。
 * validate 与 run 在任务 12 补上。
 */
@RestController
@RequestMapping("/api/rule")
public class RuleController {

    private final RuleService ruleService;

    public RuleController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @PostMapping("/list")
    public ApiResponse<RulePageResponse> list(@RequestBody RuleListRequest request) {
        return ApiResponse.ok(ruleService.list(request));
    }

    @PostMapping("/create")
    public ApiResponse<RuleDetailResponse> create(@Valid @RequestBody RuleCreateRequest request) {
        return ApiResponse.ok(ruleService.create(request));
    }

    @PostMapping("/detail")
    public ApiResponse<RuleDetailResponse> detail(@Valid @RequestBody RuleIdRequest request) {
        return ApiResponse.ok(ruleService.detail(request.ruleId()));
    }

    @PostMapping("/update")
    public ApiResponse<RuleDetailResponse> update(@Valid @RequestBody RuleUpdateRequest request) {
        return ApiResponse.ok(ruleService.update(request));
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody RuleIdRequest request) {
        ruleService.delete(request.ruleId());
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 14 个测试通过（6 + 8）。

- [ ] **Step 7: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 规则 CRUD 后端接口与全部 DTO 定义"
```

---

## Task 6: 规则列表页前端

对应需求文档 4.2。列表 + 模糊搜索 + 分页 + 新建（创建后跳工作台）+ 改名改描述 + 删除二次确认。

**Files:**
- Create: `script_front/src/api/__tests__/rule.spec.js`
- Create: `script_front/src/api/rule.js`
- Modify: `script_front/src/views/RuleListView.vue`（整体替掉任务 3 的占位骨架）

**Interfaces:**
- Consumes: `post` / `reportError`（任务 3）；`/api/rule/list|create|detail|update|delete`（任务 5）；CSS 变量（任务 3）
- Produces（`src/api/rule.js`，任务 12/13/20 会继续用到）:
  - `listRules(name, page, size)` → `Promise<{items, total, page, size}>`
  - `createRule(name, description)` → `Promise<RuleDetail>`
  - `getRuleDetail(ruleId)` → `Promise<{id, name, description, scriptContent, updatedAt}>`
  - `updateRule({ruleId, name, description, scriptContent})` → `Promise<RuleDetail>`（为 **undefined** 的字段不出现在请求体里，后端保持原值；空串是**合法值**会照发，用于清空脚本或描述）
  - `deleteRule(ruleId)` → `Promise<null>`

- [ ] **Step 1: 写 `api/rule.js` 的失败测试**

`rule.js` 不是薄封装：`listRules` 有 `name || null` 归一，`updateRule` 用三个 `if (x !== undefined)` 实现「部分更新」。按全局约束「测试与 TDD」段的策略，它属于「有分支逻辑的纯 JS 模块」，必须写单测。

最值钱的边界是 `scriptContent: ''` —— 用户清空脚本时空串是合法值、必须发出去；一旦写成 `if (scriptContent)` 就会静默丢掉这个操作，而这种 bug 手工验收几乎撞不到。

沿用 `http.spec.js` 的手法：**不 mock `./http`，而是 stub 全局 fetch**，因为要断言的正是「真正发出去的请求体长什么样」。`rule.js` 间接 import 了 element-plus，所以同样要把它换掉。

`src/api/__tests__/rule.spec.js`

```js
import { describe, it, expect, vi, afterEach } from 'vitest'

// rule.js → http.js 顶层 import 了 element-plus，测试里换成空实现，避开样式与 DOM 依赖
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), success: vi.fn() } }))

const { listRules, createRule, getRuleDetail, updateRule, deleteRule } = await import('../rule.js')

function stubFetch(payload = { code: 0, message: 'success', data: null }) {
  return vi.fn(async () => ({ ok: true, status: 200, json: async () => payload }))
}

// 取出真正发出去的请求体（JSON 序列化后的结果，所以 undefined 的键会消失）
function sentBody(fetchMock) {
  return JSON.parse(fetchMock.mock.calls[0][1].body)
}

describe('rule.js 组装请求体', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('listRules 原样传出有值的搜索词与分页参数', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await listRules('VIP', 2, 10)
    expect(f.mock.calls[0][0]).toBe('/api/rule/list')
    expect(sentBody(f)).toEqual({ name: 'VIP', page: 2, size: 10 })
  })

  it('listRules 把空串搜索词归一成 null，而不是发空串给后端做模糊匹配', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await listRules('', 1, 10)
    expect(sentBody(f).name).toBeNull()
  })

  it('listRules 把 undefined 搜索词也归一成 null', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await listRules(undefined, 1, 10)
    expect(sentBody(f).name).toBeNull()
  })

  it('createRule 透传名称与描述', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await createRule('VIP 客户折扣判定', '按年龄与会员等级判定')
    expect(f.mock.calls[0][0]).toBe('/api/rule/create')
    expect(sentBody(f)).toEqual({ name: 'VIP 客户折扣判定', description: '按年龄与会员等级判定' })
  })

  it('getRuleDetail 透传 ruleId', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await getRuleDetail(7)
    expect(f.mock.calls[0][0]).toBe('/api/rule/detail')
    expect(sentBody(f)).toEqual({ ruleId: 7 })
  })

  it('updateRule 四个字段都给了就都发出去', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await updateRule({ ruleId: 7, name: 'n', description: 'd', scriptContent: 'return 1' })
    expect(sentBody(f)).toEqual({ ruleId: 7, name: 'n', description: 'd', scriptContent: 'return 1' })
  })

  it('updateRule 只改名称时，请求体里根本不出现 description / scriptContent 这两个键', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await updateRule({ ruleId: 7, name: '新名字' })
    const body = sentBody(f)
    expect(body).toEqual({ ruleId: 7, name: '新名字' })
    // 要求是「键不存在」而不是「值为 null」：后端对 null 与缺字段的处理不一定相同，
    // 部分更新的契约是不发这个键
    expect('scriptContent' in body).toBe(false)
    expect('description' in body).toBe(false)
  })

  it('updateRule 把空串脚本内容当合法值发出去（清空脚本不能静默失效）', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await updateRule({ ruleId: 7, scriptContent: '' })
    expect(sentBody(f)).toEqual({ ruleId: 7, scriptContent: '' })
  })

  it('deleteRule 透传 ruleId', async () => {
    const f = stubFetch()
    vi.stubGlobal('fetch', f)
    await deleteRule(3)
    expect(f.mock.calls[0][0]).toBe('/api/rule/delete')
    expect(sentBody(f)).toEqual({ ruleId: 3 })
  })
})
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：`rule.spec.js` **整个文件收集失败**，报 `Cannot find module '../rule.js'`（顶层的 `await import` 抛错，9 条用例一条也跑不起来）；`http.spec.js` 的 6 条仍然全绿。**这是预期的红**，不要去改测试断言、也不要先建个空的 `rule.js` 糊弄过去。

- [ ] **Step 3: 写 `src/api/rule.js`**

```js
// 规则相关接口。校验（validateScript）与运行（runScript）在任务 12 补上
import { post } from './http'

export function listRules(name, page, size) {
  return post('/api/rule/list', { name: name || null, page, size })
}

export function createRule(name, description) {
  return post('/api/rule/create', { name, description })
}

export function getRuleDetail(ruleId) {
  return post('/api/rule/detail', { ruleId })
}

/**
 * 部分更新：只把非 undefined 的字段发出去，后端对 null 字段保持原值
 */
export function updateRule({ ruleId, name, description, scriptContent }) {
  const body = { ruleId }
  if (name !== undefined) body.name = name
  if (description !== undefined) body.description = description
  if (scriptContent !== undefined) body.scriptContent = scriptContent
  return post('/api/rule/update', body)
}

export function deleteRule(ruleId) {
  return post('/api/rule/delete', { ruleId })
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：**15 个前端单测全绿**（任务 3 的 `http.spec.js` 6 条 + 本任务 `rule.spec.js` 9 条）。

- [ ] **Step 5: 整体替掉 `src/views/RuleListView.vue`**

```vue
<template>
  <div class="page">
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="tag">规则列表</div>
      <div class="spacer"></div>
      <el-button class="topbar-btn" round @click="openCreate">新建规则</el-button>
    </div>

    <div class="body">
      <div class="panel">
        <div class="panel-title">共 {{ total }} 条规则</div>

        <div class="toolbar">
          <el-input
            v-model="keyword"
            class="search"
            placeholder="按规则名称搜索"
            clearable
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" round @click="search">搜索</el-button>
        </div>

        <el-table :data="items" v-loading="loading" empty-text="还没有规则，点右上角新建一个">
          <el-table-column prop="name" label="规则名称" min-width="180" show-overflow-tooltip />
          <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="180" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openWorkbench(row)">打开</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑信息</el-button>
              <el-button link type="danger" @click="confirmDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next, total"
            :total="total"
            :page-size="size"
            :current-page="page"
            @current-change="onPageChange"
          />
        </div>
      </div>
    </div>

    <!-- 新建 / 编辑信息共用一个弹窗：列表页只改名称和描述，改脚本要进工作台 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑规则信息' : '新建规则'" width="480px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="60" placeholder="例如：VIP 客户折扣判定" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" placeholder="一句话说明这条规则干什么" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createRule, deleteRule, listRules, updateRule } from '@/api/rule'
import { reportError } from '@/api/http'

const router = useRouter()

const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

const dialogVisible = ref(false)
const submitting = ref(false)
const editingId = ref(null)
const form = reactive({ name: '', description: '' })

async function load() {
  loading.value = true
  try {
    const data = await listRules(keyword.value.trim(), page.value, size.value)
    items.value = data.items
    total.value = data.total
  } catch (error) {
    reportError(error)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function onPageChange(next) {
  page.value = next
  load()
}

function openCreate() {
  editingId.value = null
  form.name = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  dialogVisible.value = true
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写规则名称')
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      await updateRule({ ruleId: editingId.value, name: form.name.trim(), description: form.description.trim() })
      ElMessage.success('已保存')
      dialogVisible.value = false
      await load()
    } else {
      // 需求 4.2：创建后直接跳转工作台
      const created = await createRule(form.name.trim(), form.description.trim())
      dialogVisible.value = false
      router.push({ name: 'rule-workbench', params: { id: created.id } })
    }
  } catch (error) {
    reportError(error)
  } finally {
    submitting.value = false
  }
}

function openWorkbench(row) {
  router.push({ name: 'rule-workbench', params: { id: row.id } })
}

async function confirmDelete(row) {
  // 需求交互规则 #6：删除必须二次确认，且要告知会连带删掉会话与用例
  try {
    await ElMessageBox.confirm(
      `删除规则「${row.name}」后，它的 AI 对话历史与测试用例会一并清除，且无法恢复。确定删除？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' }
    )
  } catch (cancelled) {
    return
  }
  try {
    await deleteRule(row.id)
    ElMessage.success('已删除')
    if (items.value.length === 1 && page.value > 1) page.value -= 1
    await load()
  } catch (error) {
    reportError(error)
  }
}

onMounted(load)
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.body { flex: 1; padding: 20px; overflow: auto; }
.toolbar { display: flex; gap: 10px; padding: 16px 20px; align-items: center; }
.search { width: 280px; }
.pager { display: flex; justify-content: flex-end; padding: 14px 20px; }
.topbar-btn { background: rgba(255, 255, 255, .92); border: none; color: var(--brand-from); font-weight: 500; }
</style>
```

- [ ] **Step 6: 构建验证**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：单测 **15 个**通过（任务 3 的 6 + 本任务的 9），build 成功。`RuleListView.vue` 是纯装配型组件，按任务 13 Step 5 说清的取舍不写组件单测，靠本步构建 + 下一步手工验收把关。

- [ ] **Step 7: 浏览器手工验收**

两个终端分别起后端与前端：

```bash
# 终端 A
cd /Users/zhoudingyan/workspace/zdy_test/script_back && source ../tools/env.sh && mvn -q spring-boot:run
# 终端 B
cd /Users/zhoudingyan/workspace/zdy_test/script_front && source ../tools/env.sh && npm run dev
```

打开 `http://localhost:5173/`，逐项确认：

- [ ] 渐变顶栏（#5b5fc7 → #8b5cf6）+ 白色圆角面板 + 柔和阴影，与 `docs/ui-mockups/style3-gradient.html` 观感一致
- [ ] 点「新建规则」，不填名称直接确定 → 弹「请填写规则名称」
- [ ] 填名称「VIP 客户折扣判定」+ 描述，确定 → 自动跳到 `/rule/<新id>`（工作台目前是占位页，能看到规则 ID 就算对）
- [ ] 回列表页，能看到刚建的规则，更新时间是刚刚
- [ ] 建 12 条以上规则 → 分页出现，翻页正常
- [ ] 搜索框输「VIP」回车 → 只留匹配的；清空 → 全部回来
- [ ] 点「编辑信息」改名称与描述 → 列表刷新后生效
- [ ] 点「删除」→ 出现二次确认弹窗且文案提到会连带清除对话与用例；取消 → 不删；确定 → 消失
- [ ] 把后端停掉再刷新页面 → 弹「无法连接后端服务，请确认 script_back 已在 8080 端口启动」，页面不白屏

验收完两个终端都 `Ctrl+C` 停掉。

- [ ] **Step 8: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_front
git commit -m "feat: 规则列表页（搜索/分页/新建/改信息/删除二次确认）"
```

---

## Task 7: GroovyLexScanner（字符级区域扫描）

这是占位符替换的地基。为什么需要它：参考稿的示例脚本里 `int age = ${age}`（代码区）和 `String level = "${level}"`（双引号串内）同时存在，两种位置的替换策略必须不同，否则会把值多包一层引号。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/groovy/GroovyLexScanner.java`
- Test: `script_back/src/test/java/com/xd/rulescript/groovy/GroovyLexScannerTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum GroovyLexScanner.Region { CODE, SINGLE_QUOTED, DOUBLE_QUOTED }`
  - `static GroovyLexScanner.Region[] GroovyLexScanner.scan(String script)` —— 返回与 `script` **等长**的数组，下标 i 表示 `script.charAt(i)` 所处区域。字符串区域**包含引号字符本身**。

设计约定（故意从简，写进注释里让后人知道边界）：
- 注释区（`//`、`/* */`）归为 `CODE`。注释里的 `${x}` 照样会被当占位符提取，但替换后仍在注释里，不影响编译。
- 不识别 slashy 字符串 `/.../`（Groovy 正则字面量）。规则脚本用不到，归为 `CODE` 无害。
- 未闭合的字符串扫到行尾就停，不报错（后面语法校验会给出行号）。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/xd/rulescript/groovy/GroovyLexScannerTest.java`

```java
package com.xd.rulescript.groovy;

import static com.xd.rulescript.groovy.GroovyLexScanner.Region.CODE;
import static com.xd.rulescript.groovy.GroovyLexScanner.Region.DOUBLE_QUOTED;
import static com.xd.rulescript.groovy.GroovyLexScanner.Region.SINGLE_QUOTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.groovy.GroovyLexScanner.Region;
import org.junit.jupiter.api.Test;

/**
 * 区域扫描是占位符替换的依据，这里的每个用例都对应一种真实脚本写法。
 */
class GroovyLexScannerTest {

    /** 返回子串第一个字符所属的区域，方便断言 */
    private static Region regionAt(String script, String needle) {
        int index = script.indexOf(needle);
        assertThat(index).as("脚本里应该能找到 %s", needle).isGreaterThanOrEqualTo(0);
        return GroovyLexScanner.scan(script)[index];
    }

    @Test
    void 返回数组与脚本等长() {
        String script = "int age = ${age}";
        assertThat(GroovyLexScanner.scan(script)).hasSize(script.length());
    }

    @Test
    void 空脚本不报错() {
        assertThat(GroovyLexScanner.scan("")).isEmpty();
    }

    @Test
    void 代码区的占位符归为CODE() {
        assertThat(regionAt("int age = ${age}", "${age}")).isEqualTo(CODE);
    }

    @Test
    void 双引号串内的占位符归为DOUBLE_QUOTED() {
        String script = "String level = \"${level}\"";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
        // 引号字符本身也算串内
        assertThat(regionAt(script, "\"${")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 双引号串内嵌入文字时依然归为DOUBLE_QUOTED() {
        assertThat(regionAt("return \"等级：${level}，年龄：${age}\"", "${level}")).isEqualTo(DOUBLE_QUOTED);
        assertThat(regionAt("return \"等级：${level}，年龄：${age}\"", "${age}")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 单引号串内归为SINGLE_QUOTED() {
        assertThat(regionAt("def s = '等级${level}'", "${level}")).isEqualTo(SINGLE_QUOTED);
    }

    @Test
    void 三引号字符串整体标记() {
        String script = "def s = \"\"\"\n多行 ${level}\n\"\"\"";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 字符串结束后回到CODE() {
        String script = "String level = \"${level}\"\nint age = ${age}";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
        assertThat(regionAt(script, "${age}")).isEqualTo(CODE);
    }

    @Test
    void 转义引号不会提前结束字符串() {
        // \" 是串内的转义引号，后面的 ${level} 仍在串内
        String script = "def s = \"他说\\\"你好\\\" ${level}\"";
        assertThat(regionAt(script, "${level}")).isEqualTo(DOUBLE_QUOTED);
    }

    @Test
    void 行注释里的内容归为CODE() {
        // 注释不当字符串处理：里面的 ${x} 替换后仍在注释里，无害
        assertThat(regionAt("// 说明 ${note}\nreturn 1", "${note}")).isEqualTo(CODE);
    }

    @Test
    void 块注释里的内容归为CODE() {
        assertThat(regionAt("/* 说明 ${note} */\nreturn 1", "${note}")).isEqualTo(CODE);
    }

    @Test
    void 块注释里的引号不会误开字符串() {
        // 如果把注释里的 " 当成字符串开头，后面的 ${age} 就会被误判为串内
        String script = "/* 不要写 \"xxx\" */\nint age = ${age}";
        assertThat(regionAt(script, "${age}")).isEqualTo(CODE);
    }

    @Test
    void 未闭合字符串扫到行尾不越界() {
        String script = "def s = \"没有闭合\ndef t = ${age}";
        Region[] regions = GroovyLexScanner.scan(script);
        assertThat(regions).hasSize(script.length());
        // 换行终止了未闭合的串，下一行回到代码区
        assertThat(regions[script.indexOf("${age}")]).isEqualTo(CODE);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：编译失败，`GroovyLexScanner` 找不到。

- [ ] **Step 3: 写 `GroovyLexScanner`**

```java
package com.xd.rulescript.groovy;

import java.util.Arrays;

/**
 * 扫描 Groovy 脚本，标记每个字符所处的区域。
 *
 * <p>只区分三类：代码区、单引号字符串内、双引号字符串内。占位符替换需要按区域
 * 采用不同策略（代码区补引号、双引号串内不能补引号），因此这个扫描结果是替换逻辑的唯一依据。
 *
 * <p>故意从简的边界（都是安全方向）：
 * <ul>
 *   <li>注释区（// 与 /* *\/）归为 CODE：注释里的 ${x} 替换后仍在注释里，不影响编译</li>
 *   <li>不识别 slashy 字符串 /.../（Groovy 正则字面量），规则脚本用不到</li>
 *   <li>未闭合的字符串扫到行尾就停，不报错（语法校验会给出行号）</li>
 * </ul>
 */
public final class GroovyLexScanner {

    /** 字符所处区域 */
    public enum Region {
        /** 代码区（含注释区） */
        CODE,
        /** 单引号字符串内，'...' 或 '''...''' */
        SINGLE_QUOTED,
        /** 双引号字符串内，"..." 或 \"\"\"...\"\"\"；只有这里面的 ${x} 是 GString 插值 */
        DOUBLE_QUOTED
    }

    private GroovyLexScanner() {
    }

    /**
     * @param script 原始脚本（含 ${占位符}，此时还不是合法 Groovy）
     * @return 与 script 等长的区域数组
     */
    public static Region[] scan(String script) {
        if (script == null || script.isEmpty()) {
            return new Region[0];
        }
        int length = script.length();
        Region[] regions = new Region[length];
        Arrays.fill(regions, Region.CODE);

        int i = 0;
        while (i < length) {
            char c = script.charAt(i);

            // 行注释：扫到行尾，全程保持 CODE
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '/') {
                while (i < length && script.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // 块注释：扫到 */，全程保持 CODE
            if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                int end = i + 2;
                while (end + 1 < length && !(script.charAt(end) == '*' && script.charAt(end + 1) == '/')) {
                    end++;
                }
                i = Math.min(length, end + 2);
                continue;
            }

            // 三引号字符串（''' 或 \"\"\"）
            if ((c == '\'' || c == '"') && i + 2 < length
                    && script.charAt(i + 1) == c && script.charAt(i + 2) == c) {
                Region region = (c == '"') ? Region.DOUBLE_QUOTED : Region.SINGLE_QUOTED;
                int end = findTripleQuoteEnd(script, i + 3, c);
                Arrays.fill(regions, i, end, region);
                i = end;
                continue;
            }

            // 单行字符串
            if (c == '\'' || c == '"') {
                Region region = (c == '"') ? Region.DOUBLE_QUOTED : Region.SINGLE_QUOTED;
                int end = findQuoteEnd(script, i + 1, c);
                Arrays.fill(regions, i, end, region);
                i = end;
                continue;
            }

            i++;
        }
        return regions;
    }

    /** 找单行字符串的结束位置（返回值含结尾引号）；遇换行或末尾就停 */
    private static int findQuoteEnd(String script, int from, char quote) {
        int length = script.length();
        int j = from;
        while (j < length) {
            char c = script.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) {
                return Math.min(length, j + 1);
            }
            if (c == '\n') {
                return j;
            }
            j++;
        }
        return length;
    }

    /** 找三引号字符串的结束位置（返回值含结尾的三个引号） */
    private static int findTripleQuoteEnd(String script, int from, char quote) {
        int length = script.length();
        int j = from;
        while (j < length) {
            char c = script.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote && j + 2 < length && script.charAt(j + 1) == quote && script.charAt(j + 2) == quote) {
                return j + 3;
            }
            j++;
        }
        return length;
    }
}
```

**写的时候注意两个转义坑：**
1. 上面代码里的 `\"\"\"` 只存在于 **javadoc 注释与 enum 注释**中（javadoc 里写三个连续双引号会被 IDE/工具误处理，所以转义）。
2. `/** 双引号字符串内，"..." 或 \"\"\"...\"\"\"；... */` 这一行如果写成未转义的三个双引号，**不会**影响 javac 编译（注释内容不参与词法分析），但为了可读性与工具链友好，保持转义写法。
3. 块注释结束符在 javadoc 里必须写成 `*\/`（代码中已如此），否则注释会提前结束。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 28 个测试通过（14 + 14）。

- [ ] **Step 5: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: Groovy 词法区域扫描器（区分代码区/单引号串/双引号串）"
```

---

## Task 8: PlaceholderSubstitutor（按区域分派的占位符替换）

任务 7 的区域扫描在这里第一次派上用场。核心问题：同一个 `${level}`，写在代码区和写在字符串里，替换出来的东西**必须不一样**。

```groovy
int age = ${age}              // 代码区：要换成 28（裸字面量）
String level = "${level}"     // 双引号串内：要换成 vip（不能再套一层引号）
def tag = '等级${level}'       // 单引号串内：要换成 vip，且 $ 不能转义
```

若一律按「字符串就加引号」处理，第二行会变成 `String level = "'vip'"`，运行结果多出一对引号。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/groovy/PlaceholderValueException.java`
- Create: `script_back/src/main/java/com/xd/rulescript/groovy/PlaceholderSubstitutor.java`
- Test: `script_back/src/test/java/com/xd/rulescript/groovy/PlaceholderSubstitutorTest.java`

**Interfaces:**
- Consumes: `GroovyLexScanner.scan` / `Region`（任务 7）
- Produces:
  - `PlaceholderValueException extends RuntimeException`，构造 `PlaceholderValueException(String message)`；`groovy` 包不依赖 Spring，所以用普通运行时异常承载中文错误信息，由 `GroovyEngineService`（任务 11）翻译成 `RunResult`
  - `static final String PlaceholderSubstitutor.IDENT_PREFIX = "__ph_"`
  - `static List<String> extractNames(String script)` —— 按出现顺序去重返回全部占位符名
  - `static String toIdentifiers(String script)` —— `${age}` → `__ph_age`，**所有区域一视同仁**（只给 AST 看，不参与编译执行）
  - `static String toFakeValues(String script, Map<String,String> types)` —— 语法校验用，按类型填假值
  - `static String toLiterals(String script, Map<String,String> params, Map<String,String> types)` —— 沙箱运行用，填真实值；缺值或类型不合法抛 `PlaceholderValueException`（中文提示）

替换策略表（`type` 只可能是 `int`/`long`/`double`/`boolean`/`String`，由任务 9 归一）：

| type | 代码区字面量 | 串内裸值 |
|---|---|---|
| `int` | `0` / 真实值原样 | 同左 |
| `long` | `0L` / 真实值 + `L` | 不带 `L`（串里只是文本） |
| `double` | `0.0d` / 真实值 + `d` | 不带 `d` |
| `boolean` | `false` / `true`、`false` | 同左 |
| `String` | `""` / `"转义后的值"` | 转义后的值，**不加引号** |

转义规则按区域分开，这是本任务最容易写错的地方：

- **代码区 String 字面量**与**双引号串内**（两者都是 Groovy 双引号字符串语境）：`\` → `\\`、`"` → `\"`、`$` → `\$`（防 GString 二次插值）、换行 → `\n`、回车 → `\r`、制表 → `\t`
- **单引号串内**：`\` → `\\`、`'` → `\'`、换行/回车/制表同上；**`$` 不转义**（Groovy 单引号串不做插值，写 `\$` 反而会多出一个反斜杠）

- [ ] **Step 1: 写失败测试**

`src/test/java/com/xd/rulescript/groovy/PlaceholderSubstitutorTest.java`

```java
package com.xd.rulescript.groovy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 每个用例对应一种真实脚本写法，重点盯住「代码区 vs 串内」的差异与转义。
 */
class PlaceholderSubstitutorTest {

    private static Map<String, String> types(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, String> params(String... kv) {
        return types(kv);
    }

    // ---------- extractNames ----------

    @Test
    void 提取占位符按出现顺序去重() {
        String script = "int age = ${age}\nString level = \"${level}\"\nreturn ${age} + ${level}";
        assertThat(PlaceholderSubstitutor.extractNames(script)).containsExactly("age", "level");
    }

    @Test
    void 无占位符返回空列表() {
        assertThat(PlaceholderSubstitutor.extractNames("return 1 + 1")).isEmpty();
    }

    @Test
    void 空脚本返回空列表() {
        assertThat(PlaceholderSubstitutor.extractNames("")).isEmpty();
    }

    @Test
    void 不完整的占位符语法不提取() {
        // 变量名必须是 \w+，${}、${a-b}、$age 都不算
        assertThat(PlaceholderSubstitutor.extractNames("return \"${}\" + \"$age\" + \"${a-b}\"")).isEmpty();
    }

    @Test
    void 注释里的占位符照样提取() {
        // 设计约定：注释区归 CODE，提取出来无害（替换后仍在注释里，不影响编译）
        assertThat(PlaceholderSubstitutor.extractNames("// 用到 ${age}\nreturn 1")).containsExactly("age");
    }

    // ---------- toIdentifiers ----------

    @Test
    void 转成假标识符供AST使用() {
        assertThat(PlaceholderSubstitutor.toIdentifiers("int age = ${age}"))
                .isEqualTo("int age = __ph_age");
    }

    @Test
    void 串内的占位符也转成标识符且不破坏引号() {
        assertThat(PlaceholderSubstitutor.toIdentifiers("String level = \"${level}\""))
                .isEqualTo("String level = \"__ph_level\"");
    }

    // ---------- toFakeValues（语法校验用）----------

    @Test
    void 代码区假值按类型填充() {
        String script = "int a = ${age}\nlong b = ${ts}\ndouble c = ${rate}\nboolean d = ${vip}\nString e = ${name}";
        Map<String, String> t = types("age", "int", "ts", "long", "rate", "double", "vip", "boolean", "name", "String");
        assertThat(PlaceholderSubstitutor.toFakeValues(script, t))
                .isEqualTo("int a = 0\nlong b = 0L\ndouble c = 0.0d\nboolean d = false\nString e = \"\"");
    }

    @Test
    void 双引号串内假值不再套引号() {
        assertThat(PlaceholderSubstitutor.toFakeValues("String level = \"${level}\"", types("level", "String")))
                .isEqualTo("String level = \"\"");
    }

    @Test
    void 串内嵌入文字时假值直接拼接() {
        assertThat(PlaceholderSubstitutor.toFakeValues("return \"等级：${level}\"", types("level", "String")))
                .isEqualTo("return \"等级：\"");
    }

    @Test
    void 类型缺失时按String处理() {
        assertThat(PlaceholderSubstitutor.toFakeValues("def x = ${unknown}", Map.of()))
                .isEqualTo("def x = \"\"");
    }

    // ---------- toLiterals（运行用）----------

    @Test
    void 代码区数字直接落字面量() {
        assertThat(PlaceholderSubstitutor.toLiterals("return ${age} * 2", params("age", "28"), types("age", "int")))
                .isEqualTo("return 28 * 2");
    }

    @Test
    void long补L后缀double补d后缀() {
        String script = "return ${ts} + ${rate}";
        assertThat(PlaceholderSubstitutor.toLiterals(script,
                params("ts", "1700000000", "rate", "1.5"),
                types("ts", "long", "rate", "double")))
                .isEqualTo("return 1700000000L + 1.5d");
    }

    @Test
    void 串内值不加引号() {
        assertThat(PlaceholderSubstitutor.toLiterals("String level = \"${level}\"",
                params("level", "vip"), types("level", "String")))
                .isEqualTo("String level = \"vip\"");
    }

    @Test
    void 代码区String值要加引号() {
        assertThat(PlaceholderSubstitutor.toLiterals("String level = ${level}",
                params("level", "vip"), types("level", "String")))
                .isEqualTo("String level = \"vip\"");
    }

    @Test
    void 串内值含双引号要转义() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"他说：${msg}\"",
                params("msg", "\"你好\""), types("msg", "String")))
                .isEqualTo("return \"他说：\\\"你好\\\"\"");
    }

    @Test
    void 串内值含美元符号要转义防止二次插值() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"价格：${price}\"",
                params("price", "$100"), types("price", "String")))
                .isEqualTo("return \"价格：\\$100\"");
    }

    @Test
    void 单引号串内美元符号不转义() {
        // Groovy 单引号串不做插值，写成 \$ 反而会多出一个反斜杠
        assertThat(PlaceholderSubstitutor.toLiterals("return '价格：${price}'",
                params("price", "$100"), types("price", "String")))
                .isEqualTo("return '价格：$100'");
    }

    @Test
    void 单引号串内单引号要转义() {
        assertThat(PlaceholderSubstitutor.toLiterals("return '它是${what}'",
                params("what", "a'b"), types("what", "String")))
                .isEqualTo("return '它是a\\'b'");
    }

    @Test
    void 值里的换行转成转义序列() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"${msg}\"",
                params("msg", "第一行\n第二行"), types("msg", "String")))
                .isEqualTo("return \"第一行\\n第二行\"");
    }

    @Test
    void 负数与小数正常通过() {
        assertThat(PlaceholderSubstitutor.toLiterals("return ${a} + ${b}",
                params("a", "-5", "b", "-0.25"), types("a", "int", "b", "double")))
                .isEqualTo("return -5 + -0.25d");
    }

    // ---------- 错误路径 ----------

    @Test
    void 缺少填值抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${age}",
                Map.of(), types("age", "int")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("age")
                .hasMessageContaining("缺少");
    }

    @Test
    void int填入非数字抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${age}",
                params("age", "abc"), types("age", "int")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("age")
                .hasMessageContaining("整数");
    }

    @Test
    void double填入非数字抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${rate}",
                params("rate", "1.2.3"), types("rate", "double")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("小数");
    }

    @Test
    void boolean填入非布尔抛中文异常() {
        assertThatThrownBy(() -> PlaceholderSubstitutor.toLiterals("return ${vip}",
                params("vip", "yes"), types("vip", "boolean")))
                .isInstanceOf(PlaceholderValueException.class)
                .hasMessageContaining("true")
                .hasMessageContaining("false");
    }

    @Test
    void 填值为空串对String合法() {
        assertThat(PlaceholderSubstitutor.toLiterals("return \"${msg}\"",
                params("msg", ""), types("msg", "String")))
                .isEqualTo("return \"\"");
    }

    @Test
    void 多余的填值被忽略() {
        assertThat(PlaceholderSubstitutor.toLiterals("return ${age}",
                params("age", "28", "extra", "x"), types("age", "int")))
                .isEqualTo("return 28");
    }
}
```

跑一次确认全红（此时 `PlaceholderSubstitutor` 还不存在，应当是**编译失败**）：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test 2>&1 | tail -20
```

- [ ] **Step 2: 写 `PlaceholderValueException`**

`src/main/java/com/xd/rulescript/groovy/PlaceholderValueException.java`

```java
package com.xd.rulescript.groovy;

/**
 * 占位符填值不合法（缺值、类型对不上）时抛出。
 *
 * <p>groovy 包保持零 Spring 依赖，所以这里用普通运行时异常承载中文提示，
 * 由 GroovyEngineService 统一翻译成接口响应，不让堆栈漏到前端。
 */
public class PlaceholderValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlaceholderValueException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: 写 `PlaceholderSubstitutor`**

`src/main/java/com/xd/rulescript/groovy/PlaceholderSubstitutor.java`

```java
package com.xd.rulescript.groovy;

import com.xd.rulescript.groovy.GroovyLexScanner.Region;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 占位符 ${变量名} 的提取与替换。
 *
 * <p>替换必须区分「代码区」和「字符串区」：同一个 ${level}，写在 int age = ${age} 里要换成
 * 裸字面量，写在 "${level}" 里要换成裸文本（外面已经有引号了，再套一层就错了）。
 * 区域由 {@link GroovyLexScanner} 给出。
 *
 * <p>本类无状态、不依赖 Spring，可脱离上下文单测。
 */
public final class PlaceholderSubstitutor {

    /** 类型推断阶段把 ${age} 换成这个前缀加变量名，让它成为一个合法标识符 */
    public static final String IDENT_PREFIX = "__ph_";

    /** 占位符语法：${变量名}，变量名限定 \w+ */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\w+)\\}");

    /** 类型只有这五种，推不出来一律按 String（与需求文档 4.3.2 一致） */
    private static final String T_INT = "int";
    private static final String T_LONG = "long";
    private static final String T_DOUBLE = "double";
    private static final String T_BOOLEAN = "boolean";
    private static final String T_STRING = "String";

    private static final Pattern INT_LITERAL = Pattern.compile("-?\\d+");
    private static final Pattern DOUBLE_LITERAL = Pattern.compile("-?(\\d+\\.\\d*|\\.\\d+|\\d+)");

    private PlaceholderSubstitutor() {
    }

    /** 按出现顺序去重返回全部占位符名 */
    public static List<String> extractNames(String script) {
        Set<String> names = new LinkedHashSet<>();
        if (script == null || script.isEmpty()) {
            return new ArrayList<>(names);
        }
        Matcher m = PLACEHOLDER.matcher(script);
        while (m.find()) {
            names.add(m.group(1));
        }
        return new ArrayList<>(names);
    }

    /**
     * 把每个 ${name} 换成 __ph_name，供类型推断阶段解析 AST 用。
     * 所有区域一视同仁：串内换成 __ph_name 后仍在引号里，是个合法的字符串字面量。
     */
    public static String toIdentifiers(String script) {
        return replace(script, (name, region) -> IDENT_PREFIX + name);
    }

    /** 语法校验用：按类型填假值，让脚本能被编译（不执行） */
    public static String toFakeValues(String script, Map<String, String> types) {
        return replace(script, (name, region) -> {
            String type = typeOf(types, name);
            return region == Region.CODE ? fakeCodeLiteral(type) : fakeRawToken(type);
        });
    }

    /**
     * 沙箱运行用：填真实值。
     *
     * @throws PlaceholderValueException 缺值，或值与声明类型对不上（消息为可读中文）
     */
    public static String toLiterals(String script, Map<String, String> params, Map<String, String> types) {
        return replace(script, (name, region) -> {
            String type = typeOf(types, name);
            if (params == null || !params.containsKey(name)) {
                throw new PlaceholderValueException("缺少占位符 " + name + " 的值，请先在下方填写");
            }
            String value = params.get(name) == null ? "" : params.get(name);
            String checked = checkAndNormalize(name, type, value);
            if (region == Region.CODE) {
                return codeLiteral(type, checked);
            }
            return region == Region.SINGLE_QUOTED
                    ? escapeSingleQuoted(checked)
                    : escapeDoubleQuoted(checked);
        });
    }

    // ================= 内部实现 =================

    /** 单个占位符的替换决策 */
    private interface Replacer {
        String apply(String name, Region region);
    }

    /** 区域数组与原始脚本等长且下标对齐，所以能直接用 ${ 的位置查区域 */
    private static String replace(String script, Replacer replacer) {
        if (script == null) {
            return "";
        }
        if (script.isEmpty()) {
            return script;
        }
        Region[] regions = GroovyLexScanner.scan(script);
        Matcher m = PLACEHOLDER.matcher(script);
        StringBuilder sb = new StringBuilder(script.length() + 32);
        int cursor = 0;
        while (m.find()) {
            sb.append(script, cursor, m.start());
            sb.append(replacer.apply(m.group(1), regions[m.start()]));
            cursor = m.end();
        }
        sb.append(script, cursor, script.length());
        return sb.toString();
    }

    private static String typeOf(Map<String, String> types, String name) {
        if (types == null) {
            return T_STRING;
        }
        String t = types.get(name);
        return t == null || t.isBlank() ? T_STRING : t;
    }

    private static String fakeCodeLiteral(String type) {
        return switch (type) {
            case T_INT -> "0";
            case T_LONG -> "0L";
            case T_DOUBLE -> "0.0d";
            case T_BOOLEAN -> "false";
            default -> "\"\"";
        };
    }

    /** 串内的假值：不加引号，String 直接给空文本 */
    private static String fakeRawToken(String type) {
        return switch (type) {
            case T_INT, T_LONG -> "0";
            case T_DOUBLE -> "0.0";
            case T_BOOLEAN -> "false";
            default -> "";
        };
    }

    /**
     * 校验填值是否符合声明类型，并返回可安全嵌入脚本的形式。
     * 错误消息一律中文、带上变量名和实际填入的值，方便用户自己改。
     */
    private static String checkAndNormalize(String name, String type, String value) {
        String v = value.trim();
        switch (type) {
            case T_INT, T_LONG -> {
                if (!INT_LITERAL.matcher(v).matches()) {
                    throw new PlaceholderValueException(
                            "占位符 " + name + " 需要整数，但填入的「" + value + "」不是合法整数");
                }
                return v;
            }
            case T_DOUBLE -> {
                if (!DOUBLE_LITERAL.matcher(v).matches()) {
                    throw new PlaceholderValueException(
                            "占位符 " + name + " 需要小数，但填入的「" + value + "」不是合法数字");
                }
                return v;
            }
            case T_BOOLEAN -> {
                if (!"true".equals(v) && !"false".equals(v)) {
                    throw new PlaceholderValueException(
                            "占位符 " + name + " 只能填 true 或 false，当前是「" + value + "」");
                }
                return v;
            }
            default -> {
                // String 不做格式校验，空串也合法；注意这里返回原值，不 trim
                return value;
            }
        }
    }

    /** 代码区的字面量：数字补后缀强制类型，字符串补引号并转义 */
    private static String codeLiteral(String type, String value) {
        return switch (type) {
            case T_LONG -> value + "L";
            case T_DOUBLE -> value + "d";
            case T_INT, T_BOOLEAN -> value;
            default -> "\"" + escapeDoubleQuoted(value) + "\"";
        };
    }

    /**
     * 双引号字符串语境的转义：反斜杠、双引号、换行/回车/制表，以及 $。
     * $ 必须转义，否则用户填的值里带 ${xxx} 会被 Groovy 当 GString 再插值一次。
     */
    private static String escapeDoubleQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '$' -> sb.append("\\$");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 单引号字符串语境的转义：只处理反斜杠、单引号和空白控制符。
     * 注意 $ 不转义 —— 单引号串不做插值，写 \$ 会让结果多出一个反斜杠。
     */
    private static String escapeSingleQuoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: 跑测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 53 个测试通过（任务 2 的 1 + 任务 7 的 28 + 本任务的 24）。

常见失败与对策：
- `串内值含美元符号要转义防止二次插值` 红 → `escapeDoubleQuoted` 漏了 `case '$'`
- `单引号串内美元符号不转义` 红 → 单引号分支误用了 `escapeDoubleQuoted`
- `long补L后缀double补d后缀` 红 → 后缀加到了串内分支上；后缀只属于代码区

- [ ] **Step 5: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 占位符替换器（区分代码区与串内，含类型校验与转义）"
```

---

## Task 9: PlaceholderTypeInferer（AST 类型推断）

需求文档 4.3.2 要求「根据变量声明语句解析出类型」。做法是技术方案 3.2 的四步：把 `${age}` 换成合法标识符 `__ph_age` → 解析成 AST → 找初始化表达式是该标识符的声明语句 → 取声明类型；找不到就按 `String`。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/groovy/PlaceholderTypeInferer.java`
- Test: `script_back/src/test/java/com/xd/rulescript/groovy/PlaceholderTypeInfererTest.java`

**Interfaces:**
- Consumes: `PlaceholderSubstitutor.toIdentifiers` / `IDENT_PREFIX` / `extractNames`（任务 8）
- Produces:
  - `static Map<String,String> PlaceholderTypeInferer.infer(String script)` —— 入参是**原始脚本**（内部自己转标识符），返回 `LinkedHashMap`，键为占位符名、值为归一后的五种类型之一；顺序与 `extractNames` 一致
  - `static String PlaceholderTypeInferer.normalize(String declaredType)` —— 把 Groovy 声明类型名归一为 `int`/`long`/`double`/`boolean`/`String`；认不出的一律 `String`（含 `def`、`Object`、`var`、`List` 等）

归一映射表（`normalize` 的唯一事实来源，写测试就照这张表）：

| 声明写法 | 归一结果 |
|---|---|
| `int` / `Integer` / `java.lang.Integer` / `short` / `byte` 及其包装类 | `int` |
| `long` / `Long` / `java.lang.Long` / `BigInteger` | `long` |
| `double` / `Double` / `float` / `Float` / `BigDecimal` / `Number` | `double` |
| `boolean` / `Boolean` / `java.lang.Boolean` | `boolean` |
| `String` / `CharSequence` / `GString` / `char` / `Character` | `String` |
| `def` / `var` / `Object` / 其它任何类型 / `null` / 空串 | `String` |

关键约束：
- **解析失败不得抛异常**。`infer` 内部 catch 一切解析错误并返回「全部按 String」的结果 —— 因为语法校验流程里推断跑在编译之前，脚本本来可能就是坏的，这时候要把行号错误让给任务 10 去报，不能在这里先炸。
- 只认**声明语句**（`DeclarationExpression`）且右值是 `__ph_` 开头的变量表达式。`return ${age}`、`foo(${age})` 这类没有声明的，一律 `String`。
- 用 `Phases.CONVERSION` 阶段，只做语法树构建、不做语义解析，避免未定义变量报错。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/xd/rulescript/groovy/PlaceholderTypeInfererTest.java`

```java
package com.xd.rulescript.groovy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 类型推断决定前端渲染什么输入控件，推错会让用户填不进值。
 */
class PlaceholderTypeInfererTest {

    // ---------- normalize ----------

    @Test
    void 基本类型与包装类归一到int() {
        assertThat(PlaceholderTypeInferer.normalize("int")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("Integer")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("java.lang.Integer")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("short")).isEqualTo("int");
        assertThat(PlaceholderTypeInferer.normalize("byte")).isEqualTo("int");
    }

    @Test
    void 长整型归一到long() {
        assertThat(PlaceholderTypeInferer.normalize("long")).isEqualTo("long");
        assertThat(PlaceholderTypeInferer.normalize("Long")).isEqualTo("long");
        assertThat(PlaceholderTypeInferer.normalize("java.math.BigInteger")).isEqualTo("long");
    }

    @Test
    void 浮点与大数归一到double() {
        assertThat(PlaceholderTypeInferer.normalize("double")).isEqualTo("double");
        assertThat(PlaceholderTypeInferer.normalize("float")).isEqualTo("double");
        assertThat(PlaceholderTypeInferer.normalize("BigDecimal")).isEqualTo("double");
        assertThat(PlaceholderTypeInferer.normalize("Number")).isEqualTo("double");
    }

    @Test
    void 布尔归一到boolean() {
        assertThat(PlaceholderTypeInferer.normalize("boolean")).isEqualTo("boolean");
        assertThat(PlaceholderTypeInferer.normalize("java.lang.Boolean")).isEqualTo("boolean");
    }

    @Test
    void 字符序列归一到String() {
        assertThat(PlaceholderTypeInferer.normalize("String")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("java.lang.String")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("CharSequence")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("char")).isEqualTo("String");
    }

    @Test
    void 认不出的类型一律String() {
        assertThat(PlaceholderTypeInferer.normalize("def")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("var")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("Object")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("java.util.List")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize("")).isEqualTo("String");
        assertThat(PlaceholderTypeInferer.normalize(null)).isEqualTo("String");
    }

    // ---------- infer ----------

    @Test
    void 从声明语句推断出各类型() {
        String script = """
                int age = ${age}
                long ts = ${ts}
                double rate = ${rate}
                boolean vip = ${vip}
                String name = ${name}
                return age
                """;
        assertThat(PlaceholderTypeInferer.infer(script)).containsExactly(
                Map.entry("age", "int"),
                Map.entry("ts", "long"),
                Map.entry("rate", "double"),
                Map.entry("vip", "boolean"),
                Map.entry("name", "String"));
    }

    @Test
    void 没有声明的占位符按String() {
        assertThat(PlaceholderTypeInferer.infer("return ${age} + 1"))
                .containsEntry("age", "String");
    }

    @Test
    void def声明按String() {
        assertThat(PlaceholderTypeInferer.infer("def age = ${age}\nreturn age"))
                .containsEntry("age", "String");
    }

    @Test
    void 串内的占位符推断不到声明按String() {
        // "${level}" 转成 "__ph_level" 后落在字符串字面量里，不是变量表达式，所以推不出来
        assertThat(PlaceholderTypeInferer.infer("String level = \"${level}\"\nreturn level"))
                .containsEntry("level", "String");
    }

    @Test
    void 同一占位符多处出现只保留一条且以首个声明为准() {
        String script = "int age = ${age}\nreturn ${age} * 2";
        assertThat(PlaceholderTypeInferer.infer(script))
                .hasSize(1)
                .containsEntry("age", "int");
    }

    @Test
    void 返回顺序与脚本中出现顺序一致() {
        String script = "String b = ${beta}\nint a = ${alpha}\nreturn 1";
        assertThat(PlaceholderTypeInferer.infer(script).keySet())
                .containsExactly("beta", "alpha");
    }

    @Test
    void 无占位符返回空map() {
        assertThat(PlaceholderTypeInferer.infer("return 1 + 1")).isEmpty();
    }

    @Test
    void 空脚本与null不报错() {
        assertThat(PlaceholderTypeInferer.infer("")).isEmpty();
        assertThat(PlaceholderTypeInferer.infer(null)).isEmpty();
    }

    @Test
    void 语法错误的脚本不抛异常而是全部按String() {
        // 推断跑在语法校验之前，脚本可能是坏的；这里必须让路，把行号错误留给语法校验去报
        String broken = "int age = ${age}\nreturn ((( ";
        assertThat(PlaceholderTypeInferer.infer(broken)).containsEntry("age", "String");
    }

    @Test
    void 方法参数位置的占位符按String() {
        assertThat(PlaceholderTypeInferer.infer("println(${msg})\nreturn 1"))
                .containsEntry("msg", "String");
    }

    @Test
    void 声明在方法体内也能推断() {
        String script = """
                def calc() {
                    int age = ${age}
                    return age
                }
                return calc()
                """;
        assertThat(PlaceholderTypeInferer.infer(script)).containsEntry("age", "int");
    }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test 2>&1 | tail -20
```

预期编译失败（类还不存在）。

- [ ] **Step 2: 写 `PlaceholderTypeInferer`**

`src/main/java/com/xd/rulescript/groovy/PlaceholderTypeInferer.java`

```java
package com.xd.rulescript.groovy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;

/**
 * 从变量声明语句推断 ${占位符} 的类型。
 *
 * <p>做法：把 ${age} 换成合法标识符 __ph_age（见 PlaceholderSubstitutor#toIdentifiers），
 * 解析到 CONVERSION 阶段拿到语法树，遍历所有声明语句，凡是右值为 __ph_ 开头变量的，
 * 就取左值的声明类型并归一为五种之一。
 *
 * <p>只解析不编译执行，所以脚本里有未定义变量也不会报错。解析失败时返回「全部按 String」，
 * 把报错的机会让给语法校验 —— 推断本来就跑在校验之前，此时脚本很可能还是坏的。
 */
public final class PlaceholderTypeInferer {

    /** 归一映射表，是 normalize 的唯一事实来源 */
    private static final Map<String, String> NORMALIZE_TABLE = Map.ofEntries(
            Map.entry("int", "int"), Map.entry("Integer", "int"),
            Map.entry("java.lang.Integer", "int"), Map.entry("short", "int"),
            Map.entry("Short", "int"), Map.entry("byte", "int"), Map.entry("Byte", "int"),
            Map.entry("long", "long"), Map.entry("Long", "long"),
            Map.entry("java.lang.Long", "long"), Map.entry("BigInteger", "long"),
            Map.entry("java.math.BigInteger", "long"),
            Map.entry("double", "double"), Map.entry("Double", "double"),
            Map.entry("java.lang.Double", "double"), Map.entry("float", "double"),
            Map.entry("Float", "double"), Map.entry("java.lang.Float", "double"),
            Map.entry("BigDecimal", "double"), Map.entry("java.math.BigDecimal", "double"),
            Map.entry("Number", "double"), Map.entry("java.lang.Number", "double"),
            Map.entry("boolean", "boolean"), Map.entry("Boolean", "boolean"),
            Map.entry("java.lang.Boolean", "boolean"),
            Map.entry("String", "String"), Map.entry("java.lang.String", "String"),
            Map.entry("CharSequence", "String"), Map.entry("java.lang.CharSequence", "String"),
            Map.entry("GString", "String"), Map.entry("char", "String"),
            Map.entry("Character", "String"), Map.entry("java.lang.Character", "String"));

    private static final String DEFAULT_TYPE = "String";

    private PlaceholderTypeInferer() {
    }

    /** 把 Groovy 声明类型名归一为 int/long/double/boolean/String，认不出的一律 String */
    public static String normalize(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            return DEFAULT_TYPE;
        }
        String key = declaredType.trim();
        String hit = NORMALIZE_TABLE.get(key);
        if (hit != null) {
            return hit;
        }
        // 兜底再试一次简单类名，覆盖用户写完全限定名的情况
        int dot = key.lastIndexOf('.');
        if (dot >= 0 && dot < key.length() - 1) {
            hit = NORMALIZE_TABLE.get(key.substring(dot + 1));
            if (hit != null) {
                return hit;
            }
        }
        return DEFAULT_TYPE;
    }

    /**
     * 推断原始脚本里每个占位符的类型。
     *
     * @param script 原始脚本（含 ${} 原样写法），可为 null
     * @return 键为占位符名、值为归一类型的有序 map；顺序与占位符在脚本中的出现顺序一致
     */
    public static Map<String, String> infer(String script) {
        List<String> names = PlaceholderSubstitutor.extractNames(script);
        Map<String, String> result = new LinkedHashMap<>();
        // 先全部按 String 占位，保证即使解析失败每个占位符也都有类型
        for (String name : names) {
            result.put(name, DEFAULT_TYPE);
        }
        if (names.isEmpty()) {
            return result;
        }
        try {
            collectFromAst(PlaceholderSubstitutor.toIdentifiers(script)).forEach((name, type) -> {
                if (result.containsKey(name)) {
                    result.put(name, type);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            // 语法错误、解析器内部异常都吞掉，保持「全部 String」的兜底结果
        }
        return result;
    }

    /** 解析到 CONVERSION 阶段，遍历所有类的声明语句，收集 __ph_ 变量的声明类型 */
    private static Map<String, String> collectFromAst(String scriptWithIdents) {
        CompilationUnit unit = new CompilationUnit(new CompilerConfiguration());
        SourceUnit sourceUnit = unit.addSource("PlaceholderInfer.groovy", scriptWithIdents);
        unit.compile(Phases.CONVERSION);

        CompileUnit compileUnit = unit.getAST();
        DeclarationVisitor visitor = new DeclarationVisitor(sourceUnit);
        // 必须遍历所有 module 的 getClasses()，否则方法体内的声明收不到。
        // getAST() 返回的是 CompileUnit（module 的容器），脚本编译后只有一个 module
        for (ModuleNode module : compileUnit.getModules()) {
            for (ClassNode classNode : module.getClasses()) {
                visitor.visitClass(classNode);
            }
        }
        return visitor.collected;
    }

    /** 只关心「声明语句的右值是个 __ph_ 变量」这一种形态 */
    private static final class DeclarationVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit sourceUnit;
        private final Map<String, String> collected = new LinkedHashMap<>();

        private DeclarationVisitor(SourceUnit sourceUnit) {
            this.sourceUnit = sourceUnit;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            Expression right = expression.getRightExpression();
            if (right instanceof VariableExpression variable) {
                String varName = variable.getName();
                if (varName != null && varName.startsWith(PlaceholderSubstitutor.IDENT_PREFIX)) {
                    String placeholder = varName.substring(PlaceholderSubstitutor.IDENT_PREFIX.length());
                    String declared = expression.getLeftExpression().getType().getName();
                    // 同一占位符被多处声明时以首个为准，不覆盖
                    collected.putIfAbsent(placeholder, normalize(declared));
                }
            }
            super.visitDeclarationExpression(expression);
        }
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 72 个测试通过（53 + 本任务 19）。

常见失败与对策：
- `语法错误的脚本不抛异常` 红 → `infer` 的 catch 没兜住。Groovy 解析失败抛的是 `MultipleCompilationErrorsException`（属 `RuntimeException`），但个别路径会抛 `NullPointerException` 或 `LinkageError`，所以 catch 要写成 `RuntimeException | LinkageError`
- `声明在方法体内也能推断` 红 → 只遍历了脚本主体而没遍历 `module.getClasses()`；方法体里的声明只有走 `visitClass` 才能覆盖到
- `串内的占位符推断不到声明按String` 红 → 说明 `toIdentifiers` 把引号弄丢了，回任务 8 检查
- `def声明按String` 红 → `def` 在 AST 里的类型名是 `java.lang.Object`，应当落到兜底分支；若被映射成别的，检查归一表里有没有误加 `Object`

- [ ] **Step 4: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 占位符类型推断（AST 遍历声明语句，归一为五种类型）"
```

---

## Task 10: 语法校验 + 编译期安全拦截

技术方案 3.2 的「语法校验」与「编译期沙箱」在这一任务落地。两件事必须一起做，因为它们共用同一个 `CompilerConfiguration`：校验时就要按沙箱规则编译，否则用户会先看到「语法通过」，点运行才被拦截，体验割裂。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/groovy/ScriptSecurityException.java`
- Create: `script_back/src/main/java/com/xd/rulescript/groovy/ScriptSafetyCustomizer.java`
- Create: `script_back/src/main/java/com/xd/rulescript/config/GroovySandboxConfig.java`
- Create: `script_back/src/main/java/com/xd/rulescript/config/ScriptExecutorConfig.java`
- Create: `script_back/src/main/java/com/xd/rulescript/service/GroovyEngineService.java`（本任务只写占位符提取与语法校验，`run` 在任务 11 补）
- Test: `script_back/src/test/java/com/xd/rulescript/service/GroovyEngineSyntaxTest.java`

**Interfaces:**
- Consumes: `PlaceholderSubstitutor`（任务 8）、`PlaceholderTypeInferer`（任务 9）、`SyntaxCheckResult` / `PlaceholderInfo`（任务 5）
- Produces:
  - `ScriptSecurityException.MARKER = "【安全拦截】"`；`ScriptSecurityException(String message)`（构造时自动加前缀）；`static boolean isSecurityMessage(String)`；`static String stripMarker(String)`
  - `ScriptSafetyCustomizer extends CompilationCustomizer`，构造无参，在 `CompilePhase.CONVERSION` 阶段把违规项以 `SyntaxErrorMessage` 写进 `ErrorCollector`（消息带 MARKER 前缀与行号）
  - `static CompilerConfiguration GroovySandboxConfig.build()` + `@Bean CompilerConfiguration groovyCompilerConfiguration()`
  - `static ExecutorService ScriptExecutorConfig.build()` + `@Bean(destroyMethod="shutdownNow") ExecutorService scriptExecutor()`
  - `GroovyEngineService(CompilerConfiguration, ExecutorService, int timeoutSeconds)`
    - `List<PlaceholderInfo> extractPlaceholders(String script)`
    - `SyntaxCheckResult checkSyntax(String script)`
    - `static String humanizeCompileError(SyntaxException)` —— 把 Groovy 的英文报错转成「中文说明 + 原始细节」

**已核实的 Groovy 4.0.24 API（不要照旧文档写，这些名字都变过）：**

| 用途 | 正确写法 | 常见错误写法 |
|---|---|---|
| 编译错误消息类 | `org.codehaus.groovy.control.messages.SyntaxErrorMessage` | `SyntaxMessage`（**不存在**） |
| 造带行号的错误 | `new SyntaxException(msg, line, column)` | — |
| 写进错误收集器 | `sourceUnit.getErrorCollector().addErrorAndContinue(new SyntaxErrorMessage(se, sourceUnit))` | `addError(String)` |
| 禁 import | `setAllowedImports(List.of())` 等四个白名单置空 | `setImportsBlacklist`（旧名，仍在但不推荐） |
| 接收者黑名单 | `setDisallowedReceiversClasses(List<Class>)` | `setReceiversClassesBlackList`（旧名） |
| 自定义编译期检查 | `extends CompilationCustomizer`，覆写 `call(SourceUnit, GeneratorContext, ClassNode)` | 覆写 `visit*` |
| 死循环可中断 | `new ASTTransformationCustomizer(groovy.transform.ThreadInterrupt.class)` | 注解在 `org.codehaus.groovy.transform` 包（**错**，那是变换实现类） |

**安全拦截的边界（务必写进注释，别让人误以为沙箱是密不透风的）**

拦截走两条腿：
1. `SecureASTCustomizer`（Groovy 自带）：禁 package、禁四种 import、接收者黑名单。它只在**接收者是静态可辨识的类表达式**时生效，`def x = System; x.exit(0)` 这种绕法它抓不到。
2. `ScriptSafetyCustomizer`（本任务自研）：在 CONVERSION 阶段遍历 AST，凡是 `MethodCallExpression` / `PropertyExpression` / `ConstructorCallExpression` / `ClassExpression` / `VariableExpression` 的名字命中黑名单就报错。它按**名字**匹配，所以能抓住上面那种绕法的第一跳（`def x = System` 里的 `System` 就是个 VariableExpression）。

两层都是静态检查，挡不住「运行时才拼出类名再反射」这类刻意绕过。技术方案第 6 节已经认了这个账：**这是单用户内部工具，静态黑名单 + 5 秒超时兜底，风险可接受**。运行期还有任务 11 的线程中断。

已知的**过度拦截**代价：黑名单按简单类名匹配，用户若把变量命名为 `File`、`Thread`、`System`（首字母大写）会被误伤。这是故意的取舍 —— 规则脚本里这么命名的概率极低，而漏拦的代价高。测试里要有对应用例把这条边界钉住。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/xd/rulescript/service/GroovyEngineSyntaxTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.config.GroovySandboxConfig;
import com.xd.rulescript.config.ScriptExecutorConfig;
import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.groovy.ScriptSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 语法校验与安全拦截。刻意不用 @SpringBootTest —— GroovyEngineService 只依赖
 * CompilerConfiguration、ExecutorService 和一个 int，手动 new 出来测更快也更稳。
 */
class GroovyEngineSyntaxTest {

    private static ExecutorService executor;
    private static GroovyEngineService engine;

    @BeforeAll
    static void setUp() {
        executor = ScriptExecutorConfig.build();
        engine = new GroovyEngineService(GroovySandboxConfig.build(), executor, 5);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 语法校验：通过路径 ----------

    @Test
    void 合法脚本语法通过() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nreturn a + 1");
        assertThat(r.ok()).isTrue();
        assertThat(r.line()).isNull();
        assertThat(r.message()).isNull();
    }

    @Test
    void 空脚本语法通过() {
        assertThat(engine.checkSyntax("").ok()).isTrue();
    }

    @Test
    void 含占位符的脚本按假值替换后能通过() {
        // int age = ${age} 原文是编译不过的，替换成 int age = 0 才行
        assertThat(engine.checkSyntax("int age = ${age}\nreturn age * 2").ok()).isTrue();
    }

    @Test
    void 串内占位符也能通过() {
        assertThat(engine.checkSyntax("String level = \"${level}\"\nreturn \"等级：${level}\"").ok()).isTrue();
    }

    @Test
    void 闭包与集合操作不误伤() {
        String script = """
                def list = [3, 1, 2]
                def sorted = list.collect { it * 2 }.findAll { it > 2 }
                return sorted.join(",")
                """;
        assertThat(engine.checkSyntax(script).ok()).isTrue();
    }

    @Test
    void 自定义方法定义不误伤() {
        String script = """
                def calc(int x) { return x * 2 }
                return calc(${n})
                """;
        assertThat(engine.checkSyntax(script).ok()).isTrue();
    }

    // ---------- 语法校验：错误路径 ----------

    @Test
    void 括号不闭合给出行号与中文说明() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nreturn (a + 1");
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).isNotNull();
        assertThat(r.message()).isNotBlank();
        // 必须是中文可读提示，不能只是英文原文
        assertThat(r.message()).containsAnyOf("没有写完", "缺少", "不该有的符号", "语法错误");
    }

    @Test
    void 多余右括号报出准确行号() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nint b = 2\nreturn a + b))");
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).isEqualTo(3);
    }

    @Test
    void 动态类型不匹配在语法校验放行() {
        // 动态 Groovy 里 int age = "" 能正常编译，类型不符只在运行时才抛 GroovyCastException。
        // checkSyntax 只 parse 不执行（靠填假值让脚本能 parse），语法层拦不到也不该拦；
        // 值/类型不匹配属运行期职责，由任务 11 的 toLiterals + 运行时异常翻译覆盖并测试。
        SyntaxCheckResult r = engine.checkSyntax("String name = ${name}\nint age = ${name}\nreturn age");
        assertThat(r.ok()).isTrue();
    }

    @Test
    void 错误信息不含异常堆栈() {
        String message = engine.checkSyntax("return ((( ").message();
        assertThat(message).doesNotContain("at org.codehaus").doesNotContain("\tat ");
    }

    @Test
    void 错误信息不含英文异常类名() {
        String message = engine.checkSyntax("int a = \nreturn a").message();
        assertThat(message).doesNotContain("MultipleCompilationErrorsException");
        assertThat(message).doesNotContain("SyntaxException");
    }

    // ---------- 安全拦截 ----------

    @Test
    void 拦截System调用() {
        SyntaxCheckResult r = engine.checkSyntax("System.exit(0)\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
        assertThat(r.message()).contains("System");
        assertThat(r.line()).isEqualTo(1);
    }

    @Test
    void 拦截Runtime执行外部命令() {
        SyntaxCheckResult r = engine.checkSyntax("Runtime.getRuntime().exec(\"ls\")\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截文件访问() {
        SyntaxCheckResult r = engine.checkSyntax("def f = new File(\"/etc/passwd\")\nreturn f.text");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
        assertThat(r.message()).contains("File");
    }

    @Test
    void 拦截网络访问() {
        SyntaxCheckResult r = engine.checkSyntax("def u = new URL(\"http://x.com\")\nreturn u.text");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截自建线程() {
        SyntaxCheckResult r = engine.checkSyntax("new Thread({ println 1 }).start()\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截import语句() {
        SyntaxCheckResult r = engine.checkSyntax("import java.io.File\nreturn new File(\"x\").exists()");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
        assertThat(r.message()).contains("import");
    }

    @Test
    void 拦截package声明() {
        SyntaxCheckResult r = engine.checkSyntax("package com.evil\nreturn 1");
        assertThat(r.ok()).isFalse();
    }

    @Test
    void 拦截反射调用() {
        SyntaxCheckResult r = engine.checkSyntax(
                "def m = String.class.getMethod(\"toString\")\nreturn m.invoke(\"x\")");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 拦截先赋值给变量再调用的绕法() {
        // SecureASTCustomizer 抓不到这种，靠自研 customizer 按名字匹配兜住
        SyntaxCheckResult r = engine.checkSyntax("def s = System\ns.exit(0)\nreturn 1");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 多处违规时报第一处并给出行号() {
        SyntaxCheckResult r = engine.checkSyntax("int a = 1\nSystem.exit(0)\nnew File(\"x\")\nreturn a");
        assertThat(r.ok()).isFalse();
        assertThat(r.line()).isEqualTo(2);
    }

    // ---------- 过度拦截的已知边界（钉住取舍，改动前必须知道会红）----------

    @Test
    void 变量名恰好叫File会被误伤() {
        // 已知取舍：黑名单按简单类名匹配。规则脚本里这么命名的概率极低，漏拦代价更高
        SyntaxCheckResult r = engine.checkSyntax("def File = 1\nreturn File");
        assertThat(r.ok()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.message())).isTrue();
    }

    @Test
    void 小写变量名file不受影响() {
        assertThat(engine.checkSyntax("def file = \"报告.txt\"\nreturn file.length()").ok()).isTrue();
    }

    // ---------- 占位符提取 ----------

    @Test
    void 提取占位符带类型() {
        List<PlaceholderInfo> list = engine.extractPlaceholders(
                "int age = ${age}\nString level = ${level}\nreturn age");
        assertThat(list).containsExactly(
                new PlaceholderInfo("age", "int"),
                new PlaceholderInfo("level", "String"));
    }

    @Test
    void 无占位符返回空列表() {
        assertThat(engine.extractPlaceholders("return 1 + 1")).isEmpty();
    }

    @Test
    void 语法错误的脚本仍能提取占位符() {
        // 提取不依赖编译成功，类型推断失败时兜底为 String
        assertThat(engine.extractPlaceholders("int age = ${age}\nreturn ((( "))
                .containsExactly(new PlaceholderInfo("age", "String"));
    }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test 2>&1 | tail -20
```

预期编译失败（四个类都还不存在）。

- [ ] **Step 2: 写 `ScriptSecurityException`**

`src/main/java/com/xd/rulescript/groovy/ScriptSecurityException.java`

```java
package com.xd.rulescript.groovy;

/**
 * 脚本触碰沙箱黑名单时抛出，消息即给前端看的中文提示。
 *
 * <p>所有安全类消息统一带 {@link #MARKER} 前缀，这样 GroovyEngineService 能把它和普通语法错误
 * 区分开：语法错误提示「哪一行写错了」，安全拦截提示「这个操作被禁止」。
 */
public class ScriptSecurityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 安全拦截消息的统一前缀，也是识别标记 */
    public static final String MARKER = "【安全拦截】";

    public ScriptSecurityException(String message) {
        super(message == null || message.startsWith(MARKER) ? message : MARKER + message);
    }

    /** 判断一段错误消息是不是安全拦截产生的 */
    public static boolean isSecurityMessage(String message) {
        return message != null && message.startsWith(MARKER);
    }

    /** 去掉前缀，用于把安全提示拼进更长的句子里 */
    public static String stripMarker(String message) {
        if (message == null) {
            return "";
        }
        return message.startsWith(MARKER) ? message.substring(MARKER.length()) : message;
    }
}
```

- [ ] **Step 3: 写 `ScriptSafetyCustomizer`**

`src/main/java/com/xd/rulescript/groovy/ScriptSafetyCustomizer.java`

```java
package com.xd.rulescript.groovy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

/**
 * 编译期危险能力拦截（自研，补 SecureASTCustomizer 的盲区）。
 *
 * <p>SecureASTCustomizer 只在接收者是静态可辨识的类表达式时生效，def s = System; s.exit(0)
 * 这种两跳写法它抓不到。本 customizer 在 CONVERSION 阶段按**名字**扫 AST，
 * MethodCallExpression / PropertyExpression / ConstructorCallExpression / ClassExpression /
 * VariableExpression 五种形态都查，能兜住第一跳。
 *
 * <p>代价是会误伤恰好命名为 File / Thread / System 的变量（首字母大写才算）。
 * 这是有意的取舍：规则脚本里这么命名极少见，而漏拦一次可能就把宿主进程干掉。
 *
 * <p>注意这只是静态黑名单，挡不住运行时拼类名再反射的刻意绕过。技术方案第 6 节已认账：
 * 单用户内部工具，静态黑名单 + 5 秒超时兜底，风险可接受。
 */
public class ScriptSafetyCustomizer extends CompilationCustomizer {

    /** 黑名单：简单类名 → 中文原因。用 LinkedHashMap 保证报错顺序稳定，测试才好断言 */
    private static final Map<String, String> FORBIDDEN = new LinkedHashMap<>();

    static {
        FORBIDDEN.put("System", "禁止访问宿主进程与环境变量");
        FORBIDDEN.put("Runtime", "禁止启动外部进程");
        FORBIDDEN.put("ProcessBuilder", "禁止启动外部进程");
        FORBIDDEN.put("Process", "禁止操作外部进程");
        FORBIDDEN.put("Thread", "禁止自行创建线程");
        FORBIDDEN.put("Runnable", "禁止自行创建线程");
        FORBIDDEN.put("Executor", "禁止自行创建线程池");
        FORBIDDEN.put("ExecutorService", "禁止自行创建线程池");
        FORBIDDEN.put("ClassLoader", "禁止动态加载类");
        FORBIDDEN.put("GroovyClassLoader", "禁止动态加载类");
        FORBIDDEN.put("GroovyShell", "禁止执行额外的脚本");
        FORBIDDEN.put("GroovyScriptEngine", "禁止执行额外的脚本");
        FORBIDDEN.put("File", "禁止访问文件");
        FORBIDDEN.put("Files", "禁止访问文件");
        FORBIDDEN.put("Paths", "禁止访问文件");
        FORBIDDEN.put("Path", "禁止访问文件");
        FORBIDDEN.put("FileInputStream", "禁止访问文件");
        FORBIDDEN.put("FileOutputStream", "禁止访问文件");
        FORBIDDEN.put("FileReader", "禁止访问文件");
        FORBIDDEN.put("FileWriter", "禁止访问文件");
        FORBIDDEN.put("RandomAccessFile", "禁止访问文件");
        FORBIDDEN.put("Socket", "禁止访问网络");
        FORBIDDEN.put("ServerSocket", "禁止访问网络");
        FORBIDDEN.put("URL", "禁止访问网络");
        FORBIDDEN.put("URI", "禁止访问网络");
        FORBIDDEN.put("URLConnection", "禁止访问网络");
        FORBIDDEN.put("HttpURLConnection", "禁止访问网络");
        FORBIDDEN.put("HttpClient", "禁止访问网络");
        FORBIDDEN.put("Method", "禁止用反射绕过沙箱");
        FORBIDDEN.put("Field", "禁止用反射绕过沙箱");
        FORBIDDEN.put("Constructor", "禁止用反射绕过沙箱");
        FORBIDDEN.put("Unsafe", "禁止用反射绕过沙箱");
        FORBIDDEN.put("ScriptEngine", "禁止执行额外的脚本");
        FORBIDDEN.put("ScriptEngineManager", "禁止执行额外的脚本");
    }

    /**
     * 反射 / 动态类加载的方法名黑名单。类名黑名单抓不到这些：
     * String.class.getMethod("x") 里根本没出现 "Method" 这个类名 token，只有方法名 getMethod。
     * 所以按方法名再拦一层，堵住反射绕过。
     */
    private static final Map<String, String> FORBIDDEN_METHODS = new LinkedHashMap<>();

    static {
        FORBIDDEN_METHODS.put("getMethod", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getMethods", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredMethod", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredMethods", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getField", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getFields", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredField", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredFields", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getConstructor", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getConstructors", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredConstructor", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("getDeclaredConstructors", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("invoke", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("setAccessible", "禁止用反射绕过沙箱");
        FORBIDDEN_METHODS.put("forName", "禁止用反射动态加载类");
        FORBIDDEN_METHODS.put("getClassLoader", "禁止动态加载类");
        FORBIDDEN_METHODS.put("newInstance", "禁止用反射实例化类");
    }

    public ScriptSafetyCustomizer() {
        super(CompilePhase.CONVERSION);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode)
            throws SecurityException {
        ErrorReporter reporter = new ErrorReporter(source);

        // 1) package 声明：脚本不该有包名，有就说明在试图伪装成工程代码
        ModuleNode module = source.getAST();
        if (module != null) {
            if (module.getPackage() != null) {
                reporter.report(module.getPackage(), "禁止在脚本里声明 package");
            }
            // 2) 四种 import 一律禁止：需要的能力都在默认导入里，要 import 的基本都是想干坏事
            module.getImports().forEach(i -> reporter.report(i, "禁止使用 import 语句"));
            module.getStarImports().forEach(i -> reporter.report(i, "禁止使用 import 语句"));
            module.getStaticImports().forEach((name, i) -> reporter.report(i, "禁止使用 import 语句"));
            module.getStaticStarImports().forEach((name, i) -> reporter.report(i, "禁止使用 import 语句"));
        }

        // 3) 遍历 AST 查黑名单名字
        SafetyVisitor visitor = new SafetyVisitor(source, reporter);
        visitor.visitClass(classNode);
    }

    /** 把违规项转成带行号的编译错误，走 Groovy 原生错误通道，前端能像语法错误一样标红 */
    private static final class ErrorReporter {

        private final SourceUnit source;

        private ErrorReporter(SourceUnit source) {
            this.source = source;
        }

        private void report(ASTNode node, String reason) {
            int line = node == null ? -1 : node.getLineNumber();
            int column = node == null ? -1 : node.getColumnNumber();
            String text = ScriptSecurityException.MARKER + reason;
            SyntaxException se = new SyntaxException(text, Math.max(line, 1), Math.max(column, 1));
            // addErrorAndContinue 会在超出容错阈值时立刻抛 MultipleCompilationErrorsException
            source.getErrorCollector().addErrorAndContinue(new SyntaxErrorMessage(se, source));
        }
    }

    /** 五种表达式形态都查，命中黑名单就报 */
    private static final class SafetyVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit source;
        private final ErrorReporter reporter;

        private SafetyVisitor(SourceUnit source, ErrorReporter reporter) {
            this.source = source;
            this.reporter = reporter;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return source;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            check(call.getObjectExpression());
            // 再按方法名拦一层反射：getMethod/invoke 这类没有类名 token，类名黑名单抓不到
            String methodName = call.getMethodAsString();
            if (methodName != null) {
                String reason = FORBIDDEN_METHODS.get(methodName);
                if (reason != null) {
                    reporter.report(call, "禁止调用 " + methodName + "（" + reason + "）");
                }
            }
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            check(expression.getObjectExpression());
            super.visitPropertyExpression(expression);
        }

        @Override
        public void visitConstructorCallExpression(ConstructorCallExpression call) {
            // isSpecialCall() 为 true 时是 this()/super()，不是真的在 new 东西
            if (!call.isSpecialCall()) {
                hit(call.getType().getNameWithoutPackage(), call);
            }
            super.visitConstructorCallExpression(call);
        }

        @Override
        public void visitClassExpression(ClassExpression expression) {
            hit(expression.getType().getNameWithoutPackage(), expression);
            super.visitClassExpression(expression);
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            // 这一条是为了兜住 def s = System; s.exit(0)：此时 System 还是个变量表达式
            hit(expression.getName(), expression);
            super.visitVariableExpression(expression);
        }

        private void check(Expression expression) {
            if (expression instanceof ClassExpression classExpression) {
                hit(classExpression.getType().getNameWithoutPackage(), expression);
            } else if (expression instanceof VariableExpression variable) {
                hit(variable.getName(), expression);
            } else if (expression instanceof PropertyExpression property) {
                // java.lang.System 这种全限定写法，整段文本拿去比对
                hit(property.getText(), expression);
            }
        }

        /** 名字可能是简单名、全限定名或 a.b.C 形式，取最后一段比对，再整体比对一次 */
        private void hit(String name, ASTNode node) {
            if (name == null || name.isEmpty()) {
                return;
            }
            String reason = FORBIDDEN.get(name);
            if (reason == null) {
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1) {
                    reason = FORBIDDEN.get(name.substring(dot + 1));
                }
            }
            if (reason != null) {
                reporter.report(node, "禁止使用 " + simpleName(name) + "（" + reason + "）");
            }
        }

        private static String simpleName(String name) {
            int dot = name.lastIndexOf('.');
            return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
        }
    }

    /** 便于测试断言：暴露黑名单条目数，改动黑名单时能察觉 */
    static int forbiddenCount() {
        return FORBIDDEN.size();
    }

    /** 便于其它层复用黑名单名字（例如任务 11 的运行期兜底提示） */
    static List<String> forbiddenNames() {
        return List.copyOf(FORBIDDEN.keySet());
    }
}
```

- [ ] **Step 4: 写 `GroovySandboxConfig`**

`src/main/java/com/xd/rulescript/config/GroovySandboxConfig.java`

```java
package com.xd.rulescript.config;

import com.xd.rulescript.groovy.ScriptSafetyCustomizer;
import groovy.lang.GroovyShell;
import java.io.File;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱编译配置。语法校验和运行共用这一份，保证「校验通过」和「运行不被拦」是一致的。
 *
 * <p>三层防护：
 * <ol>
 *   <li>SecureASTCustomizer —— Groovy 自带，禁 package、禁四种 import、接收者黑名单</li>
 *   <li>ScriptSafetyCustomizer —— 自研，按名字扫 AST，补上「先赋值给变量再调用」的盲区</li>
 *   <li>@ThreadInterrupt —— 往循环里注入中断检查点，让任务 11 的 future.cancel(true) 真能杀掉死循环。
 *       没有它，while(true) 是不响应 Thread.interrupt() 的，线程会一直空转泄漏</li>
 * </ol>
 */
@Configuration
public class GroovySandboxConfig {

    /**
     * 接收者黑名单里那些确定危险的类，用 Class 对象比字符串更精确。
     * setDisallowedReceiversClasses 形参是 raw List<Class>，泛型不变性下 List<Class<?>> 传不进，故用 raw 类型。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final List<Class> FORBIDDEN_RECEIVER_CLASSES = List.of(
            System.class,
            Runtime.class,
            ProcessBuilder.class,
            Thread.class,
            File.class,
            Socket.class,
            ServerSocket.class,
            URL.class,
            Method.class,
            ClassLoader.class,
            GroovyShell.class);

    /** 静态工厂，方便脱离 Spring 上下文做单测 */
    public static CompilerConfiguration build() {
        CompilerConfiguration config = new CompilerConfiguration();

        SecureASTCustomizer secure = new SecureASTCustomizer();
        // 禁 package 声明
        secure.setPackageAllowed(false);
        // 四种 import 白名单全部置空 = 一个都不许写。
        // 空列表在 Groovy 里表示「白名单已启用且不含任何项」，不是「不启用」
        secure.setAllowedImports(List.of());
        secure.setAllowedStarImports(List.of());
        secure.setAllowedStaticImports(List.of());
        secure.setAllowedStaticStarImports(List.of());
        // 不开间接 import 检查（保持默认 false）：与上面四种 import 空白名单组合时，Groovy 会把
        // java.lang.String 这类默认导入类型也当成「未授权的间接 import」，误杀几乎所有方法调用
        // （实测 "abc".length() 都报 Importing [java.lang.String] is not allowed）。
        // 全限定名绕法由 ScriptSafetyCustomizer 的名字黑名单兜住，这里必须留 false。
        secure.setIndirectImportCheckEnabled(false);
        secure.setDisallowedReceiversClasses(FORBIDDEN_RECEIVER_CLASSES);

        config.addCompilationCustomizers(
                secure,
                new ScriptSafetyCustomizer(),
                new ASTTransformationCustomizer(groovy.transform.ThreadInterrupt.class));
        return config;
    }

    @Bean
    public CompilerConfiguration groovyCompilerConfiguration() {
        return build();
    }
}
```

- [ ] **Step 5: 写 `ScriptExecutorConfig`**

`src/main/java/com/xd/rulescript/config/ScriptExecutorConfig.java`

```java
package com.xd.rulescript.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 脚本执行专用线程池。必须和用户脚本隔离，否则一个死循环就能把 Tomcat 的工作线程占满，
 * 整个工具（包括规则列表页）都会卡死 —— 这正是非功能要求 #1 要防的事。
 *
 * <p>队列满时用 AbortPolicy 直接拒绝，由 GroovyEngineService 翻译成「当前运行的脚本过多，
 * 请稍后重试」。宁可让用户重试，也不要让请求无限堆积。
 *
 * <p>线程设为 daemon，JVM 退出不被卡住的脚本拖住；destroyMethod 用 shutdownNow，
 * 关应用时中断还在跑的脚本（配合 @ThreadInterrupt 真能停下来）。
 */
@Configuration
public class ScriptExecutorConfig {

    private static final int CORE_SIZE = 2;
    private static final int MAX_SIZE = 8;
    private static final int QUEUE_CAPACITY = 50;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    /** 静态工厂，方便脱离 Spring 上下文做单测 */
    public static ExecutorService build() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                CORE_SIZE,
                MAX_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "groovy-run-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService scriptExecutor() {
        return build();
    }
}
```

- [ ] **Step 6: 写 `GroovyEngineService`（本任务只到语法校验）**

`src/main/java/com/xd/rulescript/service/GroovyEngineService.java`

```java
package com.xd.rulescript.service;

import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.groovy.PlaceholderSubstitutor;
import com.xd.rulescript.groovy.PlaceholderTypeInferer;
import com.xd.rulescript.groovy.ScriptSecurityException;
import groovy.lang.GroovyShell;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.syntax.SyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Groovy 引擎编排层：提取占位符、语法校验、沙箱运行。
 *
 * <p>本类不含算法（算法在 groovy 包里），只做三件事：按顺序调用、把异常翻译成中文、
 * 保证任何情况下都不把堆栈漏给前端。
 *
 * <p>构造参数全是普通类型，所以单测可以直接 new，不必起 Spring 上下文。
 */
@Service
public class GroovyEngineService {

    private final CompilerConfiguration compilerConfiguration;
    private final ExecutorService scriptExecutor;
    private final int timeoutSeconds;

    public GroovyEngineService(
            CompilerConfiguration groovyCompilerConfiguration,
            ExecutorService scriptExecutor,
            @Value("${app.script.timeout-seconds:5}") int timeoutSeconds) {
        this.compilerConfiguration = groovyCompilerConfiguration;
        this.scriptExecutor = scriptExecutor;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 提取脚本里的全部占位符及其类型，顺序与脚本中出现顺序一致。
     * 不依赖编译成功：脚本语法坏掉时类型推断会兜底为 String，仍能把名字列出来。
     */
    public List<PlaceholderInfo> extractPlaceholders(String script) {
        Map<String, String> types = PlaceholderTypeInferer.infer(script);
        List<PlaceholderInfo> result = new ArrayList<>(types.size());
        types.forEach((name, type) -> result.add(new PlaceholderInfo(name, type)));
        return result;
    }

    /**
     * 语法校验。流程：推断类型 → 按类型填假值 → 用沙箱配置编译（只编译不执行）。
     *
     * <p>先填假值再编译是必须的：int age = ${age} 原文根本编译不过。
     * 安全拦截也在这一步完成，所以「校验通过」就意味着「运行不会被沙箱挡住」。
     */
    public SyntaxCheckResult checkSyntax(String script) {
        if (script == null || script.isBlank()) {
            return SyntaxCheckResult.pass();
        }
        Map<String, String> types = PlaceholderTypeInferer.infer(script);
        String compilable = PlaceholderSubstitutor.toFakeValues(script, types);
        try {
            // 每次都新建 GroovyShell：它内部持有 GroovyClassLoader，每次 parse 都会生成新类。
            // 复用同一个 shell 会让类一直挂在同一个 loader 上，跑几百次就 metaspace 溢出
            new GroovyShell(compilerConfiguration).parse(compilable);
            return SyntaxCheckResult.pass();
        } catch (MultipleCompilationErrorsException e) {
            return fromCompileErrors(e);
        } catch (RuntimeException e) {
            return SyntaxCheckResult.error(null, "脚本无法编译，请检查写法后重试");
        }
    }

    /** 从 Groovy 的编译错误里取出第一条，转成「行号 + 中文说明」 */
    private static SyntaxCheckResult fromCompileErrors(MultipleCompilationErrorsException e) {
        ErrorCollector collector = e.getErrorCollector();
        if (collector == null) {
            return SyntaxCheckResult.error(null, "脚本存在错误，请检查后重试");
        }
        for (int i = 0; i < collector.getErrorCount(); i++) {
            Exception raw = collector.getException(i);
            SyntaxException syntax = unwrapSyntax(raw);
            if (syntax != null) {
                String message = syntax.getOriginalMessage();
                // 安全拦截的消息本来就是中文，直接透传；语法错误要翻译
                String readable = ScriptSecurityException.isSecurityMessage(message)
                        ? message
                        : humanizeCompileError(syntax);
                int line = syntax.getLine();
                return SyntaxCheckResult.error(line > 0 ? line : null, readable);
            }
        }
        return SyntaxCheckResult.error(null, "脚本存在错误，请检查后重试");
    }

    /**
     * ErrorCollector#getException 内部已经把 SyntaxErrorMessage 拆成了它的 cause（即 SyntaxException），
     * 所以拿到的 raw 通常直接就是 SyntaxException；不是的话返回 null 让上层兜底。
     */
    private static SyntaxException unwrapSyntax(Exception raw) {
        return raw instanceof SyntaxException syntax ? syntax : null;
    }

    /**
     * 把 Groovy 的英文编译报错转成中文说明，同时保留原始细节方便定位。
     * 格式：中文说明（原始英文细节）
     *
     * <p>**不带行号前缀**（Step 9 修正 D1）：行号由 SyntaxCheckResult.line 单独承载
     * （技术方案 §5「错误(行号+原因)」把两者分开），前端 ValidationTabs 用 errorLine 自己拼
     * 「第 N 行：」。若这里也拼一份，前端再拼就成了「第 2 行：第 2 行：…」。安全拦截路径
     * 本就是纯 message，去掉前缀后两条错误路径一致。
     *
     * <p>保留英文细节是有意的：Groovy 的原文里带着具体是哪个符号出的问题，
     * 全翻掉反而难查。但中文说明必须在前面，满足「错误信息可读」的要求。
     */
    static String humanizeCompileError(SyntaxException e) {
        String raw = e.getOriginalMessage() == null ? "" : e.getOriginalMessage().trim();
        String hint = hintOf(raw);
        StringBuilder sb = new StringBuilder(hint);
        if (!raw.isEmpty()) {
            sb.append("（").append(raw).append("）");
        }
        return sb.toString();
    }

    /** 常见 Groovy 编译错误的中文对照。命中不了就给通用说法，绝不返回英文类名 */
    private static String hintOf(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("unexpected end of file") || lower.contains("reached eof")) {
            return "脚本没有写完，括号或引号可能没闭合";
        }
        if (lower.contains("unexpected token")) {
            return "出现了不该有的符号";
        }
        if (lower.contains("unable to resolve class")) {
            return "用到了不存在的类";
        }
        if (lower.contains("cannot cast") || lower.contains("cannot assign")
                || lower.contains("incompatible") || lower.contains("cannot find matching")) {
            return "类型对不上";
        }
        if (lower.contains("expecting")) {
            return "这里还缺少内容";
        }
        if (lower.contains("duplicate") || lower.contains("already defined")) {
            return "有重复定义的名字";
        }
        if (lower.contains("illegal") || lower.contains("invalid")) {
            return "写法不合法";
        }
        return "语法错误";
    }

    /** 供任务 11 的 run 方法使用 */
    int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 供任务 11 的 run 方法使用 */
    ExecutorService scriptExecutor() {
        return scriptExecutor;
    }

    /** 供任务 11 的 run 方法使用 */
    CompilerConfiguration compilerConfiguration() {
        return compilerConfiguration;
    }
}
```

- [ ] **Step 7: 跑测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 97 个测试通过（前置 71 + 本任务 GroovyEngineSyntaxTest 26）。

常见失败与对策：

| 红的测试 | 十有八九的原因 |
|---|---|
| `拦截import语句` | 只加了 `ScriptSafetyCustomizer` 没检查 `module.getImports()`；或 CONVERSION 阶段 `source.getAST()` 返回 null，需判空 |
| `拦截先赋值给变量再调用的绕法` | `visitVariableExpression` 没覆写，或忘了调 `super` 导致子树没走完 |
| `多余右括号报出准确行号` | 行号取成了 `getStartLineNumber()` 或从消息文本里正则抠；应当直接用 `SyntaxException.getLine()` |
| `错误信息不含英文异常类名` | `humanizeCompileError` 把 `e.getMessage()` 整个拼进去了（那个版本带类名）；要用 `getOriginalMessage()` |
| `动态类型不匹配在语法校验放行` | 这不是 bug：动态 Groovy 里 `int age = ""` 能编译，类型不符只在运行时抛 `GroovyCastException`；checkSyntax 只 parse 故必然 ok=true。值/类型不匹配的运行期拦截由任务 11（toLiterals + 运行时异常翻译）覆盖 |
| `变量名恰好叫File会被误伤` 红成通过 | 黑名单匹配写成了忽略大小写，或只查了 `MethodCallExpression` 的接收者没查 `VariableExpression` |
| 启动时报 `ClassNotFoundException: groovy.transform.ThreadInterrupt` | 注解在 `groovy.transform` 包，不是 `org.codehaus.groovy.transform`（后者是变换实现类，不带注解元信息） |

- [ ] **Step 8: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: Groovy 语法校验与编译期安全拦截（双层黑名单 + 可中断循环）"
```

---

## Task 11: 沙箱运行 + 5 秒超时

任务 10 只编译不执行，这一任务补上真正跑起来的部分。核心风险有两个，都必须在测试里钉死：

1. **死循环杀不掉。** Groovy 的 `while (true) {}` 是纯 CPU 循环，**不响应 `Thread.interrupt()`** —— 光靠 `future.cancel(true)` 线程会一直空转泄漏，跑十几次线程池就废了。任务 10 加的 `@ThreadInterrupt` 变换往循环里注入了中断检查点，才让 `cancel(true)` 真的生效。本任务要有一个测试**直接断言线程被归还**，否则这条链路等于没验证。
2. **运行期异常泄露英文类名。** Global Constraints 明确要求前端看到的必须是可读中文，不能出现原始堆栈和异常类名。所以异常翻译表是本任务的主体工作量，不是附属品。

**Files:**
- Modify: `script_back/src/main/java/com/xd/rulescript/service/GroovyEngineService.java`（补 `run` 方法与异常翻译）
- Test: `script_back/src/test/java/com/xd/rulescript/service/GroovyEngineRunTest.java`
- Test: `script_back/src/test/java/com/xd/rulescript/service/GroovyEngineSandboxTest.java`

**Interfaces:**
- Consumes: 任务 10 的 `GroovyEngineService` 全部、`PlaceholderSubstitutor.toLiterals`（任务 8）、`PlaceholderTypeInferer.infer`（任务 9）、`RunResult`（任务 5）
- Produces:
  - `RunResult GroovyEngineService.run(String script, Map<String,String> params)`
  - `static String GroovyEngineService.stringify(Object value)` —— `null` → `""`；其余 `String.valueOf`；超长截断
  - `static String GroovyEngineService.translateRunError(Throwable cause)` —— 把运行期异常翻成中文

**`run` 的返回契约（任务 14 的前端表单直接依赖这几条，改动要同步）：**

| 情况 | success | value | errorMessage | timeout |
|---|---|---|---|---|
| 正常返回 | `true` | 返回值的字符串形式 | `null` | `false` |
| 脚本没有返回值（`null`） | `true` | `""` | `null` | `false` |
| 超过 5 秒 | `false` | `null` | `脚本执行超过 5 秒，已自动中断` | **`true`** |
| 安全拦截 | `false` | `null` | 带 `【安全拦截】` 前缀的中文 | `false` |
| 填值不合法 | `false` | `null` | `缺少占位符 x 的值…` / `占位符 x 需要整数…` | `false` |
| 运行期异常 | `false` | `null` | 翻译后的中文（不含类名与堆栈） | `false` |
| 线程池打满 | `false` | `null` | `当前运行的脚本过多，请稍后重试` | `false` |

前端约定：`value` 为空串时显示「（无返回值）」，不要显示成空白。

- [ ] **Step 1: 写失败测试 —— 运行结果**

`src/test/java/com/xd/rulescript/service/GroovyEngineRunTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.config.GroovySandboxConfig;
import com.xd.rulescript.config.ScriptExecutorConfig;
import com.xd.rulescript.dto.RunResult;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 沙箱运行的返回值与异常翻译。超时与拦截的用例在 GroovyEngineSandboxTest 里，
 * 因为它们要么慢（真等超时）要么要单独的引擎实例。
 */
class GroovyEngineRunTest {

    private static ExecutorService executor;
    private static GroovyEngineService engine;

    @BeforeAll
    static void setUp() {
        executor = ScriptExecutorConfig.build();
        engine = new GroovyEngineService(GroovySandboxConfig.build(), executor, 5);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 正常返回 ----------

    @Test
    void 返回整数() {
        RunResult r = engine.run("return 1 + 2", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("3");
        assertThat(r.errorMessage()).isNull();
        assertThat(r.timeout()).isFalse();
    }

    @Test
    void 返回字符串() {
        assertThat(engine.run("return \"你好，世界\"", Map.of()).value()).isEqualTo("你好，世界");
    }

    @Test
    void 占位符真值代入后计算正确() {
        RunResult r = engine.run("int age = ${age}\nreturn age >= 18 ? \"成年\" : \"未成年\"",
                Map.of("age", "28"));
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("成年");
    }

    @Test
    void 串内占位符替换后不带多余引号() {
        RunResult r = engine.run("String level = \"${level}\"\nreturn \"等级：\" + level",
                Map.of("level", "vip"));
        assertThat(r.value()).isEqualTo("等级：vip");
    }

    @Test
    void 串内值含美元符号不被二次插值() {
        RunResult r = engine.run("return \"价格：${price}\"", Map.of("price", "$100"));
        assertThat(r.value()).isEqualTo("价格：$100");
    }

    @Test
    void 多类型混合填值() {
        RunResult r = engine.run("""
                int age = ${age}
                long ts = ${ts}
                double rate = ${rate}
                boolean vip = ${vip}
                String name = ${name}
                return "${name}:" + age + ":" + rate + ":" + vip + ":" + (ts > 0)
                """,
                Map.of("age", "28", "ts", "1700000000", "rate", "0.85", "vip", "true", "name", "张三"));
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("张三:28:0.85:true:true");
    }

    @Test
    void 集合与闭包能正常跑() {
        RunResult r = engine.run("def list = [1, 2, 3]\nreturn list.collect { it * it }.sum()", Map.of());
        assertThat(r.value()).isEqualTo("14");
    }

    @Test
    void 无返回值的脚本给空串而不是null() {
        // 末尾裸 return 才真正无返回值：Groovy 脚本默认返回最后一个表达式的值，a = a + 1 会返回 2
        RunResult r = engine.run("def a = 1\na = a + 1\nreturn", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("");
    }

    @Test
    void 返回Map时转成字符串() {
        RunResult r = engine.run("return [a: 1, b: 2]", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).contains("a").contains("1");
    }

    // ---------- 填值错误 ----------

    @Test
    void 缺少填值给中文提示() {
        RunResult r = engine.run("return ${age}", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("缺少").contains("age");
        assertThat(r.timeout()).isFalse();
    }

    @Test
    void int填入字母给中文提示() {
        RunResult r = engine.run("int age = ${age}\nreturn age", Map.of("age", "abc"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("整数");
    }

    @Test
    void params传null给中文提示() {
        RunResult r = engine.run("return ${age}", null);
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("缺少");
    }

    // ---------- 运行期异常翻译 ----------

    @Test
    void 除以零翻译成中文() {
        RunResult r = engine.run("return 1 / 0", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("0");
        assertThat(r.errorMessage()).doesNotContain("ArithmeticException");
    }

    @Test
    void 空指针翻译成中文() {
        RunResult r = engine.run("def x = null\nreturn x.length()", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("空值");
        assertThat(r.errorMessage()).doesNotContain("NullPointerException");
    }

    @Test
    void 未定义变量翻译成中文并带变量名() {
        RunResult r = engine.run("return notDefinedVariable", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("notDefinedVariable");
        assertThat(r.errorMessage()).doesNotContain("MissingPropertyException");
    }

    @Test
    void 调用不存在的方法翻译成中文() {
        RunResult r = engine.run("return \"abc\".thisMethodDoesNotExist()", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).doesNotContain("MissingMethodException");
    }

    @Test
    void 下标越界翻译成中文() {
        // 用 .get(5) 而非 [5]：Groovy 列表 list[5] 越界返回 null 不抛异常，.get(5) 才真抛 IndexOutOfBoundsException
        RunResult r = engine.run("def list = [1]\nreturn list.get(5)", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).doesNotContain("IndexOutOfBoundsException");
    }

    @Test
    void 递归爆栈不会把服务搞挂() {
        RunResult r = engine.run("def f() { return f() }\nreturn f()", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).isNotBlank();
        // 关键：异常之后引擎还能继续用
        assertThat(engine.run("return 1", Map.of()).success()).isTrue();
    }

    @Test
    void 错误信息里不含堆栈() {
        String message = engine.run("return 1 / 0", Map.of()).errorMessage();
        assertThat(message).doesNotContain("\tat ").doesNotContain("at org.codehaus");
    }

    @Test
    void 运行期异常后引擎仍可继续使用() {
        engine.run("def x = null\nreturn x.length()", Map.of());
        engine.run("return 1 / 0", Map.of());
        assertThat(engine.run("return 42", Map.of()).value()).isEqualTo("42");
    }

    // ---------- stringify ----------

    @Test
    void null转空串() {
        assertThat(GroovyEngineService.stringify(null)).isEqualTo("");
    }

    @Test
    void 超长结果被截断() {
        RunResult r = engine.run("return 'a' * 50000", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).contains("截断");
        assertThat(r.value().length()).isLessThan(20000);
    }
}
```

- [ ] **Step 2: 写失败测试 —— 超时与拦截**

`src/test/java/com/xd/rulescript/service/GroovyEngineSandboxTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.config.GroovySandboxConfig;
import com.xd.rulescript.config.ScriptExecutorConfig;
import com.xd.rulescript.dto.RunResult;
import com.xd.rulescript.groovy.ScriptSecurityException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 沙箱的两条硬指标：死循环必须能在超时后真的停下来（线程不泄漏），
 * 危险操作必须在运行前就被拦住。
 *
 * <p>超时用 1 秒的独立引擎实例，避免整个测试套件被拖慢；生产值是 5 秒（app.script.timeout-seconds）。
 */
class GroovyEngineSandboxTest {

    private static ExecutorService executor;
    private static GroovyEngineService fastEngine;

    @BeforeAll
    static void setUp() {
        executor = ScriptExecutorConfig.build();
        fastEngine = new GroovyEngineService(GroovySandboxConfig.build(), executor, 1);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    // ---------- 超时 ----------

    @Test
    void 死循环超时被中断() {
        RunResult r = fastEngine.run("while (true) { }", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.timeout()).isTrue();
        assertThat(r.errorMessage()).contains("秒").contains("中断");
    }

    @Test
    void for循环死转也能超时() {
        RunResult r = fastEngine.run("for (;;) { }", Map.of());
        assertThat(r.timeout()).isTrue();
    }

    @Test
    void 闭包内的死循环也能超时() {
        RunResult r = fastEngine.run("def c = { while (true) { } }\nc.call()\nreturn 1", Map.of());
        assertThat(r.timeout()).isTrue();
    }

    @Test
    void 超时后线程被归还不会泄漏() throws InterruptedException {
        fastEngine.run("while (true) { }", Map.of());

        // 这条是整个沙箱设计的关键验证：@ThreadInterrupt 往循环里注入了中断检查点，
        // future.cancel(true) 才能真正停掉线程。没有它，线程会一直空转，跑十几次池子就废了。
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        long deadline = System.currentTimeMillis() + 3000;
        while (pool.getActiveCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(pool.getActiveCount()).as("超时后线程应被归还").isZero();
    }

    @Test
    void 连续超时后引擎依然可用() {
        for (int i = 0; i < 3; i++) {
            assertThat(fastEngine.run("while (true) { }", Map.of()).timeout()).isTrue();
        }
        RunResult r = fastEngine.run("return 1 + 1", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("2");
    }

    @Test
    void 正常脚本不受超时逻辑影响() {
        RunResult r = fastEngine.run("def sum = 0\nfor (int i = 0; i < 1000; i++) { sum += i }\nreturn sum",
                Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("499500");
        assertThat(r.timeout()).isFalse();
    }

    // ---------- 运行前的安全拦截 ----------

    @Test
    void 运行时同样拦截System() {
        RunResult r = fastEngine.run("System.exit(0)\nreturn 1", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
    }

    @Test
    void 运行时拦截文件读取() {
        RunResult r = fastEngine.run("return new File('/etc/passwd').text", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
        assertThat(r.errorMessage()).contains("文件");
    }

    @Test
    void 运行时拦截外部命令() {
        RunResult r = fastEngine.run("return Runtime.getRuntime().exec('ls').text", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
    }

    @Test
    void 运行时拦截网络请求() {
        RunResult r = fastEngine.run("return new URL('http://example.com').text", Map.of());
        assertThat(r.success()).isFalse();
        assertThat(ScriptSecurityException.isSecurityMessage(r.errorMessage())).isTrue();
        assertThat(r.errorMessage()).contains("网络");
    }

    @Test
    void 拦截信息不含堆栈() {
        String message = fastEngine.run("System.exit(0)", Map.of()).errorMessage();
        assertThat(message).doesNotContain("\tat ").doesNotContain("MultipleCompilationErrorsException");
    }

    // ---------- 误伤检查 ----------

    @Test
    void 正常业务脚本不被沙箱误伤() {
        // discount 用 BigDecimal（不带 d 后缀）：0.8*0.9 在 double 下是 0.7200000000000001，BigDecimal 才精确等于 0.72
        String script = """
                int age = ${age}
                String level = "${level}"
                def discount = 1.0
                if (level == "vip") {
                    discount = 0.8
                }
                if (age >= 60) {
                    discount = discount * 0.9
                }
                def tags = ["年龄:" + age, "等级:" + level]
                return tags.join(" / ") + " 折扣:" + discount
                """;
        RunResult r = fastEngine.run(script, Map.of("age", "65", "level", "vip"));
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("年龄:65 / 等级:vip 折扣:0.72");
    }

    @Test
    void 字符串方法与数学函数不被误伤() {
        RunResult r = fastEngine.run(
                "def s = 'Hello World'\nreturn s.toLowerCase().replaceAll('o', '0') + Math.max(1, 2)", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("hell0 w0rld2");
    }

    @Test
    void 日期计算不被误伤() {
        RunResult r = fastEngine.run("return new Date(0).time", Map.of());
        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("0");
    }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test 2>&1 | tail -20
```

预期编译失败（`run` / `stringify` 还不存在）。

- [ ] **Step 3: 给 `GroovyEngineService` 补 `run` 与异常翻译**

在任务 10 写的 `GroovyEngineService.java` 里，把三个「供任务 11 使用」的包级访问器（`timeoutSeconds()` / `scriptExecutor()` / `compilerConfiguration()`）**替换**成下面的实现，并补上 import：

```java
// 追加到 import 区
import com.xd.rulescript.dto.RunResult;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
```

```java
    /** 运行结果字符串的上限，防止脚本返回一个几 MB 的串把响应撑爆 */
    private static final int MAX_VALUE_LENGTH = 10_000;

    /** 错误信息的上限，够定位问题即可 */
    private static final int MAX_ERROR_LENGTH = 300;

    /**
     * 沙箱运行脚本。
     *
     * <p>流程：推断类型 → 把占位符替换成真实字面量 → 提交到独立线程池编译并执行 →
     * 限时等待。超时后 cancel(true) 中断，配合任务 10 注入的 @ThreadInterrupt 检查点，
     * 死循环才真的停得下来。
     *
     * <p>任何异常都在这一层收口，翻译成中文，绝不让堆栈或英文类名流到前端。
     */
    public RunResult run(String script, Map<String, String> params) {
        if (script == null || script.isBlank()) {
            return new RunResult(false, null, "脚本内容为空，请先写点东西再运行", false);
        }

        Map<String, String> types = PlaceholderTypeInferer.infer(script);
        String source;
        try {
            source = PlaceholderSubstitutor.toLiterals(script, params, types);
        } catch (PlaceholderValueException e) {
            return new RunResult(false, null, e.getMessage(), false);
        }

        Future<Object> future;
        try {
            future = scriptExecutor.submit(() -> {
                // 每次新建 GroovyShell，避免生成的脚本类都挂在同一个 ClassLoader 上导致 metaspace 泄漏
                return new GroovyShell(compilerConfiguration).evaluate(source);
            });
        } catch (RejectedExecutionException e) {
            return new RunResult(false, null, "当前运行的脚本过多，请稍后重试", false);
        }

        try {
            Object value = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return new RunResult(true, stringify(value), null, false);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new RunResult(false, null,
                    "脚本执行超过 " + timeoutSeconds + " 秒，已自动中断。请检查是否有死循环或过大的计算量", true);
        } catch (ExecutionException e) {
            return new RunResult(false, null, translateRunError(e.getCause()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RunResult(false, null, "脚本执行被中断，请重试", false);
        }
    }

    /**
     * 把返回值转成字符串。null 统一给空串，前端据此显示「（无返回值）」。
     * 超长结果截断，避免一个脚本把响应体撑到几 MB。
     */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        try {
            text = String.valueOf(value);
        } catch (RuntimeException e) {
            // toString 自己抛异常的极端情况，不能让整个运行失败
            return "（返回值无法转成文本）";
        }
        if (text.length() > MAX_VALUE_LENGTH) {
            return text.substring(0, MAX_VALUE_LENGTH) + "…（结果过长，已截断）";
        }
        return text;
    }

    /**
     * 运行期异常翻译成可读中文。
     *
     * <p>两条硬要求：不能出现英文异常类名，不能出现堆栈。所以每种常见异常都有对照说法，
     * 认不出来的只取原因首行并剥掉「com.x.Y: 」这种类名前缀。
     */
    public static String translateRunError(Throwable cause) {
        Throwable t = unwrap(cause);
        if (t == null) {
            return "脚本执行出错，请检查脚本内容";
        }

        // 编译期问题（含安全拦截）复用语法校验那一套提取逻辑
        if (t instanceof MultipleCompilationErrorsException mce) {
            SyntaxCheckResult result = fromCompileErrors(mce);
            return result.message() == null ? "脚本无法编译，请检查写法后重试" : result.message();
        }
        if (t instanceof InterruptedException || t instanceof java.io.InterruptedIOException) {
            return "脚本被中断（可能因为执行超时）";
        }
        if (t instanceof StackOverflowError) {
            return "脚本递归太深或循环嵌套过多，已终止";
        }
        if (t instanceof OutOfMemoryError) {
            return "脚本占用的内存过大，已终止。请减少一次性构造的数据量";
        }
        if (t instanceof MissingPropertyException mpe) {
            return "用到了未定义的变量「" + mpe.getProperty() + "」";
        }
        if (t instanceof MissingMethodException mme) {
            return "调用了不存在的方法「" + mme.getMethod() + "」，请检查方法名和参数";
        }
        if (t instanceof ArithmeticException) {
            return "计算出错，可能是除以 0";
        }
        if (t instanceof NullPointerException) {
            return "脚本里出现了空值，对 null 取属性或调用方法了";
        }
        if (t instanceof ClassCastException) {
            return "类型转换失败，值的实际类型和期望的不一致";
        }
        if (t instanceof NumberFormatException) {
            return "字符串转数字失败，请检查填进去的值";
        }
        if (t instanceof IndexOutOfBoundsException) {
            return "下标越界，访问了不存在的位置";
        }
        if (t instanceof UnsupportedOperationException) {
            return "不支持的操作，可能是修改了只读的集合";
        }
        if (t instanceof SecurityException) {
            return ScriptSecurityException.MARKER + "操作被沙箱禁止";
        }

        String raw = t.getMessage();
        if (raw == null || raw.isBlank()) {
            return "脚本执行出错：" + simpleTypeName(t);
        }
        return truncate("脚本执行出错：" + stripClassPrefix(firstLine(raw)));
    }

    /** 剥掉 InvocationTargetException 这类包装，拿到真正的业务异常 */
    private static Throwable unwrap(Throwable cause) {
        Throwable t = cause;
        int guard = 0;
        while (t != null && guard++ < 10
                && (t instanceof java.lang.reflect.InvocationTargetException
                        || t instanceof java.lang.reflect.UndeclaredThrowableException
                        || t instanceof groovy.lang.GroovyRuntimeException && t.getCause() != null
                        && t.getMessage() == null)) {
            t = t.getCause();
        }
        return t;
    }

    /** 去掉消息里的「com.foo.Bar: 」类名前缀，Global Constraints 不允许英文类名出现在前端 */
    private static String stripClassPrefix(String raw) {
        String text = raw.trim();
        int colon = text.indexOf(':');
        if (colon > 0 && colon < 80) {
            String head = text.substring(0, colon);
            // 只有整段都是「点分隔的标识符」才认定是类名前缀，避免误删正常中文冒号前的内容
            if (head.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
                return text.substring(colon + 1).trim();
            }
        }
        return text;
    }

    /** 只取首行，Groovy 的异常消息常常后面跟着一大段候选列表 */
    private static String firstLine(String raw) {
        int nl = raw.indexOf('\n');
        return nl > 0 ? raw.substring(0, nl) : raw;
    }

    /** 认不出异常类型时给个中文说法，不直接暴露类名 */
    private static String simpleTypeName(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            return "参数不合法";
        }
        if (t instanceof IllegalStateException) {
            return "状态不正确";
        }
        if (t instanceof groovy.lang.GroovyRuntimeException) {
            return "脚本运行出错";
        }
        return "脚本内部错误";
    }

    private static String truncate(String text) {
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) + "…" : text;
    }
```

同时把任务 10 里那三个临时访问器删掉（它们只是为了占位，现在字段可以直接用）：

```java
    // 删除这三段：timeoutSeconds()、scriptExecutor()、compilerConfiguration()
```

- [ ] **Step 4: 跑测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 145 个测试通过（99 + RunTest 26 + SandboxTest 20）。整个套件会因为超时用例多花约 6~8 秒，属正常。

常见失败与对策：

| 红的测试 | 十有八九的原因 |
|---|---|
| `超时后线程被归还不会泄漏` | 任务 10 的 `ASTTransformationCustomizer(ThreadInterrupt.class)` 没加，或注解包名写成了 `org.codehaus.groovy.transform`（那是变换实现类，不带注解元信息，customizer 会静默失效）。**这条红了不要绕过测试，它证明的是整个沙箱设计成不成立** |
| `闭包内的死循环也能超时` | `ThreadInterrupt` 默认 `applyToAllClasses=true`，但若被改过参数会漏掉闭包；确认用的是无参构造 |
| `正常业务脚本不被沙箱误伤` | 黑名单误伤了 `Date` / `Math` 之类；检查 `ScriptSafetyCustomizer.FORBIDDEN` 有没有加进不该加的名字 |
| `除以零翻译成中文` 仍含 `ArithmeticException` | `stripClassPrefix` 没生效，或直接用了 `t.toString()`（那个必然带类名）。要用 `t.getMessage()` |
| `未定义变量翻译成中文并带变量名` | `MissingPropertyException.getProperty()` 拿的是属性名；若为 null 要退回通用说法 |
| `无返回值的脚本给空串而不是null` | `stringify` 对 null 返回了 `"null"` 字符串 |
| `连续超时后引擎依然可用` 卡住 | 线程池核心线程被泄漏的脚本占满，新任务排队；本质还是第一条的问题 |

- [ ] **Step 5: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: Groovy 沙箱运行（5 秒超时中断 + 运行期异常中文翻译）"
```

---

## Task 12: validate / run 接口

把任务 9~11 的能力接到 HTTP 上。**注意本任务的 validate 还不含大模型 CR** —— `AiService` 要到任务 16 才写，所以这里先返回降级文案，任务 16 会回来把这一处 TODO 替换掉。这是有意的增量顺序：先让「语法校验 + 占位符提取 + 运行」整条链路能跑通，AI 是叠加上去的第三段。

**Files:**
- Modify: `script_back/src/main/java/com/xd/rulescript/service/RuleService.java`（补 `validate` / `run`）
- Modify: `script_back/src/main/java/com/xd/rulescript/controller/RuleController.java`（补两个端点）
- Modify: `script_back/src/test/java/com/xd/rulescript/service/RuleServiceTest.java`（加 `app.ai.enabled=false` 并补用例）
- Modify: `script_front/src/api/rule.js`（补 `validateScript` / `runScript`）

**Interfaces:**
- Consumes: `GroovyEngineService`（任务 10、11）、`ValidateRequest` / `ValidateResponse` / `RunRequest` / `RunResponse` / `AiReviewResult` / `PlaceholderInfo`（任务 5）
- Produces:
  - `ValidateResponse RuleService.validate(ValidateRequest)`
  - `RunResponse RuleService.run(RunRequest)`
  - `POST /api/rule/validate` → `ApiResponse<ValidateResponse>`
  - `POST /api/rule/run` → `ApiResponse<RunResponse>`
  - `validateScript(scriptContent)` → `Promise<ValidateResponse>`（前端）
  - `runScript(scriptContent, params)` → `Promise<RunResponse>`（前端）

**行为约定：**
- `validate` 与 `run` 都**只吃请求体里的脚本内容**，不读数据库。需求文档交互规则 #3 说得很明确：校验、运行、AI 对话用的都是编辑器当前内容，含未保存的修改。所以这两个接口不需要 `ruleId`。
- `validate` 的三步是同步串行的，全部完成才返回（需求文档 4.3.2）。语法不通过时**不再调 CR**，直接返回 —— 省时间也省额度。
- CR 降级时 `aiReview.available = false`，`text` 给中文说明，`suggestedScript = null`。**语法结论不受 CR 影响**：只要 `syntaxOk = true`，前端就该解锁运行按钮（需求文档交互规则 #5：CR 是建议性质，不阻断运行）。

- [ ] **Step 1: 给 `RuleServiceTest` 加降级开关并写失败测试**

把类上的注解改成：

```java
@SpringBootTest(properties = "app.ai.enabled=false")
@Transactional
class RuleServiceTest {
```

> 为什么必须加这一行：任务 16 接上真实 CR 之后，`validate` 会真的发起大模型调用。单测里跑真实调用会烧额度、会因网络抖动变红。Global Constraints 第 4 条已经把这个定为硬约束。

追加用例（放在类末尾）：

> 注：不需在测试里 `@Autowired GroovyEngineService` —— 下面 10 条用例全部走 `ruleService.validate/run`，没有一个直接用引擎，加了就是死字段（而且它与测试同包， import 也多余）。

```java
    // ---------- validate ----------

    @Test
    void 校验合法脚本返回语法通过和占位符列表() {
        ValidateResponse r = ruleService.validate(new ValidateRequest(
                "int age = ${age}\nString level = \"${level}\"\nreturn age >= 18 ? level : \"minor\""));

        assertThat(r.syntaxOk()).isTrue();
        assertThat(r.errorLine()).isNull();
        assertThat(r.errorMessage()).isNull();
        assertThat(r.placeholders()).containsExactly(
                new PlaceholderInfo("age", "int"),
                new PlaceholderInfo("level", "String"));
    }

    @Test
    void 校验语法错误脚本返回行号与中文原因() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("int a = 1\nreturn a + b))"));

        assertThat(r.syntaxOk()).isFalse();
        assertThat(r.errorLine()).isEqualTo(2);
        assertThat(r.errorMessage()).isNotBlank();
        // 语法不通过时占位符仍要给，方便用户改完再校验
        assertThat(r.placeholders()).isNotNull();
    }

    @Test
    void 语法错误时不调用大模型CR() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("return ((( "));

        assertThat(r.syntaxOk()).isFalse();
        assertThat(r.aiReview().available()).isFalse();
        assertThat(r.aiReview().suggestedScript()).isNull();
    }

    @Test
    void AI关闭时校验仍给出降级文案且不影响语法结论() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("return 1 + 1"));

        assertThat(r.syntaxOk()).isTrue();
        assertThat(r.aiReview().available()).isFalse();
        assertThat(r.aiReview().text()).isNotBlank();
        assertThat(r.aiReview().suggestedScript()).isNull();
    }

    @Test
    void 校验空脚本不报错() {
        ValidateResponse r = ruleService.validate(new ValidateRequest(""));
        assertThat(r.syntaxOk()).isTrue();
        assertThat(r.placeholders()).isEmpty();
    }

    @Test
    void 校验危险脚本被拦截且语法结论为不通过() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("System.exit(0)\nreturn 1"));

        assertThat(r.syntaxOk()).isFalse();
        assertThat(r.errorMessage()).contains("【安全拦截】");
    }

    // ---------- run ----------

    @Test
    void 运行返回结果字符串() {
        RunResponse r = ruleService.run(new RunRequest(
                "int age = ${age}\nreturn age >= 18 ? \"成年\" : \"未成年\"",
                Map.of("age", "28")));

        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("成年");
        assertThat(r.errorMessage()).isNull();
        assertThat(r.timeout()).isFalse();
    }

    @Test
    void 运行缺少填值返回中文提示而不是异常() {
        RunResponse r = ruleService.run(new RunRequest("return ${age}", Map.of()));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("缺少");
    }

    @Test
    void 运行危险脚本被拦截() {
        RunResponse r = ruleService.run(new RunRequest(
                "return new File('/etc/passwd').text", Map.of()));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("【安全拦截】");
    }

    @Test
    void 运行结果不泄露堆栈() {
        RunResponse r = ruleService.run(new RunRequest("return 1 / 0", Map.of()));
        assertThat(r.errorMessage()).doesNotContain("\tat ").doesNotContain("Exception");
    }
```

需要在测试类 import 区补：

```java
import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.RunRequest;
import com.xd.rulescript.dto.RunResponse;
import com.xd.rulescript.dto.ValidateRequest;
import com.xd.rulescript.dto.ValidateResponse;
import java.util.Map;
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test 2>&1 | tail -20
```

预期编译失败（`RuleService.validate` / `run` 还不存在）。

- [ ] **Step 2: 给 `RuleService` 补两个方法**

在 `RuleService.java` 里注入引擎并补方法。构造器改成：

```java
    private final RuleRepository ruleRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final TestCaseRepository testCaseRepository;
    private final GroovyEngineService groovyEngineService;

    public RuleService(RuleRepository ruleRepository,
                       ConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       TestCaseRepository testCaseRepository,
                       GroovyEngineService groovyEngineService) {
        this.ruleRepository = ruleRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.testCaseRepository = testCaseRepository;
        this.groovyEngineService = groovyEngineService;
    }
```

把任务 5 里那两处「validate 与 run 在任务 12 补上」的占位注释替换成实现：

```java
    /**
     * 同步校验：语法校验 → 提取占位符 → 大模型 CR，三步全部完成才返回。
     *
     * <p>语法不通过时不再调 CR：既省时间也省额度，用户改完再校验一次就行。
     * <p>CR 是建议性质，不影响 syntaxOk（需求文档交互规则 #5）。
     */
    public ValidateResponse validate(ValidateRequest request) {
        String script = request == null ? null : request.scriptContent();

        SyntaxCheckResult syntax = groovyEngineService.checkSyntax(script);
        List<PlaceholderInfo> placeholders = groovyEngineService.extractPlaceholders(script);

        AiReviewResult review;
        if (!syntax.ok()) {
            // 语法都没过，不必惊动大模型
            review = new AiReviewResult("语法未通过，已跳过 AI 审查。请先修正上面的语法错误", null, false);
        } else {
            // TODO(任务 16): 换成 aiService.reviewScript(script)。
            // 现在 AiService 还不存在，先返回降级文案，保证整条校验链路能先跑通。
            review = new AiReviewResult("AI 审查尚未接入，语法校验与运行不受影响", null, false);
        }

        return new ValidateResponse(
                syntax.ok(),
                syntax.line(),
                syntax.message(),
                placeholders,
                review);
    }

    /**
     * 沙箱运行。只吃请求体里的脚本与填值，不读数据库 ——
     * 需求文档交互规则 #3：运行用的是编辑器当前内容，含未保存的修改。
     */
    public RunResponse run(RunRequest request) {
        if (request == null) {
            throw new BizException("运行参数不能为空");
        }
        RunResult result = groovyEngineService.run(request.scriptContent(), request.params());
        return new RunResponse(result.success(), result.value(), result.errorMessage(), result.timeout());
    }
```

import 区补：

```java
import com.xd.rulescript.dto.AiReviewResult;
import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.RunRequest;
import com.xd.rulescript.dto.RunResponse;
import com.xd.rulescript.dto.RunResult;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.dto.ValidateRequest;
import com.xd.rulescript.dto.ValidateResponse;
import java.util.List;
```

- [ ] **Step 3: 给 `RuleController` 补两个端点**

```java
    /** 同步校验：语法 + 占位符 + 大模型 CR，全部完成才返回 */
    @PostMapping("/validate")
    public ApiResponse<ValidateResponse> validate(@RequestBody ValidateRequest request) {
        return ApiResponse.ok(ruleService.validate(request));
    }

    /** 沙箱运行，超时 5 秒自动中断 */
    @PostMapping("/run")
    public ApiResponse<RunResponse> run(@RequestBody RunRequest request) {
        return ApiResponse.ok(ruleService.run(request));
    }
```

import 区补 `RunRequest` / `RunResponse` / `ValidateRequest` / `ValidateResponse`。

> 注意路径：类上已经有 `@RequestMapping("/api/rule")`，所以方法上只写 `/validate`、`/run`，别重复写全路径。

- [ ] **Step 4: 跑测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，共 143 个测试通过（133 + 本任务 10）。

- [ ] **Step 5: 起服务手工验一次接口**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q spring-boot:run
```

另开终端：

```bash
curl -s -X POST http://localhost:8080/api/rule/validate \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"int age = ${age}\nreturn age >= 18 ? \"成年\" : \"未成年\""}'
```

预期 `code=0`，`data.syntaxOk=true`，`data.placeholders` 是 `[{"name":"age","type":"int"}]`，`data.aiReview.available=false`。

```bash
curl -s -X POST http://localhost:8080/api/rule/run \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"int age = ${age}\nreturn age >= 18 ? \"成年\" : \"未成年\"","params":{"age":"28"}}'
```

预期 `data.success=true`、`data.value="成年"`。

再验一次拦截（**这条必须失败**，失败才说明沙箱在工作）：

```bash
curl -s -X POST http://localhost:8080/api/rule/run \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"return new File(\"/etc/passwd\").text","params":{}}'
```

预期 `data.success=false`，`data.errorMessage` 以 `【安全拦截】` 开头，**且不含任何堆栈或英文类名**。

验证完 `Ctrl+C` 停掉。

- [ ] **Step 6: 前端补两个 API 函数**

在 `script_front/src/api/rule.js` 里，把任务 6 留的「校验（validateScript）与运行（runScript）在任务 12 补上」注释替换成：

```js
/**
 * 同步校验脚本：语法 + 占位符提取 + 大模型 CR，后端全部做完才返回。
 * 慢是常态（CR 要几秒到十几秒），调用方必须自己管加载态。
 * @returns {Promise<{syntaxOk:boolean, errorLine:number|null, errorMessage:string|null,
 *                    placeholders:Array<{name:string,type:string}>,
 *                    aiReview:{text:string, suggestedScript:string|null, available:boolean}}>}
 */
export function validateScript(scriptContent) {
  return post('/api/rule/validate', { scriptContent })
}

/**
 * 沙箱运行脚本。只传编辑器当前内容与填值，不需要 ruleId ——
 * 运行的永远是编辑器里的内容，含未保存的修改。
 * @returns {Promise<{success:boolean, value:string|null, errorMessage:string|null, timeout:boolean}>}
 */
export function runScript(scriptContent, params) {
  return post('/api/rule/run', { scriptContent, params })
}
```

- [ ] **Step 7: 前端构建校验**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：单测 **15 个**通过（任务 3 的 6 + 任务 6 的 9，本任务不新增），构建成功。本任务的 `api/script.js` 是 `post()` 的薄封装（两个函数都是一行转发，无任何分支），按「只给有分支逻辑的纯 JS 模块写单测」的策略不写单测 —— 注意别和任务 6 的 `api/rule.js` 搞混，那个有三个 `if (x !== undefined)`，是要测的。

- [ ] **Step 8: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back script_front
git commit -m "feat: 校验与运行接口（同步三步校验，CR 待任务 16 接入）"
```

---

## Task 13: 工作台页面骨架 + ScriptEditor

编辑器是整个工作台的地基，也是唯一一个「用错库就全盘返工」的组件。已核实：`@codemirror/legacy-modes@6.4.0/mode/groovy.js` 存在，导出方式是 `export const groovy = {...}`（一个 legacy mode 对象，**必须**用 `StreamLanguage.define()` 包一层才能给 CodeMirror 6 用，直接传进去不会报错但也不会有高亮）。

`.cm-errorLine`、`.cm-placeholder` 这些类名任务 3 已经写进 `theme.css` 了，本任务只负责把装饰挂到正确的类名上，**不要在组件里重写颜色**。

**Files:**
- Create: `script_front/src/components/ScriptEditor.vue`
- Modify: `script_front/src/views/RuleWorkbenchView.vue`（整体替掉任务 3 的占位骨架）

**Interfaces:**
- Consumes: `getRuleDetail` / `updateRule`（任务 6）、`reportError`（任务 3）、`theme.css` 的 CSS 变量与 `.cm-*` 类（任务 3）
- Produces（`ScriptEditor.vue`，任务 14/15/19 都要用）:
  - props：`modelValue: String`（编辑器内容，双向）、`errorLine: Number|null`（要标红的行号，1 起算）
  - emits：`update:modelValue(String)`、`save()`（Ctrl+S / Cmd+S 触发）
  - expose：`focus()`
  - 行为：外部改 `modelValue`（例如任务 15 的「应用到编辑器」）会整篇覆盖编辑器内容，并**保留撤销历史**

**布局契约（任务 14、15、19 往里填，先定死结构免得后面返工）：**

```
.workbench (height:100%, flex column)
├── .topbar-slot        ← 任务 14 放 TopBar.vue（本任务先内联一个只有[保存]的简版）
└── .body (flex:1, flex row, gap:16px, overflow:hidden)
    ├── .left (flex:1, flex column, min-width:0)
    │   ├── .editor-slot (flex:1, min-height:0)   ← ScriptEditor
    │   └── .bottom-slot (height:280px)           ← 任务 14 放标签页：校验结果/填值/运行结果
    └── .right (width:380px, flex-shrink:0)       ← 任务 19 放 ChatPanel
```

`.left` 必须有 `min-width:0`，否则 CodeMirror 的长行会把 flex 容器撑破，右侧面板被挤没。这是 flex 布局的经典坑，先写上。

- [ ] **Step 1: 写 `ScriptEditor.vue`**

`src/components/ScriptEditor.vue`

```vue
<script setup>
/**
 * Groovy 脚本编辑器（CodeMirror 6）。
 *
 * 三个能力：groovy 语法高亮、错误行标红、占位符 ${x} 高亮。
 * 颜色一律走 theme.css 里的 .cm-* 类，组件内不写死色值（风格三的统一要求）。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorState, StateEffect, StateField } from '@codemirror/state'
import {
  Decoration,
  EditorView,
  ViewPlugin,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { StreamLanguage, defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { groovy } from '@codemirror/legacy-modes/mode/groovy'

const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 语法错误行号，1 起算；null 表示不标红 */
  errorLine: { type: Number, default: null },
})
const emit = defineEmits(['update:modelValue', 'save'])

const host = ref(null)
let view = null
/** 外部整篇覆盖内容时置 true，避免回写 emit 造成父子互相触发的死循环 */
let applyingExternal = false

// ---------- 错误行标红 ----------

const setErrorLine = StateEffect.define()

const errorLineField = StateField.define({
  create: () => Decoration.none,
  update(decorations, tr) {
    // 内容变了先让装饰跟着位移，否则行号会错位
    decorations = decorations.map(tr.changes)
    for (const effect of tr.effects) {
      if (effect.is(setErrorLine)) {
        const line = effect.value
        if (!line || line < 1 || line > tr.state.doc.lines) {
          return Decoration.none
        }
        const mark = Decoration.line({ class: 'cm-errorLine' })
        return Decoration.set([mark.range(tr.state.doc.line(line).from)])
      }
    }
    return decorations
  },
  provide: (field) => EditorView.decorations.from(field),
})

// ---------- 占位符高亮 ----------

const PLACEHOLDER_PATTERN = /\$\{\w+\}/g
const PLACEHOLDER_MARK = Decoration.mark({ class: 'cm-placeholder' })

const placeholderHighlighter = ViewPlugin.fromClass(
  class {
    constructor(v) {
      this.decorations = buildPlaceholderDecorations(v)
    }
    update(u) {
      if (u.docChanged || u.viewportChanged) {
        this.decorations = buildPlaceholderDecorations(u.view)
      }
    }
  },
  { decorations: (instance) => instance.decorations },
)

/** 只扫当前视口，超大脚本也不会卡 */
function buildPlaceholderDecorations(v) {
  const marks = []
  const { from, to } = v.visibleRanges.length
    ? { from: v.visibleRanges[0].from, to: v.visibleRanges[v.visibleRanges.length - 1].to }
    : { from: 0, to: v.state.doc.length }
  const text = v.state.sliceDoc(from, to)
  PLACEHOLDER_PATTERN.lastIndex = 0
  let match
  while ((match = PLACEHOLDER_PATTERN.exec(text)) !== null) {
    marks.push(PLACEHOLDER_MARK.range(from + match.index, from + match.index + match[0].length))
  }
  return Decoration.set(marks, true)
}

// ---------- 装配 ----------

function createView() {
  const saveKeymap = keymap.of([
    {
      key: 'Mod-s',
      preventDefault: true,
      run: () => {
        emit('save')
        return true
      },
    },
    indentWithTab,
    ...defaultKeymap,
    ...historyKeymap,
  ])

  const state = EditorState.create({
    doc: props.modelValue ?? '',
    extensions: [
      lineNumbers(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      history(),
      saveKeymap,
      StreamLanguage.define(groovy),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      errorLineField,
      placeholderHighlighter,
      EditorView.lineWrapping,
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !applyingExternal) {
          emit('update:modelValue', update.state.doc.toString())
        }
      }),
    ],
  })

  return new EditorView({ state, parent: host.value })
}

onMounted(() => {
  view = createView()
  applyErrorLine(props.errorLine)
})

onBeforeUnmount(() => {
  view?.destroy()
  view = null
})

// 外部改内容（保存回填、AI 建议一键应用）→ 整篇替换。
// 用 changes 而不是重建编辑器，这样撤销历史还在，用户 Ctrl+Z 能退回去。
watch(
  () => props.modelValue,
  (next) => {
    if (!view) return
    const current = view.state.doc.toString()
    if (current === (next ?? '')) return
    applyingExternal = true
    view.dispatch({
      changes: { from: 0, to: current.length, insert: next ?? '' },
    })
    applyingExternal = false
  },
)

watch(() => props.errorLine, (line) => applyErrorLine(line))

function applyErrorLine(line) {
  if (!view) return
  view.dispatch({ effects: setErrorLine.of(line ?? null) })
}

function focus() {
  view?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div ref="host" class="script-editor"></div>
</template>

<style scoped>
.script-editor {
  height: 100%;
  overflow: hidden;
  border: 1px solid var(--border-light);
  border-radius: var(--panel-radius);
  background: var(--panel-bg);
}
</style>
```

**实现要点（CodeMirror 6 这里最容易踩的三个坑）：**

1. **`import` 必须全部写在 `<script setup>` 顶层。** SFC 编译成 ES module，文件中间插 `import` 直接语法报错。
2. **`EditorView.decorations.from(...)` 只能包 `StateField`**，不能包 `ViewPlugin`。`ViewPlugin` 的装饰靠第二个参数 `{ decorations: instance => instance.decorations }` 暴露。两套机制别混用（混用的典型症状是编辑器能渲染但装饰一条都不出现）。
3. **占位符扫描只扫 `view.visibleRanges`**，不要 `doc.iter()` 全文扫。规则脚本可能上千行，全文正则在每次按键时都会跑一遍，能明显感觉到卡。

- [ ] **Step 2: 替换 `RuleWorkbenchView.vue`**

`src/views/RuleWorkbenchView.vue`（整体替掉任务 3 的占位骨架）

```vue
<script setup>
/**
 * 规则工作台总装页。
 *
 * 这里是「编辑器当前内容」的唯一持有者：校验、运行、AI 对话、AI 审查
 * 用的都是这份内容（含未保存的修改），不是数据库里的已保存版本 ——
 * 需求文档交互规则 #3。
 *
 * 本任务只装编辑器 + 保存。校验/运行面板见任务 14，AI 审查卡片见任务 15，
 * 右侧对话面板见任务 19。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ScriptEditor from '../components/ScriptEditor.vue'
import { getRuleDetail, updateRule } from '../api/rule'
import { reportError } from '../api/http'

const props = defineProps({ id: { type: String, required: true } })

const ruleId = computed(() => Number(props.id))
const ruleName = ref('')
const updatedAt = ref('')
const scriptContent = ref('')
const loading = ref(true)
const saving = ref(false)
const loadFailed = ref(false)

/** 已保存的脚本快照，用来判断「有未保存的修改」 */
const savedScript = ref('')
const dirtyForSave = computed(() => scriptContent.value !== savedScript.value)

onMounted(loadDetail)

async function loadDetail() {
  loading.value = true
  loadFailed.value = false
  try {
    const detail = await getRuleDetail(ruleId.value)
    ruleName.value = detail.name
    updatedAt.value = detail.updatedAt
    scriptContent.value = detail.scriptContent ?? ''
    savedScript.value = scriptContent.value
  } catch (e) {
    loadFailed.value = true
    reportError(e)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (saving.value) return
  saving.value = true
  try {
    const detail = await updateRule({ ruleId: ruleId.value, scriptContent: scriptContent.value })
    savedScript.value = detail.scriptContent ?? scriptContent.value
    updatedAt.value = detail.updatedAt
    ElMessage.success('已保存')
  } catch (e) {
    reportError(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="workbench" v-loading="loading">
    <!-- topbar-slot：任务 14 换成 <TopBar>，这里先内联一个只有保存的简版 -->
    <div class="topbar">
      <div class="logo">脚本规则工作台</div>
      <div class="rule-name">{{ ruleName || '加载中…' }}</div>
      <div class="spacer"></div>
      <div class="updated" v-if="updatedAt">更新于 {{ updatedAt }}</div>
      <el-button class="btn-save" :loading="saving" :disabled="!dirtyForSave" @click="handleSave">
        保存<span class="kbd">⌘S</span>
      </el-button>
    </div>

    <div class="body">
      <div class="left">
        <div class="editor-slot">
          <ScriptEditor v-model="scriptContent" :error-line="null" @save="handleSave" />
        </div>
        <!-- bottom-slot：任务 14 放「校验结果 / 填值 / 运行结果」标签页 -->
        <div class="bottom-slot">
          <el-empty description="校验与运行面板待接入（任务 14）" :image-size="72" />
        </div>
      </div>
      <!-- right-slot：任务 19 放 ChatPanel -->
      <div class="right">
        <el-empty description="AI 对话面板待接入（任务 19）" :image-size="72" />
      </div>
    </div>

    <el-dialog v-model="loadFailed" title="加载失败" width="420px" :close-on-click-modal="false">
      <p>规则详情没能加载出来，可能是规则已被删除。</p>
      <template #footer>
        <el-button @click="loadDetail">重试</el-button>
        <el-button type="primary" @click="$router.push('/')">回列表页</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.workbench { height: 100%; display: flex; flex-direction: column; }

.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  height: 56px;
  flex-shrink: 0;
  background: var(--brand-gradient);
  box-shadow: var(--topbar-shadow);
  color: #fff;
}
.logo { font-size: 15px; font-weight: 600; opacity: .92; }
.rule-name { font-size: 15px; font-weight: 600; }
.spacer { flex: 1; }
.updated { font-size: 12px; opacity: .75; }
.btn-save { border-radius: 20px; }
.kbd { margin-left: 6px; font-size: 11px; opacity: .6; }

.body {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px 20px 20px;
  min-height: 0;          /* 允许子项收缩，否则编辑器会把页面撑出滚动条 */
  overflow: hidden;
}
.left {
  flex: 1;
  min-width: 0;          /* 关键：不给 0 的话 CodeMirror 长行会撑破 flex，把右侧面板挤没 */
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.editor-slot { flex: 1; min-height: 0; }
.bottom-slot {
  height: 280px;
  flex-shrink: 0;
  background: var(--panel-bg);
  border-radius: var(--panel-radius);
  box-shadow: var(--panel-shadow);
  padding: 16px;
  overflow: auto;
}
.right {
  width: 380px;
  flex-shrink: 0;
  background: var(--panel-bg);
  border-radius: var(--panel-radius);
  box-shadow: var(--panel-shadow);
  padding: 16px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
```

- [ ] **Step 3: 构建验证**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：单测仍 6 个通过（本任务不新增单测，理由见 Step 5），构建成功。

构建失败的常见原因：
- `'groovy' is not exported by ...` → legacy-modes 的 groovy 模式是**具名导出** `export const groovy`，别写成 `import groovy from`
- `Unexpected token 'import'` → 有 `import` 没写在 `<script setup>` 顶层（参考实现要点第 1 条）
- 构建能过但编辑器里占位符/错误行**一条装饰都不出现** → 把 `ViewPlugin` 错塞进了 `EditorView.decorations.from(...)`；`ViewPlugin` 只能用 `{ decorations: instance => instance.decorations }` 暴露（参考实现要点第 2 条）
- 样式全丢 → 忘了任务 3 已在 `main.js` 里 `import './styles/theme.css'`，检查该文件是否被改坏

- [ ] **Step 4: 浏览器手工验收**

后端起着（任务 2 Step 9 第 1 次的方式），前端 `npm run dev`，浏览器打开 `http://localhost:5173/`：

1. 列表页点任意一条规则 → 进工作台，顶栏显示规则名
2. 编辑器里默认脚本有 **groovy 语法高亮**（关键字有色）和**行号**
3. `${age}`、`${level}` 这些占位符显示成**蓝紫色小胶囊**（`.cm-placeholder` 生效）
4. 光标所在行有淡色背景（`highlightActiveLine` 生效）
5. 改一个字 → 顶栏[保存]按钮从禁用变为可点
6. 按 **Cmd+S**（Windows 是 Ctrl+S）→ 弹出「已保存」，按钮回到禁用态，**浏览器自己的保存网页对话框不能弹出来**
7. 点[保存]按钮 → 同样效果
8. 刷新页面 → 刚才的修改还在（说明真的写进库了）
9. 把窗口拉窄 → 右侧面板不被挤没，编辑器区域正常收缩

第 8 条做完顺手用 MySQL MCP 核一下数据真落库了（这是 Global Constraints 要求的，不能只看接口返回）：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, name, LEFT(script_content, 60) AS head, updated_at FROM rule WHERE id = ? ORDER BY id", "params": [1]})
```

预期 `head` 里能看到你刚改的内容，`updated_at` 是刚才的时间。

- [ ] **Step 5: 为什么这个任务不加前端单测**

`ScriptEditor.vue` 的全部逻辑都是「把 CodeMirror 装配起来」，没有可独立验证的分支；`RuleWorkbenchView.vue` 的分支（加载失败、脏标记）要在任务 14 引入状态机后才成型。给它们写单测得先引入 `jsdom` + `@vue/test-utils` + CodeMirror 的 DOM mock，成本高而收益低。

这与全局约束里定下的策略一致（见本计划开头「测试与 TDD」段）：**只给有分支逻辑的纯 JS 模块写单测** —— 任务 3 的 `http.js`、任务 6 的 `api/rule.js`、任务 14 的 `useValidationState.js`、任务 19 的 SSE 解析器与 `messageBlocks.js`、任务 20 的 `testCaseParams.js`，组件靠构建 + 明确的手工验收步骤把关。

注意区分「装配型」与「有分支」：本任务的两个组件是前者；而任务 6 的 `api/rule.js` 看着也像薄封装，实际有三个 `if (x !== undefined)`，属于后者，所以它是要测的。判定依据是**读代码看有没有分支**，不是看文件像不像「封装层」。

- [ ] **Step 6: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_front
git commit -m "feat: 工作台骨架与 Groovy 编辑器（高亮/行号/错误行/占位符胶囊/⌘S 保存）"
```

---

## Task 14: 校验状态机 + 填值表单 + 运行结果

把任务 12 的 `validate` / `run` 接口接到界面上，落实需求文档交互规则 #1、#2、#4、#5。

**核心难点是状态机，不是 UI。** 「脚本一改，校验结果作废、运行重新锁定」这条规则（交互规则 #2）看起来简单，实际有四个坑，本任务的状态机把它们全部收口：

1. **保存回填导致的误作废。** 保存成功后父组件会拿后端返回的 `scriptContent` 回写，若把它当成「用户改了脚本」就会立刻把刚通过的校验作废掉。所以判定依据是**内容字符串是否真的不同**，而不是「有没有触发过变更事件」。
2. **校验请求的竞态。** 大模型 CR 要十几秒，用户这期间继续打字，等响应回来时它对应的已经不是当前内容了。用递增 token 丢弃过期响应。
3. **语法失败也要记快照。** 否则用户不改脚本、光重复点校验，会被误判成 `stale`。
4. **网络抖动不该作废有效结论。** 脚本没变、上一次校验通过了，这次请求失败，结论仍然可信 —— 保持原 phase，只提示错误。

**Files:**
- Create: `script_front/src/composables/validationState.js`（纯 reducer，不依赖 Vue，单测对象）
- Create: `script_front/src/composables/__tests__/validationState.spec.js`
- Create: `script_front/src/composables/useValidationState.js`（薄 Vue 包装）
- Create: `script_front/src/composables/paramRules.js`（填值格式校验，纯函数，单测对象）
- Create: `script_front/src/composables/__tests__/paramRules.spec.js`
- Create: `script_front/src/components/TopBar.vue`
- Create: `script_front/src/components/ParamsForm.vue`
- Create: `script_front/src/components/RunResultCard.vue`
- Create: `script_front/src/components/ValidationTabs.vue`
- Modify: `script_front/src/views/RuleWorkbenchView.vue`

**Interfaces:**
- Consumes: `validateScript` / `runScript`（任务 12）、`updateRule` / `getRuleDetail`（任务 6）、`reportError`（任务 3）、`theme.css` 变量（任务 3）、任务 13 的 `ScriptEditor`（`error-line` prop）
- Produces:
  ```
  // validationState.js —— 纯函数，可单测
  PHASE = { IDLE:'idle', VALIDATING:'validating', PASSED:'passed', SYNTAX_FAILED:'syntax-failed', STALE:'stale' }
  initialState() -> State
  reduce(state, action) -> State
  isRunDisabled(state) -> boolean
  errorLineOf(state) -> number|null
  action 类型：VALIDATE_START / VALIDATE_SUCCESS{token,script,result} / VALIDATE_FAILURE{token,message}
              / SCRIPT_CHANGED{script} / RUN_SUCCESS{result} / RUN_FAILURE{message} / RESET_RUN

  // useValidationState.js
  useValidationState(getScript: () => string) -> {
    phase, validating, running, runDisabled, errorLine, result, runResult, errorMessage, params,
    validate(), run(), syncScript(current), setParams(next), resetRun()
  }

  // paramRules.js —— 纯函数，可单测
  checkValue({name,type}, raw) -> string|null      // null 表示通过
  checkAll(placeholders, params) -> {ok, message, values}
  defaultValueFor(type) -> string

  // 组件
  TopBar        props: ruleName, updatedAt, dirtyForSave, saving, phase, validating, running, runDisabled
                emits: save, validate, run
  ParamsForm    props: placeholders, modelValue   emits: update:modelValue
  RunResultCard props: result, running, errorMessage
  ValidationTabs props: phase, result, runResult, errorMessage, validating, running, params
                emits: update:params, run
  ```

- [ ] **Step 1: 写状态机的失败测试**

`src/composables/__tests__/validationState.spec.js`

```js
import { describe, it, expect } from 'vitest'
import {
  PHASE, initialState, reduce, isRunDisabled, errorLineOf,
} from '../validationState'

const OK_RESULT = {
  syntaxOk: true,
  errorLine: null,
  errorMessage: null,
  placeholders: [{ name: 'age', type: 'int' }, { name: 'level', type: 'String' }],
  aiReview: { text: '没问题', suggestedScript: null, available: true },
}
const BAD_RESULT = {
  syntaxOk: false,
  errorLine: 3,
  errorMessage: '第 3 行括号不匹配',
  placeholders: [],
  aiReview: { text: '语法未通过，已跳过 AI 审查', suggestedScript: null, available: false },
}

const SCRIPT = 'int age = ${age}'

/** 走完一次成功校验，返回该状态 */
function passed(script = SCRIPT, result = OK_RESULT) {
  let s = reduce(initialState(), { type: 'VALIDATE_START' })
  return reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script, result })
}

describe('运行按钮锁定规则（交互规则 #1、#5）', () => {
  it('初始未校验时运行锁定', () => {
    const s = initialState()
    expect(s.phase).toBe(PHASE.IDLE)
    expect(isRunDisabled(s)).toBe(true)
  })

  it('校验中运行锁定', () => {
    const s = reduce(initialState(), { type: 'VALIDATE_START' })
    expect(s.phase).toBe(PHASE.VALIDATING)
    expect(isRunDisabled(s)).toBe(true)
  })

  it('语法通过则解锁运行', () => {
    const s = passed()
    expect(s.phase).toBe(PHASE.PASSED)
    expect(isRunDisabled(s)).toBe(false)
  })

  it('语法失败则锁定，并给出要标红的行号', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: SCRIPT, result: BAD_RESULT })
    expect(s.phase).toBe(PHASE.SYNTAX_FAILED)
    expect(isRunDisabled(s)).toBe(true)
    expect(errorLineOf(s)).toBe(3)
  })

  it('CR 不可用不影响解锁（交互规则 #5：AI 审查是建议性质）', () => {
    const s = passed(SCRIPT, { ...OK_RESULT, aiReview: { text: 'AI 未接入', suggestedScript: null, available: false } })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(isRunDisabled(s)).toBe(false)
  })

  it('非语法失败状态下不标红任何行', () => {
    expect(errorLineOf(passed())).toBeNull()
    expect(errorLineOf(initialState())).toBeNull()
  })
})

describe('脚本改动使结果作废（交互规则 #2）', () => {
  it('通过后改了脚本 → stale，结果与运行结果都清空，运行重新锁定', () => {
    const s = passed()
    const next = reduce(s, { type: 'SCRIPT_CHANGED', script: SCRIPT + '\nreturn age' })
    expect(next.phase).toBe(PHASE.STALE)
    expect(next.result).toBeNull()
    expect(isRunDisabled(next)).toBe(true)
  })

  it('语法失败后改了脚本 → stale，错误行标红被清掉', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: SCRIPT, result: BAD_RESULT })
    const next = reduce(s, { type: 'SCRIPT_CHANGED', script: 'int a = 1' })
    expect(next.phase).toBe(PHASE.STALE)
    expect(errorLineOf(next)).toBeNull()
  })

  it('内容完全相同时不作废（保存回填、重复 setScript 不能误伤）', () => {
    const s = passed()
    const next = reduce(s, { type: 'SCRIPT_CHANGED', script: SCRIPT })
    expect(next).toBe(s)          // 原样返回，连引用都不变
    expect(next.phase).toBe(PHASE.PASSED)
  })

  it('从没校验过时改脚本仍是 idle，不该报「结果已作废」', () => {
    const next = reduce(initialState(), { type: 'SCRIPT_CHANGED', script: 'anything' })
    expect(next.phase).toBe(PHASE.IDLE)
  })

  it('作废后再次校验能重新解锁', () => {
    let s = reduce(passed(), { type: 'SCRIPT_CHANGED', script: 'int b = 2' })
    s = reduce(s, { type: 'VALIDATE_START' })
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: s.pendingToken, script: 'int b = 2', result: OK_RESULT })
    expect(s.phase).toBe(PHASE.PASSED)
  })
})

describe('校验请求竞态（CR 要十几秒，用户会继续打字）', () => {
  it('等待期间改脚本，过期响应被丢弃', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    const staleToken = s.pendingToken
    s = reduce(s, { type: 'SCRIPT_CHANGED', script: '改过的内容' })
    // 旧请求姗姗来迟，必须被忽略
    const after = reduce(s, { type: 'VALIDATE_SUCCESS', token: staleToken, script: SCRIPT, result: OK_RESULT })
    expect(after).toBe(s)
    expect(isRunDisabled(after)).toBe(true)
  })

  it('连续两次校验，只有最后一次的响应生效', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    const firstToken = s.pendingToken
    s = reduce(s, { type: 'VALIDATE_START' })
    const secondToken = s.pendingToken
    expect(secondToken).not.toBe(firstToken)

    // 乱序返回：第二次先回，第一次后回
    s = reduce(s, { type: 'VALIDATE_SUCCESS', token: secondToken, script: 'B', result: OK_RESULT })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(s.validatedScript).toBe('B')

    const late = reduce(s, { type: 'VALIDATE_SUCCESS', token: firstToken, script: 'A', result: BAD_RESULT })
    expect(late).toBe(s)
    expect(late.phase).toBe(PHASE.PASSED)
  })

  it('过期的失败响应同样被丢弃', () => {
    let s = reduce(initialState(), { type: 'VALIDATE_START' })
    const staleToken = s.pendingToken
    s = reduce(s, { type: 'SCRIPT_CHANGED', script: 'x' })
    const after = reduce(s, { type: 'VALIDATE_FAILURE', token: staleToken, message: '网络错误' })
    expect(after.errorMessage).toBeNull()
  })
})

describe('失败与运行不干扰校验结论', () => {
  it('请求失败保持原 phase，只记错误信息（脚本没变，旧结论仍可信）', () => {
    let s = passed()
    s = reduce(s, { type: 'VALIDATE_START' })
    const failed = reduce(s, { type: 'VALIDATE_FAILURE', token: s.pendingToken, message: '无法连接后端服务' })
    // phase 停在 VALIDATING 是有意为之：既不当成通过，也不把已有结论抹掉（权衡详见 Step 2）
    expect(failed.phase).toBe(PHASE.VALIDATING)
    expect(failed.errorMessage).toBe('无法连接后端服务')
    expect(failed.pendingToken).toBe(0)
  })

  it('运行成功不改变校验阶段', () => {
    let s = passed()
    s = reduce(s, { type: 'RUN_SUCCESS', result: { success: true, value: 'vip', errorMessage: null, timeout: false } })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(s.runResult.value).toBe('vip')
  })

  it('运行失败把错误摆到界面上，但不作废校验', () => {
    let s = passed()
    s = reduce(s, { type: 'RUN_FAILURE', message: '脚本执行超过 5 秒，已自动中断' })
    expect(s.phase).toBe(PHASE.PASSED)
    expect(s.runResult).toBeNull()
    expect(s.errorMessage).toContain('5 秒')
  })

  it('清空运行结果', () => {
    let s = reduce(passed(), { type: 'RUN_SUCCESS', result: { success: true, value: '1', errorMessage: null, timeout: false } })
    expect(reduce(s, { type: 'RESET_RUN' }).runResult).toBeNull()
  })
})

describe('占位符填值随校验结果重建', () => {
  it('校验通过后按占位符列表初始化填值，boolean 默认 false', () => {
    const s = passed(SCRIPT, {
      ...OK_RESULT,
      placeholders: [{ name: 'age', type: 'int' }, { name: 'vip', type: 'boolean' }],
    })
    expect(s.params).toEqual({ age: '', vip: 'false' })
  })

  it('重新校验后仍存在的占位符保留用户已填的值', () => {
    let s = passed(SCRIPT, { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }] })
    s = { ...s, params: { age: '28' } }
    const next = reduce(s, { type: 'VALIDATE_START' })
    const after = reduce(next, {
      type: 'VALIDATE_SUCCESS', token: next.pendingToken, script: SCRIPT,
      result: { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }, { name: 'level', type: 'String' }] },
    })
    expect(after.params).toEqual({ age: '28', level: '' })
  })

  it('被删掉的占位符不会留下脏键', () => {
    let s = passed(SCRIPT, { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }, { name: 'gone', type: 'String' }] })
    s = { ...s, params: { age: '28', gone: 'x' } }
    const next = reduce(s, { type: 'VALIDATE_START' })
    const after = reduce(next, {
      type: 'VALIDATE_SUCCESS', token: next.pendingToken, script: SCRIPT,
      result: { ...OK_RESULT, placeholders: [{ name: 'age', type: 'int' }] },
    })
    expect(Object.keys(after.params)).toEqual(['age'])
  })
})
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：`validationState` 模块找不到 → 全部失败。这是正常的（TDD 先红）。

- [ ] **Step 2: 实现状态机**

`src/composables/validationState.js`

```js
/**
 * 校验 / 运行状态机（纯 reducer，不依赖 Vue）。
 *
 * 需求文档交互规则 #1、#2、#4、#5 的全部判定都收口在这里，组件只负责渲染：
 *   #1 未校验或校验未通过 → 运行按钮锁定
 *   #2 脚本内容被修改 → 校验结果作废，运行重新锁定
 *   #4 校验（含大模型 CR）同步等待，全部完成才解锁
 *   #5 AI 审查是建议性质，只看 syntaxOk 决定解锁，CR 报错不阻断
 *
 * 刻意拆成「纯 reducer + 薄 Vue 包装」两层：reducer 不 import vue，
 * 单测可以直接跑分支，不需要 mount 组件、不需要 jsdom。
 */

export const PHASE = {
  IDLE: 'idle',                      // 从没校验过
  VALIDATING: 'validating',          // 校验中（CR 可能十几秒）
  PASSED: 'passed',                  // 语法通过，运行已解锁
  SYNTAX_FAILED: 'syntax-failed',    // 语法错误，运行锁定，编辑器标红 errorLine
  STALE: 'stale',                    // 校验过但脚本又改了，结论作废
}

export function initialState() {
  return {
    phase: PHASE.IDLE,
    /** 上次校验用的脚本快照，判定「内容是否被修改」的唯一依据 */
    validatedScript: null,
    /** 已发放的请求序号 */
    token: 0,
    /** 当前有效请求的序号；不匹配的响应一律丢弃 */
    pendingToken: 0,
    result: null,       // ValidateResponse
    runResult: null,    // RunResponse
    errorMessage: null,
    /** 占位符填值，键为占位符名，值一律是字符串（后端按字符串收） */
    params: {},
  }
}

/** 运行按钮是否锁定：只有 PASSED 解锁（交互规则 #1、#5） */
export function isRunDisabled(state) {
  return state.phase !== PHASE.PASSED
}

/** 编辑器要标红的行号，只有语法失败时有值 */
export function errorLineOf(state) {
  if (state.phase !== PHASE.SYNTAX_FAILED) return null
  return state.result?.errorLine ?? null
}

export function reduce(state, action) {
  switch (action.type) {
    case 'VALIDATE_START': {
      const token = state.token + 1
      return {
        ...state,
        token,
        pendingToken: token,
        phase: PHASE.VALIDATING,
        errorMessage: null,
        // 重新校验意味着上一轮运行结果已经不能代表当前脚本了
        runResult: null,
      }
    }

    case 'VALIDATE_SUCCESS': {
      // 过期响应：等待期间用户又改了脚本，这份结论对应的已不是当前内容
      if (action.token !== state.pendingToken) return state
      const { script, result } = action
      return {
        ...state,
        pendingToken: 0,
        // 语法失败也记快照。不记的话用户不改脚本、光重复点校验会被 SCRIPT_CHANGED 误判成 stale
        validatedScript: script,
        result,
        phase: result.syntaxOk ? PHASE.PASSED : PHASE.SYNTAX_FAILED,
        params: pickParams(state.params, result.placeholders),
        errorMessage: null,
      }
    }

    case 'VALIDATE_FAILURE': {
      if (action.token !== state.pendingToken) return state
      // 刻意不回退 phase：脚本没变，上一次的成功结论仍然可信，
      // 不该因为一次网络抖动就把用户已经拿到的「校验通过」抹掉
      return { ...state, pendingToken: 0, errorMessage: action.message }
    }

    case 'SCRIPT_CHANGED': {
      // 内容与上次校验的快照一致（保存回填、重复 setScript），别误伤 —— 连引用都不变，Vue 可跳过重渲染
      if (action.script === state.validatedScript) return state

      // 到这里内容确实变了。要分开判断两件独立的事：有没有「历史结论」、有没有「在途请求」。
      const wasValidated = state.validatedScript !== null  // 之前校验出过结论（成功或失败都算）
      const hasPending = state.pendingToken !== 0          // 有一个请求还在飞

      // 从没校验过、也没有在途请求：纯粹是 idle 状态下打字，无事发生，原样返回
      if (!wasValidated && !hasPending) return state

      // 有在途请求就作废它：内容都变了，迟到的响应对应的已不是当前脚本，靠 token 归零让它对不上号而被丢弃。
      // 首次校验途中打字也必须走这条 —— 这正是本计划原实现漏掉的坑（见下方「实现修正」）。
      const invalidated = { ...state, pendingToken: 0 }

      // 首次校验途中（还没结论）：仅丢弃在途请求，phase 维持原样（validating），不该谎报「结果已作废」
      if (!wasValidated) return invalidated

      // 之前有结论 → 作废它：标 stale，清空校验结果与运行结果，运行重新锁定
      return {
        ...invalidated,
        phase: PHASE.STALE,
        result: null,
        runResult: null,
        validatedScript: null,
      }
    }

    case 'RUN_SUCCESS':
      return { ...state, runResult: action.result, errorMessage: null }

    case 'RUN_FAILURE':
      return { ...state, runResult: null, errorMessage: action.message }

    case 'RESET_RUN':
      return { ...state, runResult: null }

    default:
      return state
  }
}

/**
 * 按新的占位符列表重建填值：仍存在的保留用户已填的值，新增的给类型默认值，
 * 被删掉的键直接丢弃（不然会带着脏键提交给后端）。
 */
function pickParams(oldParams, placeholders) {
  const next = {}
  for (const p of placeholders ?? []) {
    next[p.name] = oldParams?.[p.name] ?? defaultValueFor(p.type)
  }
  return next
}

/** boolean 用下拉，给个默认值；其余留空强制用户填 */
function defaultValueFor(type) {
  return type === 'boolean' ? 'false' : ''
}
```

> **实现修正（本次执行，已改上面代码）：** `SCRIPT_CHANGED` 原写法 `if (state.validatedScript === null) return state` 有个真 bug —— **首次校验途中**（`VALIDATE_START` 已把 `pendingToken` 置为新号、但 `validatedScript` 仍是 null）用户打字时会提前 return，`pendingToken` 没被清零，等过期响应回来 token 仍对得上而被误用，界面会错误跳到 PASSED。这会让本任务自己的 2 条测试（`等待期间改脚本，过期响应被丢弃`、`过期的失败响应同样被丢弃`）失败。按「测试即规格、不为凑绿改断言」修正实现：把「有没有历史结论」与「有没有在途请求」拆开判断，只要在途就清 `pendingToken`。修正后 21 条全绿。
>
> **这里有个真实的设计权衡，写下来免得后来人以为是 bug：** 请求失败后 phase 停在 `VALIDATING`，运行按钮保持锁定。
> 为什么不退回 `PASSED`？因为 `VALIDATE_START` 已经把 `result` 之外的一切都重置了，此时并没有一份「当前内容的有效结论」可以退回。
> 用户看到的提示是「无法连接后端服务」toast，比让运行按钮亮着但脚本其实没校验过要安全。**注意（本次执行发现的边缘场景）：** 失败后徽标仍显示「校验中…」、`校验`按钮因 `:loading="validating"` 处于禁用态，要等用户再编辑一次脚本（`SCRIPT_CHANGED` → `STALE`）才恢复可点。这只在后端不可用时出现，本次未单独处理；若要「失败后立即可重试」，需给 `VALIDATE_FAILURE` 设一个非 `VALIDATING` 的 phase（会改到 `请求失败保持原 phase` 那条测试），留待后续按需决定。

```bash
npm run test:unit
```

预期：`validationState.spec.js` 全绿（21 条）。

- [ ] **Step 3: 写 Vue 包装**

`src/composables/useValidationState.js`

```js
/**
 * 状态机的 Vue 包装：把 reduce 挂到 ref 上，并把两个接口调用包进来。
 * 组件只用这一层，不直接碰 reducer。
 */
import { computed, ref } from 'vue'
import { runScript, validateScript } from '../api/rule'
import { PHASE, errorLineOf, initialState, isRunDisabled, reduce } from './validationState'

/**
 * @param {() => string} getScript 取编辑器当前内容（含未保存修改，交互规则 #3）
 */
export function useValidationState(getScript) {
  const state = ref(initialState())
  const running = ref(false)

  const dispatch = (action) => { state.value = reduce(state.value, action) }

  const phase = computed(() => state.value.phase)
  const validating = computed(() => state.value.phase === PHASE.VALIDATING)
  const runDisabled = computed(() => isRunDisabled(state.value))
  const errorLine = computed(() => errorLineOf(state.value))
  const result = computed(() => state.value.result)
  const placeholders = computed(() => state.value.result?.placeholders ?? [])
  const aiReview = computed(() => state.value.result?.aiReview ?? null)
  const runResult = computed(() => state.value.runResult)
  const errorMessage = computed(() => state.value.errorMessage)
  const params = computed(() => state.value.params)

  async function validate() {
    if (validating.value) return          // 防连点
    const script = getScript()
    dispatch({ type: 'VALIDATE_START' })
    const token = state.value.pendingToken
    try {
      const res = await validateScript(script)
      dispatch({ type: 'VALIDATE_SUCCESS', token, script, result: res })
    } catch (e) {
      const message = e instanceof Error && e.message ? e.message : '校验失败，请稍后重试'
      dispatch({ type: 'VALIDATE_FAILURE', token, message })
    }
  }

  /**
   * @param {Record<string,string>} values 已经过 paramRules.checkAll 校验的填值
   */
  async function run(values) {
    if (running.value || runDisabled.value) return
    running.value = true
    const script = getScript()
    try {
      const res = await runScript(script, values)
      dispatch({ type: 'RUN_SUCCESS', result: res })
    } catch (e) {
      const message = e instanceof Error && e.message ? e.message : '运行失败，请稍后重试'
      dispatch({ type: 'RUN_FAILURE', message })
    } finally {
      running.value = false
    }
  }

  /** 编辑器内容变化时调用；内容真变了才作废（交互规则 #2） */
  const syncScript = (current) => dispatch({ type: 'SCRIPT_CHANGED', script: current })

  const setParams = (next) => { state.value = { ...state.value, params: next } }

  const resetRun = () => dispatch({ type: 'RESET_RUN' })

  return {
    state, phase, validating, running, runDisabled, errorLine,
    result, placeholders, aiReview, runResult, errorMessage, params,
    validate, run, syncScript, setParams, resetRun,
  }
}
```

- [ ] **Step 4: 写填值校验的测试与实现**

`src/composables/__tests__/paramRules.spec.js`

```js
import { describe, it, expect } from 'vitest'
import { checkValue, checkAll, defaultValueFor, controlOf } from '../paramRules'

describe('按类型校验填值格式（需求 4.3.3）', () => {
  it('int 接受负数与零', () => {
    expect(checkValue({ name: 'age', type: 'int' }, '28')).toBeNull()
    expect(checkValue({ name: 'age', type: 'int' }, '-3')).toBeNull()
    expect(checkValue({ name: 'age', type: 'int' }, '0')).toBeNull()
  })

  it('int 拒绝小数、非数字与空值', () => {
    expect(checkValue({ name: 'age', type: 'int' }, '3.5')).toContain('整数')
    expect(checkValue({ name: 'age', type: 'int' }, 'abc')).toContain('整数')
    expect(checkValue({ name: 'age', type: 'int' }, '')).toContain('请填写')
    expect(checkValue({ name: 'age', type: 'int' }, null)).toContain('请填写')
  })

  it('int 超范围给出可读提示', () => {
    expect(checkValue({ name: 'n', type: 'int' }, '2147483648')).toContain('超出 int 范围')
    expect(checkValue({ name: 'n', type: 'int' }, '2147483647')).toBeNull()
  })

  it('long 只校验整数格式，不做范围（JS Number 精度不够，交给后端）', () => {
    expect(checkValue({ name: 'ts', type: 'long' }, '9999999999999999999')).toBeNull()
    expect(checkValue({ name: 'ts', type: 'long' }, '1.5')).toContain('整数')
  })

  it('double 接受小数与科学计数法', () => {
    expect(checkValue({ name: 'r', type: 'double' }, '3.14')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, '-0.5')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, '1e5')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, '7')).toBeNull()
    expect(checkValue({ name: 'r', type: 'double' }, 'abc')).toContain('数字')
  })

  it('boolean 只认 true / false', () => {
    expect(checkValue({ name: 'v', type: 'boolean' }, 'true')).toBeNull()
    expect(checkValue({ name: 'v', type: 'boolean' }, 'false')).toBeNull()
    expect(checkValue({ name: 'v', type: 'boolean' }, 'TRUE')).toContain('true 或 false')
  })

  it('String 不校验格式，但空值仍要拦', () => {
    expect(checkValue({ name: 's', type: 'String' }, '任意内容 ${x} "引号"')).toBeNull()
    expect(checkValue({ name: 's', type: 'String' }, '   ')).toContain('请填写')
  })

  it('提示里带上占位符名，用户知道该改哪一格', () => {
    expect(checkValue({ name: 'level', type: 'int' }, 'vip')).toContain('level')
  })

  it('首尾空白被容忍并在校验前 trim', () => {
    expect(checkValue({ name: 'age', type: 'int' }, '  28  ')).toBeNull()
  })
})

describe('批量校验', () => {
  const PH = [{ name: 'age', type: 'int' }, { name: 'level', type: 'String' }]

  it('全部合法时返回 trim 过的字符串映射', () => {
    const r = checkAll(PH, { age: ' 28 ', level: 'vip' })
    expect(r.ok).toBe(true)
    expect(r.values).toEqual({ age: '28', level: 'vip' })
  })

  it('有一格不合法就整体不放行，并给出第一条中文原因', () => {
    const r = checkAll(PH, { age: 'x', level: 'vip' })
    expect(r.ok).toBe(false)
    expect(r.message).toContain('age')
    expect(r.values).toEqual({})
  })

  it('缺键按空值处理', () => {
    expect(checkAll(PH, { age: '28' }).ok).toBe(false)
  })

  it('没有占位符时直接放行，values 为空对象', () => {
    expect(checkAll([], {})).toEqual({ ok: true, message: '', values: {} })
  })

  it('values 全是字符串（后端 params 是 Map<String,String>）', () => {
    const r = checkAll([{ name: 'n', type: 'int' }], { n: 28 })
    expect(r.ok).toBe(true)
    expect(r.values.n).toBe('28')
    expect(typeof r.values.n).toBe('string')
  })
})

describe('控件与默认值', () => {
  it('boolean 用下拉，int/long/double 用数字框，String 用文本框（需求 L98）', () => {
    // Step 9 修正 D2：原断言 controlOf('int')==='input' 把缺陷锁死了，与需求 L98
    // 「int/long/double → 数字输入框」及本计划验收表项 13（age 是数字输入框）冲突。
    expect(controlOf('boolean')).toBe('select')
    expect(controlOf('int')).toBe('number')
    expect(controlOf('long')).toBe('number')
    expect(controlOf('double')).toBe('number')
    expect(controlOf('String')).toBe('input')
  })

  it('boolean 默认 false，其余默认空串', () => {
    expect(defaultValueFor('boolean')).toBe('false')
    expect(defaultValueFor('int')).toBe('')
    expect(defaultValueFor('String')).toBe('')
  })
})
```

`src/composables/paramRules.js`

```js
/**
 * 占位符填值的格式校验（需求 4.3.3）。
 *
 * 纯函数，不依赖 Vue 也不依赖 Element Plus —— 表单组件只负责渲染，
 * 「能不能提交」的判定全在这里，这样分支可以单测。
 *
 * 值一律按字符串处理：后端 RunRequest.params 是 Map<String,String>，
 * 由 GroovyEngineService 按推断出的类型转成字面量（任务 8、11）。
 */

export const INT_PATTERN = /^-?\d+$/
export const DOUBLE_PATTERN = /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/
export const INT_MIN = -2147483648
export const INT_MAX = 2147483647

/** 该类型用什么控件：boolean 下拉、int/long/double 数字框（需求 L98）、其余文本框 */
export function controlOf(type) {
  if (type === 'boolean') return 'select'
  if (type === 'int' || type === 'long' || type === 'double') return 'number'
  return 'input'
}

export function defaultValueFor(type) {
  return type === 'boolean' ? 'false' : ''
}

/**
 * 校验单个填值。
 * @returns {string|null} null 表示通过，否则是可直接展示的中文提示
 */
export function checkValue(placeholder, raw) {
  const name = placeholder?.name ?? '该参数'
  const text = normalize(raw)

  if (text === '') return `请填写 ${name}`

  switch (placeholder?.type) {
    case 'int': {
      if (!INT_PATTERN.test(text)) return `${name} 要填整数，例如 28`
      const n = Number(text)
      if (n < INT_MIN || n > INT_MAX) return `${name} 超出 int 范围（${INT_MIN} ~ ${INT_MAX}）`
      return null
    }
    case 'long':
      // 只校验格式不校验范围：JS Number 的安全整数只到 2^53，
      // 再长就失真了，硬拦会误伤合法值，交给后端 Long.parseLong
      if (!INT_PATTERN.test(text)) return `${name} 要填整数，例如 1000000`
      return null
    case 'double':
      if (!DOUBLE_PATTERN.test(text)) return `${name} 要填数字，例如 3.14`
      return null
    case 'boolean':
      if (text !== 'true' && text !== 'false') return `${name} 只能是 true 或 false`
      return null
    default:
      return null   // String 与未知类型不校验格式
  }
}

/**
 * 批量校验。任一格不合法就整体不放行（需求：前端校验不通过不允许提交）。
 * @returns {{ok:boolean, message:string, values:Record<string,string>}}
 */
export function checkAll(placeholders, params) {
  const values = {}
  for (const p of placeholders ?? []) {
    const err = checkValue(p, params?.[p.name])
    if (err) return { ok: false, message: err, values: {} }
    values[p.name] = normalize(params?.[p.name])
  }
  return { ok: true, message: '', values }
}

function normalize(raw) {
  if (raw === null || raw === undefined) return ''
  return String(raw).trim()
}
```

```bash
npm run test:unit
```

预期：新增 16 条全绿，累计 52 条（任务 3 的 6 + 任务 6 的 9 + 本任务 21 + 16）。

- [ ] **Step 5: 写 `TopBar.vue`**

`src/components/TopBar.vue`

```vue
<script setup>
/**
 * 工作台顶栏：规则名 + 状态徽标 + [校验] [运行] [保存]。
 * 按钮的可用性完全由父组件传入的 phase / runDisabled 决定，本组件不做业务判定。
 */
import { computed } from 'vue'
import { PHASE } from '../composables/validationState'

const props = defineProps({
  ruleName: { type: String, default: '' },
  updatedAt: { type: String, default: '' },
  dirtyForSave: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
  phase: { type: String, default: PHASE.IDLE },
  validating: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  runDisabled: { type: Boolean, default: true },
})
const emit = defineEmits(['save', 'validate', 'run'])

/** 状态徽标：文案 + 配色 class */
const badge = computed(() => {
  switch (props.phase) {
    case PHASE.VALIDATING:
      return { text: '校验中…（含 AI 审查，可能要十几秒）', cls: 'is-loading' }
    case PHASE.PASSED:
      return { text: '校验通过，可运行', cls: 'is-pass' }
    case PHASE.SYNTAX_FAILED:
      return { text: '语法错误，请修正后重新校验', cls: 'is-fail' }
    case PHASE.STALE:
      return { text: '脚本已修改，校验结果已作废', cls: 'is-stale' }
    default:
      return { text: '尚未校验，运行按钮已锁定', cls: 'is-idle' }
  }
})
</script>

<template>
  <div class="topbar">
    <div class="logo">脚本规则工作台</div>
    <div class="rule-name">{{ ruleName || '加载中…' }}</div>
    <div class="badge" :class="badge.cls">{{ badge.text }}</div>
    <div class="spacer"></div>
    <div class="updated" v-if="updatedAt">更新于 {{ updatedAt }}</div>

    <el-button class="btn" :loading="validating" @click="emit('validate')">校验</el-button>
    <!-- 运行锁定原因由徽标说明，按钮上不再堆 tooltip（交互规则 #1） -->
    <el-button
      class="btn"
      type="primary"
      :loading="running"
      :disabled="runDisabled"
      @click="emit('run')"
    >运行</el-button>
    <el-button class="btn" :loading="saving" :disabled="!dirtyForSave" @click="emit('save')">
      保存<span class="kbd">⌘S</span>
    </el-button>
  </div>
</template>

<style scoped>
.topbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  height: 56px;
  flex-shrink: 0;
  background: var(--brand-gradient);
  box-shadow: var(--topbar-shadow);
  color: #fff;
}
.logo { font-size: 15px; font-weight: 600; opacity: .92; }
.rule-name { font-size: 15px; font-weight: 600; }
.spacer { flex: 1; }
.updated { font-size: 12px; opacity: .75; }
.btn { border-radius: 20px; }
.kbd { margin-left: 6px; font-size: 11px; opacity: .6; }

/* 徽标：半透明白底，四种状态靠左侧小圆点区分色 */
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  background: rgba(255, 255, 255, .18);
  white-space: nowrap;
}
.badge::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.is-idle    { color: rgba(255, 255, 255, .85); }
.is-loading { color: #ffe9a8; }
.is-pass    { color: #c9f7d4; }
.is-fail    { color: #ffd2d2; }
.is-stale   { color: #ffe0b8; }
</style>
```

- [ ] **Step 6: 写 `ParamsForm.vue`**

`src/components/ParamsForm.vue`

```vue
<script setup>
/**
 * 占位符填值表单（需求 4.3.3）：按推断出的类型渲染控件。
 * 「能不能提交」的判定在 composables/paramRules.js，本组件只做渲染与错误提示。
 */
import { computed } from 'vue'
import { controlOf, checkValue } from '../composables/paramRules'

const props = defineProps({
  /** Array<{name:string, type:string}> */
  placeholders: { type: Array, default: () => [] },
  /** Record<string,string> */
  modelValue: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['update:modelValue'])

const rows = computed(() =>
  props.placeholders.map((p) => ({
    ...p,
    control: controlOf(p.type),
    // 边填边提示：只在该格已经有内容或已被碰过时才显示，避免一进页面满屏红
    error: checkValue(p, props.modelValue?.[p.name]),
  })),
)

function onInput(name, value) {
  emit('update:modelValue', { ...props.modelValue, [name]: value ?? '' })
}
</script>

<template>
  <div class="params-form">
    <el-empty v-if="!rows.length" description="这个脚本没有占位符，直接运行即可" :image-size="60" />

    <div v-for="row in rows" :key="row.name" class="row">
      <div class="label">
        <span class="name">{{ row.name }}</span>
        <span class="type">{{ row.type }}</span>
      </div>

      <el-select
        v-if="row.control === 'select'"
        class="control"
        :model-value="modelValue[row.name]"
        @update:model-value="(v) => onInput(row.name, v)"
      >
        <el-option label="true" value="true" />
        <el-option label="false" value="false" />
      </el-select>

      <el-input
        v-else
        class="control"
        :type="row.control === 'number' ? 'number' : 'text'"
        :model-value="modelValue[row.name]"
        :placeholder="row.type === 'String' ? '任意文本' : '请输入' + row.type"
        :class="{ 'has-error': modelValue[row.name] && row.error }"
        @update:model-value="(v) => onInput(row.name, v)"
      />

      <div class="err" v-if="modelValue[row.name] && row.error">{{ row.error }}</div>
    </div>
  </div>
</template>

<style scoped>
.params-form { display: flex; flex-direction: column; gap: 10px; }
.row {
  display: grid;
  grid-template-columns: 160px 1fr;
  align-items: center;
  gap: 4px 12px;
}
.label { display: flex; align-items: center; gap: 8px; min-width: 0; }
.name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  font-family: ui-monospace, Menlo, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.type {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  background: var(--chip-bg);
  color: var(--chip-fg);
}
.control { width: 100%; }
.err {
  grid-column: 2;
  font-size: 12px;
  color: var(--danger);
}
:deep(.has-error .el-input__wrapper) { box-shadow: 0 0 0 1px var(--danger) inset; }
</style>
```

- [ ] **Step 7: 写 `RunResultCard.vue`**

`src/components/RunResultCard.vue`

```vue
<script setup>
/**
 * 运行结果卡片。展示契约来自任务 11 的 GroovyEngineService.run：
 *   success=true            → 绿色，显示 value（空串显示「（无返回值）」）
 *   timeout=true            → 橙色，5 秒超时已中断
 *   success=false 带【安全拦截】→ 红色，编译期/运行期黑名单拦下
 *   success=false 其他       → 红色，中文原因（无堆栈、无英文异常类名）
 * errorMessage 一律是后端翻译好的中文，前端原样展示，不做二次加工。
 */
import { computed } from 'vue'

const props = defineProps({
  /** {success, value, errorMessage, timeout} | null */
  result: { type: Object, default: null },
  running: { type: Boolean, default: false },
  errorMessage: { type: String, default: null },
})

const SECURITY_MARKER = '【安全拦截】'

const kind = computed(() => {
  if (props.running) return 'running'
  if (props.result?.success) return 'success'
  if (props.result?.timeout) return 'timeout'
  if (props.result || props.errorMessage) return 'fail'
  return 'empty'
})

const isSecurity = computed(() =>
  (props.result?.errorMessage ?? props.errorMessage ?? '').includes(SECURITY_MARKER),
)

const text = computed(() => {
  if (kind.value === 'success') {
    const v = props.result.value
    // 后端把 null 与「脚本没写 return」都归一成空串（任务 11 的 stringify）
    return v === '' || v === null || v === undefined ? '（无返回值）' : v
  }
  if (kind.value === 'timeout') return props.result.errorMessage
  return props.result?.errorMessage ?? props.errorMessage ?? ''
})

const title = computed(() => ({
  running: '脚本运行中…',
  success: '运行成功',
  timeout: '执行超时',
  fail: isSecurity.value ? '被安全策略拦截' : '运行失败',
  empty: '还没运行过',
}[kind.value]))
</script>

<template>
  <div class="run-card" :class="kind">
    <div class="head">
      <span class="dot"></span>
      <span class="title">{{ title }}</span>
      <el-tag v-if="isSecurity" size="small" type="danger" effect="plain">安全拦截</el-tag>
    </div>

    <div v-if="kind === 'running'" class="body">
      <el-skeleton :rows="2" animated />
      <p class="hint">沙箱内执行，最长 5 秒，超时会自动中断</p>
    </div>

    <el-empty v-else-if="kind === 'empty'" description="填好参数后点右上角 [运行]" :image-size="60" />

    <pre v-else class="body value">{{ text }}</pre>
  </div>
</template>

<style scoped>
.run-card {
  border-radius: 12px;
  border: 1px solid var(--border-light);
  padding: 12px 14px;
  background: var(--input-bg);
}
.head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-muted); }
.title { font-size: 13px; font-weight: 600; color: var(--text-main); }

.success { border-color: #cdeed6; background: #f4fbf6; }
.success .dot { background: #22c55e; }
.timeout { border-color: #fbe6c8; background: #fffaf2; }
.timeout .dot { background: #f59e0b; }
.fail { border-color: #f6d3d3; background: var(--danger-bg); }
.fail .dot { background: var(--danger); }

.body {
  margin: 0;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;      /* 结果可能多行，别挤成一行 */
  word-break: break-all;
  max-height: 150px;
  overflow: auto;
}
.hint { margin: 8px 0 0; font-size: 12px; color: var(--text-muted); }
</style>
```

- [ ] **Step 8: 写 `ValidationTabs.vue`**

`src/components/ValidationTabs.vue`

```vue
<script setup>
/**
 * 编辑器下方的三标签页：校验结果 / 占位符填值 / 运行结果（需求 4.3 线框图）。
 *
 * 标签会自动跳到当前最该看的那一页：语法失败跳「校验结果」，
 * 校验通过跳「填值」（没占位符则直接跳「运行结果」），运行完跳「运行结果」。
 * 用户手动切走后不再抢（只在状态跃迁的那一刻切一次）。
 *
 * AI 审查卡片（任务 15）与测试用例栏（任务 20）分别插在本组件标了 slot 注释的位置。
 */
import { computed, ref, watch } from 'vue'
import { PHASE } from '../composables/validationState'
import ParamsForm from './ParamsForm.vue'
import RunResultCard from './RunResultCard.vue'

const props = defineProps({
  phase: { type: String, default: PHASE.IDLE },
  result: { type: Object, default: null },
  placeholders: { type: Array, default: () => [] },
  aiReview: { type: Object, default: null },
  runResult: { type: Object, default: null },
  errorMessage: { type: String, default: null },
  validating: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  params: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['update:params', 'run', 'apply-suggested'])

const active = ref('check')

const syntaxLine = computed(() => {
  if (props.phase === PHASE.SYNTAX_FAILED && props.result?.errorLine) {
    return `第 ${props.result.errorLine} 行：${props.result.errorMessage}`
  }
  return ''
})

// 状态跃迁时自动切页
watch(() => props.phase, (p) => {
  if (p === PHASE.SYNTAX_FAILED) active.value = 'check'
  else if (p === PHASE.PASSED) active.value = props.placeholders.length ? 'params' : 'run'
})
watch(() => props.runResult, (r) => { if (r) active.value = 'run' })
watch(() => props.running, (r) => { if (r) active.value = 'run' })
</script>

<template>
  <div class="tabs">
    <el-tabs v-model="active" class="inner">
      <!-- ---------- 校验结果 ---------- -->
      <el-tab-pane name="check">
        <template #label>
          <span>校验结果
            <el-badge v-if="phase === PHASE.SYNTAX_FAILED" is-dot type="danger" class="dot" />
          </span>
        </template>

        <div v-if="validating" class="loading">
          <!-- 图标改纯 CSS 转圈：本项目未在 main.js 全局注册 @element-plus/icons-vue，
               而它只是 element-plus 的传递依赖（package.json 未声明），不为一个 loading 图标引入幽灵依赖 -->
          <span class="spinner"></span>
          正在校验：语法 → 提取占位符 → AI 审查，全部完成才会解锁运行…
        </div>

        <template v-else-if="phase === PHASE.IDLE">
          <el-empty description="点右上角 [校验] 开始（校验通过前运行按钮锁定）" :image-size="60" />
        </template>

        <template v-else-if="phase === PHASE.STALE">
          <el-empty description="脚本已修改，校验结果作废，请重新校验" :image-size="60" />
        </template>

        <template v-else>
          <el-alert
            :type="phase === PHASE.PASSED ? 'success' : 'error'"
            :title="phase === PHASE.PASSED ? '语法校验通过' : '语法校验未通过'"
            :description="syntaxLine"
            :closable="false"
            show-icon
          />

          <div class="ph-block" v-if="placeholders.length">
            <div class="ph-title">识别到 {{ placeholders.length }} 个占位符</div>
            <div class="chips">
              <span v-for="p in placeholders" :key="p.name" class="chip">
                <code>${{ '{' }}{{ p.name }}{{ '}' }}</code>
                <em>{{ p.type }}</em>
              </span>
            </div>
          </div>
          <div class="ph-block" v-else-if="phase === PHASE.PASSED">
            <div class="ph-title">这个脚本没有占位符</div>
          </div>

          <!-- ai-review-slot：任务 15 换成 <AiReviewCard>，这里先显示纯文本 -->
          <div class="ai-block" v-if="aiReview">
            <div class="ph-title">
              AI 审查
              <el-tag v-if="!aiReview.available" size="small" type="info" effect="plain">不可用</el-tag>
            </div>
            <pre class="ai-text">{{ aiReview.text }}</pre>
          </div>
        </template>
      </el-tab-pane>

      <!-- ---------- 占位符填值 ---------- -->
      <el-tab-pane label="占位符填值" name="params">
        <ParamsForm
          :placeholders="placeholders"
          :model-value="params"
          @update:model-value="(v) => emit('update:params', v)"
        />
        <!-- testcase-slot：任务 20 放 <TestCaseBar>（保存为用例 / 回填 / 删除） -->
      </el-tab-pane>

      <!-- ---------- 运行结果 ---------- -->
      <el-tab-pane label="运行结果" name="run">
        <RunResultCard :result="runResult" :running="running" :error-message="errorMessage" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.tabs { height: 100%; }
/* Element 的 tabs 自带上下 padding，压掉一些，280px 的底栏放不下太多留白 */
.inner :deep(.el-tabs__header) { margin-bottom: 10px; }
.inner :deep(.el-tabs__content) { overflow: visible; }
.dot { margin-left: 4px; }

.loading {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-muted);
}
/* 纯 CSS 转圈，替代未注册的 <Loading> 图标 */
.spinner {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  border: 2px solid var(--border-light);
  border-top-color: #8b5cf6;
  border-radius: 50%;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.ph-block, .ai-block { margin-top: 12px; }
.ph-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: 12px;
  background: var(--chip-bg);
}
.chip code { font-size: 12px; color: var(--chip-fg); }
.chip em { font-size: 11px; color: var(--text-muted); font-style: normal; }

.ai-text {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--input-bg);
  border: 1px solid var(--border-light);
  font-size: 12px;
  line-height: 1.7;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow: auto;
}
</style>
```

> **实现修正（本次执行）：** 任务 3 的 `main.js` 只 `app.use(ElementPlus)`，**并未**注册 `@element-plus/icons-vue`（它只是 element-plus 的传递依赖，package.json 未声明）。为不引入幽灵依赖，按本条既定的兜底方案，把 `<el-icon><Loading /></el-icon>` 换成纯 CSS 转圈 `<span class="spinner">`（配套 `.spinner` + `@keyframes spin` 已加进上面的 style）。

- [ ] **Step 9: 改 `RuleWorkbenchView.vue` 把三块接上**

只改 `<script setup>` 的 import 与状态部分、以及 template 里 topbar / bottom-slot 两处。样式（`.workbench` / `.body` / `.left` / `.editor-slot` / `.bottom-slot` / `.right`）**一个字都不要动** —— 任务 13 已经调好了 flex 与 `min-width:0`，改坏会导致编辑器把右侧面板挤没。

`<script setup>` 全量替换为：

```vue
<script setup>
/**
 * 规则工作台总装页。
 *
 * 这里是「编辑器当前内容」的唯一持有者：校验、运行、AI 对话、AI 审查
 * 用的都是这份内容（含未保存的修改），不是数据库里的已保存版本 ——
 * 需求文档交互规则 #3。
 *
 * 校验/运行的全部判定在 useValidationState 里，本组件只做装配。
 * 右侧对话面板见任务 19。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ScriptEditor from '../components/ScriptEditor.vue'
import TopBar from '../components/TopBar.vue'
import ValidationTabs from '../components/ValidationTabs.vue'
import { getRuleDetail, updateRule } from '../api/rule'
import { reportError } from '../api/http'
import { useValidationState } from '../composables/useValidationState'
import { checkAll } from '../composables/paramRules'

const props = defineProps({ id: { type: String, required: true } })

const ruleId = computed(() => Number(props.id))
const ruleName = ref('')
const updatedAt = ref('')
const scriptContent = ref('')
const loading = ref(true)
const saving = ref(false)
const loadFailed = ref(false)

/** 已保存的脚本快照，用来判断「有未保存的修改」 */
const savedScript = ref('')
const dirtyForSave = computed(() => scriptContent.value !== savedScript.value)

const v = useValidationState(() => scriptContent.value)

// 内容一变就同步给状态机；内容真变了才作废（交互规则 #2）
watch(scriptContent, (next) => v.syncScript(next))

onMounted(loadDetail)

async function loadDetail() {
  loading.value = true
  loadFailed.value = false
  try {
    const detail = await getRuleDetail(ruleId.value)
    ruleName.value = detail.name
    updatedAt.value = detail.updatedAt
    scriptContent.value = detail.scriptContent ?? ''
    savedScript.value = scriptContent.value
  } catch (e) {
    loadFailed.value = true
    reportError(e)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (saving.value) return
  saving.value = true
  try {
    const detail = await updateRule({ ruleId: ruleId.value, scriptContent: scriptContent.value })
    savedScript.value = detail.scriptContent ?? scriptContent.value
    updatedAt.value = detail.updatedAt
    ElMessage.success('已保存')
  } catch (e) {
    reportError(e)
  } finally {
    saving.value = false
  }
}

async function handleRun() {
  // 需求 4.3.3：前端校验不通过不允许提交
  const checked = checkAll(v.placeholders.value, v.params.value)
  if (!checked.ok) {
    ElMessage.warning(checked.message)
    return
  }
  await v.run(checked.values)
}

async function handleValidate() {
  await v.validate()
  if (v.errorMessage.value) reportError(new Error(v.errorMessage.value))
}
</script>
```

template 里把 topbar 与 bottom-slot 两段换成：

```vue
    <TopBar
      :rule-name="ruleName"
      :updated-at="updatedAt"
      :dirty-for-save="dirtyForSave"
      :saving="saving"
      :phase="v.phase.value"
      :validating="v.validating.value"
      :running="v.running.value"
      :run-disabled="v.runDisabled.value"
      @save="handleSave"
      @validate="handleValidate"
      @run="handleRun"
    />
```

```vue
        <div class="bottom-slot">
          <ValidationTabs
            :phase="v.phase.value"
            :result="v.result.value"
            :placeholders="v.placeholders.value"
            :ai-review="v.aiReview.value"
            :run-result="v.runResult.value"
            :error-message="v.errorMessage.value"
            :validating="v.validating.value"
            :running="v.running.value"
            :params="v.params.value"
            @update:params="v.setParams"
            @run="handleRun"
          />
        </div>
```

编辑器的 `error-line` 接到状态机上（原来写死 `null`）：

```vue
        <div class="editor-slot">
          <ScriptEditor
            v-model="scriptContent"
            :error-line="v.errorLine.value"
            @save="handleSave"
          />
        </div>
```

> **`v.xxx.value` 不是笔误。** `useValidationState` 返回的是一个普通对象，里面的 `phase` / `params` 等是 `computed` ref；
> 在 `<script setup>` 里把它们解构出来会丢掉响应性，所以整体以 `v` 持有，模板中显式 `.value`。
> 如果嫌啰嗦，可以改成 `const { phase, params, ... } = useValidationState(...)` —— `computed` 解构后仍是 ref，模板里直接写 `phase` 即可（Vue 会自动 unwrap）。两种写法都对，**但别混着用**。

- [ ] **Step 10: 构建与单测**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：52 个单测通过，构建成功。

常见问题：
- `checkValue is not exported` → `paramRules.js` 里函数忘了加 `export`
- 模板里 `${p.name}` 直接写会被 Vue 当成插值语法报错 → 见 `ValidationTabs` 里的写法：`<code>${{ '{' }}{{ p.name }}{{ '}' }}</code>`
- 运行按钮一直灰 → 打开 Vue devtools 看 `phase`，多半是 `syncScript` 把状态打回 `stale` 了；检查 `watch(scriptContent)` 有没有在 `loadDetail` 回填时误触发（内容相同不该作废，若作废说明后端返回的 `scriptContent` 与本地有差异，比如尾随空白）

- [ ] **Step 11: 浏览器手工验收**

> **执行状态（本次）：⛔ 已延后并入 Task 21。** browser-use MCP 通道本会话未连接（`client not found`，Task 13 时还可用），交互式浏览器验收无法执行。本次改以下列替代证据提交：52 条单测全绿、`npm run build` 通过、curl 核对 validate/run 全部后端契约（占位符 `age:int`/`level:String`、run(28,vip)→`vip`、`return a + b))`→`errorLine:1`、`while(true){}`→`timeout:true` 含「5 秒」、`System.exit(0)`→`【安全拦截】`）、以及对 4 个组件 + 装配页的静态走查（徽标文案、`has-error`/`success`/`timeout`/`fail` 类名、`errorLine` 接线、保存只更新 `savedScript` 基线不回写 `scriptContent` 故不触发 stale）。**本 Step 11 的 12 项交互验收与 Step 12 查库核对，并入 Task 21（端到端联调 + 浏览器验收 + 查库核对）统一补做。**

后端与前端都起起来（两个终端，都要先 `source ../tools/env.sh`）：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back && mvn -q spring-boot:run
```
```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front && npm run dev
```

打开 `http://localhost:5173/`，进任意一条规则的工作台，逐条核对：

1. 刚进页面：徽标「尚未校验，运行按钮已锁定」，[运行] 灰，底部「校验结果」页显示空状态
2. 点 [校验]（脚本填 `int age = ${age}\nString level = "${level}"\nreturn age >= 18 ? level : "minor"`）：徽标变「校验中…」，底部显示校验流程提示，**十几秒内**返回
3. 校验通过后：徽标「校验通过，可运行」，[运行] 亮，自动跳到「占位符填值」页，看到 `age`(int) 与 `level`(String) 两格
4. `age` 填 `abc` → 格下立刻出红字「age 要填整数，例如 28」；点 [运行] → 弹 warning，不发请求
5. `age` 填 `28`、`level` 填 `vip` → 点 [运行] → 自动跳「运行结果」页，绿色卡片显示 `vip`
6. 脚本没写 `return` 时运行 → 显示「（无返回值）」
7. **在编辑器里加一个空格** → 徽标立刻变「脚本已修改，校验结果已作废」，[运行] 重新变灰，编辑器错误行标红消失（交互规则 #2）
8. 把空格删掉（内容与校验时完全一致）→ 徽标**仍是 stale**。这是有意的：状态机只在「与快照相同」时不作废，删空格后的内容虽然与最初相同，但中间已经作废过、快照已清空，不会自动复活。用户需要重新点校验
9. 保存（⌘S）后徽标**不应**变成 stale —— 保存回填的内容与编辑器一致
10. 语法错误脚本（`return a + b))`）→ 校验后徽标红色，编辑器对应行标红，「校验结果」页显示「第 N 行：…」
11. 死循环脚本（`while(true){}`）→ 运行约 5 秒后出橙色「执行超时」卡片
12. 危险脚本（`System.exit(0)`）→ 运行出红色卡片 + 「安全拦截」标签

第 9 条验的是保存回填不误作废，第 7、8 条验的是作废语义，这三条是本任务最容易做错的地方，务必亲手试。

- [ ] **Step 12: 用 MySQL MCP 核对保存真的落库**

第 9 条只看到了前端提示「已保存」。**接口说成功不等于真的写进去了**（Global Constraints 已定为硬要求），必须查库：

```
CallMcpTool(
  server_name="mysql",
  tool_name="exec_sql",
  arguments={
    "sql": "SELECT id, name, updated_at, CHAR_LENGTH(script_content) AS len, RIGHT(script_content, 40) AS tail FROM rule WHERE id = ?",
    "params": [1]
  }
)
```

核对三件事：
- `updated_at` 是不是刚才保存的时间（**注意时区**：若差 8 小时说明 `DB_URL` 里的 `serverTimezone=Asia/Shanghai` 没生效，回任务 1 Step 6 查 `env.sh`）
- `tail` 是否包含你最后输入的那几个字符（验内容真写进去了，没有被截断）
- `len` 与编辑器字符数是否一致

再确认表结构没被 `ddl-auto=update` 改歪：

```
CallMcpTool(server_name="mysql", tool_name="get_table_structure", arguments={"tables": "rule"})
```

`script_content` 必须是 `longtext` 或 `text`，不能是 `varchar(255)` —— 否则长脚本会被静默截断，而接口仍然返回成功。

MCP 没加载上就用 CLI 兜底：

```bash
source /Users/zhoudingyan/workspace/zdy_test/tools/env.sh
/usr/local/mysql/bin/mysql -h127.0.0.1 -uroot -p"$DB_PASS" script_workbench \
  -e "SELECT id, name, updated_at, CHAR_LENGTH(script_content) AS len FROM rule\G"
```

- [ ] **Step 13: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_front
git commit -m "feat: 校验状态机与填值运行面板（作废语义/竞态丢弃/格式校验/结果卡片）"
```

---

## Task 15: AI 审查卡片 + 「应用到编辑器」

把任务 14 留在 `ValidationTabs` 里的 `ai-review-slot` 换成真组件，并实现需求 4.3.2 第 3 步的 **[应用到编辑器]** 一键替换。

**时序说明（先读，否则会困惑）：** 本任务完成后，卡片上大概率显示「AI 审查不可用」+ 降级文案。这是**正常的** —— 真正的大模型 CR 要到任务 16 才接上，任务 12 的 `RuleService.validate` 里现在返回的是占位文案、`available=false`。本任务只负责把 UI 与「应用建议」的通路做完，验收时用**手工构造的假数据**验证渲染与替换逻辑；真实 CR 的效果留到任务 16 验收。

**为什么要抽 `useApplyScript`：** 需求里有两处「应用到编辑器」—— 4.3.2 的 AI 审查卡片（本任务）与 4.3.4 的 AI 对话面板（任务 19）。两处逻辑完全一样（确认 → 替换 → 触发作废），抽成一个 composable，任务 19 直接复用，不重复实现。

**一个必须提前讲清的语义：** 点「应用到编辑器」替换脚本后，**运行按钮会立刻变灰**。这不是 bug —— 内容真的变了，按交互规则 #2 校验结果就该作废。用户的下一步必然是重新校验，所以替换成功后弹确认框问「现在重新校验吗？」，把这条链路接上，别让用户对着灰按钮发愣。

**Files:**
- Create: `script_front/src/composables/applyScript.js`（纯函数，单测对象）
- Create: `script_front/src/composables/__tests__/applyScript.spec.js`
- Create: `script_front/src/composables/useApplyScript.js`
- Create: `script_front/src/components/AiReviewCard.vue`
- Create: `script_front/src/components/ApplyScriptDialog.vue`
- Modify: `script_front/src/components/ValidationTabs.vue`（换掉 `ai-review-slot`）
- Modify: `script_front/src/views/RuleWorkbenchView.vue`（接上应用逻辑）

**Interfaces:**
- Consumes: `aiReview`（任务 14 的 `useValidationState` 暴露）、`theme.css` 变量（任务 3）、`ScriptEditor` 的 `v-model`（任务 13）
- Produces:
  ```
  // applyScript.js —— 纯函数，可单测
  canApply(current, next) -> {ok:boolean, reason:string}
  diffSummary(current, next) -> {currentLines, nextLines, currentChars, nextChars, lineDelta, charDelta}

  // useApplyScript.js
  useApplyScript({ getScript, setScript, onApplied }) -> {
    dialogVisible, pendingScript, pendingSource, summary,
    requestApply(script, source), confirmApply(), cancelApply()
  }

  // 组件
  AiReviewCard      props: aiReview, phase     emits: apply(script)
  ApplyScriptDialog v-model:visible, props: script, source, summary   emits: confirm
  ```

- [ ] **Step 1: 写纯函数的失败测试**

`src/composables/__tests__/applyScript.spec.js`

```js
import { describe, it, expect } from 'vitest'
import { canApply, diffSummary } from '../applyScript'

describe('canApply：能不能替换', () => {
  it('正常情况放行', () => {
    expect(canApply('int a = 1', 'int a = 2').ok).toBe(true)
  })

  it('AI 没给出脚本时拦住，并说明原因', () => {
    expect(canApply('int a = 1', null).ok).toBe(false)
    expect(canApply('int a = 1', '').ok).toBe(false)
    expect(canApply('int a = 1', '   \n  ').ok).toBe(false)
    expect(canApply('int a = 1', null).reason).toContain('没有给出')
  })

  it('内容与当前完全一致时拦住（点了等于白点，别触发作废）', () => {
    const r = canApply('int a = 1', 'int a = 1')
    expect(r.ok).toBe(false)
    expect(r.reason).toContain('一致')
  })

  it('只有首尾空白差异时也算一致（避免无意义的作废）', () => {
    const r = canApply('int a = 1', '  int a = 1\n')
    expect(r.ok).toBe(false)
    expect(r.reason).toContain('一致')
  })

  it('当前脚本为空时允许应用（等于首次填入）', () => {
    expect(canApply('', 'int a = 1').ok).toBe(true)
  })

  it('reason 是可直接展示的中文', () => {
    const r = canApply('x', 'x')
    expect(r.reason).not.toMatch(/[A-Za-z]{4,}/)   // 不该夹英文异常词
  })
})

describe('diffSummary：给确认框展示的变化量', () => {
  it('统计行数与字符数', () => {
    const s = diffSummary('a\nb', 'a\nb\nc')
    expect(s.currentLines).toBe(2)
    expect(s.nextLines).toBe(3)
    expect(s.lineDelta).toBe(1)
    expect(s.currentChars).toBe(3)
    expect(s.nextChars).toBe(5)
    expect(s.charDelta).toBe(2)
  })

  it('变少时 delta 为负', () => {
    const s = diffSummary('a\nb\nc', 'a')
    expect(s.lineDelta).toBe(-2)
    expect(s.charDelta).toBe(-4)
  })

  it('空串按 0 行 0 字符算，不按 1 行算', () => {
    const s = diffSummary('', 'a')
    expect(s.currentLines).toBe(0)
    expect(s.currentChars).toBe(0)
    expect(s.nextLines).toBe(1)
  })

  it('current 为 null 时不抛异常', () => {
    expect(() => diffSummary(null, 'a')).not.toThrow()
    expect(diffSummary(null, 'a').currentLines).toBe(0)
  })

  it('末尾换行不多算一行', () => {
    // 'a\nb\n' 在编辑器里是 2 行内容，不是 3 行
    expect(diffSummary('a\nb\n', 'x').currentLines).toBe(2)
  })
})
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：`applyScript` 模块找不到 → 这 11 条失败。

- [ ] **Step 2: 实现纯函数**

`src/composables/applyScript.js`

```js
/**
 * 「应用到编辑器」的判定与统计（纯函数，不依赖 Vue）。
 *
 * 两处会用到：AI 审查卡片（需求 4.3.2）与 AI 对话面板（需求 4.3.4）。
 * 判定逻辑收在这里，两个入口的行为就必然一致。
 */

/**
 * 能不能用 next 替换 current。
 * @returns {{ok:boolean, reason:string}} reason 是可直接展示的中文
 */
export function canApply(current, next) {
  const target = (next ?? '').trim()
  if (target === '') {
    return { ok: false, reason: 'AI 没有给出可用的脚本内容' }
  }
  // 用 trim 后比较：只有首尾空白差异的替换没有意义，
  // 却会触发交互规则 #2 的作废，把用户已经拿到的「校验通过」弄没
  if ((current ?? '').trim() === target) {
    return { ok: false, reason: '建议内容与当前脚本一致，无需替换' }
  }
  return { ok: true, reason: '' }
}

/**
 * 变化量统计，给确认框看「这次替换动了多少」。
 * 整篇替换是破坏性操作，给用户一个量级判断再确认。
 */
export function diffSummary(current, next) {
  const cur = current ?? ''
  const nxt = next ?? ''
  const currentLines = countLines(cur)
  const nextLines = countLines(nxt)
  const currentChars = cur.length
  const nextChars = nxt.length
  return {
    currentLines,
    nextLines,
    currentChars,
    nextChars,
    lineDelta: nextLines - currentLines,
    charDelta: nextChars - currentChars,
  }
}

/** 空串算 0 行；末尾换行不多算一行（与编辑器的视觉行数一致） */
function countLines(text) {
  if (text === '') return 0
  return text.replace(/\n$/, '').split('\n').length
}
```

```bash
npm run test:unit
```

预期：新增 11 条全绿，累计 63 条（任务 3 的 6 + 任务 6 的 9 + 任务 14 的 37 + 本任务 11）。

- [ ] **Step 3: 写 `useApplyScript`**

`src/composables/useApplyScript.js`

```js
/**
 * 「应用到编辑器」的统一入口。
 *
 * 流程：requestApply → 判定能不能替换 → 弹确认框（带变化量）→ confirmApply 真正替换。
 * 整篇替换脚本是破坏性操作（用户可能只想采纳其中几行），所以必须过一道确认，
 * 不做静默替换。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { canApply, diffSummary } from './applyScript'

/**
 * @param {object}   opts
 * @param {() => string}          opts.getScript  取编辑器当前内容
 * @param {(next: string) => void} opts.setScript  写回编辑器（会触发状态机作废）
 * @param {() => void|Promise<void>} [opts.onApplied] 替换成功后的回调，通常用来问「是否重新校验」
 */
export function useApplyScript({ getScript, setScript, onApplied }) {
  const dialogVisible = ref(false)
  const pendingScript = ref('')
  /** 来源标签，显示在确认框标题上：'AI 审查' / 'AI 对话' */
  const pendingSource = ref('')

  const summary = computed(() => diffSummary(getScript(), pendingScript.value))

  function requestApply(script, source = 'AI') {
    const verdict = canApply(getScript(), script)
    if (!verdict.ok) {
      ElMessage.info(verdict.reason)
      return false
    }
    pendingScript.value = script
    pendingSource.value = source
    dialogVisible.value = true
    return true
  }

  async function confirmApply() {
    const script = pendingScript.value
    dialogVisible.value = false
    setScript(script)
    // 清空待定内容，否则下次打开确认框会闪一下上一次的脚本
    pendingScript.value = ''
    pendingSource.value = ''
    ElMessage.success('已替换编辑器内容，校验结果已作废')
    await onApplied?.()
  }

  function cancelApply() {
    dialogVisible.value = false
    pendingScript.value = ''
    pendingSource.value = ''
  }

  return { dialogVisible, pendingScript, pendingSource, summary, requestApply, confirmApply, cancelApply }
}
```

- [ ] **Step 4: 写 `AiReviewCard.vue`**

`src/components/AiReviewCard.vue`

```vue
<script setup>
/**
 * AI 审查卡片（需求 4.3.2 第 3 步）。
 *
 * 三种形态：
 *   available=false → 降级说明（无 Key / Key 失效 / 语法未通过跳过 CR）
 *   available=true 且 suggestedScript 为空 → 只有审查意见
 *   available=true 且 suggestedScript 非空 → 意见 + [应用到编辑器]
 *
 * 交互规则 #5：CR 是建议性质。所以这张卡片永远不出现「阻断运行」的字样，
 * 即使意见里写了「有严重问题」，也不影响运行按钮（那只由 syntaxOk 决定）。
 *
 * text 按纯文本渲染（pre + pre-wrap），不引 markdown 库：
 * CR 提示词（任务 16）会约束模型输出朴素文本，引依赖不值得。
 */
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { PHASE } from '../composables/validationState'

const props = defineProps({
  /** {text, suggestedScript, available} | null */
  aiReview: { type: Object, default: null },
  phase: { type: String, default: PHASE.IDLE },
})
const emit = defineEmits(['apply'])

const review = computed(() => props.aiReview)
const hasSuggestion = computed(() => !!review.value?.suggestedScript?.trim())

/** 语法没过时后端会跳过 CR，卡片要给对应的说法，别让用户以为是 AI 坏了 */
const skippedBySyntax = computed(() => props.phase === PHASE.SYNTAX_FAILED)

async function copySuggestion() {
  try {
    await navigator.clipboard.writeText(review.value.suggestedScript)
    ElMessage.success('建议脚本已复制到剪贴板')
  } catch {
    // 非 https 或用户拒权时 clipboard API 会失败，退化成提示手动选
    ElMessage.warning('浏览器不允许自动复制，请在下方预览框中手动选中复制')
  }
}
</script>

<template>
  <div class="ai-card" :class="{ 'is-off': !review?.available }">
    <div class="head">
      <span class="title">AI 审查</span>
      <el-tag v-if="!review?.available" size="small" type="info" effect="plain">
        {{ skippedBySyntax ? '已跳过' : '不可用' }}
      </el-tag>
      <el-tag v-else size="small" type="success" effect="plain">qwen-plus</el-tag>
      <span class="spacer"></span>
      <span class="note">建议性质，不阻断运行</span>
    </div>

    <el-empty
      v-if="!review"
      description="校验完成后这里会显示 AI 的审查意见"
      :image-size="52"
    />

    <template v-else>
      <pre class="text">{{ review.text }}</pre>

      <!-- 建议脚本预览：折叠起来，别把卡片撑得比编辑器还高 -->
      <el-collapse v-if="hasSuggestion" class="suggest">
        <el-collapse-item name="s">
          <template #title>
            <span class="suggest-title">AI 给出了修改后的完整脚本</span>
          </template>
          <pre class="code">{{ review.suggestedScript }}</pre>
          <div class="actions">
            <el-button type="primary" size="small" @click="emit('apply', review.suggestedScript)">
              应用到编辑器
            </el-button>
            <el-button size="small" @click="copySuggestion">复制</el-button>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<style scoped>
.ai-card {
  margin-top: 12px;
  border-radius: 12px;
  border: 1px solid var(--border-light);
  /* 渐变软底，跟风格三的主色呼应，让 AI 区块与普通信息区分开 */
  background: var(--brand-gradient-soft);
  padding: 12px 14px;
}
.ai-card.is-off { background: var(--input-bg); }

.head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.title { font-size: 13px; font-weight: 600; color: var(--text-main); }
.spacer { flex: 1; }
.note { font-size: 11px; color: var(--text-muted); }

.text {
  margin: 0;
  font-size: 12px;
  line-height: 1.75;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 130px;
  overflow: auto;
  font-family: inherit;      /* 意见是自然语言，用正文字体比等宽好读 */
}

.suggest { margin-top: 10px; border: none; }
.suggest :deep(.el-collapse-item__header) {
  height: 30px;
  line-height: 30px;
  border: none;
  background: transparent;
  font-size: 12px;
}
.suggest :deep(.el-collapse-item__wrap) { border: none; background: transparent; }
.suggest-title { color: var(--chip-fg); font-weight: 600; }

.code {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--border-light);
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
}
.actions { display: flex; gap: 8px; margin-top: 8px; }
.actions :deep(.el-button) { border-radius: 16px; }
</style>
```

- [ ] **Step 5: 写 `ApplyScriptDialog.vue`**

`src/components/ApplyScriptDialog.vue`

```vue
<script setup>
/**
 * 「应用到编辑器」的确认框。
 *
 * 整篇替换会丢掉当前内容，所以先给用户看两样东西再确认：
 *   1. 变化量（行数 / 字符数增减）—— 一眼看出是大改还是小改
 *   2. 新脚本全文 —— 想只采纳几行的用户可以自己复制
 * 替换后当前内容仍可从编辑器的撤销历史里退回来（任务 13 用 dispatch changes 而非重建，
 * 就是为了保住这个撤销栈），确认框里也提示了这一点。
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  script: { type: String, default: '' },
  source: { type: String, default: 'AI' },
  summary: {
    type: Object,
    default: () => ({ currentLines: 0, nextLines: 0, currentChars: 0, nextChars: 0, lineDelta: 0, charDelta: 0 }),
  },
})
const emit = defineEmits(['update:visible', 'confirm', 'cancel'])

function signed(n) {
  return n > 0 ? `+${n}` : String(n)
}
function close() {
  emit('update:visible', false)
  emit('cancel')
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="`采纳脚本建议（来源：${source}）`"
    width="640px"
    :close-on-click-modal="false"
    @update:model-value="(v) => !v && close()"
  >
    <div class="stat">
      <div class="item">
        <span class="k">行数</span>
        <span class="v">{{ summary.currentLines }} → {{ summary.nextLines }}</span>
        <em :class="summary.lineDelta >= 0 ? 'up' : 'down'">{{ signed(summary.lineDelta) }}</em>
      </div>
      <div class="item">
        <span class="k">字符</span>
        <span class="v">{{ summary.currentChars }} → {{ summary.nextChars }}</span>
        <em :class="summary.charDelta >= 0 ? 'up' : 'down'">{{ signed(summary.charDelta) }}</em>
      </div>
    </div>

    <div class="label">替换后的脚本</div>
    <pre class="code">{{ script }}</pre>

    <p class="tip">
      这会替换编辑器里的全部内容。替换后可以在编辑器里按 ⌘Z 撤回，
      但校验结果会作废（脚本内容变了），需要重新校验才能运行。
    </p>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" @click="emit('confirm')">确认替换</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.stat {
  display: flex;
  gap: 20px;
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--input-bg);
  margin-bottom: 14px;
}
.item { display: flex; align-items: baseline; gap: 8px; }
.k { font-size: 12px; color: var(--text-muted); }
.v { font-size: 13px; color: var(--text-main); font-family: ui-monospace, Menlo, monospace; }
em { font-style: normal; font-size: 12px; font-weight: 600; }
.up { color: var(--chip-fg); }
.down { color: #f59e0b; }

.label { font-size: 12px; font-weight: 600; color: var(--text-muted); margin-bottom: 6px; }
.code {
  margin: 0;
  padding: 12px 14px;
  border-radius: 10px;
  background: #fafbff;
  border: 1px solid var(--border-light);
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
}
.tip { margin: 12px 0 0; font-size: 12px; line-height: 1.7; color: var(--text-muted); }
</style>
```

- [ ] **Step 6: 把卡片接进 `ValidationTabs.vue`**

三处改动：

1）`<script setup>` 的 import 加一行（放在 `RunResultCard` 那行后面）：

```js
import AiReviewCard from './AiReviewCard.vue'
```

`defineEmits` 里已经有 `'apply-suggested'`（任务 14 预留的），不用改。

2）把 `ai-review-slot` 那一整段（从 `<!-- ai-review-slot：任务 15 换成 <AiReviewCard>... -->` 到 `</div>` 结束，含 `ai-block` 的 div）替换成：

```vue
          <AiReviewCard
            :ai-review="aiReview"
            :phase="phase"
            @apply="(s) => emit('apply-suggested', s)"
          />
```

3）`<style scoped>` 里的 `.ai-block` 与 `.ai-text` 两条规则已经没人用了，删掉：

```css
/* 删除这两条：
.ai-block { margin-top: 12px; }
.ai-text { ... }
*/
```

`.ph-block, .ai-block { margin-top: 12px; }` 这一行改成只留 `.ph-block { margin-top: 12px; }`。

> 留着不删也不影响构建，但 Vue 的 scoped 样式会照样打进产物。任务 22 收尾时会扫一遍无用样式，别攒着。

- [ ] **Step 7: 改 `RuleWorkbenchView.vue`**

1）`<script setup>` 里补 import：

```js
import { ElMessage, ElMessageBox } from 'element-plus'
import ApplyScriptDialog from '../components/ApplyScriptDialog.vue'
import { useApplyScript } from '../composables/useApplyScript'
```

（`ElMessage` 任务 14 已经引了，只需在同一行加上 `ElMessageBox`。）

2）在 `const v = useValidationState(...)` 之后加：

```js
const apply = useApplyScript({
  getScript: () => scriptContent.value,
  setScript: (next) => { scriptContent.value = next },
  // 替换后校验必然作废（交互规则 #2），主动问一句要不要立刻重校验，
  // 别让用户对着变灰的运行按钮猜原因
  onApplied: async () => {
    try {
      await ElMessageBox.confirm(
        '脚本已替换，需要重新校验才能运行。现在校验吗？（会调用一次大模型审查）',
        '重新校验',
        { confirmButtonText: '现在校验', cancelButtonText: '稍后', type: 'info' },
      )
      await v.validate()
      if (v.errorMessage.value) reportError(new Error(v.errorMessage.value))
    } catch {
      // 用户点「稍后」或关掉弹窗，什么都不做
    }
  },
})

/** AI 审查卡片与 AI 对话面板（任务 19）共用的应用入口 */
function handleApplySuggested(script) {
  apply.requestApply(script, 'AI 审查')
}
```

3）template 里给 `ValidationTabs` 补一个事件绑定：

```vue
            @apply-suggested="handleApplySuggested"
```

4）template 末尾（`el-dialog` 加载失败弹窗之后、`</div>` 之前）加确认框：

```vue
    <ApplyScriptDialog
      v-model:visible="apply.dialogVisible.value"
      :script="apply.pendingScript.value"
      :source="apply.pendingSource.value"
      :summary="apply.summary.value"
      @confirm="apply.confirmApply"
      @cancel="apply.cancelApply"
    />
```

> 与任务 14 一样，`apply.xxx.value` 不是笔误：composable 返回普通对象、里面是 ref，模板里要显式 `.value`。

- [ ] **Step 8: 构建与单测**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：63 个单测通过，构建成功。

常见问题：
- `ElMessageBox is not defined` → 步骤 7 的第 1 项忘了加，注意它与 `ElMessage` 在同一行 import
- 确认框弹不出来 → `requestApply` 返回了 `false`（判定没过）。它会把原因用 `ElMessage.info` 弹出来，看那条提示就知道是「没给出内容」还是「内容一致」
- 卡片不显示 → `ValidationTabs` 只在 `phase` 为 `passed` / `syntax-failed` 时渲染 AI 区块（`idle` / `stale` / `validating` 走的是各自分支），先点一次校验

- [ ] **Step 9: 浏览器手工验收（用假数据，任务 16 之前 CR 还没接上）**

> **本次执行：延后并入任务 21。** `browser-use` MCP host 在本环境未连接（`list_pages` 直连报 `transport error ... context canceled`），与任务 14 同一阻断。按用户既定决定，交互式浏览器验收统一并入任务 21（端到端浏览器验收）。本步骤以下列无头证据替代：`applyScript` 11 条分支单测全绿（累计 63 条）、`npm run build` 通过（AiReviewCard / ApplyScriptDialog 编译成功，1642 模块）、对「应用到编辑器」全链路静态走查（AiReviewCard `emit('apply')` → ValidationTabs `apply-suggested` → `handleApplySuggested` → `requestApply` → 确认框 → `confirmApply` → `setScript` → `watch` → `syncScript` → STALE）。下方 12 项手工核对留待任务 21 补做，规则 id=140 可复用。

因为 `validate` 现在返回的是降级数据，直接点校验看不到卡片的完整形态。用 Vue devtools 或临时代码把 `aiReview` 改成三种形态各验一遍。

**临时改法**（验收完必须还原，别提交）：在 `RuleWorkbenchView.vue` 里把 `:ai-review="v.aiReview.value"` 临时换成一个写死的对象：

```js
// 【临时验收用，验完删掉】
const fakeReview = ref({
  text: '1. 逻辑正确，但 level 未做空值判断，传入 null 时会返回 null 而不是 "minor"。\n2. 建议把三元表达式改成显式 if，便于后续加档位。',
  suggestedScript: 'int age = ${age}\nString level = "${level}"\nif (level == null) {\n  return "unknown"\n}\nreturn age >= 18 ? level : "minor"',
  available: true,
})
```

逐条核对：

1. **降级形态**（真实数据，不改代码）：点 [校验] → 卡片标题旁是灰色「不可用」标签，正文显示任务 12 的降级文案，**没有** [应用到编辑器] 按钮
2. **语法失败形态**：用 `return a + b))` 校验 → 卡片标签是「已跳过」而不是「不可用」（这两种情况后端都给 `available=false`，但前端要区分说法）
3. **有建议形态**（换成 `fakeReview`）：卡片右上出现「qwen-plus」绿标，正文按换行正常显示，下方有可折叠的「AI 给出了修改后的完整脚本」
4. 展开折叠 → 看到脚本全文 + [应用到编辑器] + [复制]
5. 点 [复制] → 提示「建议脚本已复制到剪贴板」（`http://localhost` 下 clipboard API 可用；若浏览器拒权则应显示「请在下方预览框中手动选中复制」，不能报 JS 错）
6. 点 [应用到编辑器] → 弹确认框：标题「采纳脚本建议（来源：AI 审查）」，变化量显示行数与字符数增减，下面是新脚本全文，底部有「⌘Z 可撤回 + 需重新校验」的提示
7. 点 [取消] → 编辑器内容不变，运行按钮状态不变
8. 再点 [应用到编辑器] → [确认替换] → 编辑器内容变成建议脚本，提示「已替换编辑器内容，校验结果已作废」，徽标变 `stale`，运行按钮变灰，紧接着弹「现在校验吗？」
9. 第 8 步弹窗里点 [稍后] → 什么都不发生，编辑器保持新内容，运行按钮保持灰
10. 再点一次 [确认替换] 前先在编辑器里按 ⌘Z → **能退回替换前的内容**（这条验的是任务 13 保住撤销栈的设计，很重要）
11. 把编辑器内容手动改成与建议脚本完全一致，再点 [应用到编辑器] → 弹的是 info 提示「建议内容与当前脚本一致，无需替换」，**不弹确认框**
12. 把 `fakeReview.suggestedScript` 改成 `''` → 卡片只显示意见，折叠区与按钮都消失

验收完把 `fakeReview` 相关代码删干净，恢复成 `:ai-review="v.aiReview.value"`：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
grep -n "fakeReview\|临时验收用" src/views/RuleWorkbenchView.vue
```

预期：**无输出**。有输出说明没删干净，别提交。

- [ ] **Step 10: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_front
git commit -m "feat: AI 审查卡片与应用建议到编辑器（二次确认/变化量预览/保住撤销栈）"
```

---

## Task 16: AiService —— 真实大模型 CR

本次拿到百炼 Key 后**从「降级占位」变成「真实可调」**的第一个任务。完成后，`/api/rule/validate` 的第三步会真的把脚本发给 `qwen-plus` 审查，任务 15 的 AI 审查卡片也不再显示「不可用」。

**⚠️ 本任务最大的坑（已核实，务必照做）：Spring AI 的 fluent API 会把 `{...}` 当模板变量解析。**

`.user(String)` / `.system(String)` 内部走 `PromptTemplate`，模板语法是 **`{变量名}`**。而我们的 Groovy 脚本里全是 `${age}`、`${level}` 这种占位符 —— 其中的 `{age}` 会被模板引擎当成待替换变量，没提供对应 `param` 就会渲染异常或把占位符吃掉。

**解法：绕开模板，直接用 non-fluent 的 `Prompt` 构造。** 已核实 Spring AI 1.0.0 的签名：

| 需要的 API | 核实结果（1.0.0 javadoc） |
|---|---|
| `org.springframework.ai.chat.prompt.Prompt` | 有 `Prompt(Message... messages)`、`Prompt(List<Message>)`、`Prompt(String)` |
| `org.springframework.ai.chat.messages.SystemMessage` | 有 `SystemMessage(String textContent)` |
| `org.springframework.ai.chat.messages.UserMessage` | 有 `UserMessage(String textContent)` |
| `ChatClient.Builder` | Spring Boot 自动配置的 **prototype** bean，构造器注入后 `.build()` |
| 同步取文本 | `chatClient.prompt(prompt).call().content()` → `String` |
| 流式取文本 | `chatClient.prompt(prompt).stream().content()` → `Flux<String>`（任务 18 用） |

所以 CR 的调用必须是：

```java
Prompt prompt = new Prompt(new SystemMessage(CR_SYSTEM_PROMPT), new UserMessage(script));
String reply = chatClient.prompt(prompt).call().content();
```

**不能**写成 `chatClient.prompt().system(CR_SYSTEM_PROMPT).user(script).call().content()` —— 那样脚本里的 `${age}` 就废了。这条在 Step 8 有专门的验收项。

> 顺带一个好处：提示词本身也含 `${变量名}` 字样（要告诉模型这是占位符不是错误），走 `SystemMessage` 同样不会被解析。两个方向都安全。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/service/AiCodeBlockExtractor.java`
- Create: `script_back/src/test/java/com/xd/rulescript/service/AiCodeBlockExtractorTest.java`
- Create: `script_back/src/main/java/com/xd/rulescript/service/AiService.java`
- Create: `script_back/src/test/java/com/xd/rulescript/service/AiServiceAvailabilityTest.java`
- Modify: `script_back/src/main/java/com/xd/rulescript/config/AiConfig.java`（加 CR 线程池 bean）
- Modify: `script_back/src/main/java/com/xd/rulescript/service/RuleService.java`（**替换任务 12 留下的 TODO**）
- Modify: `script_back/src/test/java/com/xd/rulescript/service/RuleServiceTest.java`（CR 相关断言收紧）

**Interfaces:**
- Consumes: `ChatClient.Builder`（DashScope starter 自动配置）、`AiReviewResult`（任务 5）、`app.ai.*` 与 `spring.ai.dashscope.*` 配置（任务 2）
- Produces:
  ```java
  // AiCodeBlockExtractor —— 纯静态，可单测
  static String AiCodeBlockExtractor.extract(String reply)   // 提不到返回 null

  // AiService
  boolean AiService.isAvailable()
  AiReviewResult AiService.reviewScript(String script)       // 永不抛异常，失败即降级
  static String AiService.UNAVAILABLE_TEXT                   // 未配置 Key 时的降级文案
  ```
- 供任务 17、18 复用的部分：`AiService` 会持有 `chatClient` 与 `isAvailable()`，对话功能直接在其上加方法，不另建服务

- [ ] **Step 1: 写代码块提取的失败测试**

大模型的输出格式不稳定，这个提取器的边界必须用测试钉死，否则「应用到编辑器」按钮会时灵时不灵。

`src/test/java/com/xd/rulescript/service/AiCodeBlockExtractorTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 从大模型回复里提取「修改后的完整脚本」。
 * 模型输出格式不稳定，这里的边界情况必须全部钉死 ——
 * 提取错了会让用户一键把半截脚本替换进编辑器。
 */
class AiCodeBlockExtractorTest {

    @Test
    void 提取带groovy标记的代码块() {
        String reply = "审查发现两个问题：\n```groovy\nint a = 1\nreturn a\n```\n以上为修改后的脚本";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("int a = 1\nreturn a");
    }

    @Test
    void 提取无语言标记的代码块() {
        String reply = "建议改成：\n```\nreturn 1\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("return 1");
    }

    @Test
    void java标记也接受() {
        // 模型有时会把 Groovy 标成 java，不该因此提不到
        String reply = "```java\nreturn 2\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("return 2");
    }

    @Test
    void 多个块时取最后一个groovy块() {
        // 模型常先给「问题片段」再给「完整脚本」，完整的那个在后面
        String reply = """
                问题在这一行：
                ```groovy
                return a
                ```
                完整修改后脚本：
                ```groovy
                int a = ${age}
                return a * 2
                ```
                """;
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("int a = ${age}\nreturn a * 2");
    }

    @Test
    void 有groovy块时优先于无标记块() {
        String reply = "```\n不是脚本\n```\n```groovy\nreturn 1\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("return 1");
    }

    @Test
    void 占位符原样保留不被吃掉() {
        // 最关键的一条：${age} 必须完整出现在提取结果里
        String reply = "```groovy\nint age = ${age}\nString lv = \"${level}\"\nreturn age\n```";
        String extracted = AiCodeBlockExtractor.extract(reply);
        assertThat(extracted).contains("${age}").contains("${level}");
    }

    @Test
    void 没有代码块返回null() {
        assertThat(AiCodeBlockExtractor.extract("审查通过，未发现明显问题")).isNull();
    }

    @Test
    void 空代码块返回null() {
        assertThat(AiCodeBlockExtractor.extract("```groovy\n```")).isNull();
        assertThat(AiCodeBlockExtractor.extract("```groovy\n   \n```")).isNull();
    }

    @Test
    void 只有开头没有闭合返回null() {
        // 模型输出被截断时会这样；把后面全部内容当脚本会毁掉用户的编辑器
        assertThat(AiCodeBlockExtractor.extract("```groovy\nint a = 1\n（输出到此中断）")).isNull();
    }

    @Test
    void CRLF换行也能提取() {
        String reply = "```groovy\r\nint a = 1\r\nreturn a\r\n```";
        assertThat(AiCodeBlockExtractor.extract(reply)).isEqualTo("int a = 1\nreturn a");
    }

    @Test
    void 语言标记后有多余空格也能提取() {
        assertThat(AiCodeBlockExtractor.extract("```groovy   \nreturn 1\n```")).isEqualTo("return 1");
    }

    @Test
    void 首尾空白被trim() {
        assertThat(AiCodeBlockExtractor.extract("```groovy\n\n  return 1  \n\n```")).isEqualTo("return 1");
    }

    @Test
    void 入参为null或空返回null且不抛异常() {
        assertThat(AiCodeBlockExtractor.extract(null)).isNull();
        assertThat(AiCodeBlockExtractor.extract("")).isNull();
        assertThat(AiCodeBlockExtractor.extract("   ")).isNull();
    }

    @Test
    void 反引号多于三个也能提取() {
        // 模型偶尔用 ```` 包裹含 ``` 的内容
        assertThat(AiCodeBlockExtractor.extract("````groovy\nreturn 1\n````")).isEqualTo("return 1");
    }

    @Test
    void 行内单反引号不被误判为代码块() {
        assertThat(AiCodeBlockExtractor.extract("建议把 `level` 改名")).isNull();
    }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test -Dtest=AiCodeBlockExtractorTest
```

预期：编译失败（类不存在）。这是 TDD 的先红。

- [ ] **Step 2: 实现提取器**

`src/main/java/com/xd/rulescript/service/AiCodeBlockExtractor.java`

```java
package com.xd.rulescript.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从大模型回复中提取「修改后的完整脚本」（需求 4.3.2：若给出修改，提供一键应用）。
 *
 * 取块策略（顺序即优先级）：
 *   1. 带 groovy / java 语言标记的块里，取**最后一个**
 *   2. 退而取无语言标记的块里，取**最后一个**
 *   3. 都没有则返回 null（前端据此隐藏「应用到编辑器」按钮）
 *
 * 为什么取最后一个：模型的典型输出是「先指出问题行、再给完整脚本」，
 * 完整脚本几乎总在后面。取第一个会把问题片段当成完整脚本替换进编辑器。
 *
 * 为什么要求必须闭合：模型输出被 token 上限截断时只有开头的 ```，
 * 这时把剩余全文当脚本会直接毁掉用户正在编辑的内容，宁可返回 null。
 */
public final class AiCodeBlockExtractor {

    /** 三个及以上反引号 + 可选语言标记 + 换行 + 内容 + 同等数量的反引号闭合 */
    private static final Pattern FENCED = Pattern.compile(
            "(`{3,})[ \\t]*([A-Za-z]*)[ \\t]*\\r?\\n(.*?)\\r?\\n[ \\t]*\\1",
            Pattern.DOTALL);

    private static final String LANG_GROOVY = "groovy";
    private static final String LANG_JAVA = "java";

    private AiCodeBlockExtractor() {
    }

    /**
     * @return 提取到的脚本（已 trim、换行统一为 \n），提不到返回 null
     */
    public static String extract(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }

        List<String> tagged = new ArrayList<>();
        List<String> untagged = new ArrayList<>();

        Matcher m = FENCED.matcher(reply);
        while (m.find()) {
            String lang = m.group(2).toLowerCase();
            String body = normalize(m.group(3));
            if (body.isBlank()) {
                continue;                       // 空块直接忽略，继续找下一个
            }
            if (LANG_GROOVY.equals(lang) || LANG_JAVA.equals(lang)) {
                tagged.add(body);
            } else if (lang.isEmpty()) {
                untagged.add(body);
            }
            // 其它语言标记（如 ```text）既不进 tagged 也不进 untagged：
            // 那多半是模型在举例说明，不是要替换的脚本
        }

        if (!tagged.isEmpty()) {
            return tagged.get(tagged.size() - 1);
        }
        if (!untagged.isEmpty()) {
            return untagged.get(untagged.size() - 1);
        }
        return null;
    }

    /** CRLF 统一成 LF 再去首尾空白，保证写进编辑器后行号与校验结果一致 */
    private static String normalize(String body) {
        return body.replace("\r\n", "\n").replace("\r", "\n").trim();
    }
}
```

> 正则里用了 `\\1` 反向引用匹配「同等数量的反引号」，所以 ```` ```` ```` 包裹的块也能正确闭合，不会被三个反引号提前截断。

```bash
mvn -q test -Dtest=AiCodeBlockExtractorTest
```

预期：15 条全绿。

- [ ] **Step 3: 写 `AiService`**

`src/main/java/com/xd/rulescript/service/AiService.java`

```java
package com.xd.rulescript.service;

import com.xd.rulescript.dto.AiReviewResult;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 大模型服务：代码审查（本任务）与对话（任务 17、18）。
 *
 * 两条铁律：
 * 1. **reviewScript 永不抛异常**。AI 挂了、Key 错了、超时了，都只返回 available=false 的降级结果。
 *    这是非功能要求 #1 的一部分：AI 不可用时，语法校验与沙箱运行必须照常可用。
 * 2. **绝不用 fluent 的 .user(script) / .system(text) 传脚本**。
 *    Spring AI 的 PromptTemplate 把 {xxx} 当模板变量，而 Groovy 占位符正是 ${xxx}，
 *    走模板会把 ${age} 解析坏。一律用 new Prompt(new SystemMessage(...), new UserMessage(...))。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 未配置 Key 时的降级文案（前端 AI 审查卡片会显示「不可用」标签） */
    public static final String UNAVAILABLE_TEXT =
            "AI 审查未启用：尚未配置大模型 API Key。语法校验与脚本运行不受影响。";
    /** 调用失败的降级文案 */
    public static final String FAILED_TEXT =
            "AI 审查暂时不可用（调用大模型失败）。语法校验结论不受影响，可直接填值运行。";

    /**
     * CR 系统提示词。
     *
     * 特别注意第一段：必须告诉模型 ${xxx} 是占位符而非错误。
     * 不交代的话模型每次都会报「变量未定义」，审查意见全是噪音。
     */
    static final String CR_SYSTEM_PROMPT = """
            你是一位资深 Groovy 代码审查专家，审查的是业务规则脚本。

            【重要背景】脚本中形如 ${变量名} 的是占位符，运行前会被真实值替换，这是本系统的正常设计。
            绝对不要把占位符报告为「语法错误」「变量未定义」「缺少声明」等问题。

            请只审查以下方面：
            1. 语义错误：条件写反、边界处理缺失、与常识不符的判断
            2. 逻辑漏洞：分支覆盖不全、可能死循环、类型误用（如把字符串当数字比较）
            3. 潜在空指针：未判空就调用方法或访问属性
            4. 明显影响正确性的可维护性问题

            输出要求（务必严格遵守）：
            - 全程用中文，分条列出，最多 5 条，每条一行，行首用「1. 2. 3.」编号
            - 如果没有发现问题，只回复一行：审查通过，未发现明显问题
            - 不要复述脚本内容，不要描述你的审查过程，不要输出客套话
            - 如需给出修改后的脚本，必须把【完整可运行的脚本】放进 ```groovy 代码块中，
              且代码块外不要再出现任何脚本片段
            - 修改后的脚本必须保留原有的全部 ${占位符}，不要替换成具体值
            """;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final String apiKey;
    private final int crTimeoutSeconds;
    private final ExecutorService crExecutor;

    /**
     * @param builderProvider 用 ObjectProvider 而不是直接注入 ChatClient.Builder：
     *                        Key 缺失时 DashScope starter 可能不创建该 bean，
     *                        直接注入会导致**整个应用启动失败**，这违反非功能要求 #1
     */
    public AiService(ObjectProvider<ChatClient.Builder> builderProvider,
                     @Value("${app.ai.enabled:true}") boolean enabled,
                     @Value("${spring.ai.dashscope.api-key:not-configured}") String apiKey,
                     @Value("${app.ai.cr-timeout-seconds:60}") int crTimeoutSeconds,
                     @Qualifier("crExecutor") ExecutorService crExecutor) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.chatClient = builder != null ? builder.build() : null;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.crTimeoutSeconds = crTimeoutSeconds;
        this.crExecutor = crExecutor;
        log.info("AiService 初始化：enabled={}，ChatClient={}，api-key={}",
                enabled, chatClient != null ? "已就绪" : "缺失", hasUsableKey() ? "已配置" : "未配置");
    }

    /** AI 是否可用。不可用时所有 AI 功能走降级，其余功能照常 */
    public boolean isAvailable() {
        return enabled && chatClient != null && hasUsableKey();
    }

    private boolean hasUsableKey() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey.trim());
    }

    /**
     * 同步代码审查（需求 4.3.2 第 3 步）。
     * 校验接口会阻塞等它返回，所以必须自带超时，绝不能把 /api/rule/validate 挂死。
     *
     * @return 永远非 null；失败时 available=false 并给中文说明
     */
    public AiReviewResult reviewScript(String script) {
        if (!isAvailable()) {
            return new AiReviewResult(UNAVAILABLE_TEXT, null, false);
        }
        if (script == null || script.isBlank()) {
            return new AiReviewResult("脚本为空，无需审查", null, true);
        }

        Future<String> future = crExecutor.submit(() -> callReview(script));
        try {
            String reply = future.get(crTimeoutSeconds, TimeUnit.SECONDS);
            if (reply == null || reply.isBlank()) {
                return new AiReviewResult("AI 没有返回内容，请重试", null, false);
            }
            return new AiReviewResult(reply.trim(), AiCodeBlockExtractor.extract(reply), true);
        } catch (TimeoutException e) {
            // get 超时不会自动停止底层任务，必须显式取消，否则线程池会被慢请求占满
            future.cancel(true);
            log.warn("AI 审查超时（{} 秒），已降级", crTimeoutSeconds);
            return new AiReviewResult(
                    "AI 审查超时（超过 " + crTimeoutSeconds + " 秒），已跳过。语法校验结论不受影响，可直接填值运行。",
                    null, false);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("AI 审查被中断", e);
            return new AiReviewResult(FAILED_TEXT, null, false);
        } catch (ExecutionException | RuntimeException e) {
            // 原始异常只进日志，绝不回传前端（Global Constraints：严禁堆栈与英文异常类名）
            log.warn("AI 审查调用失败：{}", rootMessage(e));
            return new AiReviewResult(FAILED_TEXT, null, false);
        }
    }

    /**
     * 真正发起调用。**这里是本任务最关键的一处**：
     * 用 Prompt(Message...) 而非 fluent 的 .system()/.user()，避免 ${占位符} 被模板引擎解析。
     */
    private String callReview(String script) {
        Prompt prompt = new Prompt(new SystemMessage(CR_SYSTEM_PROMPT), new UserMessage(script));
        return chatClient.prompt(prompt).call().content();
    }

    /** 取最内层原因，日志里看清楚是网络、鉴权还是限流 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
```

- [ ] **Step 4: 给 `AiConfig` 加 CR 线程池**

`src/main/java/com/xd/rulescript/config/AiConfig.java`（任务 2 已创建，此处**追加**一个 bean；ChatMemory 的 bean 留给任务 17 加）

```java
    /**
     * CR 专用线程池。
     *
     * 为什么不复用任务 10 的 scriptExecutor：脚本执行是 CPU 密集且 5 秒必超时，
     * CR 是 IO 密集且可能十几秒，两者混在一个池里会互相饿死 ——
     * 用户连点几次校验就能把跑脚本的线程全占住。
     *
     * 固定 4 线程 + 有界队列：CR 慢的时候让请求排队，配合 AiService 的超时兜底，
     * 不至于无界堆积把内存吃掉。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService crExecutor() {
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable);
            // 用 getId() 而不是 threadId()：后者是 Java 19 才加的，本项目锁定 JDK 17
            t.setName("ai-cr-" + t.getId());
            t.setDaemon(true);        // 守护线程：应用关闭时不被未完成的 CR 拖住
            return t;
        };
        return new ThreadPoolExecutor(
                4, 4,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(32),
                factory,
                // 队列满说明大模型已经严重拥堵，直接在调用线程跑（会走 AiService 的超时降级），
                // 不用 AbortPolicy 抛异常，避免用户看到「服务异常」
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
```

需要的 import：

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
```

- [ ] **Step 5: 写可用性降级测试**

**注意：这个测试绝不真调大模型**（Global Constraints 第 4 条：单测一律不烧额度、不因网络抖动变红）。真实调用只在 Step 8 手工验收做。

`src/test/java/com/xd/rulescript/service/AiServiceAvailabilityTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.xd.rulescript.dto.AiReviewResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 不可用时的降级行为（非功能要求 #1）。
 * 用 app.ai.enabled=false 强制走降级分支，不发起任何真实调用。
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "spring.ai.dashscope.api-key=not-configured",
})
class AiServiceAvailabilityTest {

    @Autowired
    private AiService aiService;

    @Test
    void 关闭开关后判定为不可用() {
        assertThat(aiService.isAvailable()).isFalse();
    }

    @Test
    void 不可用时返回降级结果而不是抛异常() {
        assertThatCode(() -> aiService.reviewScript("return 1")).doesNotThrowAnyException();

        AiReviewResult r = aiService.reviewScript("return 1");
        assertThat(r.available()).isFalse();
        assertThat(r.suggestedScript()).isNull();
        assertThat(r.text()).isEqualTo(AiService.UNAVAILABLE_TEXT);
    }

    @Test
    void 降级文案是可读中文不含英文异常词() {
        String text = aiService.reviewScript("return 1").text();
        assertThat(text).contains("AI 审查");
        assertThat(text).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("null");
    }

    @Test
    void 脚本为null或空也不抛异常() {
        assertThatCode(() -> aiService.reviewScript(null)).doesNotThrowAnyException();
        assertThatCode(() -> aiService.reviewScript("   ")).doesNotThrowAnyException();
        assertThat(aiService.reviewScript(null).available()).isFalse();
    }

    @Test
    void AI不可用时应用上下文仍能正常加载() {
        // 这条是整个降级设计的底线：ChatClient bean 缺失或 Key 非法都不能让应用起不来
        assertThat(aiService).isNotNull();
    }
}
```

- [ ] **Step 6: 替换任务 12 留下的 TODO**

打开 `src/main/java/com/xd/rulescript/service/RuleService.java`，找到任务 12 写的这一段：

```java
        // TODO(任务 16): 换成 aiService.reviewScript(script)
        AiReviewResult aiReview = new AiReviewResult(
                "AI 审查尚未接入，语法校验与运行不受影响", null, false);
```

替换为：

```java
        // 第三步：大模型 CR（同步等待，自带超时与降级，见 AiService）
        AiReviewResult aiReview = aiService.reviewScript(scriptContent);
```

并给 `RuleService` 注入 `AiService`。若是构造器注入，在参数列表里加一项、字段加一行：

```java
    private final AiService aiService;
```

构造器：

```java
    public RuleService(RuleRepository ruleRepository,
                       ConversationRepository conversationRepository,
                       ChatMessageRepository chatMessageRepository,
                       TestCaseRepository testCaseRepository,
                       GroovyEngineService groovyEngineService,
                       AiService aiService) {
        // ... 原有赋值 ...
        this.aiService = aiService;
    }
```

> 任务 12 的 `validate` 里「语法不通过就跳过 CR」的分支**保持原样不动** —— 那段返回的是
> `new AiReviewResult("语法未通过，已跳过 AI 审查…", null, false)`，不调 `aiService`。
> 语法都没过就别浪费一次大模型调用，这是任务 12 定下的、也是任务 15 前端区分「已跳过」与「不可用」两种标签的依据。

同时收紧 `RuleServiceTest`：任务 12 里那条断言降级文案的测试，现在文案变了。找到

```java
        assertThat(r.aiReview().text()).contains("尚未接入");
```

改成断言「语法通过时 CR 被调用过、且无论成败都给出非空文本」，不再钉死具体文案（文案会随 Key 有无而变，钉死了测试就不稳定）：

```java
        // 语法通过时 CR 一定会被调用；单测里 app.ai.enabled=false，所以拿到的是降级文案。
        // 这里只断言「有可读文本且标记为不可用」，不钉死具体措辞
        assertThat(r.aiReview().text()).isNotBlank();
        assertThat(r.aiReview().available()).isFalse();
```

- [ ] **Step 7: 跑全量后端测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，**176 个测试通过**（任务 12 后的 156 + 本任务 15 + 5 = 176）。

若 `AiServiceAvailabilityTest` 报 `NoSuchBeanDefinitionException: ChatClient.Builder` —— 说明 `ObjectProvider` 那层没生效，检查 `AiService` 构造器第一个参数是不是写成了直接注入 `ChatClient.Builder`。

- [ ] **Step 8: 手工验收 —— 真实调用大模型**

这一步会**真的消耗百炼额度**，是计划中仅有的几处真实调用之一。启动前确认 Key 已就位：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
echo "key=${AI_DASHSCOPE_API_KEY:0:8}…  model=$AI_CHAT_MODEL  enabled=$AI_ENABLED"
```

预期输出 `key=sk-ws-H.…  model=qwen-plus  enabled=true`。若 key 是 `not-conf…`，回任务 1 Step 5 检查 `tools/local-secret.env`。

启动：

```bash
mvn -q spring-boot:run
```

启动日志里应能看到 `AiService 初始化：enabled=true，ChatClient=已就绪，api-key=已配置`。

另开终端，逐条验收：

**① 最关键的一条：含 `${占位符}` 的脚本能正常审查（验绕开模板的设计）**

```bash
curl -s -X POST http://localhost:8080/api/rule/validate \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"int age = ${age}\nString level = \"${level}\"\nreturn age >= 18 ? level : \"minor\""}' \
  | python3 -m json.tool
```

预期：
- `code` = 0，`data.syntaxOk` = true
- `data.placeholders` 是 `[{name:age,type:int},{name:level,type:String}]`
- **`data.aiReview.available` = true**（这是本任务成功的标志）
- `data.aiReview.text` 是中文审查意见，且**不含**「变量未定义」「占位符语法错误」这类把 `${age}` 当错误的噪音
- 整个请求耗时几秒到十几秒（CR 同步等待，需求 4.3.2）

若 `available` = false 且 text 是「调用大模型失败」，去看后端日志里 `AI 审查调用失败：` 后面的根因（鉴权/网络/限流）。

**② 有明显问题的脚本，CR 要能指出来**

```bash
curl -s -X POST http://localhost:8080/api/rule/validate \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"String name = ${userName}\nreturn name.toUpperCase()"}' \
  | python3 -m json.tool
```

预期：`aiReview.text` 里指出「name 可能为 null，直接调用 toUpperCase 会空指针」之类意见。
（`userName` 推断为 String，`String name = ${userName}` 在代码区替换后是 `String name = "值"`，语法通过，所以 CR 会真的跑。）

**③ CR 给出修改脚本时，suggestedScript 要能提取出来**

```bash
curl -s -X POST http://localhost:8080/api/rule/validate \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"int score = ${score}\nif (score >= 60) {\n    return \"不及格\"\n}\nreturn \"及格\""}' \
  | python3 -m json.tool
```

这段脚本语法完全合法，但条件与返回值明显矛盾（`>= 60` 却返回「不及格」），CR 应当指出并给出修正脚本。

预期分两种，**都算通过**（模型是否输出代码块不由我们决定）：
- CR 给了代码块 → `aiReview.suggestedScript` 非 null，且内容里**保留 `${score}` 占位符**（提示词已明确要求不要替换成具体值）
- CR 只用文字说明、没给代码块 → `suggestedScript` 为 null，前端据此隐藏「应用到编辑器」按钮（任务 15 的行为）

**唯一算失败的情况**：`text` 里明显能看到 ```` ```groovy ```` 代码块，但 `suggestedScript` 仍是 null。
那说明提取器的正则没覆盖该格式 —— 把 `text` 原文贴进 `AiCodeBlockExtractorTest` 补一条用例，再改 `AiCodeBlockExtractor`。

**④ 语法错误时不调大模型（省额度）**

```bash
time curl -s -X POST http://localhost:8080/api/rule/validate \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"return a + b))"}' | python3 -m json.tool
```

预期：**毫秒级返回**（对比 ① 的十几秒），`syntaxOk` = false，`aiReview.available` = false，text 是「语法未通过，已跳过 AI 审查…」。

**⑤ 错误 Key 要降级而不是把应用搞挂**

停掉后端，用假 Key 重启：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
AI_DASHSCOPE_API_KEY=sk-invalid-on-purpose mvn -q spring-boot:run
```

再调 ① 的 curl。预期：**应用正常启动**，`/api/health` 仍返回 code=0，`validate` 的 `syntaxOk` 与 `placeholders` **完全正常**，只有 `aiReview.available` = false、text 是「AI 审查暂时不可用（调用大模型失败）…」。

这条验的是非功能要求 #1 的底线：AI 挂了不能影响工具本体。验完 `Ctrl+C`，用真 Key 重启。

**⑥ 前端串起来看**

前后端都起着，浏览器进工作台，点 [校验]：
- 徽标先变「校验中…（含 AI 审查，可能要十几秒）」
- 十几秒后变「校验通过，可运行」
- 「校验结果」页的 AI 审查卡片：右上角是绿色 **qwen-plus** 标签（不再是任务 15 时的灰色「不可用」）
- 卡片正文是中文分条意见
- 若 CR 给了脚本 → 出现可折叠的「AI 给出了修改后的完整脚本」，点 [应用到编辑器] 走任务 15 的确认流程，替换后 `${占位符}` 应原样保留

**⑦ 用 MySQL MCP 确认这一步没有意外写库**

`validate` 与 `run` 都只吃请求体、不读不写数据库（任务 12 的行为约定）。查库确认没有多出脏数据：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT (SELECT COUNT(*) FROM rule) AS rules, (SELECT COUNT(*) FROM message) AS msgs"})
```

预期：`rules` 与 `msgs` 与你验收前的数量一致 —— 校验/运行不该产生任何记录。若 `msgs` 变多了，说明有人在 `validate` 里误写会话消息，回 Step 6 检查。

- [ ] **Step 9: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 接入百炼 qwen-plus 实现真实 AI 代码审查（绕开模板解析/超时降级/代码块提取）"
```

> **执行偏差记录（实际提交 `0a171b7`，7 文件 +468 -5）**
> 1. **AiConfig.java 是新建而非修改**：计划 Files 假设任务 2 已建此文件，实际不存在；本任务新建它、只放 `crExecutor` bean，并注明 ChatMemory 的 bean 留给任务 17 追加。
> 2. **RuleServiceTest 无需改动**：计划 Step 6 要改 `contains("尚未接入")` 断言，但任务 12 落地时该测试已是松散形式（`isNotBlank` + `available=false`），无此断言，故不动。
> 3. **主动加固 GroovyEngineService**：`crExecutor` 成为第 2 个 `ExecutorService` bean 后，原无 `@Qualifier` 的 `scriptExecutor` 注入有 `NoUniqueBeanDefinitionException` 风险（会拖垮所有 `@SpringBootTest`），故补 `@Qualifier("scriptExecutor")`，已被可用性测试上下文加载 + 全量测试验证有效。
> 4. **全量测试 163 通过，非计划预估的 176**：新增 15（提取器）+ 5（可用性）全绿、旧 143 无一破坏；差异源于计划对基线估算偏高 13，非代码问题。
> 5. **Step 8 手工验收**：①②③④⑤⑦ 均以真实 qwen-plus 调用（available=true、`${占位符}` 完整保留、代码块提取成功）、假 Key 降级（日志见 HTTP 401 InvalidApiKey 被 catch、返回 FAILED_TEXT、应用不挂）、MySQL MCP 查库（rules=3、msgs=0，validate 不写库）通过；**⑥（浏览器串起来看）因 browser-use MCP 不可达，并入任务 21 统一验收**。

---

## Task 17: 会话/消息持久化 + ChatMemory

为任务 18 的 SSE 对话铺好地基：内存记忆管**上下文**，MySQL 管**历史回显**，两者分工明确（技术方案 3.3）。同时落实需求非功能要求 #5：**服务重启后历史消息不丢**。

**已核实的 Spring AI 1.0.0 API（不要照旧文档或 1.1.x 文档写）：**

| 要用的东西 | 1.0.0 的正确写法 | 常见错误写法 |
|---|---|---|
| 记忆实现 | `MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(40).build()` | `new InMemoryChatMemory()`（**1.0.0 GA 已移除**） |
| 内存仓库 | `new InMemoryChatMemoryRepository()` | — |
| `ChatMemory` 接口 | `add(String, List<Message>)`、`add(String, Message)`、`get(String)` → `List<Message>`、`clear(String)` | — |
| 会话 ID 常量 | `ChatMemory.CONVERSATION_ID`（另有 `DEFAULT_CONVERSATION_ID`，但**不要依赖它**） | 不传 param → 运行期 `IllegalArgumentException` |
| 记忆 Advisor | `MessageChatMemoryAdvisor.builder(chatMemory).build()` | `new MessageChatMemoryAdvisor(chatMemory)`（1.0.0 里**没有公开构造器**，类是 final） |
| 传会话 ID | `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))` | — |
| 消息类型 | `SystemMessage(String)`、`UserMessage(String)`、`AssistantMessage(String)`，均在 `org.springframework.ai.chat.messages`；取内容用 `getText()` | `getContent()`（1.0.0 是 `getText()`） |

**`ChatMemory` 的 key 一律加前缀 `"conv-"`**（如 `conv-12`）。裸用数字 ID 会与将来可能出现的其他记忆用途撞 key，加前缀成本为零。

**Files:**
- Modify: `script_back/src/main/java/com/xd/rulescript/config/AiConfig.java`（加 `ChatMemory` bean）
- Create: `script_back/src/main/java/com/xd/rulescript/service/ChatMemoryService.java`
- Create: `script_back/src/test/java/com/xd/rulescript/service/ChatMemoryServiceTest.java`
- Create: `script_back/src/main/java/com/xd/rulescript/service/ChatService.java`
- Create: `script_back/src/test/java/com/xd/rulescript/service/ChatServiceTest.java`
- Create: `script_back/src/main/java/com/xd/rulescript/controller/ChatController.java`（本任务只做 `history` / `clear`，`send` 留任务 18）

**Interfaces:**
- Consumes: `Conversation` / `ChatMessage` 实体与 Repository（任务 4）、`ChatHistoryRequest` / `ChatMessageDto`（任务 5）、`RuleRepository`（任务 4）、`ApiResponse` / `BizException`（任务 2）
- Produces:
  ```java
  // AiConfig
  @Bean ChatMemory chatMemory()                       // MessageWindowChatMemory，窗口 40

  // ChatMemoryService —— 只管「内存记忆」这一层
  String ChatMemoryService.keyOf(Long conversationId) // "conv-" + id
  void ChatMemoryService.ensureLoaded(Long conversationId)   // 内存空且库有历史 → 回灌
  void ChatMemoryService.rememberUser(Long conversationId, String text)
  void ChatMemoryService.rememberAssistant(Long conversationId, String text)
  void ChatMemoryService.clearMemory(Long conversationId)
  int  ChatMemoryService.memorySize(Long conversationId)     // 测试与排查用

  // ChatService —— 管 MySQL 持久化 + 会话定位
  Long ChatService.conversationIdOf(Long ruleId)             // 找不到即报中文业务异常
  List<ChatMessageDto> ChatService.history(Long ruleId)      // 从 MySQL 读，时间正序
  void ChatService.clear(Long ruleId)                        // 清内存 + 删库中消息
  void ChatService.appendUser(Long ruleId, String text)      // 写库 + 写内存
  void ChatService.appendAssistant(Long ruleId, String text)

  // HTTP
  POST /api/chat/history  {ruleId}            → ApiResponse<List<ChatMessageDto>>
  POST /api/chat/clear    {ruleId}            → ApiResponse<Void>
  ```
- 任务 18 会在此基础上加 `POST /api/chat/send`（SSE），复用 `ChatService` 与 `ChatMemoryService`，不新建服务

**两个必须讲清的设计取舍：**

1. **历史回显读 MySQL，不读 ChatMemory。** 内存窗口只留最近 40 条（控制 token），而用户打开页面要看到**完整**历史。所以 `/api/chat/history` 查 `message` 表，`ChatMemory` 只服务于「发给大模型的上下文」。
2. **回灌必须幂等且防并发重复。** 每次对话前都会调 `ensureLoaded`；若内存已有内容就直接返回。两个请求同时判定为空会灌两遍（历史翻倍、token 翻倍），所以按 conversationId 加锁。

- [ ] **Step 1: 给 `AiConfig` 加 `ChatMemory` bean**

`src/main/java/com/xd/rulescript/config/AiConfig.java`（在任务 16 加的 `crExecutor` 之后追加）

```java
    /**
     * 对话记忆：进程内、按 conversationId 隔离、滑动窗口。
     *
     * 窗口取 40 条（约 20 轮问答）：再大会让每次请求的 token 明显上涨，
     * 再小则多轮上下文容易断。完整历史由 MySQL 的 message 表负责（见 ChatService.history）。
     *
     * 注意：技术方案 3.3 写的 InMemoryChatMemory 在 Spring AI 1.0.0 GA 已被移除，
     * 这里用的是等价替代 —— MessageWindowChatMemory + InMemoryChatMemoryRepository。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(40)
                .build();
    }
```

需要的 import：

```java
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
```

> Spring AI 本身也会自动配置一个 `ChatMemory` bean。我们自己声明后，自动配置的那个因 `@ConditionalOnMissingBean` 让位 ——
> 显式声明的好处是窗口大小写死在代码里可读，不受 starter 版本默认值（20）变化影响。

- [ ] **Step 2: 写 `ChatMemoryService` 的失败测试**

`src/test/java/com/xd/rulescript/service/ChatMemoryServiceTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内存记忆与 MySQL 历史之间的搬运（需求非功能要求 #5：服务重启后历史消息不丢）。
 */
@SpringBootTest(properties = "app.ai.enabled=false")
@Transactional
class ChatMemoryServiceTest {

    @Autowired private ChatMemoryService chatMemoryService;
    @Autowired private ChatMemory chatMemory;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    private Long conversationId;

    @BeforeEach
    void setUp() {
        Conversation c = new Conversation();
        c.setRuleId(990_001L);
        c.setTitle("测试会话");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conversationId = conversationRepository.save(c).getId();
        // 每个用例都从干净的记忆开始，否则上一个用例灌进去的内容会干扰断言
        chatMemory.clear(chatMemoryService.keyOf(conversationId));
    }

    private void saveMessage(String role, String content, int seq) {
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        // 用递增秒数保证 createdAt 顺序确定，避免同一秒内排序不稳定
        m.setCreatedAt(LocalDateTime.now().plusSeconds(seq));
        chatMessageRepository.save(m);
    }

    @Test
    void key带conv前缀() {
        assertThat(chatMemoryService.keyOf(12L)).isEqualTo("conv-12");
    }

    @Test
    void 库里没历史时回灌后记忆仍为空() {
        chatMemoryService.ensureLoaded(conversationId);
        assertThat(chatMemoryService.memorySize(conversationId)).isZero();
    }

    @Test
    void 库里有历史时回灌进内存且角色正确() {
        saveMessage("user", "帮我写个规则", 0);
        saveMessage("assistant", "好的，脚本如下", 1);

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).hasSize(2);
        assertThat(memory.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(memory.get(0).getText()).isEqualTo("帮我写个规则");
        assertThat(memory.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(memory.get(1).getText()).isEqualTo("好的，脚本如下");
    }

    @Test
    void 回灌是幂等的不会灌两遍() {
        saveMessage("user", "第一条", 0);
        saveMessage("assistant", "第二条", 1);

        chatMemoryService.ensureLoaded(conversationId);
        chatMemoryService.ensureLoaded(conversationId);
        chatMemoryService.ensureLoaded(conversationId);

        assertThat(chatMemoryService.memorySize(conversationId)).isEqualTo(2);
    }

    @Test
    void 内存已有内容时不再回灌() {
        saveMessage("user", "库里的旧消息", 0);
        chatMemoryService.rememberUser(conversationId, "内存里的新消息");

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).hasSize(1);
        assertThat(memory.get(0).getText()).isEqualTo("内存里的新消息");
    }

    @Test
    void 回灌只取最近窗口条数() {
        // 造 45 条，窗口是 40，回灌后不该超过 40
        for (int i = 0; i < 45; i++) {
            saveMessage(i % 2 == 0 ? "user" : "assistant", "消息" + i, i);
        }

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).hasSizeLessThanOrEqualTo(40);
        // 且保留的是**靠后**的那些（旧的被截掉），最后一条必须是 消息44
        assertThat(memory.get(memory.size() - 1).getText()).isEqualTo("消息44");
    }

    @Test
    void 回灌按时间正序而非倒序() {
        saveMessage("user", "早", 0);
        saveMessage("assistant", "中", 1);
        saveMessage("user", "晚", 2);

        chatMemoryService.ensureLoaded(conversationId);

        assertThat(chatMemory.get(chatMemoryService.keyOf(conversationId)))
                .extracting(Message::getText)
                .containsExactly("早", "中", "晚");
    }

    @Test
    void 未知角色被跳过不炸() {
        saveMessage("user", "正常", 0);
        saveMessage("system", "系统消息", 1);      // 库里不该有，但防御性处理
        saveMessage("weird", "未知角色", 2);

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).extracting(Message::getText).contains("正常");
        assertThat(memory).noneMatch(m -> "未知角色".equals(m.getText()));
    }

    @Test
    void 空内容消息被跳过() {
        saveMessage("user", "正常", 0);
        saveMessage("assistant", "", 1);
        saveMessage("assistant", null, 2);

        chatMemoryService.ensureLoaded(conversationId);

        assertThat(chatMemoryService.memorySize(conversationId)).isEqualTo(1);
    }

    @Test
    void 写入用户与助手消息() {
        chatMemoryService.rememberUser(conversationId, "问");
        chatMemoryService.rememberAssistant(conversationId, "答");

        assertThat(chatMemory.get(chatMemoryService.keyOf(conversationId)))
                .extracting(Message::getText)
                .containsExactly("问", "答");
    }

    @Test
    void 清空记忆() {
        chatMemoryService.rememberUser(conversationId, "问");
        chatMemoryService.clearMemory(conversationId);
        assertThat(chatMemoryService.memorySize(conversationId)).isZero();
    }

    @Test
    void 清空不存在的会话不抛异常() {
        assertThat(chatMemoryService.memorySize(888_888L)).isZero();
        chatMemoryService.clearMemory(888_888L);   // 不该炸
    }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test -Dtest=ChatMemoryServiceTest
```

预期：编译失败（`ChatMemoryService` 不存在）。

- [ ] **Step 3: 实现 `ChatMemoryService`**

`src/main/java/com/xd/rulescript/service/ChatMemoryService.java`

```java
package com.xd.rulescript.service;

import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.repository.ChatMessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * 对话记忆服务：只管「内存记忆」这一层，不碰 HTTP、不管业务编排。
 *
 * 存在的理由是需求非功能要求 #5：InMemoryChatMemoryRepository 重启即丢，
 * 但 message 表里有完整历史。所以每次对话前把库里的历史回灌进内存，
 * 重启后多轮上下文也能续上。
 *
 * 分工（技术方案 3.3）：**内存记忆管上下文，MySQL 管历史回显**。
 * 前端要看的完整历史走 ChatService.history()，不从这里的窗口里取。
 */
@Service
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);

    /** 与 AiConfig 里的 maxMessages 保持一致；超出部分在回灌时就截掉，别灌进去再让窗口淘汰 */
    private static final int WINDOW_SIZE = 40;
    private static final String KEY_PREFIX = "conv-";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ChatMemory chatMemory;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 正在回灌的会话集合，用来防并发重复灌。
     * 两个请求同时发现内存为空会各灌一遍，历史直接翻倍、token 也翻倍。
     */
    private final ConcurrentHashMap<Long, Object> loadLocks = new ConcurrentHashMap<>();

    public ChatMemoryService(ChatMemory chatMemory, ChatMessageRepository chatMessageRepository) {
        this.chatMemory = chatMemory;
        this.chatMessageRepository = chatMessageRepository;
    }

    /** ChatMemory 的 key。加前缀避免与将来其他用途的记忆撞 key */
    public String keyOf(Long conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /**
     * 确保内存里有这个会话的历史：内存为空且库里有记录时回灌。
     * 幂等 —— 已经有内容就直接返回，不重复灌。
     */
    public void ensureLoaded(Long conversationId) {
        String key = keyOf(conversationId);
        if (!chatMemory.get(key).isEmpty()) {
            return;
        }
        // computeIfAbsent 保证同一会话拿到同一把锁；不同会话互不阻塞
        synchronized (loadLocks.computeIfAbsent(conversationId, id -> new Object())) {
            try {
                // 双检：等锁期间可能已被另一个线程灌好
                if (!chatMemory.get(key).isEmpty()) {
                    return;
                }
                List<Message> fromDb = loadFromDatabase(conversationId);
                if (fromDb.isEmpty()) {
                    return;
                }
                chatMemory.add(key, fromDb);
                log.info("会话 {} 从 MySQL 回灌 {} 条历史消息", conversationId, fromDb.size());
            } finally {
                loadLocks.remove(conversationId);
            }
        }
    }

    /** 从库里读最近 WINDOW_SIZE 条，转成 Spring AI 的 Message，时间正序 */
    private List<Message> loadFromDatabase(Long conversationId) {
        List<ChatMessage> all = chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        // 只留最近窗口条数，且保留的是靠后的（旧的截掉）
        int from = Math.max(0, all.size() - WINDOW_SIZE);
        List<Message> messages = new ArrayList<>(all.size() - from);
        for (ChatMessage row : all.subList(from, all.size())) {
            Message m = toMessage(row);
            if (m != null) {
                messages.add(m);
            }
        }
        return messages;
    }

    /** 库里的 role 字符串 → Spring AI 消息对象；未知角色与空内容返回 null 由调用方跳过 */
    private Message toMessage(ChatMessage row) {
        String content = row.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        return switch (row.getRole() == null ? "" : row.getRole()) {
            case ROLE_USER -> new UserMessage(content);
            case ROLE_ASSISTANT -> new AssistantMessage(content);
            // system 与未知角色不回灌：system 提示由每次请求现拼，
            // 灌进记忆会让它被窗口保留、还会在多轮里重复出现
            default -> null;
        };
    }

    public void rememberUser(Long conversationId, String text) {
        if (text != null && !text.isBlank()) {
            chatMemory.add(keyOf(conversationId), new UserMessage(text));
        }
    }

    public void rememberAssistant(Long conversationId, String text) {
        if (text != null && !text.isBlank()) {
            chatMemory.add(keyOf(conversationId), new AssistantMessage(text));
        }
    }

    /** 只清内存。库里的历史由 ChatService.clear() 负责删 */
    public void clearMemory(Long conversationId) {
        chatMemory.clear(keyOf(conversationId));
    }

    /** 当前内存里有多少条，测试与排查用 */
    public int memorySize(Long conversationId) {
        return chatMemory.get(keyOf(conversationId)).size();
    }
}
```

```bash
mvn -q test -Dtest=ChatMemoryServiceTest
```

预期：12 条全绿。

> `loadLocks.remove(conversationId)` 放在 finally 里是为了不让 Map 无限增长（会话数等于规则数，长期跑会攒很多条目）。
> 代价是极端并发下可能出现「A 移除锁后 B 又新建一把」，但因为有双检 + `chatMemory.get` 判空兜底，最坏情况也只是多读一次库，不会灌重。

- [ ] **Step 4: 写 `ChatService` 的失败测试**

`src/test/java/com/xd/rulescript/service/ChatServiceTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话定位与消息持久化。
 * 需求 4.3.4：一条规则固定一个会话，创建规则时自动建立，无会话列表。
 */
@SpringBootTest(properties = "app.ai.enabled=false")
@Transactional
class ChatServiceTest {

    @Autowired private ChatService chatService;
    @Autowired private ChatMemoryService chatMemoryService;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    /** 用一个不存在的 ruleId，避免和 RuleServiceTest 造的数据互相干扰 */
    private static final Long RULE_ID = 990_002L;
    private Long conversationId;

    @BeforeEach
    void setUp() {
        Conversation c = new Conversation();
        c.setRuleId(RULE_ID);
        c.setTitle("规则 " + RULE_ID + " 的会话");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conversationId = conversationRepository.save(c).getId();
        chatMemoryService.clearMemory(conversationId);
    }

    @Test
    void 按规则ID找到会话() {
        assertThat(chatService.conversationIdOf(RULE_ID)).isEqualTo(conversationId);
    }

    @Test
    void 规则没有会话时给中文业务异常() {
        assertThatThrownBy(() -> chatService.conversationIdOf(777_777L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话");
    }

    @Test
    void 空历史返回空列表而不是null() {
        List<ChatMessageDto> h = chatService.history(RULE_ID);
        assertThat(h).isNotNull().isEmpty();
    }

    @Test
    void 历史按时间正序返回且字段完整() {
        chatService.appendUser(RULE_ID, "第一问");
        chatService.appendAssistant(RULE_ID, "第一答");
        chatService.appendUser(RULE_ID, "第二问");

        List<ChatMessageDto> h = chatService.history(RULE_ID);

        assertThat(h).hasSize(3);
        assertThat(h.get(0).role()).isEqualTo("user");
        assertThat(h.get(0).content()).isEqualTo("第一问");
        assertThat(h.get(1).role()).isEqualTo("assistant");
        assertThat(h.get(2).content()).isEqualTo("第二问");
        // createdAt 是给前端排序与展示用的，不能为空
        assertThat(h.get(0).createdAt()).isNotBlank();
    }

    @Test
    void 历史读的是MySQL而不是内存窗口() {
        // 造 45 条：内存窗口只留 40，但历史必须能看全
        for (int i = 0; i < 45; i++) {
            chatService.appendUser(RULE_ID, "消息" + i);
        }
        assertThat(chatService.history(RULE_ID)).hasSize(45);
        assertThat(chatMemoryService.memorySize(conversationId)).isLessThanOrEqualTo(40);
    }

    @Test
    void 追加消息同时写库和写内存() {
        chatService.appendUser(RULE_ID, "问");

        List<ChatMessage> rows = chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRole()).isEqualTo("user");
        assertThat(rows.get(0).getContent()).isEqualTo("问");

        assertThat(chatMemoryService.memorySize(conversationId)).isEqualTo(1);
    }

    @Test
    void 追加助手消息角色正确() {
        chatService.appendAssistant(RULE_ID, "答");
        ChatMessage row = chatMessageRepository
                .findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).get(0);
        assertThat(row.getRole()).isEqualTo("assistant");
    }

    @Test
    void 空消息不入库() {
        chatService.appendUser(RULE_ID, "   ");
        chatService.appendUser(RULE_ID, null);
        assertThat(chatService.history(RULE_ID)).isEmpty();
    }

    @Test
    void 清空同时删库和清内存但保留会话本身() {
        chatService.appendUser(RULE_ID, "问");
        chatService.appendAssistant(RULE_ID, "答");

        chatService.clear(RULE_ID);

        assertThat(chatService.history(RULE_ID)).isEmpty();
        assertThat(chatMemoryService.memorySize(conversationId)).isZero();
        // 需求 4.3.4：会话与规则一对一且不能删除，清空对话只清消息
        assertThat(conversationRepository.findByRuleId(RULE_ID)).isPresent();
    }

    @Test
    void 清空后还能继续对话() {
        chatService.appendUser(RULE_ID, "旧消息");
        chatService.clear(RULE_ID);
        chatService.appendUser(RULE_ID, "新消息");

        List<ChatMessageDto> h = chatService.history(RULE_ID);
        assertThat(h).hasSize(1);
        assertThat(h.get(0).content()).isEqualTo("新消息");
    }

    @Test
    void 清空没有消息的会话不抛异常() {
        chatService.clear(RULE_ID);
        chatService.clear(RULE_ID);      // 连续两次也不该炸
        assertThat(chatService.history(RULE_ID)).isEmpty();
    }

    @Test
    void 消息内容里的占位符与代码块原样保留() {
        // 对话会带脚本上下文，${} 与 ``` 不能被转义或截断
        String script = "int age = ${age}\n```groovy\nreturn age\n```";
        chatService.appendUser(RULE_ID, script);

        assertThat(chatService.history(RULE_ID).get(0).content()).isEqualTo(script);
    }
}
```

- [ ] **Step 5: 实现 `ChatService`**

`src/main/java/com/xd/rulescript/service/ChatService.java`

```java
package com.xd.rulescript.service;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话与消息编排（需求 4.3.4）。
 *
 * 一条规则固定一个会话，会话在创建规则时由 RuleService 自动建立，
 * 所以这里**只查找、不新建** —— 找不到就是数据异常，直接给中文业务提示。
 *
 * 双写：每条消息既进 MySQL（管历史回显与重启恢复），也进内存记忆（管发给大模型的上下文）。
 */
@Service
public class ChatService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMemoryService chatMemoryService;

    public ChatService(ConversationRepository conversationRepository,
                       ChatMessageRepository chatMessageRepository,
                       ChatMemoryService chatMemoryService) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatMemoryService = chatMemoryService;
    }

    /** 规则对应的会话 ID。规则不存在或没建会话都归为同一种用户可见的错误 */
    public Long conversationIdOf(Long ruleId) {
        Conversation c = conversationRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new BizException("该规则的对话会话不存在，请回列表页重新进入"));
        return c.getId();
    }

    /**
     * 历史消息，时间正序。
     * 读 MySQL 而不是内存窗口：窗口只留最近 40 条，用户要看到的是完整历史。
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> history(Long ruleId) {
        Long conversationId = conversationIdOf(ruleId);
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)
                .stream()
                .map(m -> new ChatMessageDto(
                        m.getRole(),
                        m.getContent(),
                        m.getCreatedAt() == null ? "" : m.getCreatedAt().format(TS)))
                .toList();
    }

    /**
     * 清空对话（需求 4.3.4）：清内存记忆 + 删库里消息。
     * **会话记录本身保留** —— 会话与规则一对一，删了就没法再对话了。
     */
    @Transactional
    public void clear(Long ruleId) {
        Long conversationId = conversationIdOf(ruleId);
        chatMessageRepository.deleteByConversationId(conversationId);
        chatMemoryService.clearMemory(conversationId);
    }

    @Transactional
    public void appendUser(Long ruleId, String text) {
        append(ruleId, ROLE_USER, text);
    }

    @Transactional
    public void appendAssistant(Long ruleId, String text) {
        append(ruleId, ROLE_ASSISTANT, text);
    }

    private void append(Long ruleId, String role, String text) {
        if (text == null || text.isBlank()) {
            return;                                 // 空消息不入库，避免历史里出现空气泡
        }
        Long conversationId = conversationIdOf(ruleId);

        ChatMessage row = new ChatMessage();
        row.setConversationId(conversationId);
        row.setRole(role);
        row.setContent(text);
        row.setCreatedAt(LocalDateTime.now());
        chatMessageRepository.save(row);

        // 库写成功后再进内存，顺序不能反：内存进了但库写失败会出现「重启就丢」的消息
        if (ROLE_USER.equals(role)) {
            chatMemoryService.rememberUser(conversationId, text);
        } else {
            chatMemoryService.rememberAssistant(conversationId, text);
        }

        touchConversation(conversationId);
    }

    /** 会话的 updatedAt 用来在列表里体现活跃度，顺手更新 */
    private void touchConversation(Long conversationId) {
        conversationRepository.findById(conversationId).ifPresent(c -> {
            c.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(c);
        });
    }
}
```

- [ ] **Step 6: 写 `ChatController` 的 history 与 clear**

`src/main/java/com/xd/rulescript/controller/ChatController.java`

```java
package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.ChatHistoryRequest;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话接口。
 *
 * 本任务先提供 history / clear 两个普通 JSON 接口；
 * send 是 SSE 流式，见任务 18（返回类型不同，不能和这两个混在一个方法签名里）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 打开页面时加载历史消息回显（需求 4.3.4） */
    @PostMapping("/history")
    public ApiResponse<List<ChatMessageDto>> history(@Valid @RequestBody ChatHistoryRequest request) {
        return ApiResponse.ok(chatService.history(request.ruleId()));
    }

    /** 清空该会话的记忆与历史消息 */
    @PostMapping("/clear")
    public ApiResponse<Void> clear(@Valid @RequestBody ChatHistoryRequest request) {
        chatService.clear(request.ruleId());
        // 用任务 2 为 Void 专门提供的无参重载，比 ok(null) 清楚且不会有泛型推断歧义
        return ApiResponse.ok();
    }
}
```

> `clear` 复用 `ChatHistoryRequest`（任务 5 定义的就是 `{ruleId}`），不新建 DTO —— 两个接口的入参完全一样。

- [ ] **Step 7: 跑全量后端测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，**200 个测试通过**（任务 16 后的 176 + `ChatMemoryServiceTest` 12 + `ChatServiceTest` 12 = 200）。

常见失败：
- `NoSuchBeanDefinitionException: ChatMemory` → Step 1 的 bean 没加，或 `AiConfig` 少了 `@Configuration`
- `IllegalArgumentException` 提到 conversation id → 某处调 `chatMemory.get/add/clear` 传了 null，检查 `keyOf` 的入参
- 历史条数比预期少 → `ensureLoaded` 被误触发把库里的数据当成「已加载」；本任务的测试都先 `clearMemory` 就是为了避开这个

- [ ] **Step 8: 手工验收 + 用 MySQL MCP 查库核对**

启动后端：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q spring-boot:run
```

**① 确认建规则时自动建了会话**

先建一条规则：

```bash
curl -s -X POST http://localhost:8080/api/rule/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"对话联调规则","description":"任务17验收用"}' | python3 -m json.tool
```

记下返回的 `data.id`（下面用 `$RID` 指代）。

查库核对会话真的建出来了：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, rule_id, title, created_at FROM conversation WHERE rule_id = ?", "params": [<RID>]})
```

预期：**恰好 1 行**（需求 4.3.4：一条规则固定一个会话）。0 行说明 `RuleService.create` 漏了建会话；多行说明 `rule_id` 的唯一索引没建，回任务 4 查实体注解。

**② history 空会话返回空数组**

```bash
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}' | python3 -m json.tool
```

预期：`{"code":0,"message":"success","data":[]}` —— 是空数组，不是 `null`。

**③ 手工往库里塞两条消息，验证 history 与回灌**

`/api/chat/send` 要任务 18 才有，所以这一步直接写库来造历史（这正好也验证了「重启后从 MySQL 回灌」这条路径的数据基础）：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "INSERT INTO message (conversation_id, role, content, created_at) VALUES (?, 'user', '帮我写个判断年龄的规则', NOW()), (?, 'assistant', 'int age = ${age}\nreturn age >= 18', NOW())", "params": [<CID>, <CID>]})
```

（`<CID>` 是 ① 查到的 conversation.id）

再调 history：

```bash
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}' | python3 -m json.tool
```

预期：`data` 是 2 条，`role` 分别为 `user` / `assistant`，`content` 里的 `${age}` **原样保留**，`createdAt` 是 `yyyy-MM-dd HH:mm:ss` 格式。

**这里必须核对时区**：`createdAt` 应与 ① 里 `NOW()` 写入的时间一致（差 8 小时就是 `serverTimezone` 没生效，回任务 1 查 `DB_URL`）。

用 MCP 反查库里的原始值对比：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, role, LEFT(content, 40) AS head, created_at FROM message WHERE conversation_id = ? ORDER BY created_at ASC, id ASC", "params": [<CID>]})
```

**④ clear 真的删了库里的消息、但保留会话**

```bash
curl -s -X POST http://localhost:8080/api/chat/clear \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}' | python3 -m json.tool
```

预期 `code=0`。然后**必须查库确认**（接口返回成功不代表真删了）：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT (SELECT COUNT(*) FROM message WHERE conversation_id = ?) AS msgs, (SELECT COUNT(*) FROM conversation WHERE id = ?) AS convs", "params": [<CID>, <CID>]})
```

预期：`msgs = 0` 且 **`convs = 1`**。
`convs` 变 0 就是删过头了 —— 会话与规则一对一，删了这条规则就再也无法对话（需求 4.3.4 明确不能删会话）。

再调一次 history 确认接口层面也是空：

```bash
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}' | python3 -m json.tool
```

**⑤ 不存在的规则要给中文提示而不是堆栈**

```bash
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":99999999}' | python3 -m json.tool
```

预期：HTTP 200，`code` 非 0，`message` 是「该规则的对话会话不存在，请回列表页重新进入」。
**绝不能**出现 `BizException`、`NoSuchElement`、`at com.xd.…` 这类字样（Global Constraints：严禁堆栈与英文异常类名）。

**⑥ 验收完清理测试数据**

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "DELETE FROM rule WHERE id = ?", "params": [<RID>]})
```

> **注意 `@Transactional` 测试的数据在库里查不到**，这是正常的 —— Spring 测试事务结束会回滚。
> 所以「用 MCP 查库核对」只对**运行中的应用**产生的数据有效（就是上面这些 curl 造的数据），
> 别拿它去验 `mvn test` 的结果，会误判成「没写进去」。

- [ ] **Step 9: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: 对话记忆与消息持久化（MySQL 历史回灌/窗口 40 条/清空保留会话）"
```

> **执行偏差记录（实际提交 `abdf386`，8 文件 +671 -4）**
> 1. **给实体补 `setCreatedAt` + `@PrePersist` 改条件式**：计划的测试夹具与 `ChatService.append` 都调 `setCreatedAt(...)`，但 `ChatMessage`/`Conversation` 原本只有 `getCreatedAt`（靠 `@PrePersist` 自动填充）。按 TDD 方向补齐 setter（让生产代码满足测试的合理预期），并把 `@PrePersist` 改为 `if (createdAt == null)` —— 现有代码从不显式 set，故对既有行为零影响，同时让测试的递增时间戳排序意图真正生效。
> 2. **`ChatMemoryServiceTest`「空内容消息被跳过」用空白串替代 null**：`message.content` 列是 NOT NULL（已查库核实），无法落库 null；改用纯空白串 `"   "`（同样被 `isBlank` 跳过），断言与意图不变。
> 3. **全量测试 187 通过，非计划预估的 200**：新增 12（ChatMemoryService）+ 12（ChatService）全绿、旧 163 无一破坏；差异同任务 16，源于计划基线估算偏高 13。
> 4. **Step 8 手工验收全部通过（无需浏览器）**：本任务 Step 8 只用 curl + MySQL MCP（均可达）——①建规则自动建会话（CID=181，恰 1 行）②history 空返回 `[]`③2 条消息往返、`${age}` 与换行原样保留、时区一致（库 14:55:15 == API 14:55:15）④clear 后 msgs=0/convs=1（保留会话）⑤不存在规则 HTTP200+code1000+中文提示无堆栈⑥清理后 rules=3/msgs=0/无孤儿。

---

## Task 18: `/api/chat/send` —— SSE 流式对话

本次拿到百炼 Key 后**从「降级占位」变成「真实可调」**的第二个任务。完成后，右侧对话面板（任务 19）才有后端可接。

**先讲清一个容易搞错的架构点：本项目是 Servlet 栈（`spring-boot-starter-web`），却能返回 `Flux<ServerSentEvent<String>>`。**

Spring MVC 从 5.0 起内置 `ReactiveTypeHandler`：当控制器方法的返回值是 Reactive Streams 类型、且 `produces` 是 `text/event-stream` 时，MVC 会自动用 `SseEmitter` 桥接，把流里每个元素写成一个 SSE 事件并立刻 flush 给客户端。

所以：
- **不要**加 `spring-boot-starter-webflux`。两个栈共存会让 Boot 的自动配置变得含混，而这里根本用不上 Netty。
- **需要** `reactor-core` 在 classpath（`Flux` 的来源）。它由 `spring-ai-alibaba-starter-dashscope` → `spring-ai-client-chat` 传递带入，Step 1 会核实。
- `ServerSentEvent` 类在 **`spring-web`** 的 `org.springframework.http.codec` 包里，`starter-web` 已包含，不需要额外依赖。

**已核实的 Spring AI 1.0.0 流式 API：**

| 要用的东西 | 正确写法 |
|---|---|
| 流式取文本 | `chatClient.prompt(prompt).stream().content()` → `Flux<String>`（**每个元素是一小段增量文本**，不是完整回复） |
| 挂记忆 Advisor | 构建期 `.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())` |
| 每次请求指定会话 | `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))` —— **漏传会抛 `IllegalArgumentException`，没有默认值** |
| 绕开模板解析 | 仍然用 `new Prompt(new SystemMessage(...), new UserMessage(...))`，理由同任务 16（脚本里的 `${x}` 会被 `{var}` 模板吃掉） |

**三个必须处理好的流式细节：**

1. **完整回复要聚合后才能入库。** 流里是分片，MySQL 与内存记忆存的是整段。用 `StringBuilder` 在 `doOnNext` 累积、`doOnComplete` 落库。
2. **出错不能让 SSE 直接断。** 前端 `ReadableStream` 读到网络中断只会看到流莫名其妙结束，用户完全不知道发生了什么。必须 `onErrorResume` 把异常翻成一条中文 `error` 事件再正常收尾。
3. **用户消息要立刻入库，助手消息只在成功时入库。** 用户的问题一进来就存（即使流中断，历史里也该有他问过什么）；助手回复若中途失败则不存半截内容，否则回显时会看到残缺的话。

**Files:**
- Modify: `script_back/pom.xml`（加 `reactor-test`，仅 test scope；`reactor-core` 若缺则一并显式加）
- Modify: `script_back/src/main/resources/application.yml`（加 `app.ai.chat-timeout-seconds`）
- Modify: `script_back/src/main/java/com/xd/rulescript/service/AiService.java`（加 `chatStream` 与提示词组装）
- Modify: `script_back/src/main/java/com/xd/rulescript/controller/ChatController.java`（加 `send`）
- Create: `script_back/src/test/java/com/xd/rulescript/service/AiChatPromptTest.java`
- Create: `script_back/src/test/java/com/xd/rulescript/service/AiServiceChatStreamTest.java`

**Interfaces:**
- Consumes: `ChatMemoryService` / `ChatService`（任务 17）、`ChatSendRequest`（任务 5：`{ruleId, message, scriptContent}`）、`ChatClient`（任务 16）
- Produces:
  ```java
  // AiService 新增
  static String AiService.CHAT_SYSTEM_PROMPT
  static String AiService.buildChatUserText(String userMessage, String scriptContent)  // 纯函数，可单测
  Flux<String> AiService.chatStream(Long conversationId, String userMessage, String scriptContent)
  boolean AiService.isAvailable()                                                      // 任务 16 已有

  // HTTP（SSE）
  POST /api/chat/send {ruleId, message, scriptContent}
       → Content-Type: text/event-stream
         event: message  data: <增量文本>      （多条）
         event: done     data:                 （正常结束，一条）
         event: error    data: <中文原因>      （失败时替代 done，一条）
  ```
- 任务 19 的前端按这三个 `event` 名分派，不靠解析 data 里的魔法字符串

- [ ] **Step 1: 核实依赖，补 `reactor-test`**

先确认 `Flux` 与 `ServerSentEvent` 的来源都在：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q dependency:tree -Dincludes=io.projectreactor:reactor-core,org.springframework:spring-web 2>&1 | tail -20
```

预期能看到 `io.projectreactor:reactor-core:jar:3.7.x:compile` 与 `org.springframework:spring-web:jar:6.2.x:compile`。

- **`reactor-core` 在** → 只需加 `reactor-test`
- **`reactor-core` 不在**（说明 spring-ai 的传递依赖被裁了）→ 两个都显式加

在 `pom.xml` 的 `<dependencies>` 里追加：

```xml
    <!-- 流式对话（SSE）用 Flux。reactor-core 通常由 spring-ai 传递带入，
         这里显式声明是为了不被上游依赖调整悄悄弄丢 -->
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-core</artifactId>
    </dependency>

    <!-- StepVerifier：断言 Flux 的元素序列与终止信号 -->
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-test</artifactId>
      <scope>test</scope>
    </dependency>
```

版本由 `spring-ai-bom` / Spring Boot 的依赖管理托管，**不要写 `<version>`**（写死会与 Boot 管理的版本冲突）。

```bash
mvn -q dependency:tree -Dincludes=io.projectreactor 2>&1 | tail -10
```

预期：`reactor-core` 是 `compile`、`reactor-test` 是 `test`。

- [ ] **Step 2: 给对话加独立超时配置**

`src/main/resources/application.yml` 的 `app.ai` 段（任务 2 建、任务 16 用过）追加一行：

```yaml
app:
  ai:
    enabled: ${AI_ENABLED:true}
    cr-timeout-seconds: 60
    chat-timeout-seconds: 120     # 对话是流式的，整体比单次 CR 长；超时后发 error 事件收尾
```

> 为什么对话超时比 CR 长：CR 只要一段审查意见，对话可能让模型输出完整脚本（几百 token），
> 流式虽然边生成边推，但整体耗时更长。120 秒是「模型确实卡死了」与「正常长回复」的分界。

- [ ] **Step 3: 写提示词组装的失败测试**

组装逻辑是纯函数，且直接决定对话质量（模型不知道沙箱限制就会给出必然被拦截的建议），值得单独测。

`src/test/java/com/xd/rulescript/service/AiChatPromptTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 对话上下文组装（需求 4.3.4：每次发消息自动把当前编辑器脚本作为上下文）。
 */
class AiChatPromptTest {

    @Test
    void 有脚本时同时带上脚本与问题() {
        String text = AiService.buildChatUserText("这个脚本有什么问题", "int age = ${age}\nreturn age");

        assertThat(text).contains("int age = ${age}");
        assertThat(text).contains("这个脚本有什么问题");
    }

    @Test
    void 脚本与问题有明确分隔标记() {
        // 模型必须能分清哪段是脚本、哪段是提问，否则会把问题当代码审查
        String text = AiService.buildChatUserText("帮我改一下", "return 1");

        assertThat(text).contains("【当前编辑器中的脚本】");
        assertThat(text).contains("【用户的问题】");
        assertThat(text.indexOf("【当前编辑器中的脚本】"))
                .isLessThan(text.indexOf("帮我改一下"));
    }

    @Test
    void 脚本被代码块包裹() {
        String text = AiService.buildChatUserText("看看", "return 1");
        assertThat(text).contains("```groovy");
    }

    @Test
    void 占位符原样保留() {
        // 最关键的一条：${age} 不能被吃掉或转义
        String text = AiService.buildChatUserText("q", "int a = ${age}\nString s = \"${level}\"");
        assertThat(text).contains("${age}").contains("${level}");
    }

    @Test
    void 脚本为空时明确告知而不是留空白() {
        String text = AiService.buildChatUserText("帮我写个规则", "");

        assertThat(text).contains("帮我写个规则");
        assertThat(text).contains("编辑器为空");
        // 空脚本时不该出现空的代码块，那会让模型以为用户给了个空文件
        assertThat(text).doesNotContain("```groovy\n```");
    }

    @Test
    void 脚本为null时按空处理不抛异常() {
        assertThat(AiService.buildChatUserText("问", null)).contains("编辑器为空");
    }

    @Test
    void 脚本只有空白时按空处理() {
        assertThat(AiService.buildChatUserText("问", "   \n  ")).contains("编辑器为空");
    }

    @Test
    void 用户问题为空时仍有脚本上下文() {
        String text = AiService.buildChatUserText("", "return 1");
        assertThat(text).contains("return 1");
    }

    @Test
    void 用户问题里的占位符不被当脚本解析() {
        String text = AiService.buildChatUserText("${x} 是什么意思", "return 1");
        assertThat(text).contains("${x} 是什么意思");
    }

    @Test
    void 脚本里已含代码块时不破坏结构() {
        // 用户可能在脚本注释里写了 ```，组装后不该产生歧义的嵌套
        String script = "// ```groovy\nreturn 1";
        String text = AiService.buildChatUserText("q", script);
        assertThat(text).contains("return 1");
    }

    @Test
    void 系统提示词交代了沙箱限制() {
        // 不交代的话模型会建议 import 或访问文件，而那些必然被任务 10 的沙箱拦截
        assertThat(AiService.CHAT_SYSTEM_PROMPT)
                .contains("Groovy")
                .contains("占位符")
                .contains("沙箱");
    }

    @Test
    void 系统提示词要求脚本放代码块且保留占位符() {
        assertThat(AiService.CHAT_SYSTEM_PROMPT)
                .contains("```groovy")
                .contains("${变量名}");
    }

    @Test
    void 系统提示词要求中文回答() {
        assertThat(AiService.CHAT_SYSTEM_PROMPT).contains("中文");
    }
}
```

- [ ] **Step 4: 给 `AiService` 加对话能力**

在 `AiService` 里追加以下内容（任务 16 的 `reviewScript` 等保持不动）。

新增字段与构造器参数：

```java
    private final ChatMemory chatMemory;
    private final int chatTimeoutSeconds;
```

构造器签名改为（在任务 16 的基础上追加两个参数）：

```java
    public AiService(ObjectProvider<ChatClient.Builder> builderProvider,
                     ChatMemory chatMemory,
                     @Value("${app.ai.enabled:true}") boolean enabled,
                     @Value("${spring.ai.dashscope.api-key:not-configured}") String apiKey,
                     @Value("${app.ai.cr-timeout-seconds:60}") int crTimeoutSeconds,
                     @Value("${app.ai.chat-timeout-seconds:120}") int chatTimeoutSeconds,
                     @Qualifier("crExecutor") ExecutorService crExecutor) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        // 记忆 Advisor 在构建期挂上（defaultAdvisors），会话 ID 在每次请求时传
        this.chatClient = builder != null
                ? builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build()
                : null;
        this.chatMemory = chatMemory;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.crTimeoutSeconds = crTimeoutSeconds;
        this.chatTimeoutSeconds = chatTimeoutSeconds;
        this.crExecutor = crExecutor;
        log.info("AiService 初始化：enabled={}，ChatClient={}，api-key={}",
                enabled, this.chatClient != null ? "已就绪" : "缺失", hasUsableKey() ? "已配置" : "未配置");
    }
```

> **`MessageChatMemoryAdvisor.builder(chatMemory).build()` 不是 `new MessageChatMemoryAdvisor(chatMemory)`。**
> 1.0.0 里这个类是 `final` 且没有公开构造器，只有静态 `builder`。写错会直接编译失败。

新增提示词常量：

```java
    /**
     * 对话系统提示词。
     *
     * 必须交代沙箱限制：否则模型会建议 import 类、读写文件、起线程，
     * 而那些在任务 10 的编译期黑名单下必然被拦截，用户照做只会撞墙。
     */
    public static final String CHAT_SYSTEM_PROMPT = """
            你是「脚本规则工作台」里的 Groovy 编程助手，帮业务人员编写规则脚本。

            【系统背景】
            - 脚本语言是 Groovy，运行前会做语法校验，通过后在沙箱里执行
            - 形如 ${变量名} 的是占位符，运行前会被用户填的真实值替换，这是本系统的正常设计，不是错误
            - 占位符类型只有五种：int / long / double / boolean / String，由变量声明语句推断
            - 沙箱限制：禁止 import 任何类、禁止包声明、禁止访问文件/网络/进程/环境变量/反射，
              禁止 System、Runtime、Thread、ProcessBuilder、File、Socket 等类型，单次执行最长 5 秒

            【你的任务】
            - 帮用户编写、修改、解释规则脚本
            - 主动指出逻辑漏洞、边界缺失与潜在空指针
            - 用户问到沙箱为什么拦截某段代码时，如实说明命中的是哪条限制

            【输出要求】
            - 全程用中文，简洁直接，不要客套话，不要复述用户的问题
            - 给出脚本时必须放进 ```groovy 代码块，且是完整可运行的脚本（不要只给片段）
            - 代码块里的占位符保持 ${变量名} 形式，不要替换成具体值
            - 绝不建议 import 类、访问文件/网络、起线程或调用 System/Runtime，那些会被沙箱拦截
            - 脚本里必须有 return 语句返回结果，否则运行结果会显示「（无返回值）」
            """;

    /**
     * 组装发给模型的用户消息：当前脚本 + 用户的问题（需求 4.3.4）。
     *
     * 用明确的分隔标记而不是自然语言描述，模型区分「哪段是代码」更稳。
     * 这段文本通过 new UserMessage(...) 传入，不经模板引擎，所以 ${} 与 ``` 都安全。
     */
    public static String buildChatUserText(String userMessage, String scriptContent) {
        String question = userMessage == null ? "" : userMessage.trim();
        String script = scriptContent == null ? "" : scriptContent.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("【当前编辑器中的脚本】\n");
        if (script.isEmpty()) {
            sb.append("（编辑器为空，用户还没开始写脚本）\n");
        } else {
            sb.append("```groovy\n").append(script).append("\n```\n");
        }
        sb.append("\n【用户的问题】\n");
        sb.append(question.isEmpty() ? "（用户没有额外说明，请针对上面的脚本给出建议）" : question);
        return sb.toString();
    }
```

新增流式对话方法：

```java
    /**
     * 流式对话（需求 4.3.4）。
     *
     * 返回的 Flux 每个元素是一小段增量文本，控制器直接把它包成 SSE 事件推给前端。
     * 本方法**不抛异常也不发 error 信号**：AI 不可用时返回单条降级文本，
     * 调用出错时返回一条中文说明 —— 让控制器能始终以正常收尾结束响应。
     *
     * 注意：这里**不负责持久化**。写库由 ChatService 在控制器层编排
     * （用户消息立刻写，助手消息在流完成后写），职责分开便于测试。
     */
    public Flux<String> chatStream(Long conversationId, String userMessage, String scriptContent) {
        if (!isAvailable()) {
            return Flux.just("AI 对话未启用：尚未配置大模型 API Key。"
                    + "脚本的语法校验与沙箱运行不受影响，可以先用那两个功能。");
        }

        String key = conversationId == null ? null : "conv-" + conversationId;
        if (key == null) {
            return Flux.just("对话会话不存在，请回列表页重新进入这条规则。");
        }

        String userText = buildChatUserText(userMessage, scriptContent);
        Prompt prompt = new Prompt(new SystemMessage(CHAT_SYSTEM_PROMPT), new UserMessage(userText));

        return chatClient.prompt(prompt)
                // 漏传 CONVERSATION_ID 会抛 IllegalArgumentException（没有默认值），必须显式传
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))
                .stream()
                .content()
                .timeout(Duration.ofSeconds(chatTimeoutSeconds))
                .onErrorResume(e -> {
                    // 原始异常只进日志，绝不推给前端（Global Constraints：严禁堆栈与英文异常类名）
                    log.warn("AI 对话失败：{}", rootMessage(e));
                    // reactor 的 timeout(Duration) 抛的就是 java.util.concurrent.TimeoutException
                    String hint = e instanceof TimeoutException
                            ? "AI 回复超时（超过 " + chatTimeoutSeconds + " 秒），请重试或把问题拆小一点。"
                            : "AI 对话暂时不可用，请稍后重试。脚本的语法校验与运行不受影响。";
                    return Flux.just(hint);
                });
    }
```

新增 import：

```java
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
```

> **`chatMemory` 字段在本任务只用于构造 Advisor**，不直接读写 —— 记忆由 Advisor 自动维护。
> 任务 17 的 `ChatMemoryService` 才是显式操作记忆的地方（回灌、清空）。两者操作的是同一个 bean，不冲突。
>
> `reactor` 的 `timeout(Duration)` 抛的是 `java.util.concurrent.TimeoutException`（不是 reactor 自有的异常类型），
> 所以 import 那一个就够，别去引 `reactor.core.Exceptions` 之类的。
> 超时后 reactor 会自动取消上游订阅，不需要手动 cancel。

- [ ] **Step 5: 给 `ChatController` 加 `send`**

在任务 17 的 `ChatController` 里追加。注入的服务要加 `AiService` 与 `ChatMemoryService`：

```java
    private final ChatService chatService;
    private final AiService aiService;
    private final ChatMemoryService chatMemoryService;

    public ChatController(ChatService chatService,
                          AiService aiService,
                          ChatMemoryService chatMemoryService) {
        this.chatService = chatService;
        this.aiService = aiService;
        this.chatMemoryService = chatMemoryService;
    }
```

新增端点：

```java
    /**
     * 流式对话（需求 4.3.4）。
     *
     * Servlet 栈返回 Flux 是可行的：Spring MVC 的 ReactiveTypeHandler 检测到
     * 「Reactive Streams 返回值 + text/event-stream」会自动用 SseEmitter 桥接并逐段 flush。
     * 所以本项目不需要引入 webflux starter。
     *
     * 事件约定（任务 19 的前端按 event 名分派）：
     *   message → 增量文本，多条
     *   done    → 正常结束，一条，data 为空
     *   error   → 失败收尾，一条，data 是中文原因（出现 error 就不再发 done）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> send(@Valid @RequestBody ChatSendRequest request) {
        Long conversationId = chatService.conversationIdOf(request.ruleId());

        // 服务重启后内存记忆是空的，先把 MySQL 里的历史灌回去，多轮上下文才接得上
        chatMemoryService.ensureLoaded(conversationId);

        // 用户消息立刻入库：即使流中途中断，历史里也该有他问过什么
        chatService.appendUser(request.ruleId(), request.message());

        StringBuilder aggregated = new StringBuilder();

        Flux<ServerSentEvent<String>> body = aiService
                .chatStream(conversationId, request.message(), request.scriptContent())
                .doOnNext(aggregated::append)
                .map(chunk -> ServerSentEvent.<String>builder().event("message").data(chunk).build());

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            // 用 defer 保证这段在流真正结束时才执行，而不是在组装 Flux 时就执行
            String full = aggregated.toString();
            if (!full.isBlank()) {
                // 只在拿到完整回复时入库；半截内容存进去会让历史回显出现残句
                chatService.appendAssistant(request.ruleId(), full);
            }
            return Flux.just(ServerSentEvent.<String>builder().event("done").data("").build());
        });

        return body.concatWith(done)
                .onErrorResume(e -> {
                    // AiService 内部已经兜了大多数错误，这里是最后一道防线（如入库失败）
                    log.warn("对话响应流异常：{}", e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("对话处理出现异常，请重试。你的提问已保存，刷新页面可以看到。")
                            .build());
                });
    }
```

新增字段与 import：

```java
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
```

```java
import com.xd.rulescript.dto.ChatSendRequest;
import com.xd.rulescript.service.AiService;
import com.xd.rulescript.service.ChatMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
```

> **`concatWith(done)` 的顺序很重要。** 用 `startWith` 或把 done 放进 `map` 里都会导致结束事件早于内容到达，前端会在收到正文前就认为对话结束了。
>
> **`aggregated` 用普通 `StringBuilder` 而非线程安全类**：SSE 的 `doOnNext` 在同一个订阅序列上是串行的，不存在并发追加。若将来改成多路合并（`merge`），这里必须换成 `StringBuffer`。

- [ ] **Step 6: 写流式降级测试**

**不真调大模型**（Global Constraints 第 4 条）。用 `app.ai.enabled=false` 让 `chatStream` 走降级分支，验证流的形态。

`src/test/java/com/xd/rulescript/service/AiServiceChatStreamTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

/**
 * 流式对话在 AI 不可用时的降级行为（非功能要求 #1）。
 * 用 StepVerifier 断言元素序列与终止信号 —— SSE 最怕的是「流不结束」或「以 error 信号结束」，
 * 这两种情况前端都会表现为一直转圈。
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "spring.ai.dashscope.api-key=not-configured",
})
class AiServiceChatStreamTest {

    @Autowired
    private AiService aiService;

    @Test
    void 不可用时返回单条降级文本并正常结束() {
        StepVerifier.create(aiService.chatStream(1L, "帮我写个规则", "return 1"))
                .assertNext(text -> {
                    assertThat(text).contains("AI 对话未启用");
                    assertThat(text).contains("语法校验");       // 必须告知哪些功能不受影响
                })
                .verifyComplete();                                // 关键：正常 complete，不是 error
    }

    @Test
    void 降级文本不含英文异常词与堆栈() {
        StepVerifier.create(aiService.chatStream(1L, "问", null))
                .assertNext(text -> assertThat(text)
                        .doesNotContain("Exception")
                        .doesNotContain("at com.")
                        .doesNotContain("null"))
                .verifyComplete();
    }

    @Test
    void conversationId为null时给中文提示而不是抛异常() {
        StepVerifier.create(aiService.chatStream(null, "问", "return 1"))
                .assertNext(text -> assertThat(text).contains("会话不存在"))
                .verifyComplete();
    }

    @Test
    void 降级流只有一个元素不会重复推送() {
        StepVerifier.create(aiService.chatStream(1L, "问", "return 1"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void 降级文案是可读中文() {
        String text = aiService.chatStream(1L, "问", "return 1").blockFirst();
        assertThat(text).isNotBlank();
        assertThat(text).contains("API Key");
    }
}
```

- [ ] **Step 7: 跑全量后端测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，**218 个测试通过**（任务 17 后的 200 + `AiChatPromptTest` 13 + `AiServiceChatStreamTest` 5 = 218）。

常见失败：
- `NoSuchMethodError: MessageChatMemoryAdvisor.<init>` → 写成了 `new MessageChatMemoryAdvisor(...)`，1.0.0 只有静态 `builder`
- `ClassNotFoundException: reactor.test.StepVerifier` → Step 1 的 `reactor-test` 没加，或忘了 `<scope>test</scope>` 导致打包冲突
- `IllegalArgumentException` 提到 conversation → `chatStream` 里漏了 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))`
- 构造器参数不匹配 → Step 4 改了 `AiService` 构造器签名，任务 16 的测试若手工 new 过 `AiService` 需同步改（本计划里都是 Spring 注入，通常不用动）

- [ ] **Step 8: 手工验收 —— 真实流式调用**

这一步会**真的消耗百炼额度**。启动：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
echo "key=${AI_DASHSCOPE_API_KEY:0:8}…  enabled=$AI_ENABLED"
mvn -q spring-boot:run
```

建一条规则拿 `ruleId`：

```bash
curl -s -X POST http://localhost:8080/api/rule/create \
  -H 'Content-Type: application/json' \
  -d '{"name":"SSE联调规则","description":"任务18验收用"}' | python3 -m json.tool
```

**① 最关键的一条：`-N` 看到逐段推送（验流式真的生效）**

```bash
curl -N -s -X POST http://localhost:8080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":<RID>,"message":"帮我写一个判断年龄是否成年的规则","scriptContent":""}'
```

`-N` 关闭 curl 的缓冲，否则看不到流式效果。

预期终端上**逐段刷出**（不是一次性出现）：

```
event:message
data:好的

event:message
data:，下面

event:message
data:是一个判断

...

event:done
data:
```

要点核对：
- 有多个 `event:message`（证明是分片流式，不是攒完一次性返回）
- 每个 `data:` 后面是中文片段，拼起来是一段通顺的回答
- 回答里包含 ```` ```groovy ```` 代码块，块内是完整脚本
- 最后是 `event:done`
- Content-Type 是 `text/event-stream`（加 `-i` 可看响应头）

**② 带着脚本上下文提问（验需求 4.3.4 的自动带上下文）**

```bash
curl -N -s -X POST http://localhost:8080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":<RID>,"message":"这个脚本有什么风险","scriptContent":"String name = ${userName}\nreturn name.toUpperCase()"}'
```

预期：模型**针对这段脚本**指出空指针风险（`name` 可能为 null）。若回答是泛泛而谈「请提供你的脚本」，说明 `buildChatUserText` 没把脚本带进去，回 Step 4 检查。

同时验证 `${userName}` 没有被模板引擎吃掉 —— 回答里提到该占位符时应是完整的 `${userName}`。

**③ 多轮上下文接得上（验 ChatMemory + Advisor）**

连着发两条，第二条用代词指代第一条的内容：

```bash
curl -N -s -X POST http://localhost:8080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":<RID>,"message":"我刚才让你写的那个规则，把成年年龄改成 20","scriptContent":""}'
```

预期：模型知道你刚才要的是「判断年龄是否成年」的规则，直接给出改成 20 的版本。
若它反问「你没有告诉我是什么规则」→ 记忆没生效，检查 `CONVERSATION_ID` 是否传了、`defaultAdvisors` 是否挂上。

**④ 用 MySQL MCP 核对消息真的落库**

先查 conversation.id：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id FROM conversation WHERE rule_id = ?", "params": [<RID>]})
```

再查消息：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, role, CHAR_LENGTH(content) AS len, LEFT(content, 50) AS head, created_at FROM message WHERE conversation_id = ? ORDER BY created_at ASC, id ASC", "params": [<CID>]})
```

预期：
- 前面 ①②③ 三次调用共产生 **6 条**记录，`role` 严格 user/assistant 交替
- 每条 user 的 `len` > 0，每条 assistant 的 `len` 明显更大（完整回复）
- assistant 的 `head` 与终端上看到的流式内容开头一致 —— **这证明聚合逻辑对了**，存的是完整回复而不是第一个分片
- `created_at` 时间递增且与实际时间一致（差 8 小时则是时区问题，回任务 1 查 `DB_URL`）

若 assistant 的 `len` 很小（比如只有几个字）→ `doOnNext(aggregated::append)` 没生效或 `defer` 用错，回 Step 5 检查。

**⑤ history 接口能回显刚才的对话**

```bash
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}' | python3 -m json.tool
```

预期：6 条，与 ④ 查库结果一致，`content` 完整、`createdAt` 有值。

**⑥ 重启后端，验证历史不丢且上下文能续（需求非功能要求 #5）**

`Ctrl+C` 停掉后端，重新启动，然后：

```bash
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}' | python3 -m json.tool
```

预期：仍然是 6 条（内存记忆重启即丢，但 MySQL 里有，history 读的是 MySQL）。

再发一条依赖上文的消息：

```bash
curl -N -s -X POST http://localhost:8080/api/chat/send \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":<RID>,"message":"我们之前聊的那个规则，再帮我加个日志输出","scriptContent":""}'
```

预期：模型仍知道之前聊的是年龄规则 —— **这证明 `ensureLoaded` 的回灌生效了**。
后端日志里应能看到 `会话 <CID> 从 MySQL 回灌 6 条历史消息`。

若模型表示不知道上下文 → 回灌没跑，检查 Step 5 里 `ensureLoaded` 是否在 `appendUser` **之前**调用（顺序反了会把新消息也算进「内存非空」而跳过回灌）。

**⑦ 错误 Key 时前端体验不能崩**

停掉后端，用假 Key 重启：

```bash
AI_DASHSCOPE_API_KEY=sk-invalid-on-purpose mvn -q spring-boot:run
```

再发 ① 的请求。预期：**HTTP 200**，流里有内容（降级文案「AI 对话未启用…」或「AI 对话暂时不可用…」），最后正常 `event:done`。
绝不能是 HTTP 500 或直接断流 —— 前端遇到断流只会一直转圈，用户完全不知道发生了什么。

验完 `Ctrl+C`，用真 Key 重启。

**⑧ 验收完清理测试数据**

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "DELETE FROM rule WHERE id = ?", "params": [<RID>]})
```

然后确认级联删除生效（任务 5 的 `RuleService.delete` 负责级联）：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT (SELECT COUNT(*) FROM conversation WHERE rule_id = ?) AS convs, (SELECT COUNT(*) FROM message WHERE conversation_id = ?) AS msgs", "params": [<RID>, <CID>]})
```

预期：`convs = 0` 且 `msgs = 0`。若 `msgs` 还有残留，说明删规则时没级联删消息，回任务 5 检查 `RuleService.delete`。

> 注意：上面这个 DELETE 是直接操作库的，绕过了 `RuleService.delete` 的级联逻辑。
> 想验级联本身，应该调 `curl -X POST /api/rule/delete` 再查库。两种方式都试一下更稳。

- [ ] **Step 9: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back
git commit -m "feat: SSE 流式 AI 对话（多轮记忆/重启回灌/聚合入库/错误不崩流）"
```

> **执行偏差记录（实际提交 `d2e9cb3`，6 文件 +372 -3）**
> 1. **记忆 Advisor 改为按请求挂载，不用 `defaultAdvisors`**：计划 Step 4 把 `MessageChatMemoryAdvisor` 用 `defaultAdvisors` 挂在共享 `chatClient` 上，并称「漏传 CONVERSATION_ID 会抛 IllegalArgumentException，没有默认值」（10419 行）。实测 Spring AI 1.0.0 的 `BaseChatMemoryAdvisor.getConversationId(Map, defaultId)` 缺该 param 时**回退到默认会话 `"default"` 而非抛异常**（字节码已核实），计划说法不准确。若挂共享 client，`reviewScript` 每次 CR（不传 CONVERSATION_ID）都会把脚本与审查意见累积进 `"default"` 记忆，涨 token 且破坏任务 16 已验证的无状态 CR。故 `chatClient` 保持 `builder.build()`，Advisor 单独构建为字段、只在 `chatStream` 里 `.advisors(chatMemoryAdvisor)` 按请求挂。Step 8 ③ 多轮记忆验证通过，CR 路径不受影响。
> 2. **`chatStream` 守卫顺序调整为「先判会话、再判可用性」**：计划把 `!isAvailable()` 放最前，会导致 `conversationId==null` 且 AI 未启用时返回「AI 对话未启用」而非「会话不存在」，与 Step 6 测试 `conversationId为null时给中文提示`（断言含「会话不存在」，且用 `app.ai.enabled=false`）**直接冲突——按计划顺序该测试必红**。改为先判 `key==null` 再判 `!isAvailable()`。生产链路里 `conversationId` 恒非空（控制器用 `conversationIdOf` 定位，找不到即抛业务异常），故不影响真实行为，仅让防御分支与测试意图一致。
> 3. **TDD 顺序调整**：计划 Step 6（`AiServiceChatStreamTest`）排在 Step 4（`chatStream` 实现）之后，无法满足 dev-conventions「写完失败测试必须先单独跑一次亲眼看到它红」的硬约定。把 Step 3（`AiChatPromptTest`）与 Step 6（`AiServiceChatStreamTest`）都提前到实现前写、一起跑红（编译失败：`AiService` 缺 `buildChatUserText`/`CHAT_SYSTEM_PROMPT`/`chatStream`），再实现，再一起跑绿（13 + 5 全过）。
> 4. **全量测试 205 通过，非计划预估的 218**：新增 13（AiChatPromptTest）+ 5（AiServiceChatStreamTest）全绿、旧 187 无一破坏；差异同任务 16/17，源于计划对基线估算偏高 13。
> 5. **reactor-core 显式声明**：Step 1 核实 reactor-core 已由 spring-ai 传递带入（3.7.5），仍按计划显式声明 reactor-core（compile）+ reactor-test（test），`dependency:tree` 确认二者 scope 正确，防上游依赖调整悄悄弄丢。
> 6. **Step 8 手工验收全部通过（无需浏览器，真实消耗额度）**：①`-N` 逐段推送（9 个 message 事件 + done、`text/event-stream`、`${age}` 跨分片保留、重组出完整 groovy 块）②带脚本上下文（针对 toUpperCase 脚本指出 NPE、`${userName}` 保留）③多轮记忆（隔一轮仍记得年龄规则、把 18 改成 20）④聚合入库（6 条 user/assistant 交替，assistant `len` 65/687/65 是完整回复而非首分片、head 与流式内容一致、时区正确）⑤history 回显 6 条与库一致⑥重启回灌（日志「会话 215 从 MySQL 回灌 6 条历史消息」，模型仍记得 `>=20` 规则且遵守沙箱不建议 println）⑦假 key → HTTP 200 + 降级 message + done（非 500、非断流），后端 WARN 记 401 根因、前端无堆栈泄漏⑧API 级联删除 → rule/conv/msgs 全 0、无孤儿、库回到 rules=3/msgs=0。

---

## Task 19: ChatPanel 前端 —— 流式渲染与代码块应用

把任务 18 的 SSE 接口接到右侧面板，落实需求 4.3.4：流式打字机效果、自动带脚本上下文、代码块一键应用、清空对话、历史回显。

**为什么必须手写 SSE 解析器：** `EventSource` 只支持 GET，而本项目所有接口一律 POST（Global Constraints）。所以只能用 `fetch` + `ReadableStream` 自己按 SSE 规范解析。这件事有两个真实的坑，本任务把它们各自收进一个纯函数并单测：

1. **chunk 边界会切在事件中间。** 网络分片不按语义边界走，一个 `event:message\ndata:…` 完全可能被切成两个 chunk。必须用 buffer 累积、按空行切出**完整**事件，残料留到下次。直接对每个 chunk 做 `split('\n\n')` 会丢字。
2. **流式中途代码块是未闭合的。** 打字机效果下，```` ```groovy ```` 已经出现但结尾的 ```` ``` ```` 还没到。若按「必须闭合才算代码块」处理，这段文本会先当普通文字渲染、下一帧又变成代码块，用户看到整块内容反复重排闪烁。未闭合时要**直接当成代码块**渲染。

**Files:**
- Create: `script_front/src/api/sseParser.js`（纯函数，单测对象）
- Create: `script_front/src/api/__tests__/sseParser.spec.js`
- Create: `script_front/src/api/messageBlocks.js`（纯函数，单测对象）
- Create: `script_front/src/api/__tests__/messageBlocks.spec.js`
- Create: `script_front/src/api/chat.js`
- Create: `script_front/src/components/ChatPanel.vue`
- Modify: `script_front/src/views/RuleWorkbenchView.vue`（换掉 right-slot 的占位）

**Interfaces:**
- Consumes: `POST /api/chat/send|history|clear`（任务 18）、`post` / `reportError`（任务 3）、`useApplyScript`（任务 15）、`theme.css` 变量
- Produces:
  ```js
  // sseParser.js —— 纯函数，可单测
  createSseParser() -> { push(chunk) -> Array<{event:string, data:string}> }

  // messageBlocks.js —— 纯函数，可单测
  splitBlocks(text) -> Array<{type:'text'|'code', lang:string, content:string}>

  // api/chat.js
  sendChatStream({ruleId, message, scriptContent}, {onMessage, onDone, onError}) -> {abort()}
  getChatHistory(ruleId) -> Promise<Array<{role, content, createdAt}>>
  clearChat(ruleId) -> Promise<void>

  // ChatPanel.vue
  props: ruleId(Number), getScript(Function)
  emits: apply-script(String)
  ```

- [ ] **Step 1: 写 SSE 解析器的失败测试**

`src/api/__tests__/sseParser.spec.js`

```js
import { describe, it, expect } from 'vitest'
import { createSseParser } from '../sseParser'

/** 一次性喂入完整文本，返回解析出的事件 */
function parseAll(text) {
  const p = createSseParser()
  return p.push(text)
}

describe('完整事件的解析', () => {
  it('解析单个事件', () => {
    expect(parseAll('event:message\ndata:好的\n\n')).toEqual([{ event: 'message', data: '好的' }])
  })

  it('一个 chunk 里的多个事件全部解析出来，顺序保持', () => {
    const events = parseAll('event:message\ndata:一\n\nevent:message\ndata:二\n\nevent:done\ndata:\n\n')
    expect(events).toEqual([
      { event: 'message', data: '一' },
      { event: 'message', data: '二' },
      { event: 'done', data: '' },
    ])
  })

  it('event 字段缺失时按 SSE 规范默认为 message', () => {
    expect(parseAll('data:无事件名\n\n')).toEqual([{ event: 'message', data: '无事件名' }])
  })

  it('data 为空串时保留（done 事件就是这样）', () => {
    expect(parseAll('event:done\ndata:\n\n')).toEqual([{ event: 'done', data: '' }])
  })

  it('多个 data 行按规范用换行拼接', () => {
    expect(parseAll('data:第一行\ndata:第二行\n\n')).toEqual([{ event: 'message', data: '第一行\n第二行' }])
  })

  it('冒号后的一个前导空格被去掉，多余的保留', () => {
    expect(parseAll('data: 带一个空格\n\n')[0].data).toBe('带一个空格')
    expect(parseAll('data:不带空格\n\n')[0].data).toBe('不带空格')
    expect(parseAll('data:  两个空格留一个\n\n')[0].data).toBe(' 两个空格留一个')
  })

  it('以冒号开头的注释行被忽略', () => {
    expect(parseAll(':这是注释\ndata:正文\n\n')).toEqual([{ event: 'message', data: '正文' }])
  })

  it('未知字段被忽略不报错', () => {
    expect(parseAll('id:42\nretry:3000\ndata:正文\n\n')).toEqual([{ event: 'message', data: '正文' }])
  })

  it('CRLF 换行也能解析', () => {
    expect(parseAll('event:message\r\ndata:好的\r\n\r\n')).toEqual([{ event: 'message', data: '好的' }])
  })
})

describe('chunk 边界（最容易写错的地方）', () => {
  it('事件被切成两半时不丢字', () => {
    const p = createSseParser()
    expect(p.push('event:message\nda')).toEqual([])          // 还不完整，先不吐
    expect(p.push('ta:好的\n\n')).toEqual([{ event: 'message', data: '好的' }])
  })

  it('切在分隔符中间也能正确处理', () => {
    const p = createSseParser()
    expect(p.push('data:一\n')).toEqual([])
    expect(p.push('\ndata:二\n\n')).toEqual([
      { event: 'message', data: '一' },
      { event: 'message', data: '二' },
    ])
  })

  it('逐字符喂入最终结果与一次性喂入一致', () => {
    const text = 'event:message\ndata:你好\n\nevent:done\ndata:\n\n'
    const p = createSseParser()
    const collected = []
    for (const ch of text) collected.push(...p.push(ch))
    expect(collected).toEqual(parseAll(text))
  })

  it('一个 chunk 里既有完整事件又有残料', () => {
    const p = createSseParser()
    const events = p.push('data:完整\n\ndata:不完整')
    expect(events).toEqual([{ event: 'message', data: '完整' }])
    expect(p.push('\n\n')).toEqual([{ event: 'message', data: '不完整' }])
  })

  it('空 chunk 不产生事件也不破坏 buffer', () => {
    const p = createSseParser()
    p.push('data:一半')
    expect(p.push('')).toEqual([])
    expect(p.push('\n\n')).toEqual([{ event: 'message', data: '一半' }])
  })
})

describe('真实内容不被破坏', () => {
  it('中文、emoji、引号原样保留', () => {
    const text = '好的，「脚本」没问题 ✅ "引号"'
    expect(parseAll(`data:${text}\n\n`)[0].data).toBe(text)
  })

  it('脚本片段里的 ${} 与反引号原样保留', () => {
    const text = 'int age = ${age}\n```groovy\nreturn age\n```'
    // 脚本里的换行在 SSE 里必须被拆成多个 data 行，解析后要还原
    const sse = `data:int age = \${age}\ndata:\`\`\`groovy\ndata:return age\ndata:\`\`\`\n\n`
    expect(parseAll(sse)[0].data).toBe(text)
  })

  it('data 内容里含冒号不会被截断', () => {
    expect(parseAll('data:key:value:更多\n\n')[0].data).toBe('key:value:更多')
  })

  it('没有 event 字段的 done 也能被识别（前端靠 event 名分派）', () => {
    const events = parseAll('event:error\ndata:AI 对话暂时不可用\n\n')
    expect(events[0].event).toBe('error')
    expect(events[0].data).toContain('不可用')
  })
})
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：`sseParser` 找不到 → 这 18 条失败。

- [ ] **Step 2: 实现 SSE 解析器**

`src/api/sseParser.js`

```js
/**
 * 手写 SSE 解析器。
 *
 * 为什么不用 EventSource：它只支持 GET，本项目所有接口一律 POST（Global Constraints）。
 * 所以只能 fetch + ReadableStream 自己按 SSE 规范解析。
 *
 * 核心是 buffer：网络分片不按语义边界走，一个事件可能被切成多个 chunk，
 * 必须累积到出现空行（事件结束标志）才能吐出，残料留到下一次 push。
 */

/** 事件结束标志。CRLF 也要认，所以两种都作为分隔依据 */
const BOUNDARY = /\r?\n\r?\n/

/**
 * @returns {{push: (chunk: string) => Array<{event: string, data: string}>}}
 */
export function createSseParser() {
  let buffer = ''

  function push(chunk) {
    if (!chunk) return []
    buffer += chunk

    const events = []
    let match
    // 循环切出所有已完整的事件；lastIndex 由 replace 手动推进
    while ((match = BOUNDARY.exec(buffer)) !== null) {
      const rawEvent = buffer.slice(0, match.index)
      buffer = buffer.slice(match.index + match[0].length)
      const parsed = parseEvent(rawEvent)
      if (parsed) events.push(parsed)
      BOUNDARY.lastIndex = 0        // 全局正则不必用，但显式归零避免 lastIndex 残留
    }
    return events
  }

  return { push }
}

/**
 * 解析一个完整事件的文本块。
 * 全是注释或没有 data 字段时返回 null（不该往 UI 推空事件）。
 */
function parseEvent(rawEvent) {
  let event = 'message'          // SSE 规范：event 字段缺省时事件类型为 message
  const dataLines = []
  let hasData = false

  for (const line of rawEvent.split(/\r?\n/)) {
    if (line === '') continue
    if (line.startsWith(':')) continue        // 注释行（常用于心跳保活）

    const colon = line.indexOf(':')
    if (colon < 0) continue                   // 没有冒号的行按规范应视为 field=整行、value=空，实践中是脏数据，跳过

    const field = line.slice(0, colon)
    // 规范：冒号后若有空格，只去掉**一个**
    let value = line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      hasData = true
      dataLines.push(value)
    }
    // id / retry 等字段本项目用不到，忽略
  }

  if (!hasData) return null
  return { event, data: dataLines.join('\n') }
}
```

```bash
npm run test:unit
```

预期：新增 18 条全绿，累计 72 条（任务 3 的 6 + 任务 14 的 37 + 任务 15 的 11 + 本任务 18）。

> 81 是 Step 2 阶段的中间值：`messageBlocks` 的 11 条测试在 Step 3、4，那边跑完累计应为 92 条。

- [ ] **Step 3: 写消息分块的失败测试**

`src/api/__tests__/messageBlocks.spec.js`

```js
import { describe, it, expect } from 'vitest'
import { splitBlocks } from '../messageBlocks'

describe('splitBlocks：把回复切成文本段与代码块', () => {
  it('纯文本返回一个 text 块', () => {
    expect(splitBlocks('这段脚本没问题')).toEqual([{ type: 'text', lang: '', content: '这段脚本没问题' }])
  })

  it('含一个代码块时切成 text/code/text 三段', () => {
    const blocks = splitBlocks('建议改成：\n```groovy\nreturn 1\n```\n以上。')
    expect(blocks).toHaveLength(3)
    expect(blocks[0]).toMatchObject({ type: 'text' })
    expect(blocks[0].content).toContain('建议改成')
    expect(blocks[1]).toMatchObject({ type: 'code', lang: 'groovy', content: 'return 1' })
    expect(blocks[2].content).toContain('以上')
  })

  it('多个代码块全部识别', () => {
    const blocks = splitBlocks('```groovy\nA\n```\n中间\n```groovy\nB\n```')
    expect(blocks.filter((b) => b.type === 'code')).toHaveLength(2)
    expect(blocks.filter((b) => b.type === 'code')[1].content).toBe('B')
  })

  it('未闭合的代码块也当成 code（流式打字机的关键）', () => {
    // 模型正在输出，结尾的 ``` 还没到；此时若当普通文本渲染，
    // 下一帧变成代码块会导致整块内容重排闪烁
    const blocks = splitBlocks('脚本如下：\n```groovy\nint a = 1\nreturn a')
    expect(blocks).toHaveLength(2)
    expect(blocks[1]).toMatchObject({ type: 'code', lang: 'groovy' })
    expect(blocks[1].content).toBe('int a = 1\nreturn a')
  })

  it('无语言标记的代码块 lang 为空串', () => {
    expect(splitBlocks('```\nreturn 1\n```')[0]).toMatchObject({ type: 'code', lang: '' })
  })

  it('代码块内的占位符原样保留', () => {
    const blocks = splitBlocks('```groovy\nint age = ${age}\n```')
    expect(blocks[0].content).toContain('${age}')
  })

  it('空文本返回空数组（流刚开始时不该渲染空气泡）', () => {
    expect(splitBlocks('')).toEqual([])
    expect(splitBlocks(null)).toEqual([])
  })

  it('只有空白时返回空数组', () => {
    expect(splitBlocks('   \n  ')).toEqual([])
  })

  it('代码块内容为空时仍保留该块（模型刚打出 ``` 的瞬间）', () => {
    const blocks = splitBlocks('```groovy\n')
    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toMatchObject({ type: 'code', lang: 'groovy', content: '' })
  })

  it('文本段首尾空白被 trim 但代码块内容不动', () => {
    const blocks = splitBlocks('  说明  \n```groovy\n  return 1  \n```')
    expect(blocks[0].content).toBe('说明')
    // 代码里的缩进是有意义的，不能 trim 掉行首空格
    expect(blocks[1].content).toContain('  return 1  ')
  })

  it('行内单反引号不被当成代码块', () => {
    const blocks = splitBlocks('建议把 `level` 改名')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].type).toBe('text')
  })
})
```

- [ ] **Step 4: 实现分块**

`src/api/messageBlocks.js`

```js
/**
 * 把大模型回复切成「文本段」与「代码块」，供 ChatPanel 分别渲染。
 *
 * 关键取舍：**未闭合的代码块也算代码块**。
 * 流式输出时结尾的 ``` 还没到达，若按「必须闭合」判定，这段内容会先以普通文本渲染、
 * 下一帧再变成代码块，用户看到整块文字反复重排闪烁。当成代码块渲染则始终稳定。
 */

const FENCE_OPEN = /^[ \t]*(`{3,})[ \t]*([A-Za-z]*)[ \t]*$/

/**
 * @param {string|null} text
 * @returns {Array<{type:'text'|'code', lang:string, content:string}>}
 */
export function splitBlocks(text) {
  if (!text || !text.trim()) return []

  const lines = text.split('\n')
  const blocks = []
  let textBuf = []
  let codeBuf = null          // {lang, lines:[]}

  const flushText = () => {
    const content = textBuf.join('\n').trim()
    if (content) blocks.push({ type: 'text', lang: '', content })
    textBuf = []
  }
  const flushCode = () => {
    if (!codeBuf) return
    // 代码内容只去首尾空行，行内缩进原样保留（缩进对 Groovy 的可读性有意义）
    blocks.push({ type: 'code', lang: codeBuf.lang, content: codeBuf.lines.join('\n').replace(/^\n+|\n+$/g, '') })
    codeBuf = null
  }

  for (const line of lines) {
    const fence = FENCE_OPEN.exec(line)

    if (codeBuf) {
      // 已在代码块内：遇到同级别的闭合栅栏就结束，否则原样收集
      if (fence) {
        flushCode()
      } else {
        codeBuf.lines.push(line)
      }
      continue
    }

    if (fence) {
      flushText()
      codeBuf = { lang: fence[2] || '', lines: [] }
    } else {
      textBuf.push(line)
    }
  }

  // 走到这里 codeBuf 仍非空，说明代码块没闭合 —— 正是流式中途的常态
  flushText()
  flushCode()
  return blocks
}
```

```bash
npm run test:unit
```

预期：新增 11 条全绿，累计 **92 条**（6 + 9 + 37 + 11 + 18 + 11）。

- [ ] **Step 5: 写 `api/chat.js`**

`src/api/chat.js`

```js
/**
 * AI 对话接口。
 *
 * send 是 SSE 流式，不能用 api/http.js 里的 post（那个会 await 整个响应体，
 * 流式效果就没了），必须自己读 ReadableStream。
 * history / clear 是普通 JSON 接口，复用 post。
 */
import { post } from './http'
import { createSseParser } from './sseParser'

/** 历史消息回显（需求 4.3.4：打开页面时加载） */
export function getChatHistory(ruleId) {
  return post('/api/chat/history', { ruleId })
}

/** 清空该会话的记忆与历史消息 */
export function clearChat(ruleId) {
  return post('/api/chat/clear', { ruleId })
}

/**
 * 发起流式对话。
 *
 * @param {{ruleId:number, message:string, scriptContent:string}} payload
 *        scriptContent 是编辑器当前内容（含未保存修改），需求 4.3.4 要求每次自动带上
 * @param {{onMessage?:(t:string)=>void, onDone?:()=>void, onError?:(msg:string)=>void}} handlers
 * @returns {{abort: () => void}} 调 abort 可中断（切换规则、离开页面时用）
 */
export function sendChatStream(payload, handlers = {}) {
  const controller = new AbortController()

  // 用 IIFE 跑异步流程，把 abort 句柄同步返回给调用方
  ;(async () => {
    let response
    try {
      response = await fetch('/api/chat/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(payload),
        signal: controller.signal,
      })
    } catch (e) {
      if (e?.name === 'AbortError') return          // 主动中断，不算错误
      handlers.onError?.('无法连接后端服务，请确认 script_back 已在 8080 端口启动')
      return
    }

    if (!response.ok) {
      handlers.onError?.(`服务异常（HTTP ${response.status}），请稍后重试`)
      return
    }
    if (!response.body) {
      handlers.onError?.('当前浏览器不支持流式响应，请改用 Chrome 或 Edge')
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    const parser = createSseParser()
    let finished = false

    try {
      while (!finished) {
        const { done, value } = await reader.read()
        if (done) break

        // stream:true 时中文可能被切在多字节中间，decoder 必须复用同一个实例
        // （它内部会缓存不完整的字节序列），不能每次 new
        for (const evt of parser.push(decoder.decode(value, { stream: true }))) {
          if (evt.event === 'message') {
            handlers.onMessage?.(evt.data)
          } else if (evt.event === 'done') {
            finished = true
            handlers.onDone?.()
            break
          } else if (evt.event === 'error') {
            finished = true
            handlers.onError?.(evt.data || 'AI 对话暂时不可用，请稍后重试')
            break
          }
        }
      }

      // 冲刷 decoder 里可能残留的最后一个多字节字符
      const tail = decoder.decode()
      if (tail && !finished) {
        for (const evt of parser.push(tail)) {
          if (evt.event === 'message') handlers.onMessage?.(evt.data)
        }
      }

      // 后端没发 done 就把流关了（异常情况），也要让 UI 停止转圈
      if (!finished) handlers.onDone?.()
    } catch (e) {
      if (e?.name === 'AbortError') return
      handlers.onError?.('读取回复时连接中断，请重试')
    }
  })()

  return {
    abort: () => controller.abort(),
  }
}
```

- [ ] **Step 6: 写 `ChatPanel.vue`**

`src/components/ChatPanel.vue`

```vue
<script setup>
/**
 * AI 对话面板（需求 4.3.4）。
 *
 * 一条规则固定一个会话，所以这里没有会话列表、不能新建/删除会话，只有 [清空对话]。
 * 每次发消息自动带上编辑器当前内容（含未保存修改）—— 由父组件传入的 getScript 取。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearChat, getChatHistory, sendChatStream } from '../api/chat'
import { splitBlocks } from '../api/messageBlocks'
import { reportError } from '../api/http'

const props = defineProps({
  ruleId: { type: Number, required: true },
  /** 取编辑器当前内容，发消息时作为上下文一并带上 */
  getScript: { type: Function, required: true },
})
const emit = defineEmits(['apply-script'])

/** {role:'user'|'assistant', content:string, streaming?:boolean} */
const messages = ref([])
const input = ref('')
const sending = ref(false)
const loadingHistory = ref(true)
const scroller = ref(null)

let stream = null

const canSend = computed(() => !sending.value && input.value.trim().length > 0)

onMounted(loadHistory)
onBeforeUnmount(() => stream?.abort())

// 切换规则时必须中断上一个流并重新加载，否则两个会话的内容会串在一起
watch(() => props.ruleId, async () => {
  stream?.abort()
  stream = null
  sending.value = false
  messages.value = []
  await loadHistory()
})

async function loadHistory() {
  loadingHistory.value = true
  try {
    const list = await getChatHistory(props.ruleId)
    messages.value = (list ?? []).map((m) => ({ role: m.role, content: m.content }))
    await scrollToBottom()
  } catch (e) {
    reportError(e)
  } finally {
    loadingHistory.value = false
  }
}

async function handleSend() {
  if (!canSend.value) return
  const text = input.value.trim()
  input.value = ''

  messages.value.push({ role: 'user', content: text })
  // 先占一个空的 assistant 气泡，流式内容往里追加，打字机效果才连续
  messages.value.push({ role: 'assistant', content: '', streaming: true })
  sending.value = true
  await scrollToBottom()

  stream = sendChatStream(
    { ruleId: props.ruleId, message: text, scriptContent: props.getScript() },
    {
      onMessage: (chunk) => {
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant') last.content += chunk
        scrollToBottom()
      },
      onDone: () => finishStream(),
      onError: (msg) => {
        const last = messages.value[messages.value.length - 1]
        if (last && last.role === 'assistant' && !last.content) {
          // 一个字都没收到就失败：把气泡改成错误提示，别留个空气泡
          last.content = msg
          last.isError = true
        } else {
          ElMessage.error(msg)
        }
        finishStream()
      },
    },
  )
}

function finishStream() {
  const last = messages.value[messages.value.length - 1]
  if (last && last.role === 'assistant') {
    last.streaming = false
    // 完全没内容的助手气泡直接移除，历史回显时也不该有空气泡
    if (!last.content.trim() && !last.isError) messages.value.pop()
  }
  sending.value = false
  stream = null
  scrollToBottom()
}

async function handleClear() {
  try {
    await ElMessageBox.confirm(
      '会清除这条规则的全部对话记录与模型记忆，且无法恢复。确定继续吗？',
      '清空对话',
      { confirmButtonText: '确定清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return                      // 用户取消
  }
  try {
    stream?.abort()
    stream = null
    sending.value = false
    await clearChat(props.ruleId)
    messages.value = []
    ElMessage.success('对话已清空')
  } catch (e) {
    reportError(e)
  }
}

function handleKeydown(e) {
  // Enter 发送，Shift+Enter 换行（与主流对话产品一致）
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}
</script>

<template>
  <div class="chat-panel">
    <div class="head">
      <span class="title">AI 对话助手</span>
      <span class="spacer"></span>
      <el-button
        link
        size="small"
        :disabled="loadingHistory || !messages.length"
        @click="handleClear"
      >清空对话</el-button>
    </div>

    <div ref="scroller" class="scroller" v-loading="loadingHistory">
      <div v-if="!messages.length && !loadingHistory" class="empty">
        <p class="empty-title">让 AI 帮你写规则脚本</p>
        <p class="empty-hint">每次提问会自动带上编辑器里的当前脚本作为上下文</p>
        <div class="suggests">
          <span class="suggest" @click="input = '帮我写一个判断年龄是否成年的规则'">判断年龄是否成年</span>
          <span class="suggest" @click="input = '这个脚本有什么潜在问题'">检查当前脚本的问题</span>
          <span class="suggest" @click="input = '给这个脚本加上空值保护'">加上空值保护</span>
        </div>
      </div>

      <!--
        v-memo 是必需的，不是优化洁癖：流式期间每个 chunk 都会触发整个 v-for 重渲染，
        而每条消息都要跑一次 splitBlocks（带正则的全文扫描）。消息多了会明显卡顿。
        加了 memo 后只有内容真的变了的那一条（即最后一条）会重算。
        数组里必须列全所有影响渲染的字段：role 决定气泡左右与配色，
        而 :key 用的是下标，loadHistory 整体替换后同一下标可能换了角色。
      -->
      <div
        v-for="(m, i) in messages"
        :key="i"
        v-memo="[m.role, m.content, m.streaming, m.isError]"
        class="msg"
        :class="[m.role, { 'is-error': m.isError }]"
      >
        <div class="bubble">
          <template v-for="(b, j) in splitBlocks(m.content)" :key="j">
            <pre v-if="b.type === 'text'" class="text">{{ b.content }}</pre>
            <div v-else class="code-block">
              <div class="code-head">
                <span class="lang">{{ b.lang || 'code' }}</span>
                <span class="spacer"></span>
                <!-- 流式未结束时不给应用按钮：此时脚本还是半截，替换进编辑器会毁掉用户内容 -->
                <el-button
                  v-if="!m.streaming && b.content.trim()"
                  link
                  size="small"
                  type="primary"
                  @click="emit('apply-script', b.content)"
                >应用到编辑器</el-button>
                <span v-else class="generating">生成中…</span>
              </div>
              <pre class="code">{{ b.content }}</pre>
            </div>
          </template>

          <span v-if="m.streaming" class="cursor"></span>
        </div>
      </div>
    </div>

    <div class="composer">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        resize="none"
        :disabled="sending"
        placeholder="问点什么…（Enter 发送，Shift+Enter 换行）"
        @keydown="handleKeydown"
      />
      <div class="actions">
        <span class="hint" v-if="sending">AI 正在回复…</span>
        <span class="spacer"></span>
        <el-button type="primary" :loading="sending" :disabled="!canSend" @click="handleSend">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-panel { height: 100%; display: flex; flex-direction: column; min-height: 0; }

.head { display: flex; align-items: center; gap: 8px; padding-bottom: 10px; flex-shrink: 0; }
.title { font-size: 14px; font-weight: 600; color: var(--text-main); }
.spacer { flex: 1; }

.scroller { flex: 1; overflow-y: auto; min-height: 0; padding-right: 4px; }

.empty { padding: 24px 8px; text-align: center; }
.empty-title { margin: 0 0 6px; font-size: 13px; font-weight: 600; color: var(--text-main); }
.empty-hint { margin: 0 0 14px; font-size: 12px; color: var(--text-muted); line-height: 1.6; }
.suggests { display: flex; flex-direction: column; gap: 6px; }
.suggest {
  padding: 6px 10px;
  border-radius: 12px;
  background: var(--chip-bg);
  color: var(--chip-fg);
  font-size: 12px;
  cursor: pointer;
  transition: opacity .15s;
}
.suggest:hover { opacity: .78; }

.msg { display: flex; margin-bottom: 10px; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }

.bubble {
  max-width: 92%;
  padding: 8px 12px;
  border-radius: 14px;
  font-size: 13px;
  line-height: 1.7;
}
.user .bubble {
  background: var(--brand-gradient);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.assistant .bubble {
  background: var(--input-bg);
  border: 1px solid var(--border-light);
  color: var(--text-main);
  border-bottom-left-radius: 4px;
}
.is-error .bubble { background: var(--danger-bg); border-color: #f6d3d3; color: var(--danger); }

.text { margin: 0; font-family: inherit; white-space: pre-wrap; word-break: break-word; }

.code-block {
  margin: 8px 0;
  border-radius: 10px;
  background: #fff;
  border: 1px solid var(--border-light);
  overflow: hidden;
}
.code-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  background: var(--brand-gradient-soft);
  border-bottom: 1px solid var(--border-light);
}
.lang { font-size: 11px; color: var(--text-muted); font-family: ui-monospace, Menlo, monospace; }
.generating { font-size: 11px; color: var(--text-muted); }
.code {
  margin: 0;
  padding: 8px 10px;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}

/* 打字机光标 */
.cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--brand-from);
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink { to { visibility: hidden; } }

.composer { flex-shrink: 0; padding-top: 10px; }
.composer :deep(.el-textarea__inner) {
  border-radius: 12px;
  background: var(--input-bg);
  font-size: 13px;
}
.actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.hint { font-size: 12px; color: var(--text-muted); }
.actions :deep(.el-button) { border-radius: 18px; }
</style>
```

- [ ] **Step 7: 接进 `RuleWorkbenchView.vue`**

1）补 import：

```js
import ChatPanel from '../components/ChatPanel.vue'
```

2）把 right-slot 的占位整段替换掉。原来（任务 13 写的）是：

```vue
      <!-- right-slot：任务 19 放 ChatPanel -->
      <div class="right">
        <el-empty description="AI 对话面板待接入（任务 19）" :image-size="72" />
      </div>
```

换成：

```vue
      <div class="right">
        <ChatPanel
          :rule-id="ruleId"
          :get-script="() => scriptContent"
          @apply-script="handleApplyFromChat"
        />
      </div>
```

3）加处理函数（复用任务 15 的 `useApplyScript`，两个入口行为一致）：

```js
/** 对话里的代码块应用到编辑器，与 AI 审查卡片共用同一套确认流程（任务 15） */
function handleApplyFromChat(script) {
  apply.requestApply(script, 'AI 对话')
}
```

> `:get-script="() => scriptContent"` 传的是函数而非值 —— 面板在**发送那一刻**才取内容，
> 这样用户先打字提问、再改脚本、然后发送，带上去的是最新内容（交互规则 #3）。
> 若写成 `:script="scriptContent"` 让面板持有副本，就会有「发的是旧脚本」的 bug。

- [ ] **Step 8: 构建与单测**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：**92 个单测通过**，构建成功。

常见问题：
- `TextDecoder is not defined` / `AbortController is not defined` → 本项目 vitest 用的是 **`node` 环境**（任务 3 Step 3 的 `vitest.config.js`，里头注释写明了「只测纯逻辑模块，不拉 jsdom」，`package.json` 的 devDependencies 也确实没有 jsdom）。而 Node 20.18.1 **自带这两个全局**（`TextDecoder` 自 Node 11、`AbortController` 自 Node 15），正常不会报。真报了只有两种可能：① `node -v` 不是 20.x（没 `source ../tools/env.sh`，用了系统旧 node）；② 有人改过 `environment` —— 注意测试配置在 **`vitest.config.js`**，不在 `vite.config.js`，别找错文件
- 单测数不对 → 检查 `sseParser.spec.js`（18 条）与 `messageBlocks.spec.js`（11 条）是否都建了

- [ ] **Step 9: 浏览器手工验收**

前后端都起起来（都要 `source ../tools/env.sh`），浏览器进任意规则的工作台。

1. 右侧面板显示空状态：标题「让 AI 帮你写规则脚本」+ 三个可点的建议气泡，点其中一个 → 输入框被填上该文本（不自动发送）
2. 编辑器里先写点内容（如 `String name = ${userName}\nreturn name.toUpperCase()`），然后提问「这个脚本有什么风险」→ **回答必须针对这段脚本**指出空指针风险（验自动带上下文）
3. 回复过程是**打字机效果**：文字逐段出现，末尾有闪烁光标，输入框与发送按钮期间禁用，底部显示「AI 正在回复…」
4. 回复里的 ```` ```groovy ```` 代码块渲染成带边框的块，头部显示 `groovy` 标签
5. **流式进行中**代码块头部显示「生成中…」而**不是** [应用到编辑器] 按钮（此时脚本是半截的，给了按钮会毁掉编辑器内容）
6. 流结束后按钮出现，点 [应用到编辑器] → 弹任务 15 的确认框，来源显示「AI 对话」，确认后编辑器内容被替换、徽标变 stale、接着问「现在校验吗？」
7. 代码块里的 `${userName}` 占位符**原样保留**，没被吃掉或转义
8. 连续问第二轮「把刚才的脚本改成先判空」→ 回答接得上第一轮（验多轮记忆）
9. `Enter` 发送、`Shift+Enter` 换行；中文输入法候选状态下按 Enter **不该误发**（验 `isComposing` 判断）
10. 刷新页面 → 历史消息全部回显，顺序正确，代码块仍能被识别并带 [应用到编辑器] 按钮
11. 消息区自动滚到底部；手动往上滚看历史时，新内容到达**不应**把视图强行拽到底（若被拽走，说明 `scrollToBottom` 需要加「用户是否在底部」判断，属于可接受的已知小瑕疵，记录即可）
12. 点 [清空对话] → 二次确认弹窗 → 确定后消息清空，显示空状态
13. 清空后再提问 → 模型**不再知道**之前的内容（验记忆真的被清了）
14. 停掉后端，在前端发消息 → 气泡显示「无法连接后端服务…」的红色错误气泡，**不是**一直转圈，也不弹白屏
15. 发送中途切到列表页再进另一条规则 → 不报错、不串消息（验 `watch(ruleId)` 的 abort 与重载）

第 5 条与第 6 条是本任务的核心交互，务必亲手试。

- [ ] **Step 10: 用 MySQL MCP 核对前端产生的消息**

前端发的消息应该经任务 18 的 `ChatController` 落库。查库确认：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT m.role, CHAR_LENGTH(m.content) AS len, LEFT(m.content, 40) AS head, m.created_at FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.rule_id = ? ORDER BY m.created_at ASC, m.id ASC", "params": [<RID>]})
```

核对：
- `role` 严格 user / assistant 交替，没有连续两条同角色
- 条数 = 你在页面上看到的消息条数（**清空对话后再查应为 0 条**）
- assistant 的 `len` 与页面上看到的回复长度相当 —— 若明显偏小，说明存的是分片而非聚合结果，回任务 18 Step 5 查 `aggregated`
- `head` 里若含代码块，```` ``` ```` 应原样存在（验 content 是 text 类型没被截断）

清空对话后再查一次，确认 `DELETE` 真的执行了：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT COUNT(*) AS msgs FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.rule_id = ?", "params": [<RID>]})
```

预期 `msgs = 0`，但 `conversation` 表里该规则的会话记录**仍在**（需求 4.3.4：会话不能删）。

- [ ] **Step 11: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_front
git commit -m "feat: AI 对话面板（手写 SSE 解析/打字机流式/代码块应用/历史回显/清空）"
```

> **执行偏差记录（实际提交 `7e22732`，7 文件 +758 -2）**
> 1. **`chat.js` 不写单测，理由如下（dev-conventions 硬要求「决定不给某模块写测试必须写明理由」）**：`chat.js` 是 I/O 编排层（`fetch` + `ReadableStream.getReader()` + `TextDecoder` + `AbortController` + 回调分派），其真正有算法风险、最易写错的部分——SSE 分片解析——已抽成纯函数 `sseParser.js` 并被 18 条单测覆盖（含 chunk 边界切在事件中间、CRLF、多 data 行拼接、`${}`/反引号原样保留）。`chat.js` 剩余分支都是错误处理胶水（网络失败 / 非 2xx / 无 body / AbortError），在 `node` 环境下测它要 mock 整套 `fetch`+`ReadableStream`，成本高且测的是 mock 而非真实行为。dev-conventions 明确把「SSE 解析器」列为测试对象、而非 `chat.js` 本身，故只测 `sseParser`；`chat.js` 的编排正确性靠 Step 9 浏览器验收 + Task 18 Step 8 已证明的后端契约把关。
> 2. **单测累计 92 通过，与计划 Step 4（11552 行）一致；但计划 Step 2（11401 行）正文写的「72」有误**：其分解「任务3的6 + 任务14的37 + 任务15的11 + 本任务18」漏算了任务 6 的 `rule.spec.js` 9 条。实际基线为 63（http 6 + paramRules 16 + validationState 21 + rule 9 + applyScript 11），Step 2 后应为 63+18=81（计划书 11403 行的注解已自我修正为 81），Step 4 后 81+11=92。最终 92 正确、无需改动，仅记录 Step 2 正文数字与其自带注解不一致。
> 3. **TDD 顺序无需调整**：与任务 18 不同，本任务计划已是正确 TDD 顺序——Step 1（`sseParser.spec.js` 测试）→ Step 2（`sseParser.js` 实现）→ Step 3（`messageBlocks.spec.js` 测试）→ Step 4（`messageBlocks.js` 实现），每个纯函数都先测后实现。两个测试都各自单独跑过、亲眼见红（`Failed to load url ../sseParser`、`../messageBlocks`）再实现，实现后各自单独跑绿（18、11），符合 dev-conventions「写完失败测试必须先单独跑一次亲眼看到它红」。
> 4. **Step 9（15 项浏览器交互验收）+ Step 10（MySQL MCP 核对前端产生的消息）延后并入 Task 21**：browser-use MCP 不可达（同任务 14/15/16/18 的既定处理）。以无头证据替代并静态走查：①`sseParser` 18 + `messageBlocks` 11 单测覆盖两大坑（chunk 边界、未闭合代码块当 code 渲染防闪烁）；②`npm run build` 成功（1647 模块，捕获 Vue SFC 模板/脚本编译错误）；③逐条静态走查 15 项验收点均有对应实现——空状态建议气泡只填 `input` 不自动发送、`handleSend` 传 `scriptContent: props.getScript()`（发送那刻才取最新内容）、流式中代码块头显示「生成中…」而 [应用到编辑器] 按钮 `v-if="!m.streaming && b.content.trim()"`、`handleKeydown` 有 `!e.isComposing` 守卫防中文输入法误发、`watch(ruleId)` 先 `abort` 再清空重载防串消息、`onError` 把空助手气泡改红色错误提示且 `finishStream` 停转圈、`onMounted(loadHistory)` 回显历史；④后端 `/api/chat/send|history|clear` 契约已在 Task 18 Step 8 用 curl 全链路证明（真流式 9 分片、多轮记忆、假 key 降级 HTTP200、级联删除）。纯交互式视觉项（打字机动画、自动滚动、真实浏览器渲染）记为 Task 21 待办。

---

## Task 20: 测试用例（全栈）

落实需求 4.3.3 的三件事：**[保存为用例]** 把当前一组填值命名存下来、**下拉选中回填**后点运行即可重跑、**可删除用例**。后端补 `/api/testcase/list|save|delete` 与 `TestCaseService`，前端补 `api/testcase.js` 与 `TestCaseBar.vue`，插进任务 14 在 `ValidationTabs.vue` 里留的 `testcase-slot`。

实体（`TestCase`）、仓库（`TestCaseRepository`）、四个 DTO 都在任务 4、5 定稿了，本任务不新建、不修改它们，只写 service / controller / 前端。规则删除时的级联（`testCaseRepository.deleteByRuleId`）任务 5 的 `RuleService.delete` 已经做了，这里也不用管。

**三个必须提前想清楚的设计点：**

1. **`params` ↔ JSON 互转，本任务首次引入 `ObjectMapper`。** 前面 19 个任务没有任何 JSON 手工序列化（都是 Spring 自动转换），所以 `ObjectMapper` 到这里才第一次出现。Spring Boot 已自动配置好这个 bean，直接构造器注入即可，**不要自己 `new ObjectMapper()`**（自建的那个没有 Boot 的 JavaTimeModule 等定制，行为会与全局不一致）。两个 checked 异常必须就地转成 `BizException`：`writeValueAsString` 失败若漏到 controller，全局处理器只会给「系统异常」这种没用的提示，违反 API 规范「错误信息必须是可读中文」。

2. **一条脏数据不该拖垮整个列表。** `params_json` 是 TEXT 列，历史数据可能被手工改坏、或早期版本存过空串。`readValue` 遇到这些会抛异常，若不兜底，**一条坏用例会让整条规则的用例列表接口 500**，用户看到的是一个用例都没有。反序列化失败时退化成空 Map 并记日志：用例还在、名字还能看见，只是回填不出值，损失可控。

3. **回填是「完全覆盖」不是「合并」。** 需求原文是「选中后一键回填所有输入框，再点运行即可**重跑**」。「重跑」意味着要恢复到保存用例那一刻的状态；若只覆盖用例里有的键、其余保留用户当前输入，跑出来的结果会与当初保存时不同，用例就失去意义了。所以用例里没有的占位符要给**类型默认值**（把用户已填的清掉），这与 `validationState.js` 里 `pickParams` 的语义一致。

**还有一个坑单独说：用例是快照，脚本会漂移。** 保存用例后用户可能改脚本、删掉某个占位符。回填时那个键已经无处可填，若静默丢弃，用户会以为回填成功了、实际少了一个值，跑出来的结果莫名其妙。所以回填必须**报告被丢弃的键**，这就是下面把取值逻辑单独抽成纯函数 `refillParams` 的原因 —— 它返回 `{values, dropped}` 两样东西。

**Files:**
- Create: `script_back/src/main/java/com/xd/rulescript/service/TestCaseService.java`
- Create: `script_back/src/main/java/com/xd/rulescript/controller/TestCaseController.java`
- Test: `script_back/src/test/java/com/xd/rulescript/service/TestCaseServiceTest.java`
- Create: `script_front/src/composables/testCaseParams.js`（回填取值，纯函数，单测对象）
- Test: `script_front/src/composables/__tests__/testCaseParams.spec.js`
- Create: `script_front/src/api/testcase.js`
- Create: `script_front/src/components/TestCaseBar.vue`
- Modify: `script_front/src/components/ValidationTabs.vue`（填 `testcase-slot`，加 `ruleId` prop）
- Modify: `script_front/src/views/RuleWorkbenchView.vue`（把 `ruleId` 传给 `ValidationTabs`）

**Interfaces:**
- Consumes: `TestCase` / `TestCaseRepository`（任务 4）、四个 TestCase DTO（任务 5）、`ApiResponse` / `BizException`（任务 2）、`RuleRepository`（任务 4）、`post` / `reportError`（任务 3）、`defaultValueFor`（任务 14 的 `paramRules.js`）、`ValidationTabs` 的 `params` / `placeholders` / `update:params`（任务 14）
- Produces:
  ```java
  // TestCaseService
  List<TestCaseDto> list(Long ruleId)
  TestCaseDto save(TestCaseSaveRequest request)   // 同名拒绝、名称 trim、规则必须存在
  void delete(Long testCaseId)                    // 不存在则 BizException
  ```
  ```js
  // composables/testCaseParams.js
  refillParams(placeholders, savedParams) -> { values: Object, dropped: string[] }

  // api/testcase.js
  listTestCases(ruleId) -> Promise<Array<{id, name, params}>>
  saveTestCase({ruleId, name, params}) -> Promise<{id, name, params}>
  deleteTestCase(testCaseId) -> Promise<null>
  ```
  ```
  // TestCaseBar.vue
  props: ruleId(Number), placeholders(Array), params(Object)
  emits: update:params(Object)
  ```

- [ ] **Step 1: 写 `TestCaseServiceTest`（失败测试）**

`src/test/java/com/xd/rulescript/service/TestCaseServiceTest.java`

```java
package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.TestCaseDto;
import com.xd.rulescript.dto.TestCaseSaveRequest;
import com.xd.rulescript.entity.TestCase;
import com.xd.rulescript.repository.TestCaseRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试用例的存取行为。
 *
 * 重点覆盖两类容易漏的：JSON 互转的边界（空参数、null 值、键顺序、脏数据），
 * 以及业务约束（同名、名称长度、规则必须存在）。
 */
@SpringBootTest
@Transactional
class TestCaseServiceTest {

    @Autowired private TestCaseService testCaseService;
    @Autowired private RuleService ruleService;
    @Autowired private TestCaseRepository testCaseRepository;

    private Long newRule(String name) {
        return ruleService.create(new RuleCreateRequest(name, null)).id();
    }

    /** 保序 Map，模拟前端传来的 JSON 对象 */
    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void 保存后能查出来且参数完整还原() {
        Long ruleId = newRule("用例规则甲");

        testCaseService.save(new TestCaseSaveRequest(ruleId, "VIP 成年人", params("age", "28", "level", "vip")));
        List<TestCaseDto> list = testCaseService.list(ruleId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("VIP 成年人");
        assertThat(list.get(0).params()).containsEntry("age", "28").containsEntry("level", "vip");
    }

    @Test
    void 保存返回的用例带自增主键() {
        Long ruleId = newRule("用例规则乙");

        TestCaseDto saved = testCaseService.save(new TestCaseSaveRequest(ruleId, "用例一", params("a", "1")));

        assertThat(saved.id()).isNotNull();
    }

    @Test
    void 中文键值与特殊字符原样存取() {
        Long ruleId = newRule("用例规则丙");
        Map<String, String> p = params("用户名", "张三 \"admin\"", "备注", "含,逗号:冒号{花括号}");

        testCaseService.save(new TestCaseSaveRequest(ruleId, "中文用例", p));
        TestCaseDto got = testCaseService.list(ruleId).get(0);

        assertThat(got.params()).containsEntry("用户名", "张三 \"admin\"");
        assertThat(got.params()).containsEntry("备注", "含,逗号:冒号{花括号}");
    }

    @Test
    void 同一规则下同名用例被拒() {
        Long ruleId = newRule("用例规则丁");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "重复名", params("a", "1")));

        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, "重复名", params("a", "2"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在同名用例");
    }

    @Test
    void 名称首尾空白被trim后参与判重() {
        Long ruleId = newRule("用例规则戊");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "  甲  ", params("a", "1")));

        // 存进去的名字应该已经 trim 过
        assertThat(testCaseService.list(ruleId).get(0).name()).isEqualTo("甲");
        // 带空格的同名也算重复
        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, "甲 ", params("a", "2"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在同名用例");
    }

    @Test
    void 名称为空白时拒绝保存() {
        Long ruleId = newRule("用例规则己");

        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, "   ", params("a", "1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用例名称不能为空");
    }

    @Test
    void 名称超长时给出可读中文而不是数据库异常() {
        Long ruleId = newRule("用例规则庚");
        String tooLong = "长".repeat(TestCaseService.NAME_MAX + 1);

        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, tooLong, params("a", "1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用例名称太长");
    }

    @Test
    void 规则不存在时拒绝保存用例() {
        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(999999L, "孤儿用例", params("a", "1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规则不存在或已被删除");
    }

    @Test
    void 列表按id升序返回() {
        Long ruleId = newRule("用例规则辛");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "第一个", params()));
        testCaseService.save(new TestCaseSaveRequest(ruleId, "第二个", params()));
        testCaseService.save(new TestCaseSaveRequest(ruleId, "第三个", params()));

        assertThat(testCaseService.list(ruleId)).extracting(TestCaseDto::name)
                .containsExactly("第一个", "第二个", "第三个");
    }

    @Test
    void 空参数可以保存并还原为空Map() {
        Long ruleId = newRule("用例规则壬");

        TestCaseDto saved = testCaseService.save(new TestCaseSaveRequest(ruleId, "无参数用例", params()));

        assertThat(saved.params()).isNotNull().isEmpty();
        assertThat(testCaseService.list(ruleId).get(0).params()).isEmpty();
    }

    @Test
    void 参数值为null时存成空串而不是null() {
        Long ruleId = newRule("用例规则癸");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("age", null);

        testCaseService.save(new TestCaseSaveRequest(ruleId, "含null值", p));

        // 前端拿到 null 会让输入框变成 "null" 字符串或报 prop 类型错，统一成空串最省事
        assertThat(testCaseService.list(ruleId).get(0).params()).containsEntry("age", "");
    }

    @Test
    void 参数键顺序在存取后保持一致() {
        Long ruleId = newRule("用例规则子");
        // 故意用非字典序，若实现里换成 HashMap 这条就会红
        Map<String, String> p = params("zebra", "1", "apple", "2", "mango", "3");

        testCaseService.save(new TestCaseSaveRequest(ruleId, "顺序用例", p));

        assertThat(new ArrayList<>(testCaseService.list(ruleId).get(0).params().keySet()))
                .containsExactly("zebra", "apple", "mango");
    }

    @Test
    void 删除后列表里不再有该用例() {
        Long ruleId = newRule("用例规则丑");
        TestCaseDto saved = testCaseService.save(new TestCaseSaveRequest(ruleId, "待删", params("a", "1")));

        testCaseService.delete(saved.id());

        assertThat(testCaseService.list(ruleId)).isEmpty();
    }

    @Test
    void 删除不存在的用例报错() {
        assertThatThrownBy(() -> testCaseService.delete(999999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用例不存在或已被删除");
    }

    @Test
    void 不同规则的用例互不干扰() {
        Long a = newRule("规则A");
        Long b = newRule("规则B");
        testCaseService.save(new TestCaseSaveRequest(a, "A的用例", params("x", "1")));
        testCaseService.save(new TestCaseSaveRequest(b, "B的用例", params("y", "2")));

        assertThat(testCaseService.list(a)).extracting(TestCaseDto::name).containsExactly("A的用例");
        assertThat(testCaseService.list(b)).extracting(TestCaseDto::name).containsExactly("B的用例");
        // 同名判定也只在同一规则内生效
        testCaseService.save(new TestCaseSaveRequest(b, "A的用例", params("z", "3")));
        assertThat(testCaseService.list(b)).hasSize(2);
    }

    @Test
    void params_json是脏数据时列表不炸并退化成空参数() {
        Long ruleId = newRule("用例规则寅");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "正常用例", params("a", "1")));

        // 直接绕过 service 写两条坏数据，模拟历史脏数据 / 手工改库
        TestCase blank = new TestCase();
        blank.setRuleId(ruleId);
        blank.setName("空串参数");
        blank.setParamsJson("");
        testCaseRepository.save(blank);

        TestCase broken = new TestCase();
        broken.setRuleId(ruleId);
        broken.setName("非法JSON");
        broken.setParamsJson("{这不是JSON");
        testCaseRepository.save(broken);

        List<TestCaseDto> list = testCaseService.list(ruleId);

        // 关键断言：坏数据没有让整条接口失败，三条都还在
        assertThat(list).hasSize(3);
        assertThat(list).extracting(TestCaseDto::name)
                .contains("正常用例", "空串参数", "非法JSON");
        assertThat(list.stream().filter(d -> d.name().equals("非法JSON")).findFirst().orElseThrow().params())
                .isEmpty();
    }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test -Dtest=TestCaseServiceTest
```

预期：编译失败（`TestCaseService` 还不存在）。

- [ ] **Step 2: 写 `TestCaseService`**

`src/main/java/com/xd/rulescript/service/TestCaseService.java`

```java
package com.xd.rulescript.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.TestCaseDto;
import com.xd.rulescript.dto.TestCaseSaveRequest;
import com.xd.rulescript.entity.TestCase;
import com.xd.rulescript.repository.RuleRepository;
import com.xd.rulescript.repository.TestCaseRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试用例 CRUD：一组占位符填值的快照（需求 4.3.3）。
 *
 * params 以 JSON 字符串存在 test_case.params_json（TEXT 列），序列化在本类完成 ——
 * 实体只认字符串，不让 JPA 参与 JSON 转换，避免引入 AttributeConverter 之类的额外机制。
 */
@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);

    /**
     * 用例名的业务上限。DB 列宽是 128，这里取一半：
     * 下拉框放不下更长的名字，且要在它变成数据库的 Data too long 异常之前用中文拦掉。
     * 包级可见是为了让测试直接引用，改这个值不用同步改测试。
     */
    static final int NAME_MAX = 64;

    private static final TypeReference<Map<String, String>> PARAMS_TYPE = new TypeReference<>() {};

    private final TestCaseRepository testCaseRepository;
    private final RuleRepository ruleRepository;
    /** Spring Boot 自动配置的 bean，别自己 new —— 自建实例没有 Boot 的模块定制，行为会与全局不一致 */
    private final ObjectMapper objectMapper;

    public TestCaseService(TestCaseRepository testCaseRepository,
                           RuleRepository ruleRepository,
                           ObjectMapper objectMapper) {
        this.testCaseRepository = testCaseRepository;
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<TestCaseDto> list(Long ruleId) {
        if (ruleId == null) {
            throw new BizException("规则 ID 不能为空");
        }
        List<TestCaseDto> out = new ArrayList<>();
        for (TestCase entity : testCaseRepository.findByRuleIdOrderByIdAsc(ruleId)) {
            out.add(toDto(entity));
        }
        return out;
    }

    @Transactional
    public TestCaseDto save(TestCaseSaveRequest request) {
        Long ruleId = request.ruleId();
        if (ruleId == null) {
            throw new BizException("规则 ID 不能为空");
        }
        // 先确认规则在，否则会留下孤儿用例（规则删了用例还在，且再也查不出来）
        if (!ruleRepository.existsById(ruleId)) {
            throw new BizException("规则不存在或已被删除");
        }

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException("用例名称不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw new BizException("用例名称太长，请控制在 " + NAME_MAX + " 字以内");
        }

        // 判重：任务 4 定稿的仓库只有 findByRuleIdOrderByIdAsc / deleteByRuleId 两个方法，
        // 不为这一个判断去改已定稿的接口。一条规则的用例就几条到几十条，全捞出来比对完全够。
        boolean duplicated = testCaseRepository.findByRuleIdOrderByIdAsc(ruleId).stream()
                .anyMatch(existing -> name.equals(existing.getName()));
        if (duplicated) {
            throw new BizException("已存在同名用例「" + name + "」，请换个名字");
        }

        TestCase entity = new TestCase();
        entity.setRuleId(ruleId);
        entity.setName(name);
        entity.setParamsJson(writeJson(normalize(request.params())));
        return toDto(testCaseRepository.save(entity));
    }

    @Transactional
    public void delete(Long testCaseId) {
        if (testCaseId == null) {
            throw new BizException("用例 ID 不能为空");
        }
        // 显式 findById 而不是 deleteById：后者对不存在的 id 静默无操作，
        // 用户点了删除却什么反馈都没有，会以为没点上而反复点
        TestCase entity = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new BizException("用例不存在或已被删除"));
        testCaseRepository.delete(entity);
    }

    /**
     * 规整参数：用 LinkedHashMap 保住键顺序（回填时输入框的出现顺序要跟保存时一致，
     * 换成 HashMap 顺序就随哈希散了），null 值统一成空串。
     */
    private Map<String, String> normalize(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) {
            return out;
        }
        params.forEach((key, value) -> {
            if (key != null) {
                out.put(key, value == null ? "" : value);
            }
        });
        return out;
    }

    private String writeJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            // checked 异常绝不能漏到 controller：全局处理器只会给「系统异常」这种没用的提示
            log.warn("测试用例参数序列化失败: {}", e.getOriginalMessage());
            throw new BizException("参数格式有问题，无法保存用例");
        }
    }

    private Map<String, String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, PARAMS_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException e) {
            // 一条脏数据不该让整个列表接口 500：退化成空参数，用例还在、名字还能看见，
            // 只是回填不出值。损失可控，远好过用户看到「一个用例都没有」
            log.warn("测试用例参数解析失败，按空参数处理，内容长度={}", json.length());
            return new LinkedHashMap<>();
        }
    }

    private TestCaseDto toDto(TestCase entity) {
        return new TestCaseDto(entity.getId(), entity.getName(), readJson(entity.getParamsJson()));
    }
}
```

> **接口契约里没有 `ruleId`，所以 `delete` 无法校验用例归属**（`TestCaseIdRequest` 只有一个 `testCaseId`，任务 5 定稿）。
> 已知取舍：用例 id 由 `list` 接口按规则下发，前端不会跨规则传；真要越权删除得手工构造请求。
> 本项目无鉴权（需求文档第 6 节明确不做登录），所以这个风险与「任何人都能删任何规则」是同一量级，不单独设防。

- [ ] **Step 3: 写 `TestCaseController`**

`src/main/java/com/xd/rulescript/controller/TestCaseController.java`

```java
package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.TestCaseDto;
import com.xd.rulescript.dto.TestCaseIdRequest;
import com.xd.rulescript.dto.TestCaseListRequest;
import com.xd.rulescript.dto.TestCaseSaveRequest;
import com.xd.rulescript.service.TestCaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试用例接口。一律 POST，路径 /api/testcase/<动作>（API 规范）。
 */
@RestController
@RequestMapping("/api/testcase")
public class TestCaseController {

    private final TestCaseService testCaseService;

    public TestCaseController(TestCaseService testCaseService) {
        this.testCaseService = testCaseService;
    }

    @PostMapping("/list")
    public ApiResponse<List<TestCaseDto>> list(@Valid @RequestBody TestCaseListRequest request) {
        return ApiResponse.ok(testCaseService.list(request.ruleId()));
    }

    @PostMapping("/save")
    public ApiResponse<TestCaseDto> save(@Valid @RequestBody TestCaseSaveRequest request) {
        return ApiResponse.ok(testCaseService.save(request));
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@Valid @RequestBody TestCaseIdRequest request) {
        testCaseService.delete(request.testCaseId());
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 4: 跑后端测试**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q test
```

预期：BUILD SUCCESS，**234 个测试通过**（任务 18 后的 218 + 本任务 `TestCaseServiceTest` 16 = 234）。

常见问题：
- `参数键顺序在存取后保持一致` 红 → `normalize` 或 `readJson` 里用了 `HashMap`，换回 `LinkedHashMap`
- `params_json是脏数据时列表不炸` 红 → `readJson` 的 catch 漏了，或 `isBlank()` 判断漏了空串分支
- `名称超长时给出可读中文` 红且报的是 `DataIntegrityViolationException` → service 层的长度校验没生效，检查是否在 `save` 之前就 return 了
- 测试数不到 234 → 先 `mvn -q test -Dtest=TestCaseServiceTest` 单独跑，确认 16 条都在

- [ ] **Step 5: 写回填取值的失败测试**

`src/composables/__tests__/testCaseParams.spec.js`

```js
import { describe, it, expect } from 'vitest'
import { refillParams } from '../testCaseParams'

const PH = [
  { name: 'age', type: 'int' },
  { name: 'vip', type: 'boolean' },
  { name: 'level', type: 'String' },
]

describe('refillParams：用例快照回填到当前占位符', () => {
  it('全部命中时值原样回填', () => {
    const { values, dropped } = refillParams(PH, { age: '28', vip: 'true', level: 'gold' })
    expect(values).toEqual({ age: '28', vip: 'true', level: 'gold' })
    expect(dropped).toEqual([])
  })

  it('用例里缺的占位符给类型默认值，而不是保留当前输入', () => {
    // 「重跑」要求恢复到保存那一刻的状态，所以要把用户后来填的清掉
    const { values } = refillParams(PH, { age: '28' })
    expect(values).toEqual({ age: '28', vip: 'false', level: '' })
  })

  it('用例里多出的键进 dropped 且不进 values', () => {
    const { values, dropped } = refillParams(PH, { age: '28', vip: 'true', level: 'gold', oldField: 'x' })
    expect(values).not.toHaveProperty('oldField')
    expect(dropped).toEqual(['oldField'])
  })

  it('占位符为空时 values 为空、用例的键全部进 dropped', () => {
    const { values, dropped } = refillParams([], { age: '28', level: 'gold' })
    expect(values).toEqual({})
    expect(dropped).toEqual(['age', 'level'])
  })

  it('用例参数为空或未传时全是默认值、dropped 为空', () => {
    expect(refillParams(PH, {}).values).toEqual({ age: '', vip: 'false', level: '' })
    expect(refillParams(PH, null).values).toEqual({ age: '', vip: 'false', level: '' })
    expect(refillParams(PH, undefined).dropped).toEqual([])
  })

  it('placeholders 为 null 时不抛异常', () => {
    expect(() => refillParams(null, { a: '1' })).not.toThrow()
    expect(refillParams(null, { a: '1' })).toEqual({ values: {}, dropped: ['a'] })
  })

  it('值不被 trim、不被转类型（存进去什么就回填什么）', () => {
    const { values } = refillParams(
      [{ name: 'code', type: 'String' }, { name: 'n', type: 'int' }],
      { code: '  007  ', n: '0' },
    )
    // 前后空格与首导零都是有意义的数据，动了就等于篡改用户的用例
    expect(values.code).toBe('  007  ')
    expect(values.n).toBe('0')
  })

  it('values 的键顺序跟 placeholders 走，不跟用例走', () => {
    const { values } = refillParams(PH, { level: 'gold', age: '28', vip: 'true' })
    expect(Object.keys(values)).toEqual(['age', 'vip', 'level'])
  })

  it('不修改入参（纯函数）', () => {
    const saved = { age: '28', gone: 'x' }
    const snapshot = JSON.stringify(saved)
    refillParams(PH, saved)
    expect(JSON.stringify(saved)).toBe(snapshot)
  })
})
```

- [ ] **Step 6: 实现 `testCaseParams.js`**

`src/composables/testCaseParams.js`

```js
/**
 * 测试用例回填的取值逻辑（需求 4.3.3）。
 *
 * 语义是**完全覆盖**而不是合并：需求原文是「选中后一键回填所有输入框，再点运行即可重跑」，
 * 「重跑」意味着要恢复到保存用例那一刻的状态。若只覆盖用例里有的键、其余保留用户当前输入，
 * 跑出来的结果会与当初保存时不同，用例就失去意义了。
 *
 * 与 validationState.js 里的 pickParams 语义一致，但没有直接复用它，两个原因：
 * 1. pickParams 是 reducer 的模块私有函数，导出它等于把状态机的内部实现变成公共 API；
 * 2. 它不报告「哪些键被丢弃了」，而这里必须报告 —— 用例是快照、脚本会漂移，
 *    保存后用户可能删掉了某个占位符。静默丢弃会让用户以为回填成功、实际少填一个值。
 */
import { defaultValueFor } from './paramRules'

/**
 * @param {Array<{name:string, type:string}>} placeholders 当前脚本的占位符（校验通过后才有）
 * @param {Object<string,string>|null} savedParams 用例里存的快照
 * @returns {{values: Object<string,string>, dropped: string[]}}
 *          values 可直接交给 useValidationState 的 setParams；
 *          dropped 是用例里有、但当前脚本已没有的键，调用方应提示用户
 */
export function refillParams(placeholders, savedParams) {
  const list = placeholders ?? []
  const saved = savedParams ?? {}

  const values = {}
  for (const p of list) {
    // 用 hasOwnProperty 而不是 ?? ：用例里存了空串是合法值，不能被当成「没有」而落到默认值
    values[p.name] = Object.prototype.hasOwnProperty.call(saved, p.name)
      ? saved[p.name]
      : defaultValueFor(p.type)
  }

  const known = new Set(list.map((p) => p.name))
  const dropped = Object.keys(saved).filter((key) => !known.has(key))

  return { values, dropped }
}
```

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit
```

预期：新增 9 条全绿，累计 **101 条**（任务 19 后的 92 + 本任务 9）。

- [ ] **Step 7: 写 `api/testcase.js`**

`src/api/testcase.js`

```js
// 测试用例接口（需求 4.3.3）。三个都是普通 JSON 接口，直接复用 post
import { post } from './http'

/** @returns {Promise<Array<{id:number, name:string, params:Object<string,string>}>>} */
export function listTestCases(ruleId) {
  return post('/api/testcase/list', { ruleId })
}

/**
 * @param {{ruleId:number, name:string, params:Object<string,string>}} payload
 * @returns {Promise<{id:number, name:string, params:Object<string,string}>} 新建的用例
 */
export function saveTestCase({ ruleId, name, params }) {
  return post('/api/testcase/save', { ruleId, name, params })
}

export function deleteTestCase(testCaseId) {
  return post('/api/testcase/delete', { testCaseId })
}
```

- [ ] **Step 8: 写 `TestCaseBar.vue`**

`src/components/TestCaseBar.vue`

```vue
<script setup>
/**
 * 测试用例栏（需求 4.3.3）：保存为用例 / 选中回填 / 删除。
 *
 * 插在 ValidationTabs 的「占位符填值」标签页里、ParamsForm 正下方 ——
 * 用例操作的对象就是上面那组输入框，放一起才符合直觉。
 *
 * 交互上刻意用「下拉选中 + [回填] 按钮」而不是「选中即回填」：
 * el-select 的 change 只在值**变化**时触发，选中即回填的话，用户改了填值想
 * 重新回填同一个用例，再点一次是不会有任何反应的（值没变），看起来像坏了。
 * 拆成两步反而没有这个陷阱，[回填] 按钮点几次都生效。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteTestCase, listTestCases, saveTestCase } from '../api/testcase'
import { reportError } from '../api/http'
import { refillParams } from '../composables/testCaseParams'

const props = defineProps({
  ruleId: { type: Number, required: true },
  /** 当前脚本的占位符，校验通过后才有值 */
  placeholders: { type: Array, default: () => [] },
  /** 上方表单里当前的填值，保存用例时拍快照 */
  params: { type: Object, default: () => ({}) },
})
const emit = defineEmits(['update:params'])

const cases = ref([])
const loading = ref(false)
const saving = ref(false)
const selected = ref(null)

/** 没有占位符时保存用例没意义：回填不出任何值，纯噪音，禁用并说明原因 */
const noPlaceholder = computed(() => props.placeholders.length === 0)

onMounted(load)

// 切换规则时重拉列表；本组件随 ValidationTabs 常驻，不 watch 会看到上一条规则的用例
watch(() => props.ruleId, async () => {
  selected.value = null
  await load()
})

async function load() {
  // ValidationTabs 的 ruleId 有默认值 0（父组件没传时的兜底），别为它白发一次请求
  if (!props.ruleId) return
  loading.value = true
  try {
    cases.value = (await listTestCases(props.ruleId)) ?? []
  } catch (e) {
    reportError(e)
  } finally {
    loading.value = false
  }
}

async function handleSave() {
  if (saving.value || noPlaceholder.value) return

  let name
  try {
    const res = await ElMessageBox.prompt(
      '把当前这组填值存成用例，之后可以一键回填重跑。',
      '保存为用例',
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputPlaceholder: '给用例起个名字，如「VIP 成年人」',
        inputValidator: (v) => (v && v.trim() ? true : '用例名称不能为空'),
      },
    )
    name = res.value
  } catch {
    return                        // 用户取消
  }

  saving.value = true
  try {
    // 拍快照：展开一份，否则存的是响应式对象的引用，用户接着改输入框会连带改掉它
    const created = await saveTestCase({
      ruleId: props.ruleId,
      name: name.trim(),
      params: { ...props.params },
    })
    cases.value = [...cases.value, created]
    selected.value = created.id
    ElMessage.success(`用例「${created.name}」已保存`)
  } catch (e) {
    reportError(e)                // 同名被拒时后端给的是可读中文，这里直接弹出
  } finally {
    saving.value = false
  }
}

function handleRefill() {
  const target = cases.value.find((c) => c.id === selected.value)
  if (!target) return

  const { values, dropped } = refillParams(props.placeholders, target.params)
  emit('update:params', values)

  if (dropped.length) {
    // 脚本漂移了：用例里的这些占位符已经不存在。必须说，否则用户以为回填完整了
    ElMessage.warning(`已回填「${target.name}」，但 ${dropped.join('、')} 已不在当前脚本中，已跳过`)
  } else {
    ElMessage.success(`已回填「${target.name}」，点 [运行] 即可重跑`)
  }
}

async function handleDelete() {
  const target = cases.value.find((c) => c.id === selected.value)
  if (!target) return

  try {
    await ElMessageBox.confirm(
      `确定删除用例「${target.name}」吗？删除后无法恢复。`,
      '删除用例',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  try {
    await deleteTestCase(target.id)
    cases.value = cases.value.filter((c) => c.id !== target.id)
    selected.value = null
    ElMessage.success('用例已删除')
  } catch (e) {
    reportError(e)
  }
}
</script>

<template>
  <div class="case-bar">
    <div class="row">
      <span class="label">测试用例</span>

      <el-select
        v-model="selected"
        class="select"
        size="small"
        placeholder="选择用例"
        :loading="loading"
        :disabled="loading || !cases.length"
        clearable
        no-data-text="还没保存过用例"
      >
        <el-option v-for="c in cases" :key="c.id" :label="c.name" :value="c.id">
          <span class="opt-name">{{ c.name }}</span>
          <span class="opt-meta">{{ Object.keys(c.params || {}).length }} 项填值</span>
        </el-option>
      </el-select>

      <el-button size="small" :disabled="!selected" @click="handleRefill">回填</el-button>
      <el-button size="small" :disabled="!selected" @click="handleDelete">删除</el-button>

      <span class="spacer"></span>

      <el-tooltip
        :disabled="!noPlaceholder"
        content="当前脚本没有占位符，没有可保存的填值"
        placement="top"
      >
        <!-- 外面包一层 span：disabled 的按钮不派发鼠标事件，tooltip 直接挂上去不会显示 -->
        <span>
          <el-button
            size="small"
            type="primary"
            plain
            :loading="saving"
            :disabled="noPlaceholder"
            @click="handleSave"
          >保存为用例</el-button>
        </span>
      </el-tooltip>
    </div>

    <!-- 两个提示的顺序要紧：没占位符时 [保存为用例] 是禁用的，
         若还提示「填好值后点保存」就自相矛盾了，所以先判 noPlaceholder -->
    <div class="hint is-warn" v-if="noPlaceholder">
      当前脚本没有占位符，用例功能不可用（先点校验，在「校验结果」里确认占位符）。
    </div>
    <div class="hint" v-else-if="!cases.length && !loading">
      还没有用例。填好上面的值后点 [保存为用例]，之后可以一键回填重跑。
    </div>
  </div>
</template>

<style scoped>
.case-bar { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--border-light); }

.row { display: flex; align-items: center; gap: 8px; }
.label { font-size: 12px; color: var(--text-muted); flex-shrink: 0; }
.select { width: 200px; }
.spacer { flex: 1; }

.opt-name { float: left; }
.opt-meta { float: right; font-size: 11px; color: var(--text-muted); margin-left: 16px; }

.hint { margin-top: 6px; font-size: 11px; color: var(--text-muted); line-height: 1.6; }
.hint.is-warn { color: var(--danger); }

.case-bar :deep(.el-button) { border-radius: 14px; }
.case-bar :deep(.el-select__wrapper) { border-radius: 12px; }
</style>
```

- [ ] **Step 9: 接进 `ValidationTabs.vue` 与 `RuleWorkbenchView.vue`**

**1）`ValidationTabs.vue`** —— 补 import：

```js
import TestCaseBar from './TestCaseBar.vue'
```

props 里加一条（任务 14 定义的 props 块末尾追加）：

```js
  /** 当前规则 ID，测试用例栏要拿它去查/存用例 */
  ruleId: { type: Number, default: 0 },
```

把 `testcase-slot` 那行注释整段替换掉。原来（任务 14 写的）是：

```vue
        <!-- testcase-slot：任务 20 放 <TestCaseBar>（保存为用例 / 回填 / 删除） -->
```

换成：

```vue
        <TestCaseBar
          :rule-id="ruleId"
          :placeholders="placeholders"
          :params="params"
          @update:params="(v) => emit('update:params', v)"
        />
```

> `defineEmits` 里已经有 `'update:params'`（任务 14 预留的），不用改。
> 回填走的是与手工编辑**同一条**通道，所以 `useValidationState` 的 `params` 是唯一真源，
> 不会出现「用例栏自己存了一份填值、和上面表单不一致」的情况。

**2）`RuleWorkbenchView.vue`** —— 给 `ValidationTabs` 补一个 prop：

```vue
          <ValidationTabs
            :rule-id="ruleId"
            :phase="v.phase.value"
```

（其余属性与事件保持任务 14 的样子不动。）

- [ ] **Step 10: 构建与单测**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run test:unit && npm run build
```

预期：**101 个单测通过**，构建成功。

常见问题：
- `refillParams is not defined` → Step 6 的文件名或路径不对，必须在 `src/composables/` 下
- `defaultValueFor is not exported` → 它是任务 14 `paramRules.js` 的导出，确认那个文件没被改坏
- 构建报 `TestCaseBar` 未注册 → Step 9 的 import 漏了

- [ ] **Step 11: 手工验收**

前后端都起起来（都要 `source ../tools/env.sh`）。先用 curl 打通接口（`<RID>` 换成真实规则 ID）：

```bash
# ① 存一个用例
curl -s -X POST http://localhost:8080/api/testcase/save \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":<RID>,"name":"VIP 成年人","params":{"age":"28","level":"vip"}}'

# ② 同名再存一次 —— 必须被拒且是中文提示
curl -s -X POST http://localhost:8080/api/testcase/save \
  -H 'Content-Type: application/json' \
  -d '{"ruleId":<RID>,"name":"VIP 成年人","params":{"age":"30","level":"normal"}}'

# ③ 列表
curl -s -X POST http://localhost:8080/api/testcase/list \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}'

# ④ 名称超长（65 个字）—— 必须是「用例名称太长」，不能是 Data too long
curl -s -X POST http://localhost:8080/api/testcase/save \
  -H 'Content-Type: application/json' \
  -d "{\"ruleId\":<RID>,\"name\":\"$(printf '长%.0s' {1..65})\",\"params\":{}}"

# ⑤ 规则不存在
curl -s -X POST http://localhost:8080/api/testcase/save \
  -H 'Content-Type: application/json' -d '{"ruleId":999999,"name":"孤儿","params":{}}'

# ⑥ 删除（<CID> 换成 ① 返回的 id），再删一次必须报「用例不存在或已被删除」
curl -s -X POST http://localhost:8080/api/testcase/delete \
  -H 'Content-Type: application/json' -d '{"testCaseId":<CID>}'
```

核对：②④⑤⑥ 的 `code` 都非 0，`message` 都是可读中文，**没有一条露出异常堆栈或英文异常类名**（API 规范硬要求）。

然后进浏览器，任意规则的工作台：

1. 点 [校验] 通过后切到「占位符填值」标签页 → 表单下方出现「测试用例」栏，初始提示「还没有用例…」
2. 填好值（如 `age=28`、`level=vip`）→ 点 [保存为用例] → 弹出命名输入框 → 输入「VIP 成年人」→ 保存 → 提示成功，下拉里出现该用例且**已自动选中**
3. 立刻再点 [保存为用例]、输入同一个名字 → 弹出「已存在同名用例「VIP 成年人」，请换个名字」，**不是** 500 也不是英文异常
4. 把输入框的值改掉（如 `age=1`）→ 下拉选中「VIP 成年人」→ 点 [回填] → 输入框恢复成 `28` / `vip`，提示「点 [运行] 即可重跑」
5. **再点一次 [回填]** → 仍然生效（这条专门验「选中即回填」那个陷阱没有踩：改了值之后重复回填同一个用例必须还能用）
6. 点 [运行] → 运行结果与第一次跑这个用例时一致（验「重跑」语义）
7. 改脚本：删掉 `level` 那行占位符 → 重新校验通过 → 选中「VIP 成年人」→ 点 [回填] → 提示里**明确说出** `level` 已不在当前脚本中，且 `age` 正常回填了
8. 点 [删除] → 二次确认 → 确定后下拉里该用例消失，选中态清空
9. 把脚本改成一个**没有占位符**的版本（如 `return 1`）→ 重新校验 → [保存为用例] 按钮**禁用**，鼠标悬停有 tooltip 说明原因，下方出现红色提示
10. 用例很多时（存 5 个以上）下拉里每项右侧显示「N 项填值」，且下拉不错位
11. 回到列表页删掉这条规则 → 重新建一条同名规则 → 用例下拉是**空的**（验规则删除的级联，任务 5 已实现，这里只是确认没被破坏）
12. 切换到另一条规则 → 用例栏显示的是**那条规则**的用例，不串（验 `watch(ruleId)`）

第 5、7 条是本任务的核心，务必亲手试。

- [ ] **Step 12: 用 MySQL MCP 查库核对**

Global Constraints 要求联调必须自己查库，不能只看接口返回的 JSON。

**① 确认表结构与预期一致**

```
CallMcpTool(server_name="mysql", tool_name="get_table_structure", arguments={"tables": "test_case"})
```

核对：`params_json` 是 `text` 且 NOT NULL、`name` 是 `varchar(128)`、`rule_id` NOT NULL、`created_at` NOT NULL。

**② 确认用例真的落库、JSON 没被转义坏**

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, rule_id, name, params_json, CHAR_LENGTH(params_json) AS len, created_at FROM test_case WHERE rule_id = ? ORDER BY id ASC", "params": [<RID>]})
```

核对：
- `params_json` 是**紧凑的合法 JSON**（如 `{"age":"28","level":"vip"}`），没有被双重转义成 `"{\"age\":...}"`
- **键顺序与保存时一致**（验 `LinkedHashMap` 真的保住了顺序）
- 中文用例名没有乱码（若出现 `???` 说明连接字符集不是 utf8mb4，回任务 1 查 JDBC URL）
- `name` 存的是 **trim 过**的值（保存时输入了首尾空格的话）
- `created_at` 是本机时间，没有 8 小时偏移

**③ 确认删除真的执行了（不是软删）**

删掉一个用例后：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT COUNT(*) AS remain FROM test_case WHERE rule_id = ?", "params": [<RID>]})
```

预期 `remain` 比删除前少 1。表里没有 `deleted` 之类的标志位，`DELETE` 就是物理删除。

**④ 确认规则删除的级联生效**

在列表页删掉一条带用例的规则后：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT (SELECT COUNT(*) FROM rule WHERE id = ?) AS rule_remain, (SELECT COUNT(*) FROM test_case WHERE rule_id = ?) AS case_remain, (SELECT COUNT(*) FROM conversation WHERE rule_id = ?) AS conv_remain", "params": [<RID>, <RID>, <RID>]})
```

预期三个都是 0 —— 规则、用例、会话一并清掉（需求第 3 节「级联删除其会话、消息、测试用例」）。

> 注意：`mvn test` 的数据带 `@Transactional` 会回滚，**MCP 查不到**。这里查的必须是运行中的应用通过 curl / 浏览器造出来的数据。

MCP 没加载上（`CallMcpTool` 报找不到 `mysql` 服务）就用 CLI 兜底：

```bash
mysql -h127.0.0.1 -P3306 -u"$DB_USER" -p"$DB_PASS" script_workbench \
  -e "SELECT id, rule_id, name, params_json FROM test_case ORDER BY id ASC;"
```

- [ ] **Step 13: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add script_back script_front
git commit -m "feat: 测试用例（保存/回填重跑/删除，params 与 JSON 互转）"
```

> **执行偏差记录（实际提交 `3d28018`，9 文件 +775 -1）**
> 1. **后端全量 221 通过，非计划预估的 234**：新增 `TestCaseServiceTest` 16 条全绿、旧测试无一破坏；差异同任务 16–19，源于计划对基线估算偏高 13（计划写「任务 18 后的 218」，实际任务 18 收尾是 205，205+16=221）。
> 2. **前端全量 101 通过，与计划 Step 10 一致**：任务 19 后的 92 + 本任务 `testCaseParams.spec.js` 9 = 101，无需修正。
> 3. **`testcase.js` 与 `TestCaseBar.vue` 不写单测，理由（dev-conventions 硬要求）**：`testcase.js` 三个函数都是一行转发到 `post`、无任何分支（与 dev-conventions 明确点名“不测”的 `script.js` 同类）；`TestCaseBar.vue` 是装配型组件（`el-select` + 按钮 + `ElMessageBox` 胶水），其唯一有算法风险的部分——回填取值——已抽成纯函数 `testCaseParams.js` 并被 9 条单测覆盖（完全覆盖语义、dropped 报告、默认值、键顺序、纯函数不改入参）。二者靠 `npm run build` + Step 11 curl + Step 12 MCP 把关，不引 jsdom。
> 4. **TDD 顺序无需调整**：计划已是正确顺序——后端 Step 1（`TestCaseServiceTest`）→ Step 2/3（Service/Controller）→ Step 4（跑）；前端 Step 5（`testCaseParams.spec.js`）→ Step 6（实现）。两个失败测试都各自单独跑过、亲眼见红（后端：编译失败 `找不到符号 类 TestCaseService`；前端：`Failed to load url ../testCaseParams`）再实现，实现后各自单独跑绿（16、9）。
> 5. **Step 11 curl ①–⑥ + Step 12 MCP ①–④ 已无头跑完（全过）；仅 12 项浏览器交互验收延后并入 Task 21**（browser-use 不可达，同任务 14/15/16/18/19）。铁证：①save code=0 id=54 params 完整②同名 code=1000「已存在同名用例」③list 回显④65 字名「用例名称太长，请控制在 64 字以内」（非 Data too long）⑤规则 999999「规则不存在或已被删除」⑥删除+重删「用例不存在或已被删除」——全部可读中文、无堆栈、无英文异常类名。MCP：表结构 `params_json` text NOT NULL / `name` varchar NOT NULL / `rule_id` bigint NOT NULL / `created_at` datetime NOT NULL；id=55 名「顺序测试」（已 trim）、`params_json`={"zebra":"1","apple":"2","mango":"3"} 紧凑合法未双重转义、键顺序保留（LinkedHashMap 生效）；id=56 中文未乱码；created_at 本机时间无 8h 偏移；删除为物理删（remain 2→1）；级联删规则 217 → rule/case/conv 均 0、库回到 total_rules=3、orphan_cases=0。
> 6. **观察（本任务未改，超出范围）**：`GET /api/health` 会落到 `GlobalExceptionHandler` 的兵底分支返回 code=1999「服务内部错误」（`HttpRequestMethodNotSupportedException` 未被专门映射）。因 API 规范硬性要求全部 POST、健康检查也是 POST-only，客户端不应发 GET，故不影响正常链路；若要更友好可后续把 405 单独映射成「请求方法不支持」（属 Task 2 GlobalExceptionHandler 范畴，不在本任务改）。

---

## Task 21: 端到端联调 + 浏览器验收

**本任务不写新功能代码。** 把前 20 个任务串成一条完整链路，逐条过需求文档第 7 节的 **22 项验收清单**与第 6 节的 **5 项非功能要求**，用 MySQL MCP 查库核对，截图存证，产出 `docs/superpowers/verification/2026-09-02-验收记录.md`。发现缺陷就地修，修完回对应任务补一条测试再回来重验。

> 下面 Step 2–5 的表格一共是 **23 个检查点**，比需求的 22 项多一个：需求「占位符表单按类型渲染（数字框/布尔下拉/文本框），格式错误前端拦截」一句话里含两个**可独立失败**的行为（控件类型渲染错、与校验没拦住），拆成检查点 13 与 14 分开验。写验收记录时这两条合起来对应需求的一项。

**为什么要单独立一个任务，而不是把验收散在各任务里：** 各任务的手工验收只覆盖自己那一块，**跨任务的接缝才是 bug 高发区** —— 校验→填值→运行→存用例→改脚本→回填、对话里应用脚本→徽标变 stale→重新校验，这些链路没有任何一个单独的任务能验到。需求第 7 节的清单是按用户视角组织的，只有整条跑起来才算数。

**本任务有两条硬约束（来自 Global Constraints）：**

1. **必须自己用 MySQL MCP 查库核对**，不能只看接口返回的 JSON。接口说写成功不等于真的写进去了 —— 字段截断、字符集乱码、时区偏移、级联漏删，这四类问题只有查库才看得见。Step 7 是专门的查库环节。
2. **AI 相关验收必须真调大模型**。前面所有单测都是 `app.ai.enabled=false` 走降级分支，只有这里用真实 Key 跑通，才算验证了任务 16/18 的 Prompt 拼装、`${}` 与 `{var}` 的模板冲突规避、SSE 聚合。

**Files:**
- Create: `docs/superpowers/verification/2026-09-02-验收记录.md`
- Create: `docs/superpowers/verification/screenshots/*.png`（约 12 张）
- Modify: 发现缺陷时改对应代码，不新增源文件

**Interfaces:**
- Consumes: 全部 13 个接口（技术方案第 5 节）、MySQL MCP（server 名 `mysql`）、Browser 子代理（截图）
- Produces: 一份可追溯的验收记录，22 + 5 项逐条有结论、有证据

---

- [ ] **Step 1: 起服务并确认真实 AI Key 已注入**

**终端 A（后端）：**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q spring-boot:run
```

**终端 B（前端）：**

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
npm run dev
```

**终端 C（检查）：**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
source tools/env.sh

# ① 后端健康
curl -s -X POST http://localhost:8080/api/health -H 'Content-Type: application/json' -d '{}'
echo
# ② 前端可达（Vite 代理是否生效看 ③）
curl -s -o /dev/null -w 'front http_code=%{http_code}\n' http://localhost:5173/
# ③ 经 Vite 代理打后端（验 vite.config.js 的 proxy 真的转发了）
curl -s -X POST http://localhost:5173/api/health -H 'Content-Type: application/json' -d '{}'
echo
# ④ AI Key 是否注入 —— 只看长度，绝不打印 Key 本身
echo "AI_ENABLED=$AI_ENABLED  KEY_LEN=${#AI_DASHSCOPE_API_KEY}  MODEL=$AI_CHAT_MODEL"
```

预期：① 与 ③ 都返回 `code:0`；② 是 `200`；④ `AI_ENABLED=true`、`KEY_LEN` 大于 20、`MODEL=qwen-plus`。

> ④ 的 `KEY_LEN` 为 0 就说明 `tools/local-secret.env` 没被加载，**后面所有 AI 验收都会走降级分支、变成假通过**。
> 这时先回任务 1 Step 5 修好再继续，不要往下走。
> `AI_ENABLED=false` 同理：降级分支的验收在任务 16 Step 8 已经做过了，本任务要验的是真实调用。

- [ ] **Step 2: 规则列表页（验收清单第 1 组，5 项）**

浏览器打开 `http://localhost:5173/`。逐项做，每项记下「通过 / 不通过 + 现象」，需要截图的按下面的名字存到 `docs/superpowers/verification/screenshots/`。

| # | 操作 | 预期 | 截图 |
|---|---|---|---|
| 1 | 点 [新建规则]，填名称「验收-折扣判定」+ 描述「端到端验收用」，确定 | 创建成功并**自动跳转到工作台**，编辑器里已有默认脚本（含 `${age}`、`${level}`） | `01-新建规则.png` |
| 2 | 回列表页 | 该规则出现在列表里，**名称、描述、更新时间三列都有值**，更新时间不是空也不是 1970 | `02-列表页.png` |
| 3 | 再建 12 条规则（名称随意，如「批量-01」…「批量-12」） | 列表出现**分页控件**，翻页正常，总数正确 | `03-分页.png` |
| 4 | 搜索框输入「批量」 | 只列出名称含「批量」的，「验收-折扣判定」不在结果里；清空搜索后全部回来 | — |
| 5 | 对「批量-12」点 [编辑]，改名 + 改描述，保存 | 列表立刻反映新值；再点 [删除] → **有二次确认弹窗** → 确认后该行消失 | `04-删除确认.png` |

第 5 项的级联删除留到 Step 7 查库核对（这里只确认 UI 行为）。

**视觉顺带过一眼**（需求附录决策 #9，风格三）：顶栏是蓝紫渐变（`#5b5fc7 → #8b5cf6`）、面板大圆角、阴影柔和；列表页与工作台页视觉统一。与参考稿 `docs/ui-mockups/style3-gradient.html` 并排比一下，明显跑偏就记一条缺陷。

- [ ] **Step 3: 工作台 — 编辑与校验（验收清单第 2 组，7 项）**

进「验收-折扣判定」的工作台。

| # | 操作 | 预期 | 截图 |
|---|---|---|---|
| 6 | 看编辑器 | Groovy **语法高亮**生效（关键字、字符串、注释不同色）、**有行号**；按 ⌘S 能保存并提示「已保存」 | `05-编辑器.png` |
| 7 | 把脚本改成 `int a = 1\nreturn (a + 1`（少个右括号），点 [校验] | 「校验结果」页显示**行号 + 中文原因**，编辑器对应行**标红**，运行按钮**锁定** | `06-语法错误.png` |
| 8 | 改回默认脚本（含 `${age}`、`${level}`），点 [校验] | 校验中显示加载状态；完成后列出**全部占位符及类型**（`age` → int、`level` → String） | `07-占位符.png` |
| 9 | 观察校验期间 | 顶栏状态是「校验中…（含 AI 审查，可能要十几秒）」，运行按钮**全程锁定**；AI 审查卡片区域有加载态 | — |
| 10 | 等校验完成 | 出现 **AI 审查卡片**，内容是中文审查意见（真调了 qwen-plus）；若模型给了修改脚本，卡片里有 [应用到编辑器] | `08-AI审查.png` |
| 11 | 若有 [应用到编辑器]，点它 | 弹确认框，显示**变化量**（行数/字符数增减）与来源「AI 审查」；确认后编辑器内容被替换，代码块里的 `${}` 占位符**原样保留** | `09-应用脚本.png` |
| 12 | 校验通过后，在编辑器里**加一个空格**再删掉以外的任意真实改动（如加一行注释） | 状态徽标立刻变「脚本已修改，校验结果已作废」，**运行按钮重新锁定**（交互规则 #2） | — |

**第 8 项要额外盯一个隐藏坑**（任务 16 的核心决策）：审查意见里**不应该**出现「变量 age 未定义」「`${age}` 是非法语法」这类噪音。如果出现了，说明 `CR_SYSTEM_PROMPT` 没交代清楚占位符语法，或者 Prompt 走了 fluent API 被 `{age}` 模板变量吃掉了 —— 回任务 16 Step 2 查 `callReview` 是不是还在用 `new Prompt(new SystemMessage(...), new UserMessage(...))`。

**第 12 项注意**：只加空格再删掉，内容没变，**不应该**作废（`SCRIPT_CHANGED` 会比较内容）。这条也顺手验一下，是任务 14 的 reducer 语义。

- [ ] **Step 4: 工作台 — 运行（验收清单第 3 组，5 项 / 6 个检查点）**

先把脚本恢复成默认版本并校验通过，切到「占位符填值」标签页。

| # | 操作 | 预期 | 截图 |
|---|---|---|---|
| 13 | 看表单控件 | `age`（int）是**数字输入框**、`level`（String）是**文本框**；若脚本里有 boolean 占位符则是 **true/false 下拉** | `10-填值表单.png` |
| 14 | `age` 填 `abc`，点 [运行] | **前端拦截**，提示中文（含占位符名，如「age 必须是整数」），请求**没有发出去**（开 DevTools Network 确认无 `/api/rule/run`） | — |
| 15 | `age` 填 `28`、`level` 填 `vip`，点 [运行] | 「运行结果」页展示**返回值**，与脚本逻辑一致 | `11-运行结果.png` |
| 16 | 脚本换成死循环 `while (true) { }`，校验后运行 | **约 5 秒后**中断并提示超时，不是永久转圈，服务不挂 | — |
| 17 | 脚本换成 `def f = new File("/etc/passwd")\nreturn f.text`，点 [校验] | **校验阶段就被拦**，给可读中文提示（禁访问文件），运行按钮**保持锁定** | `12-危险拦截.png` |

**第 17 项的预期与需求措辞有差异，说清楚：** 需求 161 行写「危险脚本（如访问文件）被拦截并给出可读提示」，没说在哪个阶段拦。实际实现是**编译期 AST 黑名单**，而校验与运行共用同一个带沙箱的 `CompilerConfiguration`（任务 10 的设计：「校验时就要按沙箱规则编译，否则用户会先看到语法通过、点运行才被拦截，体验割裂」）。所以危险脚本在**校验**就失败，根本到不了运行。**这是符合设计意图的，判为通过**，但要在验收记录里写明这个差异。

其余危险类型顺手各试一个，都应在校验阶段被拦且提示是中文：

```
System.exit(0)
Runtime.getRuntime().exec("ls")
new URL("http://x.com").text
new Thread({ println 1 }).start()
```

**再验一条已知的过度拦截**（任务 10 记录的取舍）：脚本写 `def File = 1\nreturn File`，变量名首字母大写撞上黑名单，会被误拦。预期是**给出可读中文提示**而不是崩溃或英文异常 —— 这是故意的取舍，判为通过，记录即可。

**第 18 项：测试用例串联**（验收清单 162 行，任务 20 已单独验过，这里只验接缝）

1. 填值 `age=28`、`level=vip` → [保存为用例] 命名「VIP 成年人」
2. 把 `age` 改成 `1` → 下拉选中用例 → [回填] → 输入框恢复 `28`/`vip`
3. **再点一次 [回填]** → 仍然生效（验没踩「选中即回填」的 change 不触发陷阱）
4. 点 [运行] → 结果与第 1 步之前那次一致
5. [删除] 用例 → 二次确认 → 下拉里消失

- [ ] **Step 5: 工作台 — AI 对话（验收清单第 4 组，5 项）**

右侧对话面板。

| # | 操作 | 预期 | 截图 |
|---|---|---|---|
| 19 | 刷新页面 | **历史消息自动回显**，顺序正确（任务 18 的 `/api/chat/history` 读 MySQL，不是读内存窗口） | `13-对话回显.png` |
| 20 | 编辑器里放一段有问题的脚本，提问「这个脚本有什么风险」 | **流式打字机效果**（文字逐段出现、末尾光标闪烁），期间输入框与发送按钮禁用；回答**针对这段脚本**（验自动带上下文） | `14-流式对话.png` |
| 21 | 接着问「把刚才说的第一点改掉，给我完整脚本」 | 回答**接得上前文**（验多轮记忆）；回复里的 ```` ```groovy ```` 代码块有 [应用到编辑器]，点它 → 确认框来源显示「AI 对话」→ 应用后编辑器被替换、状态徽标变 stale | `15-代码块应用.png` |
| 22 | 点 [清空对话] → 确定 | 消息清空显示空状态；再提问「我刚才问了什么」→ 模型**不知道**（验内存记忆也清了，不只是清了 UI） | — |
| 23 | 流式进行中切到列表页、再进另一条规则 | 不报错、**不串消息**（验 `watch(ruleId)` 的 abort 与重载） | — |

**第 20 项要盯 SSE 的两个真实坑**（任务 19 的核心）：
- 回答里若含中文与代码，**不能出现乱码或半个汉字**（`TextDecoder` 必须复用同一实例）
- 流式过程中代码块**不能反复重排闪烁**（未闭合的 ```` ``` ```` 必须直接当代码块渲染）

**第 21 项还要盯 `${}` 存活**：应用后的脚本里，占位符必须还是 `${age}` 这个样子。若变成了 `age` 或 `{age}`，说明后端把模型回复过了 Spring AI 的 `PromptTemplate`（`{var}` 语法冲突），回任务 16/18 查 Prompt 构造方式。

- [ ] **Step 6: 非功能要求专项（需求第 6 节，5 项）**

**① 服务稳定（#1）+ 执行限时（#2）：连续 12 次死循环，验线程真的被归还**

这条**不能只看耗时**。线程池是 core=2 / max=8 / queue=50 / `AbortPolicy`，若 `@ThreadInterrupt` 没生效导致线程泄漏，第 3 次起的任务会进队列排队、`future.get(5s)` 一样超时返回 —— **耗时看起来完全正常，但脚本根本没跑，池子正在被占满**。所以必须数线程。

```bash
cd /Users/zhoudingyan/workspace/zdy_test
source tools/env.sh

for i in $(seq 1 12); do
  curl -s -m 30 -o /tmp/loop-$i.json -w "第 ${i} 次：耗时 %{time_total}s  http=%{http_code}\n" \
    -X POST http://localhost:8080/api/rule/run \
    -H 'Content-Type: application/json' \
    -d '{"scriptContent":"while (true) { }","params":{}}'
done

echo "--- 最后一次的返回 ---"
cat /tmp/loop-12.json; echo

# 关键：数一下还活着的 groovy 执行线程
PID=$(jps -l | grep -i "rulescript\|ScriptBackApplication" | awk '{print $1}' | head -1)
echo "后端 PID=$PID"
echo "存活的 groovy-run 线程数 = $(jstack "$PID" | grep -c '\"groovy-run-')"
```

预期：
- 12 次耗时都稳定在 **5–7 秒**（5 秒超时 + 编译开销），**不逐次变长**，没有一次撞到 `-m 30` 上限
- 每次都返回可读的超时中文提示
- **`groovy-run` 线程数 ≤ 2**（等于核心线程数，用完归还、空闲待命）

若线程数等于 12（或随次数单调增长）→ `@ThreadInterrupt` 变换没挂上，`future.cancel(true)` 杀不掉纯 CPU 循环，回任务 10/11 查 `GroovySandboxConfig` 与 `ScriptExecutorConfig`。这是任务 11 专门写过测试的链路，此处是运行态复核。

> `jps` / `jstack` 来自 JDK 17（任务 1 装的），`tools/env.sh` 已把 `$JAVA_HOME/bin` 拼进 PATH。
> 若 `jps` 找不到 PID，用 `ps aux | grep -i spring | grep -v grep` 兜底取 PID。

**② 危险操作拦截（#3）**：Step 4 第 17 项已覆盖文件/进程/网络/线程四类，此处只补一条**运行期**兜底 —— 编译期黑名单按名字匹配，挡不住运行时拼类名再反射。确认这类脚本即便绕过编译期，也会被 5 秒超时兜住而不是把服务搞挂：

```bash
curl -s -m 30 -X POST http://localhost:8080/api/rule/run \
  -H 'Content-Type: application/json' \
  -d '{"scriptContent":"def c = \"java.lang.Run\" + \"time\"\nreturn c","params":{}}'
echo
```

预期：正常返回拼接出的字符串（这段本身无害），服务不受影响。技术方案第 6 节已认账：单用户内部工具，静态黑名单 + 5 秒超时兜底，风险可接受 —— 这里只是确认没有意外崩溃。

**③ 错误可读（#4）：批量扫描所有错误响应里有没有堆栈**

这条值得自动化，手工点容易漏。下面这段跑一批**必然出错**的请求，检查返回体里没有 Java 异常类名、没有堆栈行、没有英文异常：

```bash
cd /Users/zhoudingyan/workspace/zdy_test
source tools/env.sh

check() {
  local name="$1" path="$2" body="$3"
  local resp
  resp=$(curl -s -m 20 -X POST "http://localhost:8080$path" \
           -H 'Content-Type: application/json' -d "$body")
  # 响应是**单行** JSON，堆栈会被转义成字面的 \n\t，所以不能用 ^ 锚定行首（那样永远匹配不上）
  if printf '%s' "$resp" | grep -qE 'Exception|Caused by|at (com|java|org|jdk)\.|\bjava\.lang\b|\bnull\b'; then
    echo "❌ [$name] 露出了堆栈或异常类名："
    printf '%s\n' "$resp" | head -c 400; echo
  else
    echo "✅ [$name] $(printf '%s' "$resp" | head -c 150)"
  fi
}

check "空名称建规则"   /api/rule/create      '{"name":"   ","description":null}'
check "规则不存在"     /api/rule/detail      '{"ruleId":999999}'
check "改不存在的规则" /api/rule/update      '{"ruleId":999999,"name":"x"}'
check "删不存在的规则" /api/rule/delete      '{"ruleId":999999}'
check "语法错误校验"   /api/rule/validate    '{"scriptContent":"int a = \nreturn a"}'
check "危险脚本校验"   /api/rule/validate    '{"scriptContent":"System.exit(0)"}'
check "缺参数运行"     /api/rule/run         '{"scriptContent":"int a = ${age}\nreturn a","params":{}}'
check "参数类型错"     /api/rule/run         '{"scriptContent":"int a = ${age}\nreturn a","params":{"age":"abc"}}'
check "会话不存在"     /api/chat/history     '{"ruleId":999999}'
check "用例的规则不存在" /api/testcase/save    '{"ruleId":999999,"name":"x","params":{}}'
check "删不存在的用例" /api/testcase/delete  '{"testCaseId":999999}'
check "缺字段"         /api/rule/detail      '{}'

echo "--- 非法 JSON（验 HttpMessageNotReadableException 也被接住）---"
curl -s -m 20 -X POST http://localhost:8080/api/rule/detail \
  -H 'Content-Type: application/json' -d 'this is not json'; echo

echo "--- 空请求体 ---"
curl -s -m 20 -X POST http://localhost:8080/api/rule/detail \
  -H 'Content-Type: application/json' -d ''; echo
```

预期：**全部 ✅**，最后两条也返回 `{"code":非0,"message":"可读中文",...}`。任何一条 ❌ 都是 API 规范的硬违反（「严禁返回原始异常堆栈」），必须回任务 2 的 `GlobalExceptionHandler` 补对应的异常处理分支。

> `\bnull\b` 这条会有意误伤：若某个 `message` 里正当含英文 `null`（比如「参数不能为 null」），会被标 ❌。
> 看到 ❌ 先人工判断是真露堆栈还是措辞问题；若是后者，把提示改成「不能为空」更地道，顺手就修了。

**④ 对话持久（#5）：重启后端，历史不丢且记忆能回灌**

这条是任务 17 的运行态复核 —— 单测里 `@Transactional` 会回滚，验不到真实重启。

```bash
# 1) 先在浏览器发一轮对话（问「帮我写一个判断成年的脚本」，等回答完），记下回答的关键内容
# 2) 查一下当前库里的消息数（<RID> 换成真实规则 ID）
```

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT COUNT(*) AS msgs FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.rule_id = ?", "params": [<RID>]})
```

```bash
# 3) 终端 A 里 Ctrl+C 停掉后端，等进程退出，再重新起
cd /Users/zhoudingyan/workspace/zdy_test/script_back && source ../tools/env.sh && mvn -q spring-boot:run

# 4) 起来后先确认历史还在
curl -s -X POST http://localhost:8080/api/chat/history \
  -H 'Content-Type: application/json' -d '{"ruleId":<RID>}'; echo
```

然后在浏览器**刷新页面**，接着问：「我刚才让你写的是什么脚本？」

预期：
- 历史消息**完整回显**（条数与第 2 步查库一致）
- 模型**答得上来**刚才写的是判断成年的脚本 —— 这证明 `ChatMemoryService.ensureLoaded` 真的从 MySQL 把历史回灌进了内存窗口。若模型一脸茫然，说明回灌没生效（重启后内存窗口是空的），回任务 17 查 `ensureLoaded` 是否在 `appendUser` **之前**被调用

- [ ] **Step 7: 用 MySQL MCP 做一次全库核对**

Global Constraints 的硬要求。前面各步骤都是看接口返回，这一步只看库里的真相。

**① 四张表都在，字段与实体一致**

```
CallMcpTool(server_name="mysql", tool_name="list_tables", arguments={})
```

预期：`rule`、`conversation`、`message`、`test_case` 四张表都在（`ddl-auto=update` 建的）。

```
CallMcpTool(server_name="mysql", tool_name="get_table_structure",
            arguments={"tables": "rule,conversation,message,test_case"})
```

逐表核对：
- `rule`：`script_content` 是 `text`、`name` NOT NULL、有 `created_at` / `updated_at`
- `conversation`：`rule_id` 有**唯一约束**（一条规则一个会话，需求 4.3.4）
- `message`：`content` 是 `text`（不能是 `varchar(255)`，否则长回复会被截断）、`role` NOT NULL
- `test_case`：`params_json` 是 `text`、`name` 是 `varchar(128)`

> `message.content` 若是 `varchar(255)` 就是个真缺陷：大模型回复动辄上千字，会被静默截断，
> 而接口返回看起来一切正常。这正是「只信接口 JSON」发现不了、必须查库的典型。

**② 占位符与代码块在库里原样存活**

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, name, LEFT(script_content, 120) AS head, script_content LIKE '%${age}%' AS has_ph, CHAR_LENGTH(script_content) AS len FROM rule WHERE id = ?", "params": [<RID>]})
```

预期 `has_ph = 1`（`${age}` 原样在库里，没被任何模板引擎吃掉）、`len` 与编辑器里的字符数一致。

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT role, CHAR_LENGTH(content) AS len, content LIKE '%```%' AS has_fence, LEFT(content, 60) AS head FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.rule_id = ? ORDER BY m.created_at ASC, m.id ASC", "params": [<RID>]})
```

预期：
- `role` 严格 user / assistant **交替**，没有连续两条同角色
- assistant 的 `len` 与页面上看到的回复长度**相当** —— 明显偏小说明存的是分片而非聚合结果（回任务 18 查 `aggregated`）
- 回复里有代码块时 `has_fence = 1`（反引号原样存下来了）

**③ 字符集与时区**

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT NOW() AS db_now, @@character_set_database AS cs_db, @@collation_database AS coll_db"})
```

预期：`db_now` 与本机时间一致（**差 8 小时就是时区没配好**，回任务 1 查 JDBC URL 的 `serverTimezone`）；`cs_db = utf8mb4`。

再抽查中文没乱码：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT id, name, description FROM rule WHERE name LIKE '%验收%' LIMIT 5"})
```

预期中文正常显示。若出现 `???` 或 `æ¥æ”¶`，是连接字符集问题（不是列字符集），查 JDBC URL 的 `characterEncoding=utf8mb4`。

**④ 级联删除真的删干净了**（需求第 3 节 + 验收清单第 5 项）

用 Step 2 第 5 项删掉的那条「批量-12」的规则 ID（删之前先记下来，或用 Step 8 现删一条带会话带用例的规则）：

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT (SELECT COUNT(*) FROM rule WHERE id = ?) AS rule_left, (SELECT COUNT(*) FROM conversation WHERE rule_id = ?) AS conv_left, (SELECT COUNT(*) FROM message m JOIN conversation c ON c.id = m.conversation_id WHERE c.rule_id = ?) AS msg_left, (SELECT COUNT(*) FROM test_case WHERE rule_id = ?) AS case_left", "params": [<DELETED_RID>, <DELETED_RID>, <DELETED_RID>, <DELETED_RID>]})
```

预期：**四个全是 0**。任何一个非 0 都是级联漏删 —— 尤其 `msg_left`：`message` 挂在 `conversation` 下，`RuleService.delete` 必须**先删消息再删会话**，顺序反了会留下孤儿消息（外键没建，DB 不会报错，只有查库能发现）。

**⑤ 会话与规则一对一，没有孤儿**

```
CallMcpTool(server_name="mysql", tool_name="exec_sql",
            arguments={"sql": "SELECT (SELECT COUNT(*) FROM conversation c LEFT JOIN rule r ON r.id = c.rule_id WHERE r.id IS NULL) AS orphan_conv, (SELECT COUNT(*) FROM test_case t LEFT JOIN rule r ON r.id = t.rule_id WHERE r.id IS NULL) AS orphan_case, (SELECT COUNT(*) FROM rule r LEFT JOIN conversation c ON c.rule_id = r.id WHERE c.id IS NULL) AS rule_without_conv"})
```

预期：三个都是 **0**。`rule_without_conv` 非 0 说明建规则时没同时建会话（任务 5 的 `create` 漏了），那条规则进工作台会报「对话会话不存在」。

> 这些 SQL 全是 `SELECT`，走 `exec_sql`。**别拿 `exec_sql` 跑 DELETE 去"清理"** ——
> 要清数据用 CLI，MCP 的写权限是给应用行为核对用的，不是给手工改库用的。
> 另外记住：`mvn test` 的数据带 `@Transactional` 会回滚，**库里查不到**，别拿它验单测结果。

MCP 没加载上（`CallMcpTool` 报找不到 `mysql` 服务）就用 CLI 兜底，逐条把上面的 SQL 换成：

```bash
mysql -h127.0.0.1 -P3306 -u"$DB_USER" -p"$DB_PASS" script_workbench -e "<SQL>"
```

- [ ] **Step 8: 写验收记录**

`docs/superpowers/verification/2026-09-02-验收记录.md`

```markdown
# 脚本规则工作台 —— 端到端验收记录

- 验收日期：2026-09-02
- 验收环境：macOS 13.7.8 / JDK 17 / MySQL 8.0.46 / Node 20.18.1
- 后端：Spring Boot 3.4.5 + spring-ai 1.0.0 + spring-ai-alibaba 1.0.0.3，端口 8080
- 前端：Vue 3.4 + Vite 5 + Element Plus 2.7，端口 5173
- 大模型：qwen-plus（真实调用，非降级分支）
- 单测：后端 234 通过 / 前端 101 通过
- 截图目录：`screenshots/`

## 一、需求第 7 节验收清单（22 项）

### 规则列表页
- [x] 1. 能新建规则（名称 + 描述），创建后自动进入工作台 —— 见 `screenshots/01-新建规则.png`
- [x] 2. 列表展示名称、描述、更新时间，支持分页 —— 见 `screenshots/02-列表页.png`、`03-分页.png`
- [x] 3. 支持按名称模糊搜索
- [x] 4. 能改名、改描述
- [x] 5. 删除有二次确认，删除后其会话、消息、测试用例一并清除 —— 查库核对见 Step 7 ④，四表残留均为 0

（以下按同样格式列完 6–22 项，每项写：结论 + 证据（截图名 / 查库结果 / 命令输出）+ 若有差异写明原因。
注意检查点 13（控件按类型渲染）与 14（格式错误前端拦截）合起来对应需求的一项，记录里写成一条）

## 二、需求第 6 节非功能要求（5 项）
- [x] 1. 服务稳定 —— 连续 12 次死循环后 groovy-run 线程数 = 2，服务正常
- [x] 2. 执行限时 —— 12 次耗时均在 5–7 秒，无逐次变长
- [x] 3. 危险操作拦截 —— 文件/进程/网络/线程四类均在校验阶段被拦，中文提示
- [x] 4. 错误可读 —— 12 条错误请求 + 非法 JSON + 空请求体，无一条露堆栈
- [x] 5. 对话持久 —— 重启后端后历史完整回显，且模型记得前文（MySQL 回灌生效）

## 三、与需求/技术方案的已知差异
| # | 差异 | 原因 | 是否可接受 |
|---|---|---|---|
| 1 | 危险脚本在**校验**阶段被拦，而非运行阶段 | 校验与运行共用带沙箱的 CompilerConfiguration，避免「先说通过、点了才拦」的割裂体验 | 可接受，优于需求 |
| 2 | 变量名首字母大写撞黑名单（如 `def File = 1`）会被误拦 | 黑名单按简单类名匹配，漏拦代价高于误拦 | 可接受，已在任务 10 记录 |
| 3 | （验收中发现的其他差异逐条补） | | |

## 四、验收中发现并修复的缺陷
| # | 现象 | 根因 | 修复 | 补的测试 |
|---|---|---|---|---|
| 1 | | | | |

## 五、遗留问题
（没有就写「无」。有则写清楚现象、影响范围、为什么不在这次修）
```

> 这份记录**要如实写**。验收发现问题是正常且有价值的，把「全部通过」写得干干净净、
> 实际没跑过，比留下几条缺陷记录糟糕得多。第三节的「已知差异」尤其重要 ——
> 它们不是缺陷，但下一个人看到会以为是。

截图用 Browser 子代理或 browser-use MCP 抓，统一存 `docs/superpowers/verification/screenshots/`，文件名按上面表格里的编号命名，便于记录里引用。

- [ ] **Step 9: 缺陷回归**

验收中发现的每个缺陷，按这个流程闭环，**不要只改代码不补测试**：

1. 定位到根因所在的任务（对照计划里的任务边界）
2. 修代码
3. **在对应任务补一条能复现该缺陷的测试**（先确认它在修复前是红的）
4. 跑全量：后端 `mvn -q test`、前端 `npm run test:unit && npm run build`
5. 回本任务重验该项，更新验收记录第一/四节
6. 若缺陷暴露的是**计划本身写错了**（比如某个契约前后不一致），同步改计划文档，别让下一个人再踩

修完缺陷后测试数会变，把 Step 8 记录里的「后端 234 / 前端 101」更新成实际数字。

- [ ] **Step 10: Commit**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
git add docs/superpowers/verification script_back script_front
git status --short
git commit -m "test: 端到端联调与验收记录（需求第 7 节 22 项 + 第 6 节 5 项）"
```

> 截图是二进制文件，会让仓库变大。本项目是内部工具、截图是验收存证，**入库是值得的**。
> 若后续觉得太大，可以只保留有争议项的截图，其余在记录里写文字描述。

---

## Task 22: 收尾 —— 文档同步、README、清理、最终提交

前置：任务 21 验收通过（或遗留问题已在验收记录第五节写清楚）。

**代码写完不等于交付完成。** 有三件事只有到这一步才能做，也最容易被跳过：

1. **技术方案是设计时写的，实现过程中必然偏离。** 不同步，下一个人（包括三个月后的自己）照着方案读代码会处处对不上，比没有文档更糟。本任务把偏离分成两类处理：**会直接误导人的硬错误就地改**（比如方案里写的 `InMemoryChatMemory` 在 Spring AI 1.0.0 GA 已经被移除，照它写根本编译不过），**属于设计演进的集中记在新增的一节里**（比如 MySQL 历史回灌是方案里没有、实现时补的增强）。
2. **README 里的启动方式还是「规划中」的占位**，开头就写着「代码开发中，以下为规划中的启动方式，完成后会更新为真实命令」。用户照着跑不起来。
3. **22 个任务累积下来的占位组件、无用样式、调试残留**要扫一遍。

**Files:**
- Modify: `docs/superpowers/specs/2026-09-01-script-rule-workbench-技术方案.md`（3.3 节就地修正、第 5 节补接口、第 8 节同步、末尾新增第 9 节）
- Modify: `README.md`（怎么跑 / 配置项 / 项目结构）
- Modify: `Day-by-day/day3.md`（只补流水账三节）
- Modify: 清理阶段可能改到 `script_front/src/` 下任意文件

**Interfaces:**
- Consumes: 任务 21 的验收记录、`tools/env.sh` 的变量清单、任务 16/17/18 的实际实现
- Produces: 文档与代码一致的仓库 + 一次干净的最终提交

---

- [ ] **Step 1: 就地修正技术方案 3.3「AI 服务」**

这一节是全篇偏离最大的地方，而且偏离点**会直接导致照抄者写不出能跑的代码**，所以整节替换，不用「差异表」兜。

把 `docs/superpowers/specs/2026-09-01-script-rule-workbench-技术方案.md` 第 86–99 行（`### 3.3 AI 服务` 到 `- 清空：...` 为止）整段换成：

````markdown
### 3.3 AI 服务

`AiService` 基于 Spring AI 1.0.0 的 `ChatClient`（DashScope，模型 `qwen-plus`，配置化）。

**Prompt 构造的硬约束（实现时踩出来的，务必保留）**

- 一律用 `new Prompt(new SystemMessage(...), new UserMessage(...))`，**不用** fluent 的 `.system(text)` / `.user(script)`。
- 原因：Spring AI 的 `PromptTemplate` 用 `{var}` 语法，而 Groovy 脚本里全是 `${age}` —— 其中 `{age}` 会被模板引擎当成待替换变量解析掉，脚本传到模型手里时已经残缺。这个坑会让「AI 审查」和「AI 对话」两个功能**同时静默失效**：不报错、有回复，只是回复驴唇不对马嘴。
- 同理，系统提示词里举例用的 `${变量名}` 也受这条保护。

**同步 CR（校验用）**

- 提示词除审查要点外，**必须先交代 `${变量名}` 是占位符而不是未定义变量**，否则模型每次都报「变量 age 未定义」，审查意见全是噪音。
- 输出要求：中文、最多 5 条、编号；无问题时只回一行「审查通过，未发现明显问题」；给脚本必须放在 ```groovy 块里且保留占位符。
- 用独立线程池 + `future.get(cr-timeout-seconds)` 做超时，超时/中断要 `future.cancel(true)`（`get` 超时不会自动停底层任务，否则线程池被慢请求占满）。失败一律降级为「AI 审查暂时不可用」，不影响语法校验结果。
- `ChatClient.Builder` 用 `ObjectProvider` 注入而不是直接注入：Key 缺失时 starter 可能不创建该 bean，直接注入会让**整个应用启动失败**。
- 模型给出的修改脚本由 `AiCodeBlockExtractor` 提取：取**最后一个闭合的** ```groovy/```java 块（取最后一个是因为模型习惯先指出问题行、再给完整脚本，取第一个会把问题片段当完整脚本替换进编辑器；要求闭合是因为输出被 token 上限截断时只有开头围栏，把剩余全文当脚本会直接毁掉用户正在编辑的内容）。

**对话**

- 记忆：`MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(40).build()`。
  （**本方案原先写的 `InMemoryChatMemory` 在 Spring AI 1.0.0 GA 已被移除**，照抄编译不过。）
- Advisor：`MessageChatMemoryAdvisor.builder(chatMemory).build()`。该类在 1.0.0 是 `final` 且**没有公开构造器**，不能 `new MessageChatMemoryAdvisor(chatMemory)`。
- 会话隔离：key = `"conv-" + conversationId`，每次请求必须显式 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))` —— **漏传抛 `IllegalArgumentException`，没有默认值兜底**。
- 每次请求把**当前编辑器脚本内容**拼进 user 消息，用 `【当前编辑器中的脚本】` / `【用户的问题】` 这样的明确分隔标记，比自然语言描述更稳（模型更容易分清哪段是代码）。系统提示词里必须交代沙箱限制（禁 import、禁文件/网络/进程/反射、5 秒超时），否则模型会给出必然被拦的建议。
- 流式：`chatClient.prompt(prompt).stream().content()` 得到 `Flux<String>`（每个元素是增量分片），map 成 `ServerSentEvent` 由 controller 返回。
  **Servlet 栈即可，不要引入 `spring-boot-starter-webflux`**：Spring MVC 5.0+ 内置 `ReactiveTypeHandler`，检测到「Reactive Streams 返回值 + `produces=text/event-stream`」会自动用 `SseEmitter` 桥接并逐段 flush。加了 webflux 反而会与 MVC 抢栈。`ServerSentEvent` 在 **spring-web** 的 `org.springframework.http.codec` 包（starter-web 已含）；`StepVerifier` 需要显式加 `reactor-test`（test scope）。
- 事件约定：`message`（增量，多条）/ `done`（正常结束，data 空）/ `error`（失败收尾，出现即不发 done）。前端按 **event 名**分派，不解析 data 里的魔法字符串。因为所有接口一律 POST，前端不能用 `EventSource`（只支持 GET），改为 `fetch` + `ReadableStream` 手写 SSE 解析。
- 持久化：用户消息**先写库成功再进内存**（反了会出现「重启就丢」的消息）；助手回复在流结束时把分片聚合成完整内容一次性写库。空消息不入库。
- **历史回灌（本方案原先没有，实现时补的增强）**：`ChatMemoryService.ensureLoaded(conversationId)` 在会话首次使用时从 MySQL 取最近 40 条灌进内存窗口，所以**服务重启后多轮记忆也能恢复**，不只是历史回显。必须在 `appendUser` **之前**调用，否则新消息会让「内存非空」判定成立而跳过回灌；并发用双检锁 + `computeIfAbsent` 防重复灌，`finally` 里移除锁对象防 Map 无限增长。`system` 角色的消息不回灌（系统提示每次请求现拼，灌进去会被窗口保留且多轮重复出现）。
- `history(ruleId)` 读 **MySQL 而不是内存窗口**（窗口只 40 条，用户要看完整历史）。
- 清空：`chatMemory.clear(key)` + 删除库中消息，但**保留 conversation 记录**（会话与规则一对一，删了就无法再对话）。
````

> 替换时注意：新内容里有嵌套的三反引号（```groovy），所以外层用了**四反引号**围栏。
> 粘进 markdown 文件时只粘围栏**里面**的内容，别把外层的 ```` 也带进去。

- [ ] **Step 2: 技术方案第 5 节接口表补 `/api/health`**

第 5 节的表列了 13 个接口，但**漏了 `/api/health`**（任务 2 就实现了，是非功能要求 #1 的验证入口：AI 不可用时它仍必须返回 `code=0`）。

在 `| /api/rule/list | ... |` 那一行**之前**插入：

```markdown
| /api/health | 无（传 `{}`） | 存活探针。AI 不可用时也必须返回 code=0，用来验证「工具本体不被 AI 拖挂」 |
```

同时核对表里其余 13 行与实际实现一致（路径、入参、出参摘要）。**API 规范要求「新增/修改接口需先更新该文档」**，这一步是补欠账。

- [ ] **Step 3: 技术方案末尾新增第 9 节「实现与方案的差异」**

在第 8 节之后追加。这一节的价值是**让下一个人不必逐行比对代码与方案**：

````markdown
## 9. 实现与方案的差异（2026-09-02 收尾同步）

方案是设计时写的，实现过程中有偏离。会直接误导人的硬错误已在 3.3 / 第 5 节就地改正，其余集中记在这里。

| # | 方案原文 | 实际实现 | 原因 |
|---|---|---|---|
| 1 | `InMemoryChatMemory` | `MessageWindowChatMemory` + `InMemoryChatMemoryRepository`，窗口 40 条 | 前者在 Spring AI 1.0.0 GA 已移除 |
| 2 | `new MessageChatMemoryAdvisor(chatMemory)` | `MessageChatMemoryAdvisor.builder(chatMemory).build()` | 1.0.0 该类是 final 且无公开构造器 |
| 3 | 记忆「以 conversationId 隔离」 | key 是字符串 `"conv-" + conversationId`，且必须显式传 `ChatMemory.CONVERSATION_ID` param | `ChatMemory` 接口的 key 类型是 String；漏传该 param 抛 `IllegalArgumentException`，无默认值 |
| 4 | 「内存记忆管上下文，MySQL 管历史回显」 | 多了一层：会话首次使用时从 MySQL 回灌最近 40 条进内存 | 否则服务重启即失忆，违背非功能要求 #5「服务重启后历史消息不丢」的精神 |
| 5 | `ChatClient.stream()` 转 SSE | `chatClient.prompt(prompt).stream().content()` → `Flux<ServerSentEvent>`，**Servlet 栈**，未引入 webflux | Spring MVC 的 `ReactiveTypeHandler` 会自动桥接；加 webflux 会与 MVC 抢栈 |
| 6 | 未提 Prompt 构造方式 | 一律 `new Prompt(Message...)`，禁用 fluent `.user()` / `.system()` | `PromptTemplate` 的 `{var}` 语法会吃掉 Groovy 脚本里的 `${age}`，导致 AI 功能静默失效 |
| 7 | 第 5 节接口表 13 个 | 14 个（补 `/api/health`） | 健康检查是任务 2 就有的，方案漏列 |
| 8 | 第 8 节「每步跑 `mvn test`、`npm run build`」 | `source ../tools/env.sh && mvn -q test`；前端 `npm run test:unit && npm run build` | 工具链是仓库内 `tools/` 的便携版，不 source 就没有 JDK/Maven/Node，也没有 DB 凭据 |
| 9 | 第 8 节开发顺序 7 步 | 实际拆成 22 个任务，见 `docs/superpowers/plans/2026-09-02-script-rule-workbench.md` | 7 步粒度太粗，无法交给子代理并行执行 |
| 10 | 未提单测如何对待大模型 | 后端单测统一 `app.ai.enabled=false` 走降级分支 | 真调会烧额度、会因网络抖动让测试变红；真实调用只在手工验收做 |
| 11 | 需求非功能要求 #3「危险操作拦截」未说在哪个阶段拦 | 在**校验**阶段就被拦，运行按钮保持锁定 | 校验与运行共用带沙箱的 `CompilerConfiguration`，避免「先说语法通过、点运行才被拦」的割裂体验 |
| 12 | 未提黑名单的误伤 | 变量名首字母大写撞黑名单（如 `def File = 1`）会被误拦 | 黑名单按简单类名匹配；漏拦代价高于误拦，且规则脚本里这么命名的概率极低 |

第 11、12 条不是缺陷，是**优于或有别于需求字面表述的设计取舍**，验收时判为通过（见验收记录第三节）。
````

- [ ] **Step 4: 技术方案第 8 节同步真实测试规模**

把第 8 节末尾那句：

```markdown
**每步完成后运行验证命令**：`mvn test`、`npm run build`，通过再继续。
```

换成：

````markdown
**每步完成后运行验证命令**（工具链在仓库内 `tools/`，必须先 source）：

```bash
cd script_back  && source ../tools/env.sh && mvn -q test                    # 234 个测试
cd script_front && source ../tools/env.sh && npm run test:unit && npm run build   # 101 个测试
```

后端单测统一以 `app.ai.enabled=false` 运行，**不真调大模型**（会烧额度、会因网络抖动变红）；真实调用只在任务 21 的手工验收里做。

上面「开发顺序（7 步）」是设计时的粗粒度规划，实际执行拆成了 22 个任务，逐个任务的完整代码与验证命令见 `docs/superpowers/plans/2026-09-02-script-rule-workbench.md`。
````

- [ ] **Step 5: 更新 README**

README 现在开头就写着「代码开发中，以下为规划中的启动方式」，必须换掉。

**1）把整个「## 怎么跑」小节**（从 `## 怎么跑` 到 `## 项目结构` 之前）替换为：

````markdown
## 怎么跑

前置只有一个：**MySQL 8**，建库 `script_workbench`（utf8mb4）。

JDK 17 / Maven / Node 20 **不需要系统安装** —— 本机把便携版放在 `tools/` 下，`source tools/env.sh` 会自动挂上 PATH 并注入配置。

> ⚠️ **便携工具链本身不入库**：`.gitignore` 排除了 `tools/jdk17/`、`tools/maven/`、`tools/node/`（只留 `env.sh`），
> 因为把几百 MB 二进制塞进 git 会让仓库没法用。所以**换机器或新 clone 时这三样要自己放进 `tools/`**：
> Temurin JDK 17（macOS x64，解压后应有 `tools/jdk17/Contents/Home`）、Maven 3.9.9（`tools/maven/bin/mvn`）、
> Node 20（`tools/node/bin/node`）。也可以改 `env.sh` 里的 `JAVA_HOME` / `MAVEN_HOME` / `PATH` 指向自己系统装的位置。
>
> 同理，`git` 用的是 `/usr/local/bin/git`（Homebrew 2.20.1）。本机 `/usr/bin/git` 是坏的空壳（CommandLineTools 残缺，
> 一调就报 `xcrun: error: invalid active developer path`），`env.sh` 已把 `/usr/local/bin` 显式加进 PATH 来绕开它。

### 1. 填凭据（只需一次）

真实凭据不入库。在 `tools/local-secret.env` 里写自己的值：

```bash
cat > tools/local-secret.env <<'EOF'
export DB_PASS='你的 MySQL root 密码'
export AI_DASHSCOPE_API_KEY='你的百炼 API Key'
EOF
chmod 600 tools/local-secret.env
```

该文件已在 `.gitignore` 里，不会被提交；`tools/env.sh` 开头会自动 source 它。

**不配 API Key 也能跑**：AI 审查与 AI 对话会降级成「暂时不可用」的中文提示，语法校验、占位符提取、沙箱运行全部照常。这是刻意设计 —— AI 不可用不能把工具本体搞挂。

### 2. 起服务

```bash
# 后端（端口 8080）
cd script_back
source ../tools/env.sh
mvn spring-boot:run

# 前端（另开一个终端；端口 5173，/api 自动代理到 8080）
cd script_front
source ../tools/env.sh
npm install        # 首次
npm run dev
```

浏览器打开 <http://localhost:5173/>。

### 3. 跑测试

```bash
cd script_back  && source ../tools/env.sh && mvn -q test                     # 234 个
cd script_front && source ../tools/env.sh && npm run test:unit               # 101 个
```

单测统一以 `app.ai.enabled=false` 跑，不会真调大模型。

## 配置项

环境变量都在 `tools/env.sh` 里有默认值，覆盖方式是在 `tools/local-secret.env` 里 export 同名变量。

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/script_workbench?...serverTimezone=Asia/Shanghai` | JDBC 连接串，含 `allowPublicKeyRetrieval=true` |
| `DB_USER` | `root` | |
| `DB_PASS` | 空（**必须自己给**） | 不给会报 `Access denied for user 'root'` |
| `AI_ENABLED` | `true` | 置 `false` 整体关掉 AI，走降级分支 |
| `AI_DASHSCOPE_API_KEY` | `not-configured` | 阿里百炼 Key |
| `AI_BASE_URL` | `https://dashscope.aliyuncs.com` | 公共端点即可；业务空间专属 Key 在公共端点上实测也能用 |
| `AI_CHAT_MODEL` | `qwen-plus` | |

`script_back/src/main/resources/application.yml` 里还有两个业务配置（不是环境变量）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.script.timeout-seconds` | `5` | 单次脚本执行上限，超时中断（需求非功能要求 #2） |
| `app.ai.cr-timeout-seconds` | `60` | 同步 CR 的自身超时，超时降级为提示 |
````

**2）把「## 项目结构」里的目录树**替换为（补上 `tools/`、`plans/`、`verification/`）：

````markdown
```
xd-rule-script/
├─ README.md                     # 本文件：是什么 + 怎么跑 + 配置项
├─ JOURNAL.md                    # 开发日志（重点）
├─ Day-by-day/                   # 每日流水账（day1 ~ day7）
├─ .qoder/rules/                 # 为本项目配置的 AI 开发规则（等价于模板中的 .claude/）
├─ tools/                        # 环境脚本 + 本机便携工具链
│  ├─ env.sh                     # 【入库】source 它即可，会挂 PATH 并注入配置
│  ├─ local-secret.env           # 【不入库】真实凭据，已 gitignore
│  └─ jdk17/ maven/ node/        # 【不入库】便携工具链，换机器需自备（见「怎么跑」）
├─ docs/
│  ├─ superpowers/specs/         # 需求文档、技术方案
│  ├─ superpowers/plans/         # 实施计划（22 个任务的完整代码与验证命令）
│  ├─ superpowers/verification/  # 端到端验收记录 + 截图存证
│  └─ ui-mockups/                # 三套 UI 风格稿（已选定风格三）
├─ script_front/                 # 前端：Vue 3 + Vite + Element Plus + CodeMirror 6
└─ script_back/                  # 后端：Spring Boot 3.4.5 + Spring AI 1.0.0 + Groovy 4
```
````

**3）「## 文档索引」补两条**：

```markdown
- 实施计划：`docs/superpowers/plans/2026-09-02-script-rule-workbench.md`
- 验收记录：`docs/superpowers/verification/2026-09-02-验收记录.md`
```

- [ ] **Step 6: 清理占位、死代码与凭据泄漏**

22 个任务里留了不少「待接入（任务 N）」的占位，此刻应该全清了。逐条扫：

```bash
cd /Users/zhoudingyan/workspace/zdy_test

echo "=== ① 前端占位文案（应为空）==="
grep -rn "待接入\|待实现\|待补充\|TODO\|FIXME" script_front/src/ || echo "✅ 无残留"

echo "=== ② console.log 残留（应为空）==="
grep -rn "console\.log" script_front/src/ || echo "✅ 无残留"

echo "=== ③ 后端 TODO / 打印残留（应为空）==="
grep -rn "TODO\|FIXME\|System\.out\.print\|printStackTrace" script_back/src/main/ || echo "✅ 无残留"

echo "=== ④ 定义了却没人用的 CSS 变量 ==="
for v in $(grep -oE '^[[:space:]]*--[a-z-]+' script_front/src/styles/theme.css | tr -d ' '); do
  n=$(grep -ro -- "$v" script_front/src/ | wc -l | tr -d ' ')
  [ "$n" -le 1 ] && echo "⚠️  $v 只出现 $n 次（只有定义、没有使用）"
done
echo "（无输出即全部变量都在用）"

echo "=== ⑤ 凭据没进版本库（关键）==="
source tools/env.sh
git check-ignore -v tools/local-secret.env || echo "❌ local-secret.env 没被忽略！"
if [ -n "$DB_PASS" ]; then
  git grep -nI -- "$DB_PASS" -- . && echo "❌ 版本库里出现了真实数据库密码" || echo "✅ 无数据库密码"
fi
if [ "$AI_DASHSCOPE_API_KEY" != "not-configured" ]; then
  git grep -nI -- "$AI_DASHSCOPE_API_KEY" -- . && echo "❌ 版本库里出现了真实 API Key" || echo "✅ 无 API Key"
fi
```

> ⑤ **刻意用变量而不是字面值**去搜：把真实密码或 Key 写进这条命令，等于把它写进了计划文档，
> 直接违反 Global Constraints「任何代码、配置、文档、提交信息里都不得出现真实密码或 Key」。

⑤ 的 `git grep` 只覆盖**已入库**的内容。还没提交、但下一步就要 `git add -A` 进去的文件它看不到，所以再扫一遍整个工作区：

```bash
echo "=== ⑥ 工作区里没有明文 Key（含尚未提交的文件）==="
# 排除 local-secret.env：它是唯一合法存放真实凭据的地方，且已被 gitignore
grep -rn "sk-[A-Za-z0-9._-]\{20,\}" . \
  --include="*.md" --include="*.yml" --include="*.yaml" --include="*.sh" \
  --include="*.java" --include="*.js" --include="*.vue" --include="*.json" --include="*.html" \
  --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=target --exclude-dir=dist \
  --exclude=local-secret.env \
  && echo "❌ 上面这些文件里出现了疑似真实 Key" || echo "✅ 无明文 Key"
```

处理原则：
- ①②③ 有输出就**清掉**。占位文案若还在，说明对应任务的接入步骤漏做了 —— 回那个任务补，别只删文案
- ④ 报出来的变量，确认是真的没用（不是被 JS 动态拼的名字）再删；删了同步更新任务 3 里 `theme.css` 的清单
- ⑤ 任何一条 ❌ 都是**必须先解决再提交**的问题：`git rm --cached` 移出索引、补 `.gitignore`，若已经推到远端则要轮换凭据
- ⑥ 报出来的文件**必须先脱敏再提交**：改成 `<占位符>`，并在旁边注明真实值该从哪里取。这条不是形式主义 —— 本项目在写实施计划时就真的把**完整的百炼 API Key 与 MySQL 密码明文写进了 `docs/superpowers/plans/` 下的计划文档**，而该文档要入库、远端是 GitHub，靠这一步才发现。计划文档、技术方案、JOURNAL、Day-by-day 全都在入库范围内，一处都不能写真实值

- [ ] **Step 7: 全量回归**

从零跑一遍，确认清理没弄坏东西：

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_back
source ../tools/env.sh
mvn -q clean test
```

预期：BUILD SUCCESS，**234 个测试通过**。

```bash
cd /Users/zhoudingyan/workspace/zdy_test/script_front
source ../tools/env.sh
rm -rf node_modules/.vite dist
npm run test:unit && npm run build
```

预期：**101 个单测通过**，构建成功，`dist/` 产出。

> 这里用 `clean` 与清 Vite 缓存，是因为前面 21 个任务都是增量跑的，
> 陈旧的编译产物可能掩盖真实问题。收尾这一次必须从干净状态验证。

数字对不上就是清理时删多了 —— `git diff` 看改了什么，回退误删的部分。

- [ ] **Step 8: 补 `Day-by-day/` 的流水账**

`JOURNAL.md` 里写明了分工：**「做了什么 / 卡在哪 / 怎么解开的」由 AI 助手记录素材，「对 AI 编程助手的认知变化」由本人撰写**。所以这里只填前三节。

**先确认该填哪个文件**：`Day-by-day/dayN.md` 按天记，day1=2026-08-31、day2=2026-09-01、day3=2026-09-02。任务 1–21 的执行**大概率跨天**，别把几天的事挤进一个文件；也别把执行阶段的事写进 day3 —— day3 实际做的是**规划**（拿到百炼 Key 后重做实施计划，把技术方案里的 7 步粗规划拆成 22 个可并行执行的任务、逐任务写全代码与验证命令，并配好 MySQL MCP）。

```bash
cd /Users/zhoudingyan/workspace/zdy_test
for f in Day-by-day/day*.md; do printf '%-24s -> ' "$f"; head -1 "$f"; done
```

标题还带「（日期待定）」的就是空模板。day7 比其余几天多一节「一周回顾」。

下面是**执行阶段**的素材，按实际发生的那天填、按真实情况取舍改写：

````markdown
# Day N（YYYY-MM-DD）：22 个任务落地，从脚手架到端到端验收

## 做了什么

- 补齐本机工具链：JDK 17 + Maven 装进仓库内 `tools/`（Homebrew 已损坏，走便携版），连同已有的 Node 20 一起由 `tools/env.sh` 统一挂载
- 后端 12 个任务：脚手架与统一响应/异常、4 张表实体与仓库、规则 CRUD、Groovy 词法扫描与占位符提取、AST 类型推断、编译期沙箱、5 秒超时运行、`/api/rule/validate|run`
- 接入百炼 qwen-plus：同步 CR（含代码块提取）、SSE 流式对话、ChatMemory + MySQL 历史回灌
- 前端 8 个任务：列表页、CodeMirror 6 编辑器（Groovy 高亮 + 错误行标红）、校验状态机、填值表单、运行结果卡、AI 审查卡片、对话面板（手写 SSE 解析 + 打字机）、测试用例栏
- 端到端验收：需求第 7 节 22 项 + 第 6 节 5 项非功能要求，MySQL MCP 查库核对，截图存证
- 最终规模：后端 234 个测试、前端 101 个测试

## 卡在哪

1. **Spring AI 的 `{var}` 模板语法会吃掉 Groovy 的 `${age}`。** 用 fluent 的 `.user(script)` 时，脚本里的 `{age}` 被 `PromptTemplate` 当成待替换变量，传到模型手里已经残缺。症状极隐蔽：不报错、有回复，只是答非所问。
2. **`InMemoryChatMemory` 在 1.0.0 GA 被移除了**，而网上大量教程还是旧写法；`MessageChatMemoryAdvisor` 也变成了 final + 只有静态 builder。照着方案原文写直接编译不过。
3. **死循环杀不掉。** `while (true) {}` 是纯 CPU 循环，不响应 `Thread.interrupt()`，光靠 `future.cancel(true)` 线程会一直空转泄漏。
4. **`ChatMemory.CONVERSATION_ID` 没有默认值**，漏传就抛 `IllegalArgumentException`，报错信息完全没提示是漏了这个 param。
5. **Servlet 栈能不能返回 `Flux`** 一开始没底，差点多加一个 webflux starter 把栈搞冲突。
6. **前端不能用 `EventSource`**：它只支持 GET，而本项目所有接口一律 POST。
7. **本机 `git` 一调就报 `xcrun: error: invalid active developer path`**：`/usr/bin/git` 是 Apple 的 shim，要靠 CommandLineTools，而那套东西自 2019 年起就是残缺空壳（`usr/bin/` 下只剩一个 `stapler`）。
8. **写实施计划时把完整的 API Key 和数据库密码明文写进了计划文档**，而计划文档要入库、远端是 GitHub。

## 怎么解开的

1. 一律改用 non-fluent 的 `new Prompt(new SystemMessage(...), new UserMessage(...))`，绕开模板引擎。这一条同时保护了两个方向：脚本里的 `${}`、提示词里举例用的 `${变量名}`。
2. 不再信教程，逐个 API 去查 **Spring AI 1.0.0 的官方 javadoc** 交叉验证，把正确写法与常见错误写法做成对照表钉进实施计划，避免执行期返工。
3. 加 Groovy 的 `@ThreadInterrupt` AST 变换，往循环里注入中断检查点，`cancel(true)` 才真的生效。并且写了「直接断言线程被归还」的测试 —— 只测「返回了超时提示」是验不出泄漏的，因为排队后 `future.get` 一样会超时。
4. 把「必须显式传 CONVERSATION_ID」写进计划的 API 对照表，并在封装层统一处理，调用方拿不到漏传的机会。
5. 查清 Spring MVC 5.0+ 内置 `ReactiveTypeHandler`：检测到 Reactive 返回值 + `produces=text/event-stream` 会自动用 `SseEmitter` 桥接并逐段 flush。所以不引入 webflux，只补一个 test scope 的 `reactor-test`。
6. 手写 SSE 解析器，并把它做成纯函数单测。过程中发现两个真实的坑：chunk 边界会切在事件中间（必须用 buffer 累积按空行切分），以及流式中途代码块未闭合会导致整块内容反复重排闪烁（未闭合时直接当代码块渲染）。
7. 查清真正可用的 git 在 `/usr/local/bin/git`（Homebrew 2.20.1，完整），只是执行环境的 PATH 不含 `/usr/local/bin`，于是先撞上了坏的 shim。在 `tools/env.sh` 的 PATH 里显式补上 `/usr/local/bin`，一处解决；顺带确认 2.20.1 不支持 `git switch`/`git restore`（需 2.23+），全计划只用老命令。
8. 收尾的泄漏扫描里补了一条「扫整个工作区而不只扫已入库内容」—— `git grep` 只看得到已提交的，还没 `git add` 的文件它查不到，恰好漏掉最危险的那一刻。脱敏用**按行号替换**（`sed -i '' '342s|.*|...|'`），这样修复命令本身不会再出现一次真实 Key。
````

> **`## 对 AI 编程助手的认知变化` 这一节不要动**，那是本人撰写的部分。
> 上面三节是素材，写进文件前按真实发生的情况核对一遍 —— 记错比不记更糟。

- [ ] **Step 9: 最终提交**

```bash
cd /Users/zhoudingyan/workspace/zdy_test
source tools/env.sh         # 必须：否则 PATH 里没有 /usr/local/bin，git 会撞上坏掉的 /usr/bin/git 并报 xcrun 错误
git --version               # 预期 git version 2.20.1
git status --short
git add -A
git status --short          # 再确认一次：不该有 local-secret.env、不该有 node_modules/ 或 target/
git commit -m "docs: 收尾同步技术方案与 README，清理占位与死代码"
```

提交前最后确认三件事：

1. `git status --short` 的输出里**没有** `tools/local-secret.env`、`tools/jdk17/`、`tools/maven/`、`tools/node/`、`node_modules/`、`target/`、`dist/`。前四项一旦进去，仓库会凭空多出几百 MB 二进制；`local-secret.env` 进去则是直接泄凭据
2. `git log --oneline | head -30` 看提交历史，每条都符合 `<类型>: <简述>` 格式，类型取 docs/feat/fix/refactor/test/chore
3. 提交信息里**没有**真实密码或 Key

```bash
# 收尾确认：工作区干净
git status --short && echo "（无输出即干净）"
```

至此 22 个任务全部完成。若任务 21 的验收记录第五节还有遗留问题，在此处再列一遍并说明为什么留到下一轮，不要让它随着收尾提交一起被忘掉。

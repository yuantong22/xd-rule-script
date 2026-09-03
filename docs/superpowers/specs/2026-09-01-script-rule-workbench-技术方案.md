# 脚本规则工作台 — 技术方案

> 版本：v1.0（由 PRD v0.2 拆分而来，技术结论无变化）
> 日期：2026-09-01
> 关联文档：《需求文档》（同目录，文件名含"需求文档"）

---

## 1. 总体架构

```
┌─────────────────┐   HTTP / SSE    ┌──────────────────────────────┐
│   script_front  │ ──────────────▶ │          script_back         │
│  Vue3 + Vite    │   /api/*        │   Spring Boot 3.4（8080）     │
│  开发代理转发     │ ◀────────────── │ ┌──────────┐ ┌─────────────┐ │
└─────────────────┘                 │ │ 规则管理   │ │ Groovy 引擎  │ │
                                    │ └──────────┘ └─────────────┘ │
                                    │ ┌──────────────────────────┐ │
                                    │ │ AI 服务（CR / 对话 / 记忆）│ │
                                    │ └──────────────────────────┘ │
                                    └───────┬──────────────┬───────┘
                                            │              │
                                            ▼              ▼
                                     ┌──────────┐   ┌─────────────────┐
                                     │ MySQL 8  │   │ 阿里百炼         │
                                     │ 4 张表    │   │ DashScope       │
                                     └──────────┘   │ (qwen-plus)     │
                                                    └─────────────────┘
```

- 前端开发环境用 Vite 代理把 `/api` 转发到 `localhost:8080`，后端无需配置跨域
- 流式对话走 SSE（POST 请求 + SSE 响应）

## 2. 技术选型与理由

| 层 | 选型 | 理由 |
|---|---|---|
| 前端框架 | Vue 3 + Vite | 用户指定；Vite 构建快、配置简单 |
| 前端组件 | Element Plus | 表格、表单、弹窗、消息提示开箱即用 |
| UI 风格 | 风格三：现代渐变风 | 蓝紫渐变主色（#5b5fc7 → #8b5cf6）、大圆角、柔和阴影；两个页面统一；参考稿 `docs/ui-mockups/style3-gradient.html` |
| 代码编辑器 | CodeMirror 6 | 轻量、可编程性强，`@codemirror/legacy-modes` 自带 groovy 高亮模式 |
| 后端 | JDK 17 + Spring Boot 3.4 | 用户指定 Java；3.4 与 Spring AI Alibaba 兼容 |
| AI 接入 | Spring AI Alibaba（dashscope starter） | 用户指定 Spring AI + 阿里百炼 |
| 脚本引擎 | Groovy 4（GroovyShell + SecureASTCustomizer） | JVM 原生执行，无需外部进程；编译期可做 AST 安全限制 |
| 数据库 | MySQL 8 + Spring Data JPA | 用户指定 MySQL；JPA 开发快，`ddl-auto=update` 自动建表 |
| 流式输出 | SSE（Server-Sent Events） | 单向打字机场景足够，比 WebSocket 简单 |

## 3. 模块设计

### 3.1 后端模块划分

```
script_back/
└── src/main/java/.../
    ├── controller/    RuleController、ChatController、TestCaseController
    ├── service/       RuleService、GroovyEngineService、AiService、TestCaseService
    ├── entity/        Rule、Conversation、Message、TestCase
    ├── repository/    对应 4 个 JpaRepository
    ├── dto/           请求/响应对象 + 统一响应包装 {code, message, data}
    └── config/        ChatClient 配置、线程池配置
```

### 3.2 Groovy 引擎（核心）

`GroovyEngineService` 提供三个能力：

**占位符提取与类型推断**
1. 正则 `\$\{(\w+)\}` 收集全部占位符变量名
2. 把每个占位符替换为合法标识符（如 `${age}` → `__ph_age`），用 Groovy 解析成 AST
3. 遍历 AST 中的变量声明语句，找到初始化表达式为该标识符的声明，取其声明类型
4. 未找到声明的占位符默认按 `String` 处理
5. 输出：`[{name, type}]`

**语法校验**
- 把占位符按推断类型替换为假值（`int→0`、`long→0L`、`double→0.0`、`boolean→false`、`String→""`）
- 用带沙箱配置的 `GroovyShell` 编译（不执行）
- 编译失败时从 `MultipleCompilationErrorsException` 提取**行号 + 错误描述**返回

**沙箱执行**
- 占位符替换为真实字面量（字符串做引号转义），编译 + 执行，取返回值转字符串
- 安全措施两层：
  - **编译期**：`SecureASTCustomizer` 禁止 import/包声明；接收者黑名单（`System`、`Runtime`、`Thread`、`ProcessBuilder`、`File`、`Socket` 等）
  - **运行期**：提交到独立线程池，`future.get(5, SECONDS)` 超时后中断并返回超时提示
- 所有异常统一转为可读中文错误信息

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

## 4. 数据模型（MySQL）

| 表 | 字段 | 说明 |
|---|---|---|
| rule | id, name, description, script_content(text), created_at, updated_at | 规则 |
| conversation | id, rule_id(唯一索引), title, created_at, updated_at | 会话，与规则一对一，创建规则时自动生成 |
| message | id, conversation_id, role(user/assistant), content(text), created_at | 消息 |
| test_case | id, rule_id, name, params_json(text), created_at | 占位符填值快照 |

- 建表由 JPA `ddl-auto=update` 自动完成，库名 `script_workbench`
- 删规则时由服务层级联删除 conversation、message、test_case

## 5. 接口设计（全部 POST，统一响应 {code, message, data}）

| 接口 | 入参摘要 | 出参摘要 |
|---|---|---|
| /api/health | 无（传 `{}`） | 存活探针。AI 不可用时也必须返回 code=0，用来验证「工具本体不被 AI 拖挂」 |
| /api/rule/list | name(可空)、page、size | 规则分页列表 |
| /api/rule/create | name、description | 新建的规则（自动建会话） |
| /api/rule/detail | ruleId | 规则详情（含脚本内容） |
| /api/rule/update | ruleId、name/description/scriptContent | 更新后的规则 |
| /api/rule/delete | ruleId | 成功/失败 |
| /api/rule/validate | scriptContent | syntaxOk、错误(行号+原因)、占位符列表[{name,type}]、aiReview(审查文本、修改后脚本可空) |
| /api/rule/run | scriptContent、params{变量名:值} | 返回值字符串 |
| /api/chat/history | ruleId | 消息列表[{role, content}] |
| /api/chat/send | ruleId、message、scriptContent | SSE 流（逐段文本） |
| /api/chat/clear | ruleId | 成功/失败 |
| /api/testcase/list | ruleId | 用例列表[{id, name, params}] |
| /api/testcase/save | ruleId、name、params | 新建的用例 |
| /api/testcase/delete | testCaseId | 成功/失败 |

## 6. 关键技术难点与对策

| 难点 | 对策 |
|---|---|
| 占位符类型推断 | 正则定位 + 假标识符替换 + AST 遍历声明语句；推断不到默认 String |
| 沙箱不是 100% 严密 | 静态 AST 黑名单 + 禁止 import + 超时兜底；单用户内部工具风险可接受，文档中明示 |
| POST 请求返回 SSE | 后端 POST 接口返回 `Flux<ServerSentEvent>`；前端用 `fetch` + `ReadableStream` 逐段读取（EventSource 只支持 GET，不适用） |
| 大模型 API Key 未到位 | 配置项留占位符；AI 相关功能检测无 Key 时返回明确提示，语法校验/运行功能不受影响 |
| 校验状态管理 | 前端维护"脏标记"：编辑器内容相对最近一次校验的快照有变化 → 锁定运行按钮 |
| 同步校验耗时长（等 CR） | 前端展示"AI 审查中…"加载态；后端对 CR 调用设置自身超时，超时降级提示 |

## 7. 项目结构与配置

```
zdy_test/
├── docs/             # 需求文档、技术方案
├── script_front/     # Vue 3 + Vite（npm），dev 代理 /api → localhost:8080
└── script_back/      # Maven 工程，端口 8080
```

**环境依赖**：JDK 17、Maven、Node 18+（npm）、MySQL 8（localhost:3306）

**application.yml 关键配置项**：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/script_workbench
    username: <联调前填写>
    password: <联调前填写>
  jpa:
    hibernate:
      ddl-auto: update
  ai:
    dashscope:
      api-key: <待用户提供>
      chat:
        options:
          model: qwen-plus   # 对话与 CR 统一使用
```

## 8. 开发顺序与测试计划

**开发顺序（7 步）**
1. 双端脚手架，空壳跑通（后端健康检查、前端代理连通）
2. 规则 CRUD 后端 + 列表页前端
3. 工作台：编辑器 + 语法校验 + 占位符提取 + 沙箱运行（先不接大模型）
4. 接入大模型：同步 CR（validate 完整版）
5. AI 对话：流式 + 记忆 + 消息落库 + 清空
6. 测试用例功能
7. 收尾：错误行标红打磨、代码块应用按钮、README（怎么跑、配置项说明）

**后端单测**
- 占位符提取与类型推断（多类型混合、未声明变量）
- 语法错误行号准确性
- 沙箱拦截（`System.exit`、文件访问）
- 超时中断（`while(true)`）
- 运行返回值正确性

**手工端到端**
- 规则增删改查全流程
- 示例规则（如按年龄+会员等级判定）校验 → 填值 → 运行
- 大模型 CR 建议一键应用
- 对话多轮记忆验证、清空后记忆重置

**每步完成后运行验证命令**（工具链在仓库内 `tools/`，必须先 source）：

```bash
cd script_back  && source ../tools/env.sh && mvn -q test                    # 222 个测试
cd script_front && source ../tools/env.sh && npm run test:unit && npm run build   # 101 个测试
```

后端单测统一以 `app.ai.enabled=false` 运行，**不真调大模型**（会烧额度、会因网络抖动变红）；真实调用只在任务 21 的手工验收里做。

上面「开发顺序（7 步）」是设计时的粗粒度规划，实际执行拆成了 22 个任务，逐个任务的完整代码与验证命令见 `docs/superpowers/plans/2026-09-02-script-rule-workbench.md`。

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

第 11、12 条不是缺陷，是**优于或有别于需求字面表述的设计取舍**，验收时判为通过（见验收记录第四节）。

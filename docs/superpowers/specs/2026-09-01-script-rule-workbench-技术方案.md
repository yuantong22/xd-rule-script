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

`AiService` 基于 Spring AI 的 `ChatClient`（DashScope，模型 `qwen-plus`，配置化）：

**同步 CR（校验用）**
- 提示词要点：审查语义错误、逻辑漏洞、潜在空指针；如无问题明确说"通过"；如给出修改，必须返回完整脚本并放在代码块中
- 校验接口内同步调用，超时/失败时降级为"AI 审查暂不可用"提示，不影响语法校验结果

**对话**
- 记忆：`MessageChatMemoryAdvisor` + `InMemoryChatMemory`，以会话 ID（conversationId）隔离
- 每次请求把**当前编辑器脚本内容**拼入上下文，让大模型针对当前脚本作答
- 流式：`ChatClient.stream()` 转 SSE 输出
- 持久化：用户消息与完整助手回复同步写 message 表（内存记忆管上下文，MySQL 管历史回显）
- 清空：`chatMemory.clear(conversationId)` + 删除库中消息

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

**每步完成后运行验证命令**：`mvn test`、`npm run build`，通过再继续。

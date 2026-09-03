# xd-rule-script · 脚本规则工作台

## 是什么

一个 Web 版"脚本规则工作台"：给决策引擎的 Groovy 脚本规则提供**编写、校验、试运行**的一体化工具，并支持通过大模型对话辅助写脚本。

- 前端：Vue 3 + Vite（`script_front/`）
- 后端：Java + Spring Boot 3.4 + Spring AI Alibaba（`script_back/`）
- 大模型：阿里百炼 DashScope（qwen-plus）
- 数据库：MySQL 8

## 解决了什么

决策引擎的规则用 Groovy 脚本编写，但日常开发中有三个痛点：

1. **写**：没有趁手的编辑器，写规则靠裸文本，也没人帮看写得对不对
2. **验**：脚本有没有语法错误、占位符有哪些、语义上有没有坑，只能上引擎试了才知道
3. **跑**：想验证一组输入的结果，要走完整发布流程，反馈太慢

本工具把这三件事搬进一个网页：编辑器 + 一键校验（语法 + 占位符提取 + 大模型审查）+ 填值即跑（沙箱执行、5 秒超时）。

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
cd script_back  && source ../tools/env.sh && mvn -q test                     # 222 个
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

`script_back/src/main/resources/application.yml` 里还有三个业务配置（不是环境变量）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.script.timeout-seconds` | `5` | 单次脚本执行上限，超时中断（需求非功能要求 #2） |
| `app.ai.cr-timeout-seconds` | `60` | 同步 CR 的自身超时，超时降级为提示 |
| `app.ai.chat-timeout-seconds` | `120` | 流式对话整体超时，超时后发 error 事件收尾 |

## 项目结构

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

### 与作业模板的对应关系

| 模板要求 | 本项目实现 | 说明 |
|---|---|---|
| `src/` | `script_front/` + `script_back/` | 前后端分离项目，源码拆为两个子目录 |
| `.claude/` | `.qoder/rules/` | 本项目使用 Qoder（非 Claude Code），规则目录作用相同：约束 AI 助手按项目规范干活 |
| `JOURNAL.md` | `JOURNAL.md` | 同名，内容基于 Qoder 的真实使用体验 |

## 文档索引

- 需求文档：`docs/superpowers/specs/2026-09-01-script-rule-workbench-需求文档.md`
- 技术方案：`docs/superpowers/specs/2026-09-01-script-rule-workbench-技术方案.md`
- 实施计划：`docs/superpowers/plans/2026-09-02-script-rule-workbench.md`
- 验收记录：`docs/superpowers/verification/2026-09-02-验收记录.md`
- UI 风格（已选定风格三）：`docs/ui-mockups/style3-gradient.html`

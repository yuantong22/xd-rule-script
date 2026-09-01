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

> 代码开发中，以下为规划中的启动方式，完成后会更新为真实命令。

前置：JDK 17、Maven、Node 18+、MySQL 8（建库 `script_workbench`）

```bash
# 后端（端口 8080）
cd script_back
mvn spring-boot:run

# 前端（开发模式，代理 /api 到 8080）
cd script_front
npm install
npm run dev
```

配置项在 `script_back/src/main/resources/application.yml`（MySQL 账号、百炼 API Key）。

## 项目结构

```
xd-rule-script/
├─ README.md                # 本文件：是什么 + 怎么跑 + 解决了什么
├─ JOURNAL.md               # 开发日志（重点）
├─ .qoder/rules/            # 为本项目配置的 AI 开发规则（等价于模板中的 .claude/）
├─ docs/                    # 需求文档、技术方案、UI 风格稿
│   ├─ superpowers/specs/   # 需求文档、技术方案
│   └─ ui-mockups/          # 三套 UI 风格稿（已选定风格三）
├─ script_front/            # 前端源码（对应模板中的 src/，前后端分离故拆为两个目录）
└─ script_back/             # 后端源码
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
- UI 风格（已选定风格三）：`docs/ui-mockups/style3-gradient.html`

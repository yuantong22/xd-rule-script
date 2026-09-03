# Superpowers（Qoder 插件版）

Superpowers 是一套面向编码智能体的核心技能库，包含 TDD、系统化调试、头脑风暴、计划编写与执行、代码评审、Git worktree 隔离等经过验证的协作工作流。

本插件由开源项目 [obra/superpowers](https://github.com/obra/superpowers)（v6.3.0，MIT 协议）转换而来。

## 包含的技能（14 个）

| 技能 | 用途 |
| --- | --- |
| `using-superpowers` | 技能使用入门：如何查找与调用技能 |
| `brainstorming` | 任何创造性工作前的意图式头脑风暴 |
| `writing-plans` | 把规格/需求转化为多步实施计划 |
| `executing-plans` | 在独立会话中按计划执行并设置评审检查点 |
| `subagent-driven-development` | 以子智能体逐任务执行计划并评审 |
| `dispatching-parallel-agents` | 并行派发 2+ 个相互独立的任务 |
| `test-driven-development` | 实现任何功能/修复前先写测试（TDD） |
| `systematic-debugging` | 遇到 bug、测试失败或异常行为时的系统化调试 |
| `verification-before-completion` | 声明完成前必须先运行验证命令并确认输出 |
| `requesting-code-review` | 完成任务或合并前发起代码评审 |
| `receiving-code-review` | 接收评审反馈时的技术严谨性与验证 |
| `using-git-worktrees` | 用 git worktree 创建隔离工作区 |
| `finishing-a-development-branch` | 实现完成后决定如何集成工作 |
| `writing-skills` | 创建、编辑与验证技能本身 |

所有技能连同其 `references/`、示例等支撑文件一并复制，相对链接保持有效。

## 来源与署名

- **来源**：https://github.com/obra/superpowers （main 分支，v6.3.0）
- **原作者**：Jesse Vincent <jesse@fsck.com>
- **许可**：MIT
- **Logo**：来自源仓库 `assets/superpowers-small.svg`，保存为 `assets/avatar.svg`

## 已省略的内容及原因

| 源文件 | 省略原因 |
| --- | --- |
| `hooks/`（session-start 钩子） | 该钩子为 Claude Code / Cursor 运行时格式，用于会话启动时注入 `using-superpowers` 内容；Qoder 钩子事件格式不同，无法直接移植。对应能力可通过直接调用 `using-superpowers` 技能获得 |
| `.claude-plugin/`、`.codex-plugin/`、`.cursor-plugin/`、`.devin-plugin/`、`.kimi-plugin/`、`.hermes-plugin/`、`.pi/`、`.opencode/` 等 | 其他平台的插件清单与运行时适配，非 Qoder 组件 |
| `.agents/plugins/marketplace.json` | 仅市场清单，非真实组件 |
| `package.json`、`tests/`、`scripts/`、`docs/` | pi/opencode 运行时引导与源仓库开发资料，不属于插件能力 |

## 使用方式

安装插件后，技能会出现在技能列表中。可显式调用（如通过斜杠命令或技能面板），智能体也会在匹配触发条件时自动使用。建议从阅读 `using-superpowers` 技能开始。

## 验证

- 已运行打包校验脚本 `validate_qoder_plugin.py`（结果见安装日志）
- 静态检查：`.qoder-plugin/plugin.json` 为合法 JSON；清单路径均指向真实存在的文件/目录；14 个 `SKILL.md` 均含 `name` 与 `description` 前置元数据

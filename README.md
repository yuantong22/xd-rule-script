# 脚本规则工作台（script-workbench）

决策引擎 Groovy 脚本规则的编写、校验、试运行工具，支持大模型辅助编写。

## 功能

- **写**：脚本编辑器 + AI 对话辅助编写
- **验**：一键校验（语法校验 + 占位符提取 + 大模型审查）
- **跑**：占位符填值后沙箱执行，查看返回值

## 目录结构

```
├── docs/            需求文档、技术方案、UI 风格稿
├── script_front/    前端：Vue 3 + Vite（待开发）
└── script_back/     后端：Java + Spring Boot + Spring AI Alibaba（待开发）
```

## 文档

- 需求文档：`docs/superpowers/specs/2026-09-01-script-rule-workbench-需求文档.md`
- 技术方案：`docs/superpowers/specs/2026-09-01-script-rule-workbench-技术方案.md`

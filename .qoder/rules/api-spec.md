---
trigger: always_on
---

# API 规范（后端）

- 所有接口统一使用 **POST**，路径格式：`/api/<模块>/<动作>`，例如 `/api/rule/create`、`/api/chat/send`
- 统一响应结构：`{code, message, data}`，`code=0` 表示成功
- 返回给前端的错误信息必须是**可读的中文提示**，严禁返回原始异常堆栈
- AI 对话接口 `/api/chat/send` 以 SSE 流式返回（POST + ReadableStream，不用 EventSource）
- 接口清单以 `docs/superpowers/specs/2026-09-01-script-rule-workbench-技术方案.md` 第 5 节为准，新增/修改接口需先更新该文档

---
trigger: always_on
---

# 开发约定

## 需求与文档
- 需求以 `docs/superpowers/specs/2026-09-01-script-rule-workbench-需求文档.md` 为准；技术实现以同目录《技术方案》为准
- 涉及需求变化或新的技术决策，先与用户确认，确认后更新对应文档再动手

## 脚本规则（核心业务）
- 仅支持 Groovy 脚本（不支持 Python）
- 占位符语法固定为 `${变量名}`
- 校验流程为同步：语法校验 → 提取占位符 → 大模型 CR，全部完成且语法通过才解锁运行
- 脚本内容被修改后，校验结果作废，运行重新锁定
- Groovy 执行必须走沙箱：编译期 AST 黑名单（禁 import、禁 System/Runtime/Thread/ProcessBuilder/File/Socket 等）+ 运行期 5 秒超时中断

## 前端
- UI 采用风格三（现代渐变风）：主色 #5b5fc7 → #8b5cf6、大圆角、柔和阴影，参考稿 `docs/ui-mockups/style3-gradient.html`
- 技术栈：Vue 3 + Vite + Element Plus + CodeMirror 6（legacy-modes 的 groovy 模式）

## 通用
- 代码注释、提交信息使用中文
- 提交信息格式：`<类型>: <简述>`，类型取 docs/feat/fix/refactor/test/chore

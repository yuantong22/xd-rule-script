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

## 测试（TDD，硬性约定）
- **一律先写测试再写实现**：写失败测试 → 跑一遍确认它真的失败（先红）→ 写实现 → 再跑确认通过（后绿）。不允许先写实现再补测试
- 测试跑不起来/编译不过属于预期的「红」，不要为了让它变绿而改测试断言或删 import
- **写完失败测试必须先单独跑一次、亲眼看到它红**，再动手写实现：后端 `mvn -q test -Dtest=<刚写的测试类>`，前端 `npx vitest run <刚写的 spec 文件>`。跳过这一步就无法确认测试真的在验证东西 —— 一个永远绿的空测试比没有测试更危险
- 后端用 JUnit 5，前端用 Vitest；跑之前必须先 `source tools/env.sh`
- 前端**只给有分支逻辑的纯 JS 模块写单测**（如 `http.js`、`api/rule.js`、`useValidationState.js`、SSE 解析器、回填取值）；纯装配型的 Vue 组件靠 `npm run build` + 明确的手工验收步骤把关，不为了覆盖率硬引 `jsdom` + `@vue/test-utils`（前端 vitest 用 `environment: 'node'`）
- 判定一个模块要不要测，**依据是读代码看有没有分支**，不是看它像不像「薄封装」。同在 `src/api/` 下，`script.js` 两个函数都是一行转发，不测；`rule.js` 有三个 `if (x !== undefined)`，必须测
- 决定不给某个模块写测试时，**必须写明理由**，不能默默跳过
- 修 bug 先补一条能复现它的测试，再动手修
- **大模型调用不进单测**：后端测试统一以 `app.ai.enabled=false` 走降级分支（真调会烧额度、会因网络抖动变红），真实调用只在手工验收做

## 通用
- 代码注释、提交信息使用中文
- 提交信息格式：`<类型>: <简述>`，类型取 docs/feat/fix/refactor/test/chore

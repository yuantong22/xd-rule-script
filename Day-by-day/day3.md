# Day 3（2026-09-02）：拿到百炼 Key，重做实施计划 + 深夜开工搭骨架

## 做了什么

**白天：把技术方案重做成能直接执行的实施计划**

- 拿到阿里百炼 API Key（业务空间专属，sk-ws- 前缀）后，把技术方案里 7 步粗规划拆成 **22 个可并行执行的任务**，逐任务写全参考代码与验证命令，落到 `docs/superpowers/plans/2026-09-02-script-rule-workbench.md`
- 配好 MySQL MCP，让验收阶段能直接查库核对（不只看接口 JSON）
- 逐个 API 去查 **Spring AI 1.0.0 官方 javadoc** 交叉验证，把正确写法与旧教程的错误写法做成对照表钉进计划：`InMemoryChatMemory` 已在 1.0.0 GA 移除、`MessageChatMemoryAdvisor` 变 final 只有静态 builder、`ChatMemory.CONVERSATION_ID` 必须显式传、Prompt 一律用 non-fluent 的 `new Prompt(...)` 避开模板引擎吃掉 `${}`

**深夜开工（23:06–23:55，5 笔提交，双端骨架就位）**

- 本机工具链脚本：JDK17 / Maven 装进仓库内 `tools/`，凭据走 `local-secret.env`（已 gitignore，绝不入库）
- 后端脚手架：Spring Boot 3.4.5 + 统一响应 `{code,message,data}` + 全局异常 + 健康检查
- 前端脚手架：Vue3 + Vite + Element Plus、`/api` 代理、风格三主题、请求封装
- 数据模型：rule / conversation / message / test_case 四表实体与仓库
- 规则 CRUD 后端接口与全部 DTO

## 卡在哪

- 网上大量 Spring AI 教程还是 1.0.0 GA 之前的旧写法，照着方案原文/教程抄**直接编译不过**，或更隐蔽地——功能静默失效（不报错、有回复、答非所问）
- 本机 Homebrew 已损坏，系统没有可用的 JDK / Maven；`/usr/bin/git` 是 Apple 的残缺 shim，一调就报 `xcrun: error: invalid active developer path`
- `~/.m2` 默认指向公司内网 Nexus（`10.246.80.65`），当前网络下不可达，Maven 拉依赖直接卡死

## 怎么解开的

- 不信教程，一切以 1.0.0 官方 javadoc 为准，并把「正确 vs 错误」写法做成对照表提前钉进计划，避免执行期返工
- 工具链走便携版：JDK17 / Maven 解压进 `tools/`（gitignore），由 `tools/env.sh` 统一挂 PATH 并注入配置；git 改用 `/usr/local/bin/git`（Homebrew 2.20.1，完整），在 env.sh 的 PATH 里显式补 `/usr/local/bin` 绕开坏 shim
- 单写一个 `tools/tmp-mvn/settings.xml`（gitignore）把镜像换成阿里云公共仓库，`mvn -s` 指定它，绕开不可达的内网 Nexus

## 对 AI 编程助手的认知变化

- **今天之前我以为**：
- **今天实际感受到的**：
- 一句话小结：

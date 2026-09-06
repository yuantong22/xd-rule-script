# Day 6（2026-09-05）：录屏验收日——选对大模型比调 prompt 更重要，但「最新」不等于「能用」

## 做了什么

1. 准备录屏验收：先写了一版约 2 分钟、按操作动线串起来的口播稿（列表页 → 新建规则 → 一键校验 → 填值运行 → AI 对话 → 收尾）；自己又提炼了一版更短的四点讲法——① 先验证脚本能不能编译通过 ② AI 辅助排查安全漏洞 + 给出更规范的写法 ③ 自动提取占位符让用户填值 ④ 点运行看结果是不是想要的
2. 起因：验收时体感到 `qwen-plus` 给出的 AI 审查 / 对话明显偏「降智」，决定换个更强的旗舰模型
3. 第一次换成**最新的 `qwen3.8-max`**：发现换模型压根不用改代码——模型名是透传的环境变量 `AI_CHAT_MODEL`，全局就 `application.yml` 一处引用，CR 审查和 AI 对话共用。curl 兼容模式端点返回 200（还带 `reasoning_content`，是个「会思考」的模型），后端 health 也 UP，一度以为稳了
4. **真到验收用 AI 时才发现全挂**：后端日志里 CR 调用报 `HTTP 400 InvalidParameter「url error, please check url」`，审查和对话都用不了
5. 定位 + 修复：确认项目 spring-ai-alibaba 走的是百炼**原生端点**，而 `qwen3.8-max` 只在**兼容模式端点**放出、原生端点不认它；改用 **`qwen3-max`**（原生端点实测 200），只改环境变量重启，没动一行代码
6. 端到端复验：真触发一次 CR（`/api/rule/validate` 返回 `aiReview.available=true`）+ 一次流式对话（`/api/chat/send` SSE 逐段吐字、无乱码、回答质量高），用一次性规则测完即删

## 卡在哪

1. **`qwen3.8-max` 端点不兼容**：它只在百炼兼容模式端点 `/compatible-mode/v1` 可用，而项目的 spring-ai-alibaba 1.0.0.3（`DashScopeApi.chatCompletionEntity`）走的是原生端点 `/api/v1/services/aigc/text-generation/generation`，原生端点没放出这个模型，直接 400 `url error`，整个 AI 功能瘫痪
2. **验证验错了地方，是假阳性**：换模型时只 curl 了**兼容模式端点**、又看后端 `health` UP + 日志 `AiService enabled=true` 就当成通过了。可 health 和 AiService 初始化**都不发真实模型请求**——一个只查数据库、一个只查 key 配没配，压根没碰到大模型。真正的错误只有等业务里真调一次 AI 才暴露
3. **Maven 又卡在内网 Nexus**：`mvn spring-boot:run` 默认走本机全局 settings 里那个内网镜像（10.246.80.65，当前网络不通），拉不到 parent POM 直接失败——这坑 Day 3 就踩过，今天又忘了先加镜像参数
4. **8080 端口反复「already in use」，第一次 kill 还是假成功**：用 `kill ... 2>/dev/null` 把报错吞了，又拿不严谨的 grep 判断端口，得出「已释放」的假结论；用完整的 `lsof -i :8080` + `ps` 重查，才发现一整套**跑了一天多的旧后端**（PID 5160 应用 + 5136 mvn）一直占着 8080、跑的还是旧的 `qwen-plus`，我改的环境变量根本没生效（只在进程启动时读一次）

## 怎么解开的

1. **读后端日志定位端点**：栈顶是 `DashScopeApi.chatCompletionEntity` → 确定 SDK 走原生端点，不是我以为的兼容模式
2. **写脚本把两个端点 × 三个模型全 curl 一遍**，一张表看清真相：`qwen3.8-max` 只有兼容模式活、原生端点 400；`qwen3-max` 和 `qwen-plus` 两个端点都活 → 选 `qwen3-max`（比 qwen-plus 更强的旗舰，且原生端点可用）
3. **改 `AI_CHAT_MODEL` 重启，不改代码**；停旧后端时不再吞 kill 报错，`lsof`+`ps` 核实端口真空了再起
4. **验证这次一定落到真实路径**：重启后不再只看 health，而是真发一次 `/api/rule/validate` 看 `aiReview.available=true`、再发一次 SSE 对话看逐段吐字——两条 AI 路径都绿了才算数
5. Nexus：复用项目里那份指向阿里云公共镜像的 `tools/tmp-mvn/settings.xml`，mvn 命令带上 `-s` 绕开死镜像

## 对 AI 编程助手的认知变化

- **今天之前我以为**：选个最新最强的模型、后端 health 通过就万事大吉；AI 输出不够好，接着抠 prompt 就行
- **今天实际感受到的**：
  1. **模型是能力的天花板**——同一套代码、同一套 prompt，`qwen-plus` 偏「降智」，换 `qwen3-max` 审查和对话质量明显上一个台阶，prompt 调优只是在逼近这个天花板
  2. **但「最新」不等于「能用」**——`qwen3.8-max` 更新，却跟项目 SDK 走的原生端点不兼容，一上来就把整个 AI 功能干瘫痪。选模型不只看能力强不强，还得看跟你的技术栈 / 端点兼不兼容
  3. **验证要验到真实路径上**——health UP、curl 兼容模式 200 全是假阳性，它们根本没碰大模型；只有真触发一次业务里的 AI 调用，才算验证过
- 一句话小结：**选对大模型比调 prompt 更重要，而「选对」= 既够强、又跟你的端点/SDK 兼容、还真刀真枪验证过**——三者缺一个，都会在最关键的时候翻车

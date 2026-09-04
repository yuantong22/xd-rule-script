package com.xd.rulescript.service;

import com.xd.rulescript.dto.AiReviewResult;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 大模型服务：代码审查（本任务）与对话（任务 17、18）。
 *
 * 两条铁律：
 * 1. **reviewScript 永不抛异常**。AI 挂了、Key 错了、超时了，都只返回 available=false 的降级结果。
 *    这是非功能要求 #1 的一部分：AI 不可用时，语法校验与沙箱运行必须照常可用。
 * 2. **绝不用 fluent 的 .user(script) / .system(text) 传脚本**。
 *    Spring AI 的 PromptTemplate 把 {xxx} 当模板变量，而 Groovy 占位符正是 ${xxx}，
 *    走模板会把 ${age} 解析坏。一律用 new Prompt(new SystemMessage(...), new UserMessage(...))。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 未配置 Key 时的降级文案（前端 AI 审查卡片会显示「不可用」标签） */
    public static final String UNAVAILABLE_TEXT =
            "AI 审查未启用：尚未配置大模型 API Key。语法校验与脚本运行不受影响。";
    /** 调用失败的降级文案 */
    public static final String FAILED_TEXT =
            "AI 审查暂时不可用（调用大模型失败）。语法校验结论不受影响，可直接填值运行。";

    /**
     * CR 系统提示词。
     *
     * 特别注意第一段：必须告诉模型 ${xxx} 是占位符而非错误。
     * 不交代的话模型每次都会报「变量未定义」，审查意见全是噪音。
     *
     * 【运行时环境】段也是硬要求：不交代「binding 只注入占位符」的话，模型会默认
     * 当前环境跟 Drools / Aviator / Spring 一样有 context / log / out 等隐式全局对象，
     * 不但不会报「未声明变量」，还会主动建议用户写 context.put(...)，直接造成运行时报错。
     */
    static final String CR_SYSTEM_PROMPT = """
            你是一位资深 Groovy 代码审查专家，审查的是业务规则脚本。

            【重要背景】脚本中形如 ${变量名} 的是占位符，运行前会被真实值替换，这是本系统的正常设计。
            绝对不要把占位符报告为「语法错误」「变量未定义」「缺少声明」等问题。

            【运行时环境】本项目沙箱运行时只把用户填的占位符注入 binding，除此之外没有任何隐式全局对象。
            不存在 context、log、out、session、request、binding 等常见规则引擎 / 服务端的隐式对象，
            也不存在任何预先定义的工具变量。脚本里能用的名字只有：
              • 占位符（${变量名} 形式）
              • 本脚本内 def / 类型声明的变量（如 def x = 1、int age = ${age}）
              • 本脚本内定义的方法参数、闭包参数（如 it）
              • Groovy / Java 内置类名（Integer、String、Math、List、Map 等）
            不属于以上四类的名字，运行时会抛 MissingPropertyException（前端翻译成「用到了未定义的变量」）。

            请只审查以下方面：
            1. 语义错误：条件写反、边界处理缺失、与常识不符的判断
            2. 逻辑漏洞：分支覆盖不全、可能死循环、类型误用（如把字符串当数字比较）
            3. 潜在空指针：未判空就调用方法或访问属性
            4. 未声明变量：脚本里用到的名字既不是占位符、也没有在本脚本 def / 声明、
               也不是方法 / 闭包参数或内置类名（典型幻觉：context.put(...)、log.info(...)），
               必须报告并指出具体行号与变量名
            5. 明显影响正确性的可维护性问题

            输出要求（务必严格遵守）：
            - 全程用中文，分条列出，最多 5 条，每条一行，行首用「1. 2. 3.」编号
            - 如果没有发现问题，只回复一行：审查通过，未发现明显问题
            - 不要复述脚本内容，不要描述你的审查过程，不要输出客套话
            - 如需给出修改后的脚本，必须把【完整可运行的脚本】放进 ```groovy 代码块中，
              且代码块外不要再出现任何脚本片段
            - 修改后的脚本必须保留原有的全部 ${占位符}，不要替换成具体值
            - 修改后的脚本绝不引入 context / log / out 等未声明的全局对象，需要写回结果就直接用 return
            """;

    /**
     * 对话系统提示词。
     *
     * 必须交代沙箱限制：否则模型会建议 import 类、读写文件、起线程，
     * 而那些在任务 10 的编译期黑名单下必然被拦截，用户照做只会撞墙。
     *
     * 也必须交代「运行时 binding 只有占位符」：否则模型会默认当前环境跟其他规则引擎一样
     * 有 context / log 等隐式全局对象，主动建议 context.put(...) 之类用法，造成运行时
     * MissingPropertyException。
     */
    public static final String CHAT_SYSTEM_PROMPT = """
            你是「脚本规则工作台」里的 Groovy 编程助手，帮业务人员编写规则脚本。

            【系统背景】
            - 脚本语言是 Groovy，运行前会做语法校验，通过后在沙箱里执行
            - 形如 ${变量名} 的是占位符，运行前会被用户填的真实值替换，这是本系统的正常设计，不是错误
            - 占位符类型只有五种：int / long / double / boolean / String，由变量声明语句推断
            - 沙箱限制：禁止 import 任何类、禁止包声明、禁止访问文件/网络/进程/环境变量/反射，
              禁止 System、Runtime、Thread、ProcessBuilder、File、Socket 等类型，单次执行最长 5 秒
            - 运行时 binding 里只有用户填的占位符，没有任何隐式全局对象：
              不存在 context、log、out、session、request、binding 等常见规则引擎 / 服务端的预定义对象，
              也不存在任何全局工具变量。脚本里能用的名字只有：占位符、本脚本 def / 声明的变量、
              方法 / 闭包参数（如 it）、Groovy / Java 内置类名（Integer、String、Math、List、Map 等）。
              写回结果直接用 return，不要写 context.put(...) / log.info(...)，那些运行时必然报
              「用到了未定义的变量」

            【你的任务】
            - 帮用户编写、修改、解释规则脚本
            - 主动指出逻辑漏洞、边界缺失与潜在空指针
            - 发现脚本引用了未声明的变量（典型幻觉：context、log），主动提醒并给出修正方案
            - 用户问到沙箱为什么拦截某段代码时，如实说明命中的是哪条限制

            【输出要求】
            - 全程用中文，简洁直接，不要客套话，不要复述用户的问题
            - 给出脚本时必须放进 ```groovy 代码块，且是完整可运行的脚本（不要只给片段）
            - 代码块里的占位符保持 ${变量名} 形式，不要替换成具体值
            - 绝不建议 import 类、访问文件/网络、起线程或调用 System/Runtime，那些会被沙箱拦截
            - 绝不建议使用 context / log / out / session 等未声明的全局对象，那些运行时必然报「用到了未定义的变量」
            - 脚本里必须有 return 语句返回结果，否则运行结果会显示「（无返回值）」
            """;

    private final ChatClient chatClient;
    /**
     * 记忆 Advisor。偏差（对比计划 Step 4）：不在共享 chatClient 上用 defaultAdvisors 挂，
     * 而是 chatStream 里按请求挂 —— 否则 reviewScript 不传 CONVERSATION_ID 时会回退到 "default"
     * 记忆，每次 CR 都把脚本累积进去，既涨 token 又破坏任务 16 已验证的无状态 CR。
     */
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;
    private final boolean enabled;
    private final String apiKey;
    private final int crTimeoutSeconds;
    private final int chatTimeoutSeconds;
    private final ExecutorService crExecutor;

    /**
     * @param builderProvider 用 ObjectProvider 而不是直接注入 ChatClient.Builder：
     *                        Key 缺失时 DashScope starter 可能不创建该 bean，
     *                        直接注入会导致**整个应用启动失败**，这违反非功能要求 #1
     * @param chatMemory      对话记忆 bean（任务 17 的 AiConfig 无条件装配），用来构建记忆 Advisor
     */
    public AiService(ObjectProvider<ChatClient.Builder> builderProvider,
                     ChatMemory chatMemory,
                     @Value("${app.ai.enabled:true}") boolean enabled,
                     @Value("${spring.ai.dashscope.api-key:not-configured}") String apiKey,
                     @Value("${app.ai.cr-timeout-seconds:60}") int crTimeoutSeconds,
                     @Value("${app.ai.chat-timeout-seconds:120}") int chatTimeoutSeconds,
                     @Qualifier("crExecutor") ExecutorService crExecutor) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        // 偏差（对比计划 Step 4）：chatClient 保持 builder.build()，不挂 defaultAdvisors。
        // 计划把记忆 Advisor 挂在共享 client 上，但 reviewScript 不传 CONVERSATION_ID，
        // 实测 1.0.0 的 BaseChatMemoryAdvisor 缺该 param 时回退到 "default" 记忆而非抛异常，
        // 于是每次 CR 都会把脚本与审查意见累积进 "default"，涨 token 且破坏无状态 CR。
        // 所以记忆 Advisor 单独构建、只在 chatStream 里按请求挂，reviewScript 调用链保持干净。
        this.chatClient = builder != null ? builder.build() : null;
        this.chatMemoryAdvisor = chatMemory != null
                ? MessageChatMemoryAdvisor.builder(chatMemory).build()
                : null;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.crTimeoutSeconds = crTimeoutSeconds;
        this.chatTimeoutSeconds = chatTimeoutSeconds;
        this.crExecutor = crExecutor;
        log.info("AiService 初始化：enabled={}，ChatClient={}，api-key={}",
                enabled, chatClient != null ? "已就绪" : "缺失", hasUsableKey() ? "已配置" : "未配置");
    }

    /** AI 是否可用。不可用时所有 AI 功能走降级，其余功能照常 */
    public boolean isAvailable() {
        return enabled && chatClient != null && hasUsableKey();
    }

    private boolean hasUsableKey() {
        return apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey.trim());
    }

    /**
     * 同步代码审查（需求 4.3.2 第 3 步）。
     * 校验接口会阻塞等它返回，所以必须自带超时，绝不能把 /api/rule/validate 挂死。
     *
     * @return 永远非 null；失败时 available=false 并给中文说明
     */
    public AiReviewResult reviewScript(String script) {
        if (!isAvailable()) {
            return new AiReviewResult(UNAVAILABLE_TEXT, null, false);
        }
        if (script == null || script.isBlank()) {
            return new AiReviewResult("脚本为空，无需审查", null, true);
        }

        Future<String> future = crExecutor.submit(() -> callReview(script));
        try {
            String reply = future.get(crTimeoutSeconds, TimeUnit.SECONDS);
            if (reply == null || reply.isBlank()) {
                return new AiReviewResult("AI 没有返回内容，请重试", null, false);
            }
            return new AiReviewResult(reply.trim(), AiCodeBlockExtractor.extract(reply), true);
        } catch (TimeoutException e) {
            // get 超时不会自动停止底层任务，必须显式取消，否则线程池会被慢请求占满
            future.cancel(true);
            log.warn("AI 审查超时（{} 秒），已降级", crTimeoutSeconds);
            return new AiReviewResult(
                    "AI 审查超时（超过 " + crTimeoutSeconds + " 秒），已跳过。语法校验结论不受影响，可直接填值运行。",
                    null, false);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("AI 审查被中断", e);
            return new AiReviewResult(FAILED_TEXT, null, false);
        } catch (ExecutionException | RuntimeException e) {
            // 原始异常只进日志，绝不回传前端（Global Constraints：严禁堆栈与英文异常类名）
            log.warn("AI 审查调用失败：{}", rootMessage(e));
            return new AiReviewResult(FAILED_TEXT, null, false);
        }
    }

    /**
     * 真正发起调用。**这里是本任务最关键的一处**：
     * 用 Prompt(Message...) 而非 fluent 的 .system()/.user()，避免 ${占位符} 被模板引擎解析。
     */
    private String callReview(String script) {
        Prompt prompt = new Prompt(new SystemMessage(CR_SYSTEM_PROMPT), new UserMessage(script));
        return chatClient.prompt(prompt).call().content();
    }

    /**
     * 组装发给模型的用户消息：当前脚本 + 用户的问题（需求 4.3.4）。
     *
     * 用明确的分隔标记而不是自然语言描述，模型区分「哪段是代码」更稳。
     * 这段文本通过 new UserMessage(...) 传入，不经模板引擎，所以 ${} 与 ``` 都安全。
     */
    public static String buildChatUserText(String userMessage, String scriptContent) {
        String question = userMessage == null ? "" : userMessage.trim();
        String script = scriptContent == null ? "" : scriptContent.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("【当前编辑器中的脚本】\n");
        if (script.isEmpty()) {
            sb.append("（编辑器为空，用户还没开始写脚本）\n");
        } else {
            sb.append("```groovy\n").append(script).append("\n```\n");
        }
        sb.append("\n【用户的问题】\n");
        sb.append(question.isEmpty() ? "（用户没有额外说明，请针对上面的脚本给出建议）" : question);
        return sb.toString();
    }

    /**
     * 流式对话（需求 4.3.4）。
     *
     * 返回的 Flux 每个元素是一小段增量文本，控制器直接把它包成 SSE 事件推给前端。
     * 本方法**不抛异常也不发 error 信号**：AI 不可用时返回单条降级文本，
     * 调用出错时返回一条中文说明 —— 让控制器能始终以正常收尾结束响应。
     *
     * 注意：这里**不负责持久化**。写库由 ChatService 在控制器层编排
     * （用户消息立刻写，助手消息在流完成后写），职责分开便于测试。
     */
    public Flux<String> chatStream(Long conversationId, String userMessage, String scriptContent) {
        // 偏差（对比计划 Step 4）：先判会话再判可用性。计划把 !isAvailable() 放在最前，
        // 会导致 conversationId==null 且 AI 未启用时返回「未启用」而非「会话不存在」，
        // 与本任务 Step 6 的测试意图冲突。会话缺失是更具体的路由/数据问题，优先提示更合理，
        // 且生产链路里 conversationId 恒非空（控制器用 conversationIdOf 定位，找不到即抛业务异常）。
        String key = conversationId == null ? null : "conv-" + conversationId;
        if (key == null) {
            return Flux.just("对话会话不存在，请回列表页重新进入这条规则。");
        }
        if (!isAvailable()) {
            return Flux.just("AI 对话未启用：尚未配置大模型 API Key。"
                    + "脚本的语法校验与沙箱运行不受影响，可以先用那两个功能。");
        }

        String userText = buildChatUserText(userMessage, scriptContent);
        Prompt prompt = new Prompt(new SystemMessage(CHAT_SYSTEM_PROMPT), new UserMessage(userText));

        return chatClient.prompt(prompt)
                // 偏差：按请求挂记忆 Advisor（而非构造期 defaultAdvisors），只让对话沾记忆，CR 保持无状态
                .advisors(chatMemoryAdvisor)
                // 每次请求显式指定会话，不同规则的记忆互相隔离
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, key))
                .stream()
                .content()
                .timeout(Duration.ofSeconds(chatTimeoutSeconds))
                .onErrorResume(e -> {
                    // 原始异常只进日志，绝不推给前端（Global Constraints：严禁堆栈与英文异常类名）
                    log.warn("AI 对话失败：{}", rootMessage(e));
                    // reactor 的 timeout(Duration) 抛的就是 java.util.concurrent.TimeoutException
                    String hint = e instanceof TimeoutException
                            ? "AI 回复超时（超过 " + chatTimeoutSeconds + " 秒），请重试或把问题拆小一点。"
                            : "AI 对话暂时不可用，请稍后重试。脚本的语法校验与运行不受影响。";
                    return Flux.just(hint);
                });
    }

    /** 取最内层原因，日志里看清楚是网络、鉴权还是限流 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}

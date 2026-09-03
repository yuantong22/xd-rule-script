package com.xd.rulescript.service;

import com.xd.rulescript.dto.AiReviewResult;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
     */
    static final String CR_SYSTEM_PROMPT = """
            你是一位资深 Groovy 代码审查专家，审查的是业务规则脚本。

            【重要背景】脚本中形如 ${变量名} 的是占位符，运行前会被真实值替换，这是本系统的正常设计。
            绝对不要把占位符报告为「语法错误」「变量未定义」「缺少声明」等问题。

            请只审查以下方面：
            1. 语义错误：条件写反、边界处理缺失、与常识不符的判断
            2. 逻辑漏洞：分支覆盖不全、可能死循环、类型误用（如把字符串当数字比较）
            3. 潜在空指针：未判空就调用方法或访问属性
            4. 明显影响正确性的可维护性问题

            输出要求（务必严格遵守）：
            - 全程用中文，分条列出，最多 5 条，每条一行，行首用「1. 2. 3.」编号
            - 如果没有发现问题，只回复一行：审查通过，未发现明显问题
            - 不要复述脚本内容，不要描述你的审查过程，不要输出客套话
            - 如需给出修改后的脚本，必须把【完整可运行的脚本】放进 ```groovy 代码块中，
              且代码块外不要再出现任何脚本片段
            - 修改后的脚本必须保留原有的全部 ${占位符}，不要替换成具体值
            """;

    private final ChatClient chatClient;
    private final boolean enabled;
    private final String apiKey;
    private final int crTimeoutSeconds;
    private final ExecutorService crExecutor;

    /**
     * @param builderProvider 用 ObjectProvider 而不是直接注入 ChatClient.Builder：
     *                        Key 缺失时 DashScope starter 可能不创建该 bean，
     *                        直接注入会导致**整个应用启动失败**，这违反非功能要求 #1
     */
    public AiService(ObjectProvider<ChatClient.Builder> builderProvider,
                     @Value("${app.ai.enabled:true}") boolean enabled,
                     @Value("${spring.ai.dashscope.api-key:not-configured}") String apiKey,
                     @Value("${app.ai.cr-timeout-seconds:60}") int crTimeoutSeconds,
                     @Qualifier("crExecutor") ExecutorService crExecutor) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.chatClient = builder != null ? builder.build() : null;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.crTimeoutSeconds = crTimeoutSeconds;
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

    /** 取最内层原因，日志里看清楚是网络、鉴权还是限流 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}

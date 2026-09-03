package com.xd.rulescript.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 相关配置。
 *
 * <p>任务 16 放 CR 专用线程池；任务 17 追加 ChatMemory bean（对话记忆）。
 * ChatClient.Builder 由 DashScope starter 自动配置，不在此显式声明 ——
 * Key 缺失时它可能不存在，AiService 用 ObjectProvider 兜底，避免应用启动失败（非功能要求 #1）。
 */
@Configuration
public class AiConfig {

    /**
     * CR 专用线程池。
     *
     * 为什么不复用任务 10 的 scriptExecutor：脚本执行是 CPU 密集且 5 秒必超时，
     * CR 是 IO 密集且可能十几秒，两者混在一个池里会互相饿死 ——
     * 用户连点几次校验就能把跑脚本的线程全占住。
     *
     * 固定 4 线程 + 有界队列：CR 慢的时候让请求排队，配合 AiService 的超时兜底，
     * 不至于无界堆积把内存吃掉。
     */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService crExecutor() {
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable);
            // 用 getId() 而不是 threadId()：后者是 Java 19 才加的，本项目锁定 JDK 17
            t.setName("ai-cr-" + t.getId());
            t.setDaemon(true);        // 守护线程：应用关闭时不被未完成的 CR 拖住
            return t;
        };
        return new ThreadPoolExecutor(
                4, 4,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(32),
                factory,
                // 队列满说明大模型已经严重拥堵，直接在调用线程跑（会走 AiService 的超时降级），
                // 不用 AbortPolicy 抛异常，避免用户看到「服务异常」
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 对话记忆：进程内、按 conversationId 隔离、滑动窗口。
     *
     * 窗口取 40 条（约 20 轮问答）：再大会让每次请求的 token 明显上涨，
     * 再小则多轮上下文容易断。完整历史由 MySQL 的 message 表负责（见 ChatService.history）。
     *
     * 注意：技术方案 3.3 写的 InMemoryChatMemory 在 Spring AI 1.0.0 GA 已被移除，
     * 这里用的是等价替代 —— MessageWindowChatMemory + InMemoryChatMemoryRepository。
     */
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(40)
                .build();
    }
}

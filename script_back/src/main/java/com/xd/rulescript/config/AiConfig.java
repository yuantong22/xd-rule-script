package com.xd.rulescript.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 相关配置。
 *
 * <p>本任务（任务 16）只放 CR 专用线程池；ChatMemory 的 bean 留给任务 17 追加到这里。
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
}

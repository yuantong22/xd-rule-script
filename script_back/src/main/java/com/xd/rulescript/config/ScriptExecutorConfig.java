package com.xd.rulescript.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 脚本执行专用线程池。必须和用户脚本隔离，否则一个死循环就能把 Tomcat 的工作线程占满，
 * 整个工具（包括规则列表页）都会卡死 —— 这正是非功能要求 #1 要防的事。
 *
 * <p>队列满时用 AbortPolicy 直接拒绝，由 GroovyEngineService 翻译成「当前运行的脚本过多，
 * 请稍后重试」。宁可让用户重试，也不要让请求无限堆积。
 *
 * <p>线程设为 daemon，JVM 退出不被卡住的脚本拖住；destroyMethod 用 shutdownNow，
 * 关应用时中断还在跑的脚本（配合 @ThreadInterrupt 真能停下来）。
 */
@Configuration
public class ScriptExecutorConfig {

    private static final int CORE_SIZE = 2;
    private static final int MAX_SIZE = 8;
    private static final int QUEUE_CAPACITY = 50;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    /** 静态工厂，方便脱离 Spring 上下文做单测 */
    public static ExecutorService build() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                CORE_SIZE,
                MAX_SIZE,
                KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "groovy-run-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService scriptExecutor() {
        return build();
    }
}

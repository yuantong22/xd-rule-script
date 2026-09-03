package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.ChatHistoryRequest;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.dto.ChatSendRequest;
import com.xd.rulescript.service.AiService;
import com.xd.rulescript.service.ChatMemoryService;
import com.xd.rulescript.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI 对话接口。
 *
 * history / clear 是普通 JSON 接口（任务 17）；send 是 SSE 流式对话（任务 18），
 * 返回类型是 Flux 而非 ApiResponse，所以单独一个方法，不与那两个混在一起。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AiService aiService;
    private final ChatMemoryService chatMemoryService;

    public ChatController(ChatService chatService,
                          AiService aiService,
                          ChatMemoryService chatMemoryService) {
        this.chatService = chatService;
        this.aiService = aiService;
        this.chatMemoryService = chatMemoryService;
    }

    /** 打开页面时加载历史消息回显（需求 4.3.4） */
    @PostMapping("/history")
    public ApiResponse<List<ChatMessageDto>> history(@Valid @RequestBody ChatHistoryRequest request) {
        return ApiResponse.ok(chatService.history(request.ruleId()));
    }

    /** 清空该会话的记忆与历史消息 */
    @PostMapping("/clear")
    public ApiResponse<Void> clear(@Valid @RequestBody ChatHistoryRequest request) {
        chatService.clear(request.ruleId());
        // 用任务 2 为 Void 专门提供的无参重载，比 ok(null) 清楚且不会有泛型推断歧义
        return ApiResponse.ok();
    }

    /**
     * 流式对话（需求 4.3.4）。
     *
     * Servlet 栈返回 Flux 是可行的：Spring MVC 的 ReactiveTypeHandler 检测到
     * 「Reactive Streams 返回值 + text/event-stream」会自动用 SseEmitter 桥接并逐段 flush。
     * 所以本项目不需要引入 webflux starter。
     *
     * 事件约定（任务 19 的前端按 event 名分派）：
     *   message → 增量文本，多条
     *   done    → 正常结束，一条，data 为空
     *   error   → 失败收尾，一条，data 是中文原因（出现 error 就不再发 done）
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> send(@Valid @RequestBody ChatSendRequest request) {
        Long conversationId = chatService.conversationIdOf(request.ruleId());

        // 服务重启后内存记忆是空的，先把 MySQL 里的历史灌回去，多轮上下文才接得上
        chatMemoryService.ensureLoaded(conversationId);

        // 用户消息立刻入库：即使流中途中断，历史里也该有他问过什么
        chatService.appendUser(request.ruleId(), request.message());

        StringBuilder aggregated = new StringBuilder();

        Flux<ServerSentEvent<String>> body = aiService
                .chatStream(conversationId, request.message(), request.scriptContent())
                .doOnNext(aggregated::append)
                .map(chunk -> ServerSentEvent.<String>builder().event("message").data(chunk).build());

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            // 用 defer 保证这段在流真正结束时才执行，而不是在组装 Flux 时就执行
            String full = aggregated.toString();
            if (!full.isBlank()) {
                // 只在拿到完整回复时入库；半截内容存进去会让历史回显出现残句
                chatService.appendAssistant(request.ruleId(), full);
            }
            return Flux.just(ServerSentEvent.<String>builder().event("done").data("").build());
        });

        return body.concatWith(done)
                .onErrorResume(e -> {
                    // AiService 内部已经兜了大多数错误，这里是最后一道防线（如入库失败）
                    log.warn("对话响应流异常：{}", e.getMessage());
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("对话处理出现异常，请重试。你的提问已保存，刷新页面可以看到。")
                            .build());
                });
    }
}

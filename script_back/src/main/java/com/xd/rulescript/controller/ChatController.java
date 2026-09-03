package com.xd.rulescript.controller;

import com.xd.rulescript.common.ApiResponse;
import com.xd.rulescript.dto.ChatHistoryRequest;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.service.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 对话接口。
 *
 * 本任务先提供 history / clear 两个普通 JSON 接口；
 * send 是 SSE 流式，见任务 18（返回类型不同，不能和这两个混在一个方法签名里）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
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
}

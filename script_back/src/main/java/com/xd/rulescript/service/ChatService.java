package com.xd.rulescript.service;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话与消息编排（需求 4.3.4）。
 *
 * 一条规则固定一个会话，会话在创建规则时由 RuleService 自动建立，
 * 所以这里**只查找、不新建** —— 找不到就是数据异常，直接给中文业务提示。
 *
 * 双写：每条消息既进 MySQL（管历史回显与重启恢复），也进内存记忆（管发给大模型的上下文）。
 */
@Service
public class ChatService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMemoryService chatMemoryService;

    public ChatService(ConversationRepository conversationRepository,
                       ChatMessageRepository chatMessageRepository,
                       ChatMemoryService chatMemoryService) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.chatMemoryService = chatMemoryService;
    }

    /** 规则对应的会话 ID。规则不存在或没建会话都归为同一种用户可见的错误 */
    public Long conversationIdOf(Long ruleId) {
        Conversation c = conversationRepository.findByRuleId(ruleId)
                .orElseThrow(() -> new BizException("该规则的对话会话不存在，请回列表页重新进入"));
        return c.getId();
    }

    /**
     * 历史消息，时间正序。
     * 读 MySQL 而不是内存窗口：窗口只留最近 40 条，用户要看到的是完整历史。
     */
    @Transactional(readOnly = true)
    public List<ChatMessageDto> history(Long ruleId) {
        Long conversationId = conversationIdOf(ruleId);
        return chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)
                .stream()
                .map(m -> new ChatMessageDto(
                        m.getRole(),
                        m.getContent(),
                        m.getCreatedAt() == null ? "" : m.getCreatedAt().format(TS)))
                .toList();
    }

    /**
     * 清空对话（需求 4.3.4）：清内存记忆 + 删库里消息。
     * **会话记录本身保留** —— 会话与规则一对一，删了就没法再对话了。
     */
    @Transactional
    public void clear(Long ruleId) {
        Long conversationId = conversationIdOf(ruleId);
        chatMessageRepository.deleteByConversationId(conversationId);
        chatMemoryService.clearMemory(conversationId);
    }

    @Transactional
    public void appendUser(Long ruleId, String text) {
        append(ruleId, ROLE_USER, text);
    }

    @Transactional
    public void appendAssistant(Long ruleId, String text) {
        append(ruleId, ROLE_ASSISTANT, text);
    }

    private void append(Long ruleId, String role, String text) {
        if (text == null || text.isBlank()) {
            return;                                 // 空消息不入库，避免历史里出现空气泡
        }
        Long conversationId = conversationIdOf(ruleId);

        ChatMessage row = new ChatMessage();
        row.setConversationId(conversationId);
        row.setRole(role);
        row.setContent(text);
        row.setCreatedAt(LocalDateTime.now());
        chatMessageRepository.save(row);

        // 库写成功后再进内存，顺序不能反：内存进了但库写失败会出现「重启就丢」的消息
        if (ROLE_USER.equals(role)) {
            chatMemoryService.rememberUser(conversationId, text);
        } else {
            chatMemoryService.rememberAssistant(conversationId, text);
        }

        touchConversation(conversationId);
    }

    /** 会话的 updatedAt 用来在列表里体现活跃度，顺手更新 */
    private void touchConversation(Long conversationId) {
        conversationRepository.findById(conversationId).ifPresent(c -> {
            c.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(c);
        });
    }
}

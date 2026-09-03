package com.xd.rulescript.service;

import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.repository.ChatMessageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/**
 * 对话记忆服务：只管「内存记忆」这一层，不碰 HTTP、不管业务编排。
 *
 * 存在的理由是需求非功能要求 #5：InMemoryChatMemoryRepository 重启即丢，
 * 但 message 表里有完整历史。所以每次对话前把库里的历史回灌进内存，
 * 重启后多轮上下文也能续上。
 *
 * 分工（技术方案 3.3）：**内存记忆管上下文，MySQL 管历史回显**。
 * 前端要看的完整历史走 ChatService.history()，不从这里的窗口里取。
 */
@Service
public class ChatMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);

    /** 与 AiConfig 里的 maxMessages 保持一致；超出部分在回灌时就截掉，别灌进去再让窗口淘汰 */
    private static final int WINDOW_SIZE = 40;
    private static final String KEY_PREFIX = "conv-";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ChatMemory chatMemory;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 正在回灌的会话集合，用来防并发重复灌。
     * 两个请求同时发现内存为空会各灌一遍，历史直接翻倍、token 也翻倍。
     */
    private final ConcurrentHashMap<Long, Object> loadLocks = new ConcurrentHashMap<>();

    public ChatMemoryService(ChatMemory chatMemory, ChatMessageRepository chatMessageRepository) {
        this.chatMemory = chatMemory;
        this.chatMessageRepository = chatMessageRepository;
    }

    /** ChatMemory 的 key。加前缀避免与将来其他用途的记忆撞 key */
    public String keyOf(Long conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /**
     * 确保内存里有这个会话的历史：内存为空且库里有记录时回灌。
     * 幂等 —— 已经有内容就直接返回，不重复灌。
     */
    public void ensureLoaded(Long conversationId) {
        String key = keyOf(conversationId);
        if (!chatMemory.get(key).isEmpty()) {
            return;
        }
        // computeIfAbsent 保证同一会话拿到同一把锁；不同会话互不阻塞
        synchronized (loadLocks.computeIfAbsent(conversationId, id -> new Object())) {
            try {
                // 双检：等锁期间可能已被另一个线程灌好
                if (!chatMemory.get(key).isEmpty()) {
                    return;
                }
                List<Message> fromDb = loadFromDatabase(conversationId);
                if (fromDb.isEmpty()) {
                    return;
                }
                chatMemory.add(key, fromDb);
                log.info("会话 {} 从 MySQL 回灌 {} 条历史消息", conversationId, fromDb.size());
            } finally {
                loadLocks.remove(conversationId);
            }
        }
    }

    /** 从库里读最近 WINDOW_SIZE 条，转成 Spring AI 的 Message，时间正序 */
    private List<Message> loadFromDatabase(Long conversationId) {
        List<ChatMessage> all = chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        // 只留最近窗口条数，且保留的是靠后的（旧的截掉）
        int from = Math.max(0, all.size() - WINDOW_SIZE);
        List<Message> messages = new ArrayList<>(all.size() - from);
        for (ChatMessage row : all.subList(from, all.size())) {
            Message m = toMessage(row);
            if (m != null) {
                messages.add(m);
            }
        }
        return messages;
    }

    /** 库里的 role 字符串 → Spring AI 消息对象；未知角色与空内容返回 null 由调用方跳过 */
    private Message toMessage(ChatMessage row) {
        String content = row.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        return switch (row.getRole() == null ? "" : row.getRole()) {
            case ROLE_USER -> new UserMessage(content);
            case ROLE_ASSISTANT -> new AssistantMessage(content);
            // system 与未知角色不回灌：system 提示由每次请求现拼，
            // 灌进记忆会让它被窗口保留、还会在多轮里重复出现
            default -> null;
        };
    }

    public void rememberUser(Long conversationId, String text) {
        if (text != null && !text.isBlank()) {
            chatMemory.add(keyOf(conversationId), new UserMessage(text));
        }
    }

    public void rememberAssistant(Long conversationId, String text) {
        if (text != null && !text.isBlank()) {
            chatMemory.add(keyOf(conversationId), new AssistantMessage(text));
        }
    }

    /** 只清内存。库里的历史由 ChatService.clear() 负责删 */
    public void clearMemory(Long conversationId) {
        chatMemory.clear(keyOf(conversationId));
    }

    /** 当前内存里有多少条，测试与排查用 */
    public int memorySize(Long conversationId) {
        return chatMemory.get(keyOf(conversationId)).size();
    }
}

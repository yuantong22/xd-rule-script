package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内存记忆与 MySQL 历史之间的搬运（需求非功能要求 #5：服务重启后历史消息不丢）。
 */
@SpringBootTest(properties = "app.ai.enabled=false")
@Transactional
class ChatMemoryServiceTest {

    @Autowired private ChatMemoryService chatMemoryService;
    @Autowired private ChatMemory chatMemory;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    private Long conversationId;

    @BeforeEach
    void setUp() {
        Conversation c = new Conversation();
        c.setRuleId(990_001L);
        c.setTitle("测试会话");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conversationId = conversationRepository.save(c).getId();
        // 每个用例都从干净的记忆开始，否则上一个用例灌进去的内容会干扰断言
        chatMemory.clear(chatMemoryService.keyOf(conversationId));
    }

    private void saveMessage(String role, String content, int seq) {
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        // 用递增秒数保证 createdAt 顺序确定，避免同一秒内排序不稳定
        m.setCreatedAt(LocalDateTime.now().plusSeconds(seq));
        chatMessageRepository.save(m);
    }

    @Test
    void key带conv前缀() {
        assertThat(chatMemoryService.keyOf(12L)).isEqualTo("conv-12");
    }

    @Test
    void 库里没历史时回灌后记忆仍为空() {
        chatMemoryService.ensureLoaded(conversationId);
        assertThat(chatMemoryService.memorySize(conversationId)).isZero();
    }

    @Test
    void 库里有历史时回灌进内存且角色正确() {
        saveMessage("user", "帮我写个规则", 0);
        saveMessage("assistant", "好的，脚本如下", 1);

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).hasSize(2);
        assertThat(memory.get(0).getMessageType()).isEqualTo(MessageType.USER);
        assertThat(memory.get(0).getText()).isEqualTo("帮我写个规则");
        assertThat(memory.get(1).getMessageType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(memory.get(1).getText()).isEqualTo("好的，脚本如下");
    }

    @Test
    void 回灌是幂等的不会灌两遍() {
        saveMessage("user", "第一条", 0);
        saveMessage("assistant", "第二条", 1);

        chatMemoryService.ensureLoaded(conversationId);
        chatMemoryService.ensureLoaded(conversationId);
        chatMemoryService.ensureLoaded(conversationId);

        assertThat(chatMemoryService.memorySize(conversationId)).isEqualTo(2);
    }

    @Test
    void 内存已有内容时不再回灌() {
        saveMessage("user", "库里的旧消息", 0);
        chatMemoryService.rememberUser(conversationId, "内存里的新消息");

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).hasSize(1);
        assertThat(memory.get(0).getText()).isEqualTo("内存里的新消息");
    }

    @Test
    void 回灌只取最近窗口条数() {
        // 造 45 条，窗口是 40，回灌后不该超过 40
        for (int i = 0; i < 45; i++) {
            saveMessage(i % 2 == 0 ? "user" : "assistant", "消息" + i, i);
        }

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).hasSizeLessThanOrEqualTo(40);
        // 且保留的是**靠后**的那些（旧的被截掉），最后一条必须是 消息44
        assertThat(memory.get(memory.size() - 1).getText()).isEqualTo("消息44");
    }

    @Test
    void 回灌按时间正序而非倒序() {
        saveMessage("user", "早", 0);
        saveMessage("assistant", "中", 1);
        saveMessage("user", "晚", 2);

        chatMemoryService.ensureLoaded(conversationId);

        assertThat(chatMemory.get(chatMemoryService.keyOf(conversationId)))
                .extracting(Message::getText)
                .containsExactly("早", "中", "晚");
    }

    @Test
    void 未知角色被跳过不炸() {
        saveMessage("user", "正常", 0);
        saveMessage("system", "系统消息", 1);      // 库里不该有，但防御性处理
        saveMessage("weird", "未知角色", 2);

        chatMemoryService.ensureLoaded(conversationId);

        List<Message> memory = chatMemory.get(chatMemoryService.keyOf(conversationId));
        assertThat(memory).extracting(Message::getText).contains("正常");
        assertThat(memory).noneMatch(m -> "未知角色".equals(m.getText()));
    }

    @Test
    void 空内容消息被跳过() {
        saveMessage("user", "正常", 0);
        saveMessage("assistant", "", 1);
        // 原计划此处存 null，但 message.content 列是 NOT NULL（无法落库），
        // 改用纯空白串：同样应被 isBlank 判定跳过，断言与意图不变
        saveMessage("assistant", "   ", 2);

        chatMemoryService.ensureLoaded(conversationId);

        assertThat(chatMemoryService.memorySize(conversationId)).isEqualTo(1);
    }

    @Test
    void 写入用户与助手消息() {
        chatMemoryService.rememberUser(conversationId, "问");
        chatMemoryService.rememberAssistant(conversationId, "答");

        assertThat(chatMemory.get(chatMemoryService.keyOf(conversationId)))
                .extracting(Message::getText)
                .containsExactly("问", "答");
    }

    @Test
    void 清空记忆() {
        chatMemoryService.rememberUser(conversationId, "问");
        chatMemoryService.clearMemory(conversationId);
        assertThat(chatMemoryService.memorySize(conversationId)).isZero();
    }

    @Test
    void 清空不存在的会话不抛异常() {
        assertThat(chatMemoryService.memorySize(888_888L)).isZero();
        chatMemoryService.clearMemory(888_888L);   // 不该炸
    }
}

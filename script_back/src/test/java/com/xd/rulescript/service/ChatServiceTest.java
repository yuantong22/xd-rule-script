package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.ChatMessageDto;
import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话定位与消息持久化。
 * 需求 4.3.4：一条规则固定一个会话，创建规则时自动建立，无会话列表。
 */
@SpringBootTest(properties = "app.ai.enabled=false")
@Transactional
class ChatServiceTest {

    @Autowired private ChatService chatService;
    @Autowired private ChatMemoryService chatMemoryService;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    /** 用一个不存在的 ruleId，避免和 RuleServiceTest 造的数据互相干扰 */
    private static final Long RULE_ID = 990_002L;
    private Long conversationId;

    @BeforeEach
    void setUp() {
        Conversation c = new Conversation();
        c.setRuleId(RULE_ID);
        c.setTitle("规则 " + RULE_ID + " 的会话");
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conversationId = conversationRepository.save(c).getId();
        chatMemoryService.clearMemory(conversationId);
    }

    @Test
    void 按规则ID找到会话() {
        assertThat(chatService.conversationIdOf(RULE_ID)).isEqualTo(conversationId);
    }

    @Test
    void 规则没有会话时给中文业务异常() {
        assertThatThrownBy(() -> chatService.conversationIdOf(777_777L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("会话");
    }

    @Test
    void 空历史返回空列表而不是null() {
        List<ChatMessageDto> h = chatService.history(RULE_ID);
        assertThat(h).isNotNull().isEmpty();
    }

    @Test
    void 历史按时间正序返回且字段完整() {
        chatService.appendUser(RULE_ID, "第一问");
        chatService.appendAssistant(RULE_ID, "第一答");
        chatService.appendUser(RULE_ID, "第二问");

        List<ChatMessageDto> h = chatService.history(RULE_ID);

        assertThat(h).hasSize(3);
        assertThat(h.get(0).role()).isEqualTo("user");
        assertThat(h.get(0).content()).isEqualTo("第一问");
        assertThat(h.get(1).role()).isEqualTo("assistant");
        assertThat(h.get(2).content()).isEqualTo("第二问");
        // createdAt 是给前端排序与展示用的，不能为空
        assertThat(h.get(0).createdAt()).isNotBlank();
    }

    @Test
    void 历史读的是MySQL而不是内存窗口() {
        // 造 45 条：内存窗口只留 40，但历史必须能看全
        for (int i = 0; i < 45; i++) {
            chatService.appendUser(RULE_ID, "消息" + i);
        }
        assertThat(chatService.history(RULE_ID)).hasSize(45);
        assertThat(chatMemoryService.memorySize(conversationId)).isLessThanOrEqualTo(40);
    }

    @Test
    void 追加消息同时写库和写内存() {
        chatService.appendUser(RULE_ID, "问");

        List<ChatMessage> rows = chatMessageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRole()).isEqualTo("user");
        assertThat(rows.get(0).getContent()).isEqualTo("问");

        assertThat(chatMemoryService.memorySize(conversationId)).isEqualTo(1);
    }

    @Test
    void 追加助手消息角色正确() {
        chatService.appendAssistant(RULE_ID, "答");
        ChatMessage row = chatMessageRepository
                .findByConversationIdOrderByCreatedAtAscIdAsc(conversationId).get(0);
        assertThat(row.getRole()).isEqualTo("assistant");
    }

    @Test
    void 空消息不入库() {
        chatService.appendUser(RULE_ID, "   ");
        chatService.appendUser(RULE_ID, null);
        assertThat(chatService.history(RULE_ID)).isEmpty();
    }

    @Test
    void 清空同时删库和清内存但保留会话本身() {
        chatService.appendUser(RULE_ID, "问");
        chatService.appendAssistant(RULE_ID, "答");

        chatService.clear(RULE_ID);

        assertThat(chatService.history(RULE_ID)).isEmpty();
        assertThat(chatMemoryService.memorySize(conversationId)).isZero();
        // 需求 4.3.4：会话与规则一对一且不能删除，清空对话只清消息
        assertThat(conversationRepository.findByRuleId(RULE_ID)).isPresent();
    }

    @Test
    void 清空后还能继续对话() {
        chatService.appendUser(RULE_ID, "旧消息");
        chatService.clear(RULE_ID);
        chatService.appendUser(RULE_ID, "新消息");

        List<ChatMessageDto> h = chatService.history(RULE_ID);
        assertThat(h).hasSize(1);
        assertThat(h.get(0).content()).isEqualTo("新消息");
    }

    @Test
    void 清空没有消息的会话不抛异常() {
        chatService.clear(RULE_ID);
        chatService.clear(RULE_ID);      // 连续两次也不该炸
        assertThat(chatService.history(RULE_ID)).isEmpty();
    }

    @Test
    void 消息内容里的占位符与代码块原样保留() {
        // 对话会带脚本上下文，${} 与 ``` 不能被转义或截断
        String script = "int age = ${age}\n```groovy\nreturn age\n```";
        chatService.appendUser(RULE_ID, script);

        assertThat(chatService.history(RULE_ID).get(0).content()).isEqualTo(script);
    }
}

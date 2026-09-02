package com.xd.rulescript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.entity.ChatMessage;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.entity.Rule;
import com.xd.rulescript.entity.TestCase;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

/**
 * 四张表的落库与查询冒烟测试。@Transactional 让每个用例结束后自动回滚，不留脏数据。
 * 前置：本机 MySQL 在跑，库 script_workbench 已建。
 */
@SpringBootTest
@Transactional
class RepositorySmokeTest {

    @Autowired private RuleRepository ruleRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private TestCaseRepository testCaseRepository;

    @Test
    void 规则能落库并自动填充两个时间戳() {
        Rule rule = new Rule();
        rule.setName("冒烟规则");
        rule.setDescription("用于测试");
        rule.setScriptContent("return 1");
        Rule saved = ruleRepository.save(rule);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(ruleRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void 规则能按名称模糊搜索并按更新时间倒序分页() {
        Rule a = new Rule(); a.setName("折扣判定"); a.setScriptContent(""); ruleRepository.save(a);
        Rule b = new Rule(); b.setName("风控判定"); b.setScriptContent(""); ruleRepository.save(b);

        Page<Rule> hit = ruleRepository.findByNameContaining("折扣",
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")));
        assertThat(hit.getContent()).extracting(Rule::getName).contains("折扣判定");
        assertThat(hit.getContent()).extracting(Rule::getName).doesNotContain("风控判定");
    }

    @Test
    void 会话与规则一对一并能按规则ID查到() {
        Rule rule = new Rule(); rule.setName("带会话的规则"); rule.setScriptContent("");
        rule = ruleRepository.save(rule);

        Conversation conversation = new Conversation();
        conversation.setRuleId(rule.getId());
        conversation.setTitle(rule.getName());
        conversationRepository.save(conversation);

        Optional<Conversation> found = conversationRepository.findByRuleId(rule.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("带会话的规则");
    }

    @Test
    void 消息按时间升序返回并能按会话删除() {
        Conversation conversation = new Conversation();
        conversation.setRuleId(-1L);
        conversation.setTitle("临时");
        conversation = conversationRepository.save(conversation);

        ChatMessage first = new ChatMessage();
        first.setConversationId(conversation.getId());
        first.setRole("user");
        first.setContent("第一条");
        messageRepository.save(first);

        ChatMessage second = new ChatMessage();
        second.setConversationId(conversation.getId());
        second.setRole("assistant");
        second.setContent("第二条");
        messageRepository.save(second);

        List<ChatMessage> history =
                messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
        assertThat(history).extracting(ChatMessage::getContent).containsExactly("第一条", "第二条");

        messageRepository.deleteByConversationId(conversation.getId());
        assertThat(messageRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId())).isEmpty();
    }

    @Test
    void 测试用例能按规则查询并能按规则删除() {
        TestCase testCase = new TestCase();
        testCase.setRuleId(-2L);
        testCase.setName("vip 成年");
        testCase.setParamsJson("{\"age\":\"28\",\"level\":\"vip\"}");
        testCaseRepository.save(testCase);

        assertThat(testCaseRepository.findByRuleIdOrderByIdAsc(-2L)).hasSize(1);
        testCaseRepository.deleteByRuleId(-2L);
        assertThat(testCaseRepository.findByRuleIdOrderByIdAsc(-2L)).isEmpty();
    }
}

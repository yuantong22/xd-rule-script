package com.xd.rulescript.service;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleItem;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.entity.Conversation;
import com.xd.rulescript.entity.Rule;
import com.xd.rulescript.repository.ChatMessageRepository;
import com.xd.rulescript.repository.ConversationRepository;
import com.xd.rulescript.repository.RuleRepository;
import com.xd.rulescript.repository.TestCaseRepository;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则管理。负责 CRUD、校验编排、运行编排。
 * 校验与运行方法在任务 12 补上（依赖 GroovyEngineService）。
 */
@Service
public class RuleService {

    /** 对外统一的时间格式，前端直接展示，不再做时区/格式转换 */
    static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final RuleRepository ruleRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final TestCaseRepository testCaseRepository;

    public RuleService(RuleRepository ruleRepository,
                       ConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       TestCaseRepository testCaseRepository) {
        this.ruleRepository = ruleRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.testCaseRepository = testCaseRepository;
    }

    public RulePageResponse list(RuleListRequest request) {
        int page = request.page() == null || request.page() < 1 ? 1 : request.page();
        int size = request.size() == null || request.size() < 1 ? DEFAULT_PAGE_SIZE : Math.min(request.size(), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));

        String keyword = request.name() == null ? "" : request.name().trim();
        Page<Rule> result = keyword.isEmpty()
                ? ruleRepository.findAll(pageable)
                : ruleRepository.findByNameContaining(keyword, pageable);

        List<RuleItem> items = result.getContent().stream().map(RuleService::toItem).toList();
        return new RulePageResponse(items, result.getTotalElements(), page, size);
    }

    /** 新建规则：同时自动生成一对一的会话（需求 4.3.4：规则创建时自动建立会话） */
    @Transactional
    public RuleDetailResponse create(RuleCreateRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException("规则名称不能为空");
        }
        Rule rule = new Rule();
        rule.setName(name);
        rule.setDescription(request.description() == null ? "" : request.description().trim());
        rule.setScriptContent(defaultScript(name));
        rule = ruleRepository.save(rule);

        Conversation conversation = new Conversation();
        conversation.setRuleId(rule.getId());
        conversation.setTitle(rule.getName());
        conversationRepository.save(conversation);

        return toDetail(rule);
    }

    public RuleDetailResponse detail(Long ruleId) {
        return toDetail(requireRule(ruleId));
    }

    /** 部分更新：字段为 null 表示不改 */
    @Transactional
    public RuleDetailResponse update(RuleUpdateRequest request) {
        Rule rule = requireRule(request.ruleId());
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BizException("规则名称不能为空");
            }
            rule.setName(name);
        }
        if (request.description() != null) {
            rule.setDescription(request.description().trim());
        }
        if (request.scriptContent() != null) {
            rule.setScriptContent(request.scriptContent());
        }
        return toDetail(ruleRepository.save(rule));
    }

    /** 删除规则：级联清掉它的会话、消息、测试用例（需求 4.2） */
    @Transactional
    public void delete(Long ruleId) {
        Rule rule = requireRule(ruleId);
        conversationRepository.findByRuleId(ruleId).ifPresent(conversation -> {
            messageRepository.deleteByConversationId(conversation.getId());
            conversationRepository.delete(conversation);
        });
        testCaseRepository.deleteByRuleId(ruleId);
        ruleRepository.delete(rule);
    }

    private Rule requireRule(Long ruleId) {
        if (ruleId == null) {
            throw new BizException("规则 ID 不能为空");
        }
        return ruleRepository.findById(ruleId)
                .orElseThrow(() -> new BizException("规则不存在或已被删除"));
    }

    private static RuleItem toItem(Rule rule) {
        return new RuleItem(rule.getId(), rule.getName(), rule.getDescription(),
                rule.getUpdatedAt() == null ? "" : TIMESTAMP.format(rule.getUpdatedAt()));
    }

    private static RuleDetailResponse toDetail(Rule rule) {
        return new RuleDetailResponse(rule.getId(), rule.getName(), rule.getDescription(),
                rule.getScriptContent(),
                rule.getUpdatedAt() == null ? "" : TIMESTAMP.format(rule.getUpdatedAt()));
    }

    /** 新规则的初始脚本，与 UI 参考稿里的示例一致，用户建完就能直接点校验、填值、运行 */
    static String defaultScript(String ruleName) {
        return """
                // 规则：%s
                // ${变量名} 是占位符，运行前需要在下方填值
                int age = ${age}
                String level = "${level}"
                if (level == "vip" && age >= 18) {
                    return "享受8折优惠"
                }
                return "无折扣"
                """.formatted(ruleName);
    }
}

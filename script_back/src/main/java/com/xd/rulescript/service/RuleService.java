package com.xd.rulescript.service;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.AiReviewResult;
import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleItem;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.dto.RunRequest;
import com.xd.rulescript.dto.RunResponse;
import com.xd.rulescript.dto.RunResult;
import com.xd.rulescript.dto.SyntaxCheckResult;
import com.xd.rulescript.dto.ValidateRequest;
import com.xd.rulescript.dto.ValidateResponse;
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
 * 校验与运行的实现见下方 validate / run（依赖 GroovyEngineService）。
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
    private final GroovyEngineService groovyEngineService;
    private final AiService aiService;

    public RuleService(RuleRepository ruleRepository,
                       ConversationRepository conversationRepository,
                       ChatMessageRepository messageRepository,
                       TestCaseRepository testCaseRepository,
                       GroovyEngineService groovyEngineService,
                       AiService aiService) {
        this.ruleRepository = ruleRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.testCaseRepository = testCaseRepository;
        this.groovyEngineService = groovyEngineService;
        this.aiService = aiService;
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

    /**
     * 同步校验：语法校验 → 提取占位符 → 大模型 CR，三步全部完成才返回。
     *
     * <p>语法不通过时不再调 CR：既省时间也省额度，用户改完再校验一次就行。
     * <p>CR 是建议性质，不影响 syntaxOk（需求文档交互规则 #5）。
     */
    public ValidateResponse validate(ValidateRequest request) {
        String script = request == null ? null : request.scriptContent();

        SyntaxCheckResult syntax = groovyEngineService.checkSyntax(script);
        List<PlaceholderInfo> placeholders = groovyEngineService.extractPlaceholders(script);

        AiReviewResult review;
        if (!syntax.ok()) {
            // 语法都没过，不必惊动大模型
            review = new AiReviewResult("语法未通过，已跳过 AI 审查。请先修正上面的语法错误", null, false);
        } else {
            // 第三步：大模型 CR（同步等待，自带超时与降级，见 AiService）
            review = aiService.reviewScript(script);
        }

        return new ValidateResponse(
                syntax.ok(),
                syntax.line(),
                syntax.message(),
                placeholders,
                review);
    }

    /**
     * 沙箱运行。只吃请求体里的脚本与填值，不读数据库 ——
     * 需求文档交互规则 #3：运行用的是编辑器当前内容，含未保存的修改。
     */
    public RunResponse run(RunRequest request) {
        if (request == null) {
            throw new BizException("运行参数不能为空");
        }
        RunResult result = groovyEngineService.run(request.scriptContent(), request.params());
        return new RunResponse(result.success(), result.value(), result.errorMessage(), result.timeout());
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

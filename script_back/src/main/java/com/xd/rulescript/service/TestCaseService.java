package com.xd.rulescript.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.TestCaseDto;
import com.xd.rulescript.dto.TestCaseSaveRequest;
import com.xd.rulescript.entity.TestCase;
import com.xd.rulescript.repository.RuleRepository;
import com.xd.rulescript.repository.TestCaseRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试用例 CRUD：一组占位符填值的快照（需求 4.3.3）。
 *
 * params 以 JSON 字符串存在 test_case.params_json（TEXT 列），序列化在本类完成 ——
 * 实体只认字符串，不让 JPA 参与 JSON 转换，避免引入 AttributeConverter 之类的额外机制。
 */
@Service
public class TestCaseService {

    private static final Logger log = LoggerFactory.getLogger(TestCaseService.class);

    /**
     * 用例名的业务上限。DB 列宽是 128，这里取一半：
     * 下拉框放不下更长的名字，且要在它变成数据库的 Data too long 异常之前用中文拦掉。
     * 包级可见是为了让测试直接引用，改这个值不用同步改测试。
     */
    static final int NAME_MAX = 64;

    private static final TypeReference<Map<String, String>> PARAMS_TYPE = new TypeReference<>() {};

    private final TestCaseRepository testCaseRepository;
    private final RuleRepository ruleRepository;
    /** Spring Boot 自动配置的 bean，别自己 new —— 自建实例没有 Boot 的模块定制，行为会与全局不一致 */
    private final ObjectMapper objectMapper;

    public TestCaseService(TestCaseRepository testCaseRepository,
                           RuleRepository ruleRepository,
                           ObjectMapper objectMapper) {
        this.testCaseRepository = testCaseRepository;
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<TestCaseDto> list(Long ruleId) {
        if (ruleId == null) {
            throw new BizException("规则 ID 不能为空");
        }
        List<TestCaseDto> out = new ArrayList<>();
        for (TestCase entity : testCaseRepository.findByRuleIdOrderByIdAsc(ruleId)) {
            out.add(toDto(entity));
        }
        return out;
    }

    @Transactional
    public TestCaseDto save(TestCaseSaveRequest request) {
        Long ruleId = request.ruleId();
        if (ruleId == null) {
            throw new BizException("规则 ID 不能为空");
        }
        // 先确认规则在，否则会留下孤儿用例（规则删了用例还在，且再也查不出来）
        if (!ruleRepository.existsById(ruleId)) {
            throw new BizException("规则不存在或已被删除");
        }

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException("用例名称不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw new BizException("用例名称太长，请控制在 " + NAME_MAX + " 字以内");
        }

        // 判重：任务 4 定稿的仓库只有 findByRuleIdOrderByIdAsc / deleteByRuleId 两个方法，
        // 不为这一个判断去改已定稿的接口。一条规则的用例就几条到几十条，全捞出来比对完全够。
        boolean duplicated = testCaseRepository.findByRuleIdOrderByIdAsc(ruleId).stream()
                .anyMatch(existing -> name.equals(existing.getName()));
        if (duplicated) {
            throw new BizException("已存在同名用例「" + name + "」，请换个名字");
        }

        TestCase entity = new TestCase();
        entity.setRuleId(ruleId);
        entity.setName(name);
        entity.setParamsJson(writeJson(normalize(request.params())));
        return toDto(testCaseRepository.save(entity));
    }

    @Transactional
    public void delete(Long testCaseId) {
        if (testCaseId == null) {
            throw new BizException("用例 ID 不能为空");
        }
        // 显式 findById 而不是 deleteById：后者对不存在的 id 静默无操作，
        // 用户点了删除却什么反馈都没有，会以为没点上而反复点
        TestCase entity = testCaseRepository.findById(testCaseId)
                .orElseThrow(() -> new BizException("用例不存在或已被删除"));
        testCaseRepository.delete(entity);
    }

    /**
     * 规整参数：用 LinkedHashMap 保住键顺序（回填时输入框的出现顺序要跟保存时一致，
     * 换成 HashMap 顺序就随哈希散了），null 值统一成空串。
     */
    private Map<String, String> normalize(Map<String, String> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) {
            return out;
        }
        params.forEach((key, value) -> {
            if (key != null) {
                out.put(key, value == null ? "" : value);
            }
        });
        return out;
    }

    private String writeJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            // checked 异常绝不能漏到 controller：全局处理器只会给「系统异常」这种没用的提示
            log.warn("测试用例参数序列化失败: {}", e.getOriginalMessage());
            throw new BizException("参数格式有问题，无法保存用例");
        }
    }

    private Map<String, String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, PARAMS_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException e) {
            // 一条脏数据不该让整个列表接口 500：退化成空参数，用例还在、名字还能看见，
            // 只是回填不出值。损失可控，远好过用户看到「一个用例都没有」
            log.warn("测试用例参数解析失败，按空参数处理，内容长度={}", json.length());
            return new LinkedHashMap<>();
        }
    }

    private TestCaseDto toDto(TestCase entity) {
        return new TestCaseDto(entity.getId(), entity.getName(), readJson(entity.getParamsJson()));
    }
}

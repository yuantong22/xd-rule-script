package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.PlaceholderInfo;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.dto.RunRequest;
import com.xd.rulescript.dto.RunResponse;
import com.xd.rulescript.dto.ValidateRequest;
import com.xd.rulescript.dto.ValidateResponse;
import com.xd.rulescript.repository.ConversationRepository;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则 CRUD 行为测试。
 */
@SpringBootTest(properties = "app.ai.enabled=false")
@Transactional
class RuleServiceTest {

    @Autowired private RuleService ruleService;
    @Autowired private ConversationRepository conversationRepository;

    @Test
    void 新建规则会同时生成一个会话并带默认脚本() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("VIP 折扣判定", "按年龄和会员等级判定"));

        assertThat(created.id()).isNotNull();
        assertThat(created.name()).isEqualTo("VIP 折扣判定");
        assertThat(created.scriptContent()).contains("${age}").contains("${level}");
        assertThat(conversationRepository.findByRuleId(created.id())).isPresent();
    }

    @Test
    void 名称为空时拒绝创建() {
        assertThatThrownBy(() -> ruleService.create(new RuleCreateRequest("   ", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规则名称不能为空");
    }

    @Test
    void 列表按名称模糊搜索且分页信息正确() {
        ruleService.create(new RuleCreateRequest("折扣规则甲", null));
        ruleService.create(new RuleCreateRequest("风控规则乙", null));

        RulePageResponse page = ruleService.list(new RuleListRequest("折扣", 1, 10));

        assertThat(page.items()).extracting("name").contains("折扣规则甲");
        assertThat(page.items()).extracting("name").doesNotContain("风控规则乙");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 列表页码缺省时按第一页处理() {
        ruleService.create(new RuleCreateRequest("缺省分页规则", null));
        RulePageResponse page = ruleService.list(new RuleListRequest(null, null, null));
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(10);
    }

    @Test
    void 更新时null字段保持原值() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("原名", "原描述"));
        String originalScript = created.scriptContent();

        RuleDetailResponse updated = ruleService.update(new RuleUpdateRequest(created.id(), "新名", null, null));

        assertThat(updated.name()).isEqualTo("新名");
        assertThat(updated.description()).isEqualTo("原描述");
        assertThat(updated.scriptContent()).isEqualTo(originalScript);
    }

    @Test
    void 更新脚本内容会落库() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("脚本更新", null));
        RuleDetailResponse updated = ruleService.update(
                new RuleUpdateRequest(created.id(), null, null, "return '改过了'"));
        assertThat(updated.scriptContent()).isEqualTo("return '改过了'");
    }

    @Test
    void 详情不存在时给出中文提示() {
        assertThatThrownBy(() -> ruleService.detail(999999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规则不存在");
    }

    @Test
    void 删除规则会连带删除会话() {
        RuleDetailResponse created = ruleService.create(new RuleCreateRequest("待删除", null));
        ruleService.delete(created.id());

        assertThatThrownBy(() -> ruleService.detail(created.id()))
                .isInstanceOf(BizException.class);
        assertThat(conversationRepository.findByRuleId(created.id())).isEmpty();
    }

    // ---------- validate ----------

    @Test
    void 校验合法脚本返回语法通过和占位符列表() {
        ValidateResponse r = ruleService.validate(new ValidateRequest(
                "int age = ${age}\nString level = \"${level}\"\nreturn age >= 18 ? level : \"minor\""));

        assertThat(r.syntaxOk()).isTrue();
        assertThat(r.errorLine()).isNull();
        assertThat(r.errorMessage()).isNull();
        assertThat(r.placeholders()).containsExactly(
                new PlaceholderInfo("age", "int"),
                new PlaceholderInfo("level", "String"));
    }

    @Test
    void 校验语法错误脚本返回行号与中文原因() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("int a = 1\nreturn a + b))"));

        assertThat(r.syntaxOk()).isFalse();
        assertThat(r.errorLine()).isEqualTo(2);
        assertThat(r.errorMessage()).isNotBlank();
        // 语法不通过时占位符仍要给，方便用户改完再校验
        assertThat(r.placeholders()).isNotNull();
    }

    @Test
    void 语法错误时不调用大模型CR() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("return ((( "));

        assertThat(r.syntaxOk()).isFalse();
        assertThat(r.aiReview().available()).isFalse();
        assertThat(r.aiReview().suggestedScript()).isNull();
    }

    @Test
    void AI关闭时校验仍给出降级文案且不影响语法结论() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("return 1 + 1"));

        assertThat(r.syntaxOk()).isTrue();
        assertThat(r.aiReview().available()).isFalse();
        assertThat(r.aiReview().text()).isNotBlank();
        assertThat(r.aiReview().suggestedScript()).isNull();
    }

    @Test
    void 校验空脚本不报错() {
        ValidateResponse r = ruleService.validate(new ValidateRequest(""));
        assertThat(r.syntaxOk()).isTrue();
        assertThat(r.placeholders()).isEmpty();
    }

    @Test
    void 校验危险脚本被拦截且语法结论为不通过() {
        ValidateResponse r = ruleService.validate(new ValidateRequest("System.exit(0)\nreturn 1"));

        assertThat(r.syntaxOk()).isFalse();
        assertThat(r.errorMessage()).contains("【安全拦截】");
    }

    // ---------- run ----------

    @Test
    void 运行返回结果字符串() {
        RunResponse r = ruleService.run(new RunRequest(
                "int age = ${age}\nreturn age >= 18 ? \"成年\" : \"未成年\"",
                Map.of("age", "28")));

        assertThat(r.success()).isTrue();
        assertThat(r.value()).isEqualTo("成年");
        assertThat(r.errorMessage()).isNull();
        assertThat(r.timeout()).isFalse();
    }

    @Test
    void 运行缺少填值返回中文提示而不是异常() {
        RunResponse r = ruleService.run(new RunRequest("return ${age}", Map.of()));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("缺少");
    }

    @Test
    void 运行危险脚本被拦截() {
        RunResponse r = ruleService.run(new RunRequest(
                "return new File('/etc/passwd').text", Map.of()));

        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("【安全拦截】");
    }

    @Test
    void 运行结果不泄露堆栈() {
        RunResponse r = ruleService.run(new RunRequest("return 1 / 0", Map.of()));
        assertThat(r.errorMessage()).doesNotContain("\tat ").doesNotContain("Exception");
    }
}

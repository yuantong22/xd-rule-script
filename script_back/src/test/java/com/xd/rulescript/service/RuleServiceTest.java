package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.RuleDetailResponse;
import com.xd.rulescript.dto.RuleListRequest;
import com.xd.rulescript.dto.RulePageResponse;
import com.xd.rulescript.dto.RuleUpdateRequest;
import com.xd.rulescript.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则 CRUD 行为测试。
 */
@SpringBootTest
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
}

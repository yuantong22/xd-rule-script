package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xd.rulescript.common.BizException;
import com.xd.rulescript.dto.RuleCreateRequest;
import com.xd.rulescript.dto.TestCaseDto;
import com.xd.rulescript.dto.TestCaseSaveRequest;
import com.xd.rulescript.entity.TestCase;
import com.xd.rulescript.repository.TestCaseRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试用例的存取行为。
 *
 * 重点覆盖两类容易漏的：JSON 互转的边界（空参数、null 值、键顺序、脏数据），
 * 以及业务约束（同名、名称长度、规则必须存在）。
 */
@SpringBootTest
@Transactional
class TestCaseServiceTest {

    @Autowired private TestCaseService testCaseService;
    @Autowired private RuleService ruleService;
    @Autowired private TestCaseRepository testCaseRepository;

    private Long newRule(String name) {
        return ruleService.create(new RuleCreateRequest(name, null)).id();
    }

    /** 保序 Map，模拟前端传来的 JSON 对象 */
    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void 保存后能查出来且参数完整还原() {
        Long ruleId = newRule("用例规则甲");

        testCaseService.save(new TestCaseSaveRequest(ruleId, "VIP 成年人", params("age", "28", "level", "vip")));
        List<TestCaseDto> list = testCaseService.list(ruleId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).name()).isEqualTo("VIP 成年人");
        assertThat(list.get(0).params()).containsEntry("age", "28").containsEntry("level", "vip");
    }

    @Test
    void 保存返回的用例带自增主键() {
        Long ruleId = newRule("用例规则乙");

        TestCaseDto saved = testCaseService.save(new TestCaseSaveRequest(ruleId, "用例一", params("a", "1")));

        assertThat(saved.id()).isNotNull();
    }

    @Test
    void 中文键值与特殊字符原样存取() {
        Long ruleId = newRule("用例规则丙");
        Map<String, String> p = params("用户名", "张三 \"admin\"", "备注", "含,逗号:冒号{花括号}");

        testCaseService.save(new TestCaseSaveRequest(ruleId, "中文用例", p));
        TestCaseDto got = testCaseService.list(ruleId).get(0);

        assertThat(got.params()).containsEntry("用户名", "张三 \"admin\"");
        assertThat(got.params()).containsEntry("备注", "含,逗号:冒号{花括号}");
    }

    @Test
    void 同一规则下同名用例被拒() {
        Long ruleId = newRule("用例规则丁");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "重复名", params("a", "1")));

        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, "重复名", params("a", "2"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在同名用例");
    }

    @Test
    void 名称首尾空白被trim后参与判重() {
        Long ruleId = newRule("用例规则戊");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "  甲  ", params("a", "1")));

        // 存进去的名字应该已经 trim 过
        assertThat(testCaseService.list(ruleId).get(0).name()).isEqualTo("甲");
        // 带空格的同名也算重复
        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, "甲 ", params("a", "2"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在同名用例");
    }

    @Test
    void 名称为空白时拒绝保存() {
        Long ruleId = newRule("用例规则己");

        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, "   ", params("a", "1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用例名称不能为空");
    }

    @Test
    void 名称超长时给出可读中文而不是数据库异常() {
        Long ruleId = newRule("用例规则庚");
        String tooLong = "长".repeat(TestCaseService.NAME_MAX + 1);

        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(ruleId, tooLong, params("a", "1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用例名称太长");
    }

    @Test
    void 规则不存在时拒绝保存用例() {
        assertThatThrownBy(() -> testCaseService.save(new TestCaseSaveRequest(999999L, "孤儿用例", params("a", "1"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("规则不存在或已被删除");
    }

    @Test
    void 列表按id升序返回() {
        Long ruleId = newRule("用例规则辛");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "第一个", params()));
        testCaseService.save(new TestCaseSaveRequest(ruleId, "第二个", params()));
        testCaseService.save(new TestCaseSaveRequest(ruleId, "第三个", params()));

        assertThat(testCaseService.list(ruleId)).extracting(TestCaseDto::name)
                .containsExactly("第一个", "第二个", "第三个");
    }

    @Test
    void 空参数可以保存并还原为空Map() {
        Long ruleId = newRule("用例规则壬");

        TestCaseDto saved = testCaseService.save(new TestCaseSaveRequest(ruleId, "无参数用例", params()));

        assertThat(saved.params()).isNotNull().isEmpty();
        assertThat(testCaseService.list(ruleId).get(0).params()).isEmpty();
    }

    @Test
    void 参数值为null时存成空串而不是null() {
        Long ruleId = newRule("用例规则癸");
        Map<String, String> p = new LinkedHashMap<>();
        p.put("age", null);

        testCaseService.save(new TestCaseSaveRequest(ruleId, "含null值", p));

        // 前端拿到 null 会让输入框变成 "null" 字符串或报 prop 类型错，统一成空串最省事
        assertThat(testCaseService.list(ruleId).get(0).params()).containsEntry("age", "");
    }

    @Test
    void 参数键顺序在存取后保持一致() {
        Long ruleId = newRule("用例规则子");
        // 故意用非字典序，若实现里换成 HashMap 这条就会红
        Map<String, String> p = params("zebra", "1", "apple", "2", "mango", "3");

        testCaseService.save(new TestCaseSaveRequest(ruleId, "顺序用例", p));

        assertThat(new ArrayList<>(testCaseService.list(ruleId).get(0).params().keySet()))
                .containsExactly("zebra", "apple", "mango");
    }

    @Test
    void 删除后列表里不再有该用例() {
        Long ruleId = newRule("用例规则丑");
        TestCaseDto saved = testCaseService.save(new TestCaseSaveRequest(ruleId, "待删", params("a", "1")));

        testCaseService.delete(saved.id());

        assertThat(testCaseService.list(ruleId)).isEmpty();
    }

    @Test
    void 删除不存在的用例报错() {
        assertThatThrownBy(() -> testCaseService.delete(999999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用例不存在或已被删除");
    }

    @Test
    void 不同规则的用例互不干扰() {
        Long a = newRule("规则A");
        Long b = newRule("规则B");
        testCaseService.save(new TestCaseSaveRequest(a, "A的用例", params("x", "1")));
        testCaseService.save(new TestCaseSaveRequest(b, "B的用例", params("y", "2")));

        assertThat(testCaseService.list(a)).extracting(TestCaseDto::name).containsExactly("A的用例");
        assertThat(testCaseService.list(b)).extracting(TestCaseDto::name).containsExactly("B的用例");
        // 同名判定也只在同一规则内生效
        testCaseService.save(new TestCaseSaveRequest(b, "A的用例", params("z", "3")));
        assertThat(testCaseService.list(b)).hasSize(2);
    }

    @Test
    void params_json是脏数据时列表不炸并退化成空参数() {
        Long ruleId = newRule("用例规则寅");
        testCaseService.save(new TestCaseSaveRequest(ruleId, "正常用例", params("a", "1")));

        // 直接绕过 service 写两条坏数据，模拟历史脏数据 / 手工改库
        TestCase blank = new TestCase();
        blank.setRuleId(ruleId);
        blank.setName("空串参数");
        blank.setParamsJson("");
        testCaseRepository.save(blank);

        TestCase broken = new TestCase();
        broken.setRuleId(ruleId);
        broken.setName("非法JSON");
        broken.setParamsJson("{这不是JSON");
        testCaseRepository.save(broken);

        List<TestCaseDto> list = testCaseService.list(ruleId);

        // 关键断言：坏数据没有让整条接口失败，三条都还在
        assertThat(list).hasSize(3);
        assertThat(list).extracting(TestCaseDto::name)
                .contains("正常用例", "空串参数", "非法JSON");
        assertThat(list.stream().filter(d -> d.name().equals("非法JSON")).findFirst().orElseThrow().params())
                .isEmpty();
    }
}

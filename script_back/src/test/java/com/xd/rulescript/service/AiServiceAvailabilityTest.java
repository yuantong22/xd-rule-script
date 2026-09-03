package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.xd.rulescript.dto.AiReviewResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * AI 不可用时的降级行为（非功能要求 #1）。
 * 用 app.ai.enabled=false 强制走降级分支，不发起任何真实调用。
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "spring.ai.dashscope.api-key=not-configured",
})
class AiServiceAvailabilityTest {

    @Autowired
    private AiService aiService;

    @Test
    void 关闭开关后判定为不可用() {
        assertThat(aiService.isAvailable()).isFalse();
    }

    @Test
    void 不可用时返回降级结果而不是抛异常() {
        assertThatCode(() -> aiService.reviewScript("return 1")).doesNotThrowAnyException();

        AiReviewResult r = aiService.reviewScript("return 1");
        assertThat(r.available()).isFalse();
        assertThat(r.suggestedScript()).isNull();
        assertThat(r.text()).isEqualTo(AiService.UNAVAILABLE_TEXT);
    }

    @Test
    void 降级文案是可读中文不含英文异常词() {
        String text = aiService.reviewScript("return 1").text();
        assertThat(text).contains("AI 审查");
        assertThat(text).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("null");
    }

    @Test
    void 脚本为null或空也不抛异常() {
        assertThatCode(() -> aiService.reviewScript(null)).doesNotThrowAnyException();
        assertThatCode(() -> aiService.reviewScript("   ")).doesNotThrowAnyException();
        assertThat(aiService.reviewScript(null).available()).isFalse();
    }

    @Test
    void AI不可用时应用上下文仍能正常加载() {
        // 这条是整个降级设计的底线：ChatClient bean 缺失或 Key 非法都不能让应用起不来
        assertThat(aiService).isNotNull();
    }
}

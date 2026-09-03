package com.xd.rulescript.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.test.StepVerifier;

/**
 * 流式对话在 AI 不可用时的降级行为（非功能要求 #1）。
 * 用 StepVerifier 断言元素序列与终止信号 —— SSE 最怕的是「流不结束」或「以 error 信号结束」，
 * 这两种情况前端都会表现为一直转圈。
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "spring.ai.dashscope.api-key=not-configured",
})
class AiServiceChatStreamTest {

    @Autowired
    private AiService aiService;

    @Test
    void 不可用时返回单条降级文本并正常结束() {
        StepVerifier.create(aiService.chatStream(1L, "帮我写个规则", "return 1"))
                .assertNext(text -> {
                    assertThat(text).contains("AI 对话未启用");
                    assertThat(text).contains("语法校验");       // 必须告知哪些功能不受影响
                })
                .verifyComplete();                                // 关键：正常 complete，不是 error
    }

    @Test
    void 降级文本不含英文异常词与堆栈() {
        StepVerifier.create(aiService.chatStream(1L, "问", null))
                .assertNext(text -> assertThat(text)
                        .doesNotContain("Exception")
                        .doesNotContain("at com.")
                        .doesNotContain("null"))
                .verifyComplete();
    }

    @Test
    void conversationId为null时给中文提示而不是抛异常() {
        StepVerifier.create(aiService.chatStream(null, "问", "return 1"))
                .assertNext(text -> assertThat(text).contains("会话不存在"))
                .verifyComplete();
    }

    @Test
    void 降级流只有一个元素不会重复推送() {
        StepVerifier.create(aiService.chatStream(1L, "问", "return 1"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void 降级文案是可读中文() {
        String text = aiService.chatStream(1L, "问", "return 1").blockFirst();
        assertThat(text).isNotBlank();
        assertThat(text).contains("API Key");
    }
}

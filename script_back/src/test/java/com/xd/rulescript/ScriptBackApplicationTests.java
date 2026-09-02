package com.xd.rulescript;

import static org.assertj.core.api.Assertions.assertThat;

import com.xd.rulescript.controller.HealthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载 + 健康检查冒烟测试。
 * 需要本机 MySQL 可用（库 script_workbench 已建）；DB_PASS 有值时先 export 再跑。
 */
@SpringBootTest
class ScriptBackApplicationTests {

    @Autowired
    private HealthController healthController;

    @Test
    void 上下文能加载且健康检查返回成功码() {
        assertThat(healthController.health().code()).isZero();
        assertThat(healthController.health().data()).containsEntry("status", "UP");
    }
}

package com.xd.rulescript.groovy;

/**
 * 占位符填值不合法（缺值、类型对不上）时抛出。
 *
 * <p>groovy 包保持零 Spring 依赖，所以这里用普通运行时异常承载中文提示，
 * 由 GroovyEngineService 统一翻译成接口响应，不让堆栈漏到前端。
 */
public class PlaceholderValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlaceholderValueException(String message) {
        super(message);
    }
}

package com.xd.rulescript.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从大模型回复中提取「修改后的完整脚本」（需求 4.3.2：若给出修改，提供一键应用）。
 *
 * 取块策略（顺序即优先级）：
 *   1. 带 groovy / java 语言标记的块里，取**最后一个**
 *   2. 退而取无语言标记的块里，取**最后一个**
 *   3. 都没有则返回 null（前端据此隐藏「应用到编辑器」按钮）
 *
 * 为什么取最后一个：模型的典型输出是「先指出问题行、再给完整脚本」，
 * 完整脚本几乎总在后面。取第一个会把问题片段当成完整脚本替换进编辑器。
 *
 * 为什么要求必须闭合：模型输出被 token 上限截断时只有开头的 ```，
 * 这时把剩余全文当脚本会直接毁掉用户正在编辑的内容，宁可返回 null。
 */
public final class AiCodeBlockExtractor {

    /** 三个及以上反引号 + 可选语言标记 + 换行 + 内容 + 同等数量的反引号闭合 */
    private static final Pattern FENCED = Pattern.compile(
            "(`{3,})[ \\t]*([A-Za-z]*)[ \\t]*\\r?\\n(.*?)\\r?\\n[ \\t]*\\1",
            Pattern.DOTALL);

    private static final String LANG_GROOVY = "groovy";
    private static final String LANG_JAVA = "java";

    private AiCodeBlockExtractor() {
    }

    /**
     * @return 提取到的脚本（已 trim、换行统一为 \n），提不到返回 null
     */
    public static String extract(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }

        List<String> tagged = new ArrayList<>();
        List<String> untagged = new ArrayList<>();

        Matcher m = FENCED.matcher(reply);
        while (m.find()) {
            String lang = m.group(2).toLowerCase();
            String body = normalize(m.group(3));
            if (body.isBlank()) {
                continue;                       // 空块直接忽略，继续找下一个
            }
            if (LANG_GROOVY.equals(lang) || LANG_JAVA.equals(lang)) {
                tagged.add(body);
            } else if (lang.isEmpty()) {
                untagged.add(body);
            }
            // 其它语言标记（如 ```text）既不进 tagged 也不进 untagged：
            // 那多半是模型在举例说明，不是要替换的脚本
        }

        if (!tagged.isEmpty()) {
            return tagged.get(tagged.size() - 1);
        }
        if (!untagged.isEmpty()) {
            return untagged.get(untagged.size() - 1);
        }
        return null;
    }

    /** CRLF 统一成 LF 再去首尾空白，保证写进编辑器后行号与校验结果一致 */
    private static String normalize(String body) {
        return body.replace("\r\n", "\n").replace("\r", "\n").trim();
    }
}

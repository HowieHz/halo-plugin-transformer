package top.howiehz.halo.transformer.util;

import java.util.Collection;
import java.util.LinkedHashSet;

public final class TransformationSnippetReferenceIds {
    private TransformationSnippetReferenceIds() {
    }

    /**
     * why: `snippetIds` 可能来自控制台、导入数据、历史脏存量或手工 API 调用；
     * 后端必须先把它们收敛成同一份规范形状，才能让写入、删除协调和运行时解析复用同一个 authoritative source。
     */
    public static LinkedHashSet<String> normalize(Collection<String> snippetIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (snippetIds == null) {
            return normalized;
        }
        for (String snippetId : snippetIds) {
            String normalizedId = normalizeSingle(snippetId);
            if (normalizedId != null) {
                normalized.add(normalizedId);
            }
        }
        return normalized;
    }

    public static String normalizeSingle(String snippetId) {
        if (snippetId == null) {
            return null;
        }
        String normalizedId = snippetId.trim();
        return normalizedId.isEmpty() ? null : normalizedId;
    }
}

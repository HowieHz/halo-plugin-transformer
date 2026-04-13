package top.howiehz.halo.transformer.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformationSnippetReferenceIdsTest {
    // why: `snippetIds` 的 trim / 去空 / 去重不该散落在 service、reconciler、runtime store 各做一半；
    // 这里单测直接锁住那份共享规范，后续任何一侧改语义都会马上暴露。
    @Test
    void shouldNormalizeSnippetReferenceIdsIntoCanonicalBackendShape() {
        LinkedHashSet<String> normalized = TransformationSnippetReferenceIds.normalize(
            List.of(" snippet-a ", "", "snippet-a", "snippet-b ")
        );

        assertEquals(new LinkedHashSet<>(List.of("snippet-a", "snippet-b")), normalized);
    }

    @Test
    void shouldNormalizeSingleSnippetReferenceId() {
        assertEquals("snippet-a", TransformationSnippetReferenceIds.normalizeSingle(" snippet-a "));
        assertNull(TransformationSnippetReferenceIds.normalizeSingle("  "));
        assertNull(TransformationSnippetReferenceIds.normalizeSingle(null));
    }
}

package top.howiehz.halo.transformer.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

class TransformationSnippetValidatorTest {
    private final TransformationSnippetValidator validator = new TransformationSnippetValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: 合法代码片段必须能稳定通过写入校验，避免后端误伤正常创建与更新。
    @Test
    void shouldAllowValidSnippetDuringWriteValidation() {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");

        assertDoesNotThrow(() -> validator.validateForWrite(snippet).block());
    }

    // why: 空代码片段写进去只会表现为“没有注入”，因此必须在写入期明确拦下。
    @Test
    void shouldRejectBlankCodeDuringWriteValidation() {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setCode("   ");

        TransformationSnippetValidationException error = assertThrows(
            TransformationSnippetValidationException.class,
            () -> validator.validateForWrite(snippet).block()
        );

        assertEquals("code：请填写代码内容", error.getReason());
    }

    // why: 代码片段模型已经不再接受关系字段或其它未知字段；写入期要明确拒绝脏字段。
    @Test
    void shouldRejectUnknownSnippetFieldDuringWriteValidation() throws Exception {
        TransformationSnippet snippet = objectMapper.readValue("""
            {
              "code": "<div>ok</div>",
              "unknownField": true
            }
            """, TransformationSnippet.class);

        TransformationSnippetValidationException error = assertThrows(
            TransformationSnippetValidationException.class,
            () -> validator.validateForWrite(snippet).block()
        );

        assertEquals(
            "unknownField：不支持该字段；代码片段仅支持 \"enabled\"、\"name\"、\"description\"、\"code\"",
            error.getReason()
        );
    }

    // why: `enabled` 会直接进入运行时布尔判断；允许 null 落库会把普通读路径变成潜在拆箱异常。
    @Test
    void shouldRejectNullEnabledDuringWriteValidation() {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setEnabled(null);
        snippet.setCode("<div>ok</div>");

        TransformationSnippetValidationException error = assertThrows(
            TransformationSnippetValidationException.class,
            () -> validator.validateForWrite(snippet).block()
        );

        assertEquals("enabled：必须是布尔值；仅支持 true 或 false", error.getReason());
    }
}

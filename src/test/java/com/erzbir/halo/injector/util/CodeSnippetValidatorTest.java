package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeSnippetValidatorTest {
    private final CodeSnippetValidator validator = new CodeSnippetValidator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: 合法代码块必须能稳定通过写入校验，避免后端误伤正常创建与更新。
    @Test
    void shouldAllowValidSnippetDuringWriteValidation() {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");

        assertDoesNotThrow(() -> validator.validateForWrite(snippet).block());
    }

    // why: 空代码块写进去只会表现为“没有注入”，因此必须在写入期明确拦下。
    @Test
    void shouldRejectBlankCodeDuringWriteValidation() {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setCode("   ");

        CodeSnippetValidationException error = assertThrows(
                CodeSnippetValidationException.class,
                () -> validator.validateForWrite(snippet).block()
        );

        assertEquals("code：请填写代码内容", error.getReason());
    }

    // why: 代码块模型已经不再接受关系字段或其它未知字段；写入期要明确拒绝脏字段。
    @Test
    void shouldRejectUnknownSnippetFieldDuringWriteValidation() throws Exception {
        CodeSnippet snippet = objectMapper.readValue("""
                {
                  "code": "<div>ok</div>",
                  "unknownField": true
                }
                """, CodeSnippet.class);

        CodeSnippetValidationException error = assertThrows(
                CodeSnippetValidationException.class,
                () -> validator.validateForWrite(snippet).block()
        );

        assertEquals(
                "unknownField：不支持该字段；代码块仅支持 \"enabled\"、\"name\"、\"description\"、\"code\"",
                error.getReason()
        );
    }
}

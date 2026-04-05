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

    // why: 合法代码块必须能顺利落库，避免后端兜底误伤正常创建和更新。
    @Test
    void shouldAllowValidSnippetDuringWriteValidation() {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");

        assertDoesNotThrow(() -> validator.validateForWrite(snippet).block());
    }

    // why: 空代码块即使来自导入或脚本调用，也不能进入存储层，否则运行时只会表现为“没有注入”。
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

    // why: 代码块写接口也要拒绝未知字段，避免导入或脚本调用把拼错的键静默落库。
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
                "unknownField：不支持该字段；代码块仅支持 \"enabled\"、\"name\"、\"description\"、\"code\"、\"ruleIds\"",
                error.getReason()
        );
    }
}

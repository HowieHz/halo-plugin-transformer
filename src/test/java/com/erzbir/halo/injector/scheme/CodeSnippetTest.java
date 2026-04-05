package com.erzbir.halo.injector.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CodeSnippetTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    // why: `isValid()` 只是运行期辅助判断，不应被序列化成 `valid` 再回写到写接口里。
    @Test
    void shouldNotSerializeRuntimeValidFlag() throws Exception {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName("snippet-a");
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");

        String json = objectMapper.writeValueAsString(snippet);

        assertFalse(json.contains("\"valid\""));
    }
}

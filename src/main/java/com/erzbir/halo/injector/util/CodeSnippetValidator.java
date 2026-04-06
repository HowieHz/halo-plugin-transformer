package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Component
public class CodeSnippetValidator {
    /**
     * why: 代码块写入期也要兜底校验，避免绕过 UI 后把空代码或脏字段直接落库。
     */
    public Mono<CodeSnippet> validateForWrite(CodeSnippet snippet) {
        if (snippet == null) {
            return Mono.error(new CodeSnippetValidationException("请求体不能为空"));
        }
        if (!snippet.getUnknownFields().isEmpty()) {
            String field = snippet.getUnknownFields().iterator().next();
            return Mono.error(new CodeSnippetValidationException(
                    field + "：不支持该字段；代码块仅支持 \"enabled\"、\"name\"、\"description\"、\"code\""
            ));
        }
        if (!StringUtils.hasText(snippet.getCode())) {
            return Mono.error(new CodeSnippetValidationException("code：请填写代码内容"));
        }
        return Mono.just(snippet);
    }
}

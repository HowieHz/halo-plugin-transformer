package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Component
public class CodeSnippetValidator {
    /**
     * why: 代码块也需要和注入规则一样，在写入期拦住坏数据；
     * 否则即使前端导入页做了提示，绕过 UI 的写入仍可能把空代码或错字段落库。
     */
    public Mono<CodeSnippet> validateForWrite(CodeSnippet snippet) {
        if (snippet == null) {
            return Mono.error(new CodeSnippetValidationException("请求体不能为空"));
        }
        if (!snippet.getUnknownFields().isEmpty()) {
            String field = snippet.getUnknownFields().iterator().next();
            return Mono.error(new CodeSnippetValidationException(
                    field + "：不支持该字段；代码块仅支持 \"enabled\"、\"name\"、\"description\"、\"code\"、\"ruleIds\""
            ));
        }
        if (!StringUtils.hasText(snippet.getCode())) {
            return Mono.error(new CodeSnippetValidationException("code：请填写代码内容"));
        }
        return Mono.just(snippet);
    }
}

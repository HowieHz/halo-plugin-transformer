package top.howiehz.halo.transformer.util;

import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Component
public class TransformationSnippetValidator {
    /**
     * why: 代码片段写入期也要兜底校验，避免绕过 UI 后把空代码或脏字段直接落库。
     */
    public Mono<TransformationSnippet> validateForWrite(TransformationSnippet snippet) {
        if (snippet == null) {
            return Mono.error(new TransformationSnippetValidationException("请求体不能为空"));
        }
        if (snippet.getEnabled() == null) {
            return Mono.error(new TransformationSnippetValidationException("enabled：必须是布尔值；仅支持 true 或 false"));
        }
        if (!snippet.getUnknownFields().isEmpty()) {
            String field = snippet.getUnknownFields().iterator().next();
            return Mono.error(new TransformationSnippetValidationException(
                    field + "：不支持该字段；代码片段仅支持 \"enabled\"、\"name\"、\"description\"、\"code\""
            ));
        }
        if (!StringUtils.hasText(snippet.getCode())) {
            return Mono.error(new TransformationSnippetValidationException("code：请填写代码内容"));
        }
        return Mono.just(snippet);
    }
}


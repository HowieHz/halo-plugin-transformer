package top.howiehz.halo.transformer.validation;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import top.howiehz.halo.transformer.rule.MatchRule;
import top.howiehz.halo.transformer.extension.TransformationRule;

@Component
public class TransformationRuleValidator {
    /**
     * 写入期兜底校验。
     * <p>
     * 前端负责给出更友好的编辑态提示；后端这里负责拦住会污染持久化数据的非法规则，
     * 尤其是非法正则，以及 REMOVE 携带代码片段 / 注释标记这类脏关联。
     * <p>
     * 对于会让 DOM 注入退化成“全站 HTML 处理”的规则，当前策略是不拦截写入：
     * 用户可能明确接受这笔性能成本，因此只在配置页给出警告，不在后端拒绝保存。
     */
    public Mono<TransformationRule> validateForWrite(TransformationRule rule) {
        if (rule == null) {
            return Mono.error(new TransformationRuleValidationException("请求体不能为空"));
        }
        if (rule.getEnabled() == null) {
            return Mono.error(new TransformationRuleValidationException(
                "enabled：必须是布尔值；仅支持 true 或 false"));
        }
        if (rule.getMode() == null) {
            return Mono.error(new TransformationRuleValidationException(
                "mode：仅支持 \"HEAD\"、\"FOOTER\"、\"SELECTOR\""));
        }
        if (rule.getPosition() == null) {
            return Mono.error(new TransformationRuleValidationException(
                "position：仅支持 \"APPEND\"、\"PREPEND\"、\"BEFORE\"、\"AFTER\"、\"REPLACE\"、\"REMOVE\""));
        }
        if (TransformationRule.Mode.SELECTOR.equals(rule.getMode()) && !StringUtils.hasText(
            rule.getMatch())) {
            return Mono.error(new TransformationRuleValidationException("match：请填写匹配内容"));
        }
        try {
            MatchRule.validateForWrite(rule.getMatchRule());
            if (rule.getRuntimeOrder() < 0) {
                return Mono.error(
                    new TransformationRuleValidationException("runtimeOrder：不能小于 0"));
            }
            if (rule.isSelectorRemove()
                && rule.getSnippetIds() != null
                && !rule.getSnippetIds().isEmpty()) {
                return Mono.error(new TransformationRuleValidationException(
                    "snippetIds：REMOVE 模式下无需关联代码片段"));
            }
            if (rule.isSelectorRemove() && rule.getWrapMarker()) {
                return Mono.error(new TransformationRuleValidationException(
                    "wrapMarker：REMOVE 模式下无需输出注释标记"));
            }
            return Mono.just(rule);
        } catch (IllegalArgumentException e) {
            return Mono.error(new TransformationRuleValidationException(e.getMessage()));
        }
    }
}

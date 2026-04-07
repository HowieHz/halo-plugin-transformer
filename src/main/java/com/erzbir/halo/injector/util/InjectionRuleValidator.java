package com.erzbir.halo.injector.util;

import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.core.MatchRule;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

@Component
public class InjectionRuleValidator {
    /**
     * 写入期兜底校验。
     * <p>
     * 前端负责给出更友好的编辑态提示；后端这里负责拦住会污染持久化数据的非法规则，
     * 尤其是非法正则，以及 REMOVE 携带代码块 / 注释标记这类脏关联。
     * <p>
     * 对于会让 DOM 注入退化成“全站 HTML 处理”的规则，当前策略是不拦截写入：
     * 用户可能明确接受这笔性能成本，因此只在配置页给出警告，不在后端拒绝保存。
     */
    public Mono<InjectionRule> validateForWrite(InjectionRule rule) {
        if (rule == null) {
            return Mono.error(new InjectionRuleValidationException("请求体不能为空"));
        }
        if ((InjectionRule.Mode.ID.equals(rule.getMode()) || InjectionRule.Mode.SELECTOR.equals(rule.getMode()))
                && !StringUtils.hasText(rule.getMatch())) {
            return Mono.error(new InjectionRuleValidationException("match：请填写匹配内容"));
        }
        try {
            MatchRule.validateForWrite(rule.getMatchRule());
            if (rule.getRuntimeOrder() < 0) {
                return Mono.error(new InjectionRuleValidationException("runtimeOrder：不能小于 0"));
            }
            if (InjectionRule.Position.REMOVE.equals(rule.getPosition())
                    && rule.getSnippetIds() != null
                    && !rule.getSnippetIds().isEmpty()) {
                return Mono.error(new InjectionRuleValidationException("snippetIds：REMOVE 模式下无需关联代码块"));
            }
            if (InjectionRule.Position.REMOVE.equals(rule.getPosition()) && rule.getWrapMarker()) {
                return Mono.error(new InjectionRuleValidationException("wrapMarker：REMOVE 模式下无需输出注释标记"));
            }
            return Mono.just(rule);
        } catch (IllegalArgumentException e) {
            return Mono.error(new InjectionRuleValidationException(e.getMessage()));
        }
    }
}

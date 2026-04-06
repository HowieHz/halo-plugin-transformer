package com.erzbir.halo.injector.filter;

import com.erzbir.halo.injector.core.ElementIDInjector;
import com.erzbir.halo.injector.core.SelectorInjector;
import com.erzbir.halo.injector.core.MatchRule;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.InjectHelper;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InjectorWebFilterTest {
    @Mock
    private InjectHelper injectHelper;

    // why: 同一页同时命中 SELECTOR 与 ID 规则时，应共享一次代码块解析和同一份 Jsoup Document，
    // 避免跨 mode 重复拼接 snippet、重复 parse / serialize HTML，放大 DOM 注入链路开销。
    @Test
    void shouldResolveRuleCodesOnceAndParseHtmlOnlyOnceWhenApplyingMultipleDomRules() {
        CountingInjectorWebFilter filter = new CountingInjectorWebFilter(
                injectHelper, new SelectorInjector(), new ElementIDInjector());
        InjectionRule selectorRule = domRule("rule-selector", InjectionRule.Mode.SELECTOR, ".slot");
        InjectionRule idRule = domRule("rule-id", InjectionRule.Mode.ID, "root");

        when(injectHelper.getMatchedRules("/demo", "post", InjectionRule.Mode.SELECTOR))
                .thenReturn(Flux.just(selectorRule));
        when(injectHelper.getMatchedRules("/demo", "post", InjectionRule.Mode.ID))
                .thenReturn(Flux.just(idRule));
        when(injectHelper.resolveRuleCodes(List.of(selectorRule, idRule))).thenReturn(Mono.just(List.of(
                new InjectHelper.ResolvedRuleCode(selectorRule, "<span class='selector'>S</span>"),
                new InjectHelper.ResolvedRuleCode(idRule, "<span class='id'>I</span>")
        )));

        String result = filter.inject(
                "<html><body><main id='root'><div class='slot'>A</div></main></body></html>",
                "/demo",
                "post"
        ).block();

        assertEquals(1, filter.parseCount.get());
        verify(injectHelper).resolveRuleCodes(List.of(selectorRule, idRule));
        assertTrue(result.contains("<div class=\"slot\">A<span class=\"selector\">S</span></div>"));
        assertTrue(result.contains("<main id=\"root\"><div class=\"slot\">A<span class=\"selector\">S</span></div><span class=\"id\">I</span></main>"));
    }

    // why: 没有任何 DOM 规则命中时，应直接返回原始 HTML，避免无意义的 Jsoup.parse。
    @Test
    void shouldSkipParsingWhenNoDomRulesMatch() {
        CountingInjectorWebFilter filter = new CountingInjectorWebFilter(
                injectHelper, new SelectorInjector(), new ElementIDInjector());
        String html = "<html><body><main id='root'>A</main></body></html>";

        when(injectHelper.getMatchedRules("/demo", "post", InjectionRule.Mode.SELECTOR))
                .thenReturn(Flux.empty());
        when(injectHelper.getMatchedRules("/demo", "post", InjectionRule.Mode.ID))
                .thenReturn(Flux.empty());

        String result = filter.inject(html, "/demo", "post").block();

        assertEquals(0, filter.parseCount.get());
        assertEquals(html, result);
    }

    private InjectionRule domRule(String id, InjectionRule.Mode mode, String match) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setEnabled(true);
        rule.setMode(mode);
        rule.setMatch(match);
        rule.setWrapMarker(false);
        rule.setPosition(InjectionRule.Position.APPEND);
        rule.setMatchRule(MatchRule.defaultRule());
        return rule;
    }

    private static class CountingInjectorWebFilter extends InjectorWebFilter {
        private final AtomicInteger parseCount = new AtomicInteger();

        CountingInjectorWebFilter(InjectHelper injectHelper,
                                  SelectorInjector selectorInjector,
                                  ElementIDInjector elementIDInjector) {
            super(injectHelper, selectorInjector, elementIDInjector);
        }

        @Override
        Document parseHtmlDocument(String html) {
            parseCount.incrementAndGet();
            return super.parseHtmlDocument(html);
        }
    }
}

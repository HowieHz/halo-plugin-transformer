package com.erzbir.halo.injector.filter;

import com.erzbir.halo.injector.core.SelectorInjector;
import com.erzbir.halo.injector.core.MatchRule;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.InjectHelper;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;

import java.nio.charset.StandardCharsets;
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

    private CountingInjectorWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CountingInjectorWebFilter(injectHelper, new SelectorInjector());
    }

    // why: 同一页同时命中多条 SELECTOR 规则时，应共享一次代码块解析和同一份 Jsoup Document，
    // 避免每条规则都重复 parse / serialize HTML，放大 DOM 注入链路开销。
    @Test
    void shouldResolveRuleCodesOnceAndParseHtmlOnlyOnceWhenApplyingMultipleDomRules() {
        InjectionRule selectorRule = domRule("rule-selector", InjectionRule.Mode.SELECTOR, ".slot");
        InjectionRule secondSelectorRule = domRule("rule-selector-2", InjectionRule.Mode.SELECTOR, "main");

        when(injectHelper.getMatchedRules("/demo", "post", InjectionRule.Mode.SELECTOR))
                .thenReturn(Flux.just(selectorRule, secondSelectorRule));
        when(injectHelper.resolveRuleCodes(List.of(selectorRule, secondSelectorRule))).thenReturn(Mono.just(List.of(
                new InjectHelper.ResolvedRuleCode(selectorRule, "<span class='selector'>S</span>"),
                new InjectHelper.ResolvedRuleCode(secondSelectorRule, "<span class='second'>I</span>")
        )));

        String result = filter.inject(
                "<html><body><main id='root'><div class='slot'>A</div></main></body></html>",
                "/demo",
                "post"
        ).block();

        assertEquals(1, filter.parseCount.get());
        verify(injectHelper).resolveRuleCodes(List.of(selectorRule, secondSelectorRule));
        assertTrue(result.contains("<div class=\"slot\">A<span class=\"selector\">S</span></div>"));
        assertTrue(result.contains("<main id=\"root\"><div class=\"slot\">A<span class=\"selector\">S</span></div><span class=\"second\">I</span></main>"));
    }

    // why: 没有任何 DOM 规则命中时，应直接返回原始 HTML，避免无意义的 Jsoup.parse。
    @Test
    void shouldSkipParsingWhenNoDomRulesMatch() {
        String html = "<html><body><main id='root'>A</main></body></html>";

        when(injectHelper.getMatchedRules("/demo", "post", InjectionRule.Mode.SELECTOR))
                .thenReturn(Flux.empty());

        String result = filter.inject(html, "/demo", "post").block();

        assertEquals(0, filter.parseCount.get());
        assertEquals(html, result);
    }

    // why: 空白 HTML 响应即使无需注入，也必须拷贝成新的响应 buffer；
    // 否则 writeAndFlushWith 会把已 release 的 join buffer 继续往下游写出。
    @Test
    void shouldCreateFreshResponseBufferForBlankHtml() {
        MockServerHttpResponse response = new MockServerHttpResponse(new DefaultDataBufferFactory());

        var buffer = filter.createHtmlResponseBuffer("   ", response);

        assertEquals("   ", buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(3, response.getHeaders().getContentLength());
    }

    // why: 进入 filter 链时响应状态通常尚未确定；若在 chain.filter 之前就看 status，
    // 会把本该改写的正常 HTML 响应直接漏掉。
    @Test
    void shouldDecorateRequestBeforeResponseStatusIsCommitted() {
        InjectionRule selectorRule = domRule("rule-selector", InjectionRule.Mode.SELECTOR, ".slot");
        when(injectHelper.hasDomProcessCandidate("/demo", InjectionRule.Mode.SELECTOR))
                .thenReturn(Mono.just(true));
        when(injectHelper.getMatchedRules("/demo", "", InjectionRule.Mode.SELECTOR))
                .thenReturn(Flux.just(selectorRule));
        when(injectHelper.resolveRuleCodes(List.of(selectorRule))).thenReturn(Mono.just(List.of(
                new InjectHelper.ResolvedRuleCode(selectorRule, "<span class='selector'>S</span>")
        )));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/demo")
                        .accept(MediaType.TEXT_HTML)
                        .build()
        );

        filter.filter(exchange, decoratedExchange -> {
            var response = decoratedExchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.TEXT_HTML);
            var body = response.bufferFactory()
                    .wrap("<html><body><div class='slot'>A</div></body></html>"
                            .getBytes(StandardCharsets.UTF_8));
            return response.writeAndFlushWith(Flux.just(Mono.just(body)));
        }).block();

        String result = exchange.getResponse().getBodyAsString().block();

        assertEquals(1, filter.parseCount.get());
        assertTrue(result.contains("<div class=\"slot\">A<span class=\"selector\">S</span></div>"));
    }

    // why: 部分响应链路经由 `writeWith` 而不是 `writeAndFlushWith` 输出；
    // 这两条路径都必须命中同一份注入逻辑，否则会出现“部分页面能注入、部分页面失效”的分叉行为。
    @Test
    void shouldRewriteHtmlResponsesWrittenViaWriteWith() {
        InjectionRule selectorRule = domRule("rule-selector", InjectionRule.Mode.SELECTOR, ".slot");
        when(injectHelper.hasDomProcessCandidate("/demo", InjectionRule.Mode.SELECTOR))
                .thenReturn(Mono.just(true));
        when(injectHelper.getMatchedRules("/demo", "", InjectionRule.Mode.SELECTOR))
                .thenReturn(Flux.just(selectorRule));
        when(injectHelper.resolveRuleCodes(List.of(selectorRule))).thenReturn(Mono.just(List.of(
                new InjectHelper.ResolvedRuleCode(selectorRule, "<span class='selector'>S</span>")
        )));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/demo")
                        .accept(MediaType.TEXT_HTML)
                        .build()
        );

        filter.filter(exchange, decoratedExchange -> {
            var response = decoratedExchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.TEXT_HTML);
            var body = response.bufferFactory()
                    .wrap("<html><body><div class='slot'>A</div></body></html>"
                            .getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(body));
        }).block();

        String result = exchange.getResponse().getBodyAsString().block();

        assertEquals(1, filter.parseCount.get());
        assertTrue(result.contains("<div class=\"slot\">A<span class=\"selector\">S</span></div>"));
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
                                  SelectorInjector selectorInjector) {
            super(injectHelper, selectorInjector);
        }

        @Override
        Document parseHtmlDocument(String html) {
            parseCount.incrementAndGet();
            return super.parseHtmlDocument(html);
        }
    }
}

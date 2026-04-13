package top.howiehz.halo.transformer.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import top.howiehz.halo.transformer.rule.MatchRule;
import top.howiehz.halo.transformer.runtime.RuntimeTransformationRule;
import top.howiehz.halo.transformer.extension.TransformationRule;
import top.howiehz.halo.transformer.runtime.RuntimeRuleResolver;

@ExtendWith(MockitoExtension.class)
class TransformerWebFilterTest {
    @Mock
    private RuntimeRuleResolver transformHelper;

    private CountingTransformerWebFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CountingTransformerWebFilter(transformHelper);
    }

    // why: 同一页同时命中多条 SELECTOR 规则时，应共享一次代码片段解析和同一份 Jsoup Document，
    // 避免每条规则都重复 parse / serialize HTML，放大 DOM 注入链路开销。
    @Test
    void shouldResolveRuleCodesOnceAndParseHtmlOnlyOnceWhenApplyingMultipleDomRules() {
        TransformationRule selectorRule =
            domRule("rule-selector", TransformationRule.Mode.SELECTOR, ".slot");
        TransformationRule secondSelectorRule =
            domRule("rule-selector-2", TransformationRule.Mode.SELECTOR, "main");
        RuntimeTransformationRule runtimeSelectorRule = runtimeRule(selectorRule);
        RuntimeTransformationRule runtimeSecondSelectorRule = runtimeRule(secondSelectorRule);

        when(transformHelper.getMatchedRules("/demo", "post", TransformationRule.Mode.SELECTOR))
            .thenReturn(Flux.just(runtimeSelectorRule, runtimeSecondSelectorRule));
        when(transformHelper.resolveRuleCodes(
            List.of(runtimeSelectorRule, runtimeSecondSelectorRule))).thenReturn(
            Mono.just(List.of(
                new RuntimeRuleResolver.ResolvedRuleCode(runtimeSelectorRule,
                    "<span class='selector'>S</span>"),
                new RuntimeRuleResolver.ResolvedRuleCode(runtimeSecondSelectorRule,
                    "<span class='second'>I</span>")
            ))
        );

        String result = filter.transformHtml(
            "<html><body><main id='root'><div class='slot'>A</div></main></body></html>",
            "/demo",
            "post"
        ).block();

        assertEquals(1, filter.parseCount.get());
        verify(transformHelper).resolveRuleCodes(
            List.of(runtimeSelectorRule, runtimeSecondSelectorRule));
        assertTrue(result.contains("<div class=\"slot\">A<span class=\"selector\">S</span></div>"));
        assertTrue(result.contains(
            "<main id=\"root\"><div class=\"slot\">A<span class=\"selector\">S</span></div><span "
                + "class=\"second\">I</span></main>"));
    }

    // why: 没有任何 DOM 规则命中时，应直接返回原始 HTML，避免无意义的 Jsoup.parse。
    @Test
    void shouldSkipParsingWhenNoDomRulesMatch() {
        String html = "<html><body><main id='root'>A</main></body></html>";

        when(transformHelper.getMatchedRules("/demo", "post", TransformationRule.Mode.SELECTOR))
            .thenReturn(Flux.empty());

        String result = filter.transformHtml(html, "/demo", "post").block();

        assertEquals(0, filter.parseCount.get());
        assertEquals(html, result);
    }

    // why: 空白 HTML 响应即使无需注入，也必须拷贝成新的响应 buffer；
    // 否则 writeAndFlushWith 会把已 release 的 join buffer 继续往下游写出。
    @Test
    void shouldCreateFreshResponseBufferForBlankHtml() {
        MockServerHttpResponse response =
            new MockServerHttpResponse(new DefaultDataBufferFactory());

        var buffer = filter.createHtmlResponseBuffer("   ", response);

        assertEquals("   ", buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(3, response.getHeaders().getContentLength());
    }

    // why: 进入 filter 链时响应状态通常尚未确定；若在 chain.filter 之前就看 status，
    // 会把本该改写的正常 HTML 响应直接漏掉。
    @Test
    void shouldDecorateRequestBeforeResponseStatusIsCommitted() {
        TransformationRule selectorRule =
            domRule("rule-selector", TransformationRule.Mode.SELECTOR, ".slot");
        RuntimeTransformationRule runtimeSelectorRule = runtimeRule(selectorRule);
        when(transformHelper.hasDomProcessCandidate("/demo", TransformationRule.Mode.SELECTOR))
            .thenReturn(Mono.just(true));
        when(transformHelper.getMatchedRules("/demo", "", TransformationRule.Mode.SELECTOR))
            .thenReturn(Flux.just(runtimeSelectorRule));
        when(transformHelper.resolveRuleCodes(List.of(runtimeSelectorRule))).thenReturn(
            Mono.just(List.of(
                new RuntimeRuleResolver.ResolvedRuleCode(runtimeSelectorRule,
                    "<span class='selector'>S</span>")
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

    // why: WebFlux 的普通成功响应经常依赖 implicit 200，而不是显式 `setStatusCode(OK)`；
    // write-phase guard 必须把这种空状态码视作可注入的成功响应，避免漏掉主链路 HTML 页面。
    @Test
    void shouldRewriteImplicitOkHtmlResponsesWithoutExplicitStatus() {
        TransformationRule selectorRule =
            domRule("rule-selector", TransformationRule.Mode.SELECTOR, ".slot");
        RuntimeTransformationRule runtimeSelectorRule = runtimeRule(selectorRule);
        when(transformHelper.hasDomProcessCandidate("/demo", TransformationRule.Mode.SELECTOR))
            .thenReturn(Mono.just(true));
        when(transformHelper.getMatchedRules("/demo", "", TransformationRule.Mode.SELECTOR))
            .thenReturn(Flux.just(runtimeSelectorRule));
        when(transformHelper.resolveRuleCodes(List.of(runtimeSelectorRule))).thenReturn(
            Mono.just(List.of(
                new RuntimeRuleResolver.ResolvedRuleCode(runtimeSelectorRule,
                    "<span class='selector'>S</span>")
            )));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/demo")
                .accept(MediaType.TEXT_HTML)
                .build()
        );

        filter.filter(exchange, decoratedExchange -> {
            var response = decoratedExchange.getResponse();
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

    // why: 请求侧 `Accept` 只是客户端偏好，不是最终响应类型的唯一依据；
    // 只要最终写出的是可注入 HTML，过滤器就不该因为请求没带 `Accept: text/html` 而漏掉整条注入链路。
    @Test
    void shouldNotRequireHtmlAcceptHeaderBeforeDecoratingResponse() {
        TransformationRule selectorRule =
            domRule("rule-selector", TransformationRule.Mode.SELECTOR, ".slot");
        RuntimeTransformationRule runtimeSelectorRule = runtimeRule(selectorRule);
        when(transformHelper.hasDomProcessCandidate("/demo", TransformationRule.Mode.SELECTOR))
            .thenReturn(Mono.just(true));
        when(transformHelper.getMatchedRules("/demo", "", TransformationRule.Mode.SELECTOR))
            .thenReturn(Flux.just(runtimeSelectorRule));
        when(transformHelper.resolveRuleCodes(List.of(runtimeSelectorRule))).thenReturn(
            Mono.just(List.of(
                new RuntimeRuleResolver.ResolvedRuleCode(runtimeSelectorRule,
                    "<span class='selector'>S</span>")
            )));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/demo").build()
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

    // why: 部分响应链路经由 `writeWith` 而不是 `writeAndFlushWith` 输出；
    // 这两条路径都必须命中同一份注入逻辑，否则会出现“部分页面能注入、部分页面失效”的分叉行为。
    @Test
    void shouldRewriteHtmlResponsesWrittenViaWriteWith() {
        TransformationRule selectorRule =
            domRule("rule-selector", TransformationRule.Mode.SELECTOR, ".slot");
        RuntimeTransformationRule runtimeSelectorRule = runtimeRule(selectorRule);
        when(transformHelper.hasDomProcessCandidate("/demo", TransformationRule.Mode.SELECTOR))
            .thenReturn(Mono.just(true));
        when(transformHelper.getMatchedRules("/demo", "", TransformationRule.Mode.SELECTOR))
            .thenReturn(Flux.just(runtimeSelectorRule));
        when(transformHelper.resolveRuleCodes(List.of(runtimeSelectorRule))).thenReturn(
            Mono.just(List.of(
                new RuntimeRuleResolver.ResolvedRuleCode(runtimeSelectorRule,
                    "<span class='selector'>S</span>")
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

    // why: 当前过滤器不会解压已编码响应；一旦上游已声明 Content-Encoding，
    // 就必须原样透传，避免把压缩字节误按 UTF-8 HTML 改写。
    @Test
    void shouldSkipEncodedHtmlResponses() {
        when(transformHelper.hasDomProcessCandidate("/demo", TransformationRule.Mode.SELECTOR))
            .thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/demo")
                .accept(MediaType.TEXT_HTML)
                .build()
        );

        filter.filter(exchange, decoratedExchange -> {
            var response = decoratedExchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.TEXT_HTML);
            response.getHeaders().set("Content-Encoding", "gzip");
            var body = response.bufferFactory()
                .wrap("<html><body><div class='slot'>A</div></body></html>"
                    .getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(body));
        }).block();

        String result = exchange.getResponse().getBodyAsString().block();

        assertEquals(0, filter.parseCount.get());
        assertEquals("<html><body><div class='slot'>A</div></body></html>", result);
    }

    // why: 当前实现显式约束在 UTF-8 HTML body 上；
    // 对其它 charset 直接跳过，比隐式重编码更安全、更可预期。
    @Test
    void shouldSkipNonUtf8HtmlResponses() {
        when(transformHelper.hasDomProcessCandidate("/demo", TransformationRule.Mode.SELECTOR))
            .thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/demo")
                .accept(MediaType.TEXT_HTML)
                .build()
        );

        filter.filter(exchange, decoratedExchange -> {
            var response = decoratedExchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders()
                .setContentType(new MediaType("text", "html", StandardCharsets.ISO_8859_1));
            var body = response.bufferFactory()
                .wrap("<html><body><div class='slot'>A</div></body></html>"
                    .getBytes(StandardCharsets.ISO_8859_1));
            return response.writeWith(Mono.just(body));
        }).block();

        String result = exchange.getResponse().getBodyAsString().block();

        assertEquals(0, filter.parseCount.get());
        assertEquals("<html><body><div class='slot'>A</div></body></html>", result);
    }

    private TransformationRule domRule(String id, TransformationRule.Mode mode, String match) {
        TransformationRule rule = new TransformationRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        rule.setEnabled(true);
        rule.setMode(mode);
        rule.setMatch(match);
        rule.setWrapMarker(false);
        rule.setPosition(TransformationRule.Position.APPEND);
        rule.setMatchRule(MatchRule.defaultRule());
        return rule;
    }

    private RuntimeTransformationRule runtimeRule(TransformationRule rule) {
        return RuntimeTransformationRule.fromStoredRule(rule, rule.getMatchRule());
    }

    private static class CountingTransformerWebFilter extends TransformerWebFilter {
        private final AtomicInteger parseCount = new AtomicInteger();

        CountingTransformerWebFilter(RuntimeRuleResolver transformHelper) {
            super(transformHelper);
        }

        @Override
        Document parseHtmlDocument(String html) {
            parseCount.incrementAndGet();
            return super.parseHtmlDocument(html);
        }
    }
}

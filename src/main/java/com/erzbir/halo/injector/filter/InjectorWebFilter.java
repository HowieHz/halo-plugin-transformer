package com.erzbir.halo.injector.filter;

import com.erzbir.halo.injector.core.ElementIDInjector;
import com.erzbir.halo.injector.core.HTMLInjector;
import com.erzbir.halo.injector.core.SelectorInjector;
import com.erzbir.halo.injector.scheme.InjectionRule;
import com.erzbir.halo.injector.util.ContextUtil;
import com.erzbir.halo.injector.util.InjectHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

@Slf4j
@Component
@RequiredArgsConstructor
public class InjectorWebFilter implements AdditionalWebFilter {
    private final InjectHelper injectHelper;
    private final SelectorInjector selectorInjector;
    private final ElementIDInjector elementIDInjector;
    private final ServerWebExchangeMatcher pathMatcher = createPathMatcher();

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return pathMatcher.matches(exchange)
                .flatMap(matchResult -> {
                    if (matchResult.isMatch() && shouldInject(exchange)) {
                        String path = exchange.getRequest().getPath().value();
                        return hasMatchingRules(path).flatMap(hasRules -> {
                            if (hasRules) {
                                var decoratedExchange = exchange.mutate()
                                        .response(new InjectorResponseDecorator(exchange))
                                        .build();
                                return chain.filter(decoratedExchange);
                            }
                            return chain.filter(exchange);
                        });
                    }
                    return chain.filter(exchange);
                });
    }

    /**
     * why: 这里不是判断“最终一定会注入”，而是判断“这次请求是否需要进入 DOM 注入链路”；
     * 规则若能先按路径缩小范围，就只命中少量页面；规则若不能缩小范围，就会退化成所有 HTML 页面都先缓冲。
     */
    private Mono<Boolean> hasMatchingRules(String path) {
        return Mono.zip(
                        injectHelper.hasDomProcessCandidate(path, InjectionRule.Mode.SELECTOR),
                        injectHelper.hasDomProcessCandidate(path, InjectionRule.Mode.ID)
                ).map(tuple -> tuple.getT1() || tuple.getT2())
                .defaultIfEmpty(false);
    }


    boolean shouldInject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        var statusCode = response.getStatusCode();
        return statusCode != null && statusCode.isSameCodeAs(HttpStatus.OK);
    }

    ServerWebExchangeMatcher createPathMatcher() {
        var pathMatcher = pathMatchers(HttpMethod.GET, "/**");
        var excludeMatcher =
                new NegatedServerWebExchangeMatcher(
                        pathMatchers("/console/**", "/uc/**", "/login/**",
                                "/signup/**", "/logout/**", "/themes/**",
                                "/plugins/**", "/actuator/**", "/api/**",
                                "/apis/**", "/system/**",
                                "/upload/**", "/webjars/**"));
        var mediaTypeMatcher = new MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML);
        mediaTypeMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));

        return new AndServerWebExchangeMatcher(
                mediaTypeMatcher,
                excludeMatcher,
                pathMatcher
        );
    }

    public Mono<String> inject(String html, String permalink, String templateId) {
        return collectDomInjectionPlans(permalink, templateId)
                .map(plans -> applyDomInjections(html, permalink, plans))
                .onErrorResume(e -> {
                    log.warn("Failed to inject HTML response", e);
                    return Mono.just(html);
                });
    }

    /**
     * why: 先按既有顺序收集 SELECTOR 与 ID 两类 DOM 规则，
     * 再在同一份 Document 上顺序执行，避免每命中一条规则就重复 Jsoup.parse / doc.html。
     */
    private Flux<DomInjectionPlan> collectPlans(String path, String templateId, InjectionRule.Mode mode,
                                                HTMLInjector injector) {
        return injectHelper.getMatchedRules(path, templateId, mode)
                .collectList()
                .flatMapMany(rules -> injectHelper.resolveRuleCodes(rules)
                        .flatMapMany(Flux::fromIterable)
                        .map(resolved -> new DomInjectionPlan(injector, resolved.rule(), resolved.code())));
    }

    private Mono<List<DomInjectionPlan>> collectDomInjectionPlans(String path, String templateId) {
        return Flux.concat(
                        collectPlans(path, templateId, InjectionRule.Mode.SELECTOR, selectorInjector),
                        collectPlans(path, templateId, InjectionRule.Mode.ID, elementIDInjector)
                )
                .collectList();
    }

    String applyDomInjections(String html, String path, List<DomInjectionPlan> plans) {
        if (plans.isEmpty()) {
            return html;
        }
        Document document = parseHtmlDocument(html);
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        boolean modified = false;
        for (DomInjectionPlan plan : plans) {
            boolean applied = plan.injector()
                    .inject(document, plan.rule().getMatch(), plan.code(),
                            plan.rule().getPosition(), plan.rule().getWrapMarker());
            if (applied) {
                modified = true;
                log.debug("Injected rule: [{}] into [{}]", plan.rule().getId(), path);
            }
        }
        return modified ? document.html() : html;
    }

    Document parseHtmlDocument(String html) {
        return Jsoup.parse(html);
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE - 100;
    }

    class InjectorResponseDecorator extends ServerHttpResponseDecorator {
        private final ServerWebExchange exchange;

        public InjectorResponseDecorator(ServerWebExchange exchange) {
            super(exchange.getResponse());
            this.exchange = exchange;
        }

        boolean isHtmlResponse(ServerHttpResponse response) {
            return response.getHeaders().getContentType() != null &&
                    response.getHeaders().getContentType().includes(MediaType.TEXT_HTML);
        }

        @Override
        @NonNull
        public Mono<Void> writeAndFlushWith(
                @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body) {
            var response = getDelegate();
            if (!isHtmlResponse(response)) {
                return super.writeAndFlushWith(body);
            }
            String path = exchange.getRequest().getPath().value();
            if (path.isBlank()) {
                return super.writeAndFlushWith(body);
            }
            var flattenedBody = Flux.from(body).flatMapSequential(publisher -> publisher);
            var processedBody = DataBufferUtils.join(flattenedBody).flatMap(dataBuffer -> {
                try {
                    String html = dataBuffer.toString(StandardCharsets.UTF_8);
                    if (html.isBlank()) {
                        return Mono.just(dataBuffer);
                    }
                    String templateId = ContextUtil.getTemplateId(exchange);
                    return inject(html, path, templateId).onErrorResume(e -> Mono.just(html))
                            .map(processedHtml -> {
                                byte[] resultBytes = processedHtml.getBytes(StandardCharsets.UTF_8);
                                return response.bufferFactory().wrap(resultBytes);
                            });
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            }).flux().map(Flux::just);
            return super.writeAndFlushWith(processedBody);
        }
    }

    record DomInjectionPlan(HTMLInjector injector, InjectionRule rule, String code) {
    }
}

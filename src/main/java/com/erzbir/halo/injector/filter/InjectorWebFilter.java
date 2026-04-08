package com.erzbir.halo.injector.filter;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.security.AdditionalWebFilter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

@Slf4j
@Component
@RequiredArgsConstructor
public class InjectorWebFilter implements AdditionalWebFilter {
    private final InjectHelper injectHelper;
    private final SelectorInjector selectorInjector;
    private final ServerWebExchangeMatcher pathMatcher = createPathMatcher();

    @Override
    public @NonNull Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return pathMatcher.matches(exchange)
                .flatMap(matchResult -> {
                    if (!matchResult.isMatch()) {
                        return chain.filter(exchange);
                    }
                    String path = exchange.getRequest().getPath().value();
                    return hasMatchingRules(path).flatMap(hasRules -> {
                        if (!hasRules) {
                            return chain.filter(exchange);
                        }
                        var decoratedExchange = exchange.mutate()
                                .response(new InjectorResponseDecorator(exchange))
                                .build();
                        return chain.filter(decoratedExchange);
                    });
                });
    }

    /**
     * why: 这里不是判断“最终一定会注入”，而是判断“这次请求是否需要进入 DOM 注入链路”；
     * 规则若能先按路径缩小范围，就只命中少量页面；规则若不能缩小范围，就会退化成所有 HTML 页面都先缓冲。
     */
    private Mono<Boolean> hasMatchingRules(String path) {
        return injectHelper.hasDomProcessCandidate(path, InjectionRule.Mode.SELECTOR)
                .defaultIfEmpty(false);
    }
    /**
     * why: handler 写响应头前，`statusCode` 往往还是空；因此这里只能在真正写 body 的时点
     * 用最终响应状态决定是否改写，不能在进入 filter 链前提前否决。
     */
    boolean isEligibleInjectionResponse(ServerHttpResponse response) {
        var statusCode = response.getStatusCode();
        return statusCode != null
                && statusCode.isSameCodeAs(HttpStatus.OK)
                && isInjectableHtmlResponse(response);
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
        return new AndServerWebExchangeMatcher(
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
     * why: 先收集当前请求命中的 DOM 规则，
     * 再在同一份 Document 上顺序执行，避免每命中一条规则就重复 Jsoup.parse / doc.html。
     */
    private Mono<List<InjectionRule>> collectRules(String path, String templateId, InjectionRule.Mode mode) {
        return injectHelper.getMatchedRules(path, templateId, mode)
                .collectList();
    }

    private Mono<List<DomInjectionPlan>> collectDomInjectionPlans(String path, String templateId) {
        return collectRules(path, templateId, InjectionRule.Mode.SELECTOR)
                .flatMap(selectorRules -> {
                    if (selectorRules.isEmpty()) {
                        return Mono.just(List.of());
                    }
                    return injectHelper.resolveRuleCodes(selectorRules)
                            .map(resolvedCodes -> toDomInjectionPlans(selectorRules, resolvedCodes));
                });
    }

    private List<DomInjectionPlan> toDomInjectionPlans(List<InjectionRule> selectorRules,
                                                       List<InjectHelper.ResolvedRuleCode> resolvedCodes) {
        Map<String, String> codeByRuleId = new LinkedHashMap<>();
        for (InjectHelper.ResolvedRuleCode resolvedCode : resolvedCodes) {
            codeByRuleId.put(resolvedCode.rule().getId(), resolvedCode.code());
        }
        List<DomInjectionPlan> plans = new java.util.ArrayList<>(selectorRules.size());
        for (InjectionRule rule : selectorRules) {
            plans.add(new DomInjectionPlan(selectorInjector, rule, codeByRuleId.getOrDefault(rule.getId(), "")));
        }
        return plans;
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

    /**
     * why: 下游响应体必须持有自己可用的 buffer；
     * 即使这次无需注入，也不能把已 join 并即将 release 的原始 `DataBuffer` 继续透传出去。
     */
    DataBuffer createHtmlResponseBuffer(String html, ServerHttpResponse response) {
        byte[] resultBytes = html.getBytes(StandardCharsets.UTF_8);
        response.getHeaders().setContentLength(resultBytes.length);
        return response.bufferFactory().wrap(resultBytes);
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

        @Override
        @NonNull
        public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
            var response = getDelegate();
            if (!isEligibleInjectionResponse(response)) {
                return super.writeWith(body);
            }
            String path = exchange.getRequest().getPath().value();
            if (path.isBlank()) {
                return super.writeWith(body);
            }
            return super.writeWith(rewriteHtmlBody(body, response, path));
        }

        @Override
        @NonNull
        public Mono<Void> writeAndFlushWith(
                @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body) {
            var response = getDelegate();
            if (!isEligibleInjectionResponse(response)) {
                return super.writeAndFlushWith(body);
            }
            String path = exchange.getRequest().getPath().value();
            if (path.isBlank()) {
                return super.writeAndFlushWith(body);
            }
            var flattenedBody = Flux.from(body).flatMapSequential(publisher -> publisher);
            var processedBody = rewriteHtmlBody(flattenedBody, response, path)
                    .flux()
                    .map(Flux::just);
            return super.writeAndFlushWith(processedBody);
        }

        /**
         * why: WebFlux 可能经由 `writeWith` 或 `writeAndFlushWith` 写 HTML body；
         * 两条路径必须共享同一份改写语义，避免只修一边时再出现漏拦截。
         */
        private Mono<DataBuffer> rewriteHtmlBody(Publisher<? extends DataBuffer> body,
                                                 ServerHttpResponse response,
                                                 String path) {
            return DataBufferUtils.join(Flux.from(body)).flatMap(dataBuffer -> {
                try {
                    String html = dataBuffer.toString(StandardCharsets.UTF_8);
                    String templateId = ContextUtil.getTemplateId(exchange);
                    if (html.isBlank()) {
                        return Mono.just(createHtmlResponseBuffer(html, response));
                    }
                    return inject(html, path, templateId)
                            .onErrorResume(e -> Mono.just(html))
                            .map(processedHtml -> createHtmlResponseBuffer(processedHtml, response));
                } finally {
                    DataBufferUtils.release(dataBuffer);
                }
            });
        }
    }

    record DomInjectionPlan(HTMLInjector injector, InjectionRule rule, String code) {
    }

    private boolean isHtmlResponse(ServerHttpResponse response) {
        return response.getHeaders().getContentType() != null
                && response.getHeaders().getContentType().includes(MediaType.TEXT_HTML);
    }

    /**
     * why: 当前响应改写链路只处理“未压缩的 UTF-8 HTML body”；
     * 若上游已经做了 body encoding，或声明了非 UTF-8 字符集，就显式跳过，避免隐式解码/重编码破坏响应。
     */
    private boolean isInjectableHtmlResponse(ServerHttpResponse response) {
        return isHtmlResponse(response)
                && hasNoEncodedBody(response)
                && usesUtf8Charset(response);
    }

    private boolean hasNoEncodedBody(ServerHttpResponse response) {
        List<String> encodings = response.getHeaders().getOrEmpty(HttpHeaders.CONTENT_ENCODING);
        if (encodings.isEmpty()) {
            return true;
        }
        return encodings.stream()
                .filter(encoding -> encoding != null && !encoding.isBlank())
                .allMatch("identity"::equalsIgnoreCase);
    }

    private boolean usesUtf8Charset(ServerHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || contentType.getCharset() == null) {
            return true;
        }
        return StandardCharsets.UTF_8.equals(contentType.getCharset());
    }
}

package com.erzbir.halo.injector.service;

import com.erzbir.halo.injector.scheme.CodeSnippet;
import com.erzbir.halo.injector.scheme.InjectionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceRelationWriteServiceTest {
    private ReactiveExtensionClient client;
    private ResourceRelationWriteService service;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ReactiveExtensionClient.class);
        service = new ResourceRelationWriteService(client);
    }

    // why: 创建代码块时，后端必须把规则侧的 `snippetIds` 一并补齐，避免再依赖前端第二轮补写。
    @Test
    void shouldSyncRuleSnippetIdsWhenCreatingSnippet() {
        CodeSnippet snippet = snippet("snippet-a");
        snippet.setRuleIds(new LinkedHashSet<>(Set.of("rule-a")));

        InjectionRule linkedRule = rule("rule-a");

        when(client.list(eq(InjectionRule.class), isNull(), isNull())).thenReturn(Flux.just(linkedRule));
        when(client.create(any(CodeSnippet.class))).thenReturn(Mono.just(snippet));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        assertDoesNotThrow(() -> service.createSnippetWithRelations(snippet).block());

        verify(client).update(Mockito.argThat((InjectionRule updatedRule) ->
                "rule-a".equals(updatedRule.getId())
                        && updatedRule.getSnippetIds().contains("snippet-a")));
    }

    // why: 创建阶段允许只传 generateName；应先创建拿到真实 metadata.name，再用这个真实 id 去同步反向关联。
    @Test
    void shouldAllowGeneratedSnippetNameDuringCreate() {
        CodeSnippet creatingSnippet = new CodeSnippet();
        Metadata creatingMetadata = new Metadata();
        creatingMetadata.setGenerateName("CodeSnippet-");
        creatingSnippet.setMetadata(creatingMetadata);
        creatingSnippet.setCode("<div>ok</div>");
        creatingSnippet.setRuleIds(new LinkedHashSet<>(Set.of("rule-a")));

        CodeSnippet createdSnippet = snippet("snippet-generated");
        createdSnippet.setRuleIds(new LinkedHashSet<>(Set.of("rule-a")));
        InjectionRule linkedRule = rule("rule-a");

        when(client.list(eq(InjectionRule.class), isNull(), isNull())).thenReturn(Flux.just(linkedRule));
        when(client.create(any(CodeSnippet.class))).thenReturn(Mono.just(createdSnippet));
        when(client.update(any(InjectionRule.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        CodeSnippet result = service.createSnippetWithRelations(creatingSnippet).block();

        assertEquals("snippet-generated", result.getId());
        verify(client).update(Mockito.argThat((InjectionRule updatedRule) ->
                "rule-a".equals(updatedRule.getId())
                        && updatedRule.getSnippetIds().contains("snippet-generated")));
    }

    // why: 若创建代码块后规则侧同步失败，必须把刚创建的代码块回滚删除，避免留下半成功状态。
    @Test
    void shouldRollbackCreatedSnippetWhenRuleSyncFails() {
        CodeSnippet snippet = snippet("snippet-a");
        snippet.setRuleIds(new LinkedHashSet<>(Set.of("rule-a")));

        InjectionRule linkedRule = rule("rule-a");

        when(client.list(eq(InjectionRule.class), isNull(), isNull())).thenReturn(Flux.just(linkedRule));
        when(client.create(any(CodeSnippet.class))).thenReturn(Mono.just(snippet));
        when(client.update(any(InjectionRule.class))).thenReturn(Mono.error(new RuntimeException("sync failed")));
        when(client.delete(any(CodeSnippet.class))).thenReturn(Mono.just(snippet));

        assertThrows(RuntimeException.class, () -> service.createSnippetWithRelations(snippet).block());

        verify(client).delete(Mockito.argThat((CodeSnippet createdSnippet) ->
                "snippet-a".equals(createdSnippet.getId())));
    }

    // why: 删除规则时要把代码块上的 `ruleIds` 一并清掉，防止已删除规则继续残留在代码块关联里。
    @Test
    void shouldRemoveSnippetRuleIdsWhenDeletingRule() {
        InjectionRule deletingRule = rule("rule-a");
        deletingRule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a")));

        CodeSnippet linkedSnippet = snippet("snippet-a");
        linkedSnippet.setRuleIds(new LinkedHashSet<>(Set.of("rule-a")));

        when(client.list(eq(CodeSnippet.class), isNull(), isNull())).thenReturn(Flux.just(linkedSnippet));
        when(client.update(any(CodeSnippet.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));
        when(client.delete(any(InjectionRule.class))).thenReturn(Mono.just(deletingRule));

        assertDoesNotThrow(() -> service.deleteRuleWithRelations(deletingRule).block());

        verify(client).update(Mockito.argThat((CodeSnippet updatedSnippet) ->
                "snippet-a".equals(updatedSnippet.getId())
                        && !updatedSnippet.getRuleIds().contains("rule-a")));
        verify(client).delete(deletingRule);
    }

    // why: 创建规则同样允许只传 generateName；必须等 create 返回真实 metadata.name 后再去同步代码块侧。
    @Test
    void shouldAllowGeneratedRuleNameDuringCreate() {
        InjectionRule creatingRule = new InjectionRule();
        Metadata creatingMetadata = new Metadata();
        creatingMetadata.setGenerateName("InjectionRule-");
        creatingRule.setMetadata(creatingMetadata);
        creatingRule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a")));

        InjectionRule createdRule = rule("rule-generated");
        createdRule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a")));
        CodeSnippet linkedSnippet = snippet("snippet-a");

        when(client.list(eq(CodeSnippet.class), isNull(), isNull())).thenReturn(Flux.just(linkedSnippet));
        when(client.create(any(InjectionRule.class))).thenReturn(Mono.just(createdRule));
        when(client.update(any(CodeSnippet.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        InjectionRule result = service.createRuleWithRelations(creatingRule).block();

        assertEquals("rule-generated", result.getId());
        verify(client).update(Mockito.argThat((CodeSnippet updatedSnippet) ->
                "snippet-a".equals(updatedSnippet.getId())
                        && updatedSnippet.getRuleIds().contains("rule-generated")));
    }

    // why: 若关联关系本身没有变化，就不应额外改写反向资源，避免无意义更新放大写入成本。
    @Test
    void shouldSkipReverseWritesWhenRuleLinksDoNotChange() {
        InjectionRule rule = rule("rule-a");
        rule.setSnippetIds(new LinkedHashSet<>(Set.of("snippet-a")));

        CodeSnippet linkedSnippet = snippet("snippet-a");
        linkedSnippet.setRuleIds(new LinkedHashSet<>(Set.of("rule-a")));

        when(client.list(eq(CodeSnippet.class), isNull(), isNull())).thenReturn(Flux.just(linkedSnippet));
        when(client.update(any(InjectionRule.class))).thenReturn(Mono.just(rule));

        assertDoesNotThrow(() -> service.updateRuleWithRelations(rule, rule).block());

        verify(client, never()).update(any(CodeSnippet.class));
    }

    private CodeSnippet snippet(String id) {
        CodeSnippet snippet = new CodeSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setCode("<div>ok</div>");
        return snippet;
    }

    private InjectionRule rule(String id) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        rule.setMetadata(metadata);
        return rule;
    }
}

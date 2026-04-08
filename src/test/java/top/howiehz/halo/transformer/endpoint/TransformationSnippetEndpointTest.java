package top.howiehz.halo.transformer.endpoint;

import top.howiehz.halo.transformer.manager.TransformationRuleRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.service.TransformationSnippetLifecycleService;
import top.howiehz.halo.transformer.util.TransformationSnippetValidationException;
import top.howiehz.halo.transformer.util.TransformationSnippetValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransformationSnippetEndpointTest {
    private ReactiveExtensionClient client;
    private TransformationSnippetValidator validator;
    private TransformationRuleRuntimeStore ruleRuntimeStore;
    private TransformationSnippetEndpoint endpoint;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        validator = mock(TransformationSnippetValidator.class);
        ruleRuntimeStore = mock(TransformationRuleRuntimeStore.class);
        endpoint = new TransformationSnippetEndpoint(
                client,
                validator,
                mock(TransformationSnippetLifecycleService.class),
                ruleRuntimeStore,
                mock(ConsoleReadModelMapper.class)
        );
    }

    // why: 代码片段启停现在应有独立写口，只修改 enabled；
    // 用户切换启停时不该再顺带提交整份编辑草稿。
    @Test
    void shouldToggleSnippetEnabledWithoutRequiringDraftPayload() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setVersion(7L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));
        when(validator.validateForWrite(any(TransformationSnippet.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(TransformationSnippet.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        TransformationSnippetEndpoint.EnabledPayload payload = enabledPayload(true, 7L);
        TransformationSnippet updated = endpoint.updateSnippetEnabled("snippet-a", payload).block();

        assertEquals(true, updated.isEnabled());
        verify(client).update(any(TransformationSnippet.class));
        verify(ruleRuntimeStore).invalidateAndWarmUpAsync();
    }

    // why: 停用只是把资源移出运行时，不该被历史坏内容再次卡住；因此停用路径不再复跑写入校验。
    @Test
    void shouldDisableSnippetWithoutRevalidatingItsContent() {
        TransformationSnippet snippet = snippet("snippet-a", "", true);
        snippet.getMetadata().setVersion(3L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));
        when(client.update(any(TransformationSnippet.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        TransformationSnippet updated = endpoint.updateSnippetEnabled("snippet-a", enabledPayload(false, 3L)).block();

        assertEquals(false, updated.isEnabled());
        verify(validator, never()).validateForWrite(any(TransformationSnippet.class));
    }

    // why: 启用必须围绕已保存资源重新做后端校验；
    // 否则历史坏数据会被直接重新放回运行时。
    @Test
    void shouldRejectEnablingInvalidPersistedSnippet() {
        TransformationSnippet snippet = snippet("snippet-a", "", false);
        snippet.getMetadata().setVersion(5L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));
        when(validator.validateForWrite(any(TransformationSnippet.class)))
                .thenReturn(Mono.error(new TransformationSnippetValidationException("code：请填写代码内容")));

        TransformationSnippetValidationException error = assertThrows(
                TransformationSnippetValidationException.class,
                () -> endpoint.updateSnippetEnabled("snippet-a", enabledPayload(true, 5L)).block()
        );

        assertEquals("code：请填写代码内容", error.getReason());
        verify(client, never()).update(any(TransformationSnippet.class));
    }

    // why: 启停虽然只改 enabled，但仍是写操作；旧版本请求必须被拒绝，不能静默覆盖较新的已保存状态。
    @Test
    void shouldRejectTogglingSnippetEnabledWithStaleVersion() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setVersion(7L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> endpoint.updateSnippetEnabled("snippet-a", enabledPayload(true, 6L)).block()
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("代码片段已被其他人修改，请刷新后重试", error.getReason());
        verify(client, never()).update(any(TransformationSnippet.class));
    }

    private TransformationSnippet snippet(String id, String code, boolean enabled) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setCode(code);
        snippet.setEnabled(enabled);
        return snippet;
    }

    private TransformationSnippetEndpoint.EnabledPayload enabledPayload(boolean enabled, long version) {
        TransformationSnippetEndpoint.EnabledPayload payload = new TransformationSnippetEndpoint.EnabledPayload();
        payload.setEnabled(enabled);
        Metadata metadata = new Metadata();
        metadata.setVersion(version);
        payload.setMetadata(metadata);
        return payload;
    }
}


package top.howiehz.halo.transformer.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.manager.TransformationSnippetRuntimeStore;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;
import top.howiehz.halo.transformer.service.TransformationSnippetLifecycleService;
import top.howiehz.halo.transformer.util.TransformationSnippetValidationException;
import top.howiehz.halo.transformer.util.TransformationSnippetValidator;

class TransformationSnippetEndpointTest {
    private ReactiveExtensionClient client;
    private TransformationSnippetValidator validator;
    private TransformationSnippetLifecycleService lifecycleService;
    private TransformationSnippetRuntimeStore snippetRuntimeStore;
    private TransformationSnippetEndpoint endpoint;

    @BeforeEach
    void setUp() {
        client = mock(ReactiveExtensionClient.class);
        validator = mock(TransformationSnippetValidator.class);
        lifecycleService = mock(TransformationSnippetLifecycleService.class);
        snippetRuntimeStore = mock(TransformationSnippetRuntimeStore.class);
        endpoint = new TransformationSnippetEndpoint(
            client,
            validator,
            lifecycleService,
            snippetRuntimeStore,
            mock(ConsoleReadModelMapper.class),
            mock(ResourceOrderService.class)
        );
    }

    // why: 代码片段启停现在应有独立写口，只修改 enabled；
    // 用户切换启停时不该再顺带提交整份编辑草稿。
    @Test
    void shouldToggleSnippetEnabledWithoutRequiringDraftPayload() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setVersion(7L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));
        when(validator.validateForWrite(any(TransformationSnippet.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(TransformationSnippet.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        TransformationSnippetEndpoint.EnabledPayload payload = enabledPayload(true, 7L);
        TransformationSnippet updated = endpoint.updateSnippetEnabled("snippet-a", payload).block();

        assertTrue(updated.isEnabled());
        verify(client).update(any(TransformationSnippet.class));
        verify(snippetRuntimeStore).invalidateAndWarmUpAsync();
    }

    // why: 停用只是把资源移出运行时，不该被历史坏内容再次卡住；因此停用路径不再复跑写入校验。
    @Test
    void shouldDisableSnippetWithoutRevalidatingItsContent() {
        TransformationSnippet snippet = snippet("snippet-a", "", true);
        snippet.getMetadata().setVersion(3L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));
        when(client.update(any(TransformationSnippet.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        TransformationSnippet updated =
            endpoint.updateSnippetEnabled("snippet-a", enabledPayload(false, 3L)).block();

        assertFalse(updated.isEnabled());
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
            .thenReturn(
                Mono.error(new TransformationSnippetValidationException("code：请填写代码内容")));

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

    // why: “删除中”的资源已经退出控制台当前可编辑集合；
    // 启停接口也必须复用同一套可见性语义，避免列表已隐藏但单项写口仍可继续修改。
    @Test
    void shouldRejectTogglingDeletingSnippet() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setDeletionTimestamp(java.time.Instant.now());
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));

        ServerWebInputException error = assertThrows(
            ServerWebInputException.class,
            () -> endpoint.updateSnippetEnabled("snippet-a", enabledPayload(true, 7L)).block()
        );

        assertEquals("未找到要更新的代码片段", error.getReason());
        verify(client, never()).update(any(TransformationSnippet.class));
    }

    // why: 删除也是写路径；如果不复用 metadata.version，控制台里最后一个 mutation 漏洞就会留在 delete 上。
    @Test
    void shouldRejectDeletingSnippetWithStaleVersion() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setVersion(7L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> endpoint.deleteSnippet("snippet-a", deletePayload(6L)).block()
        );

        assertEquals(409, error.getStatusCode().value());
        assertEquals("代码片段已被其他人修改，请刷新后重试", error.getReason());
        verify(lifecycleService, never()).markForDeletion(any(TransformationSnippet.class));
    }

    // why: “删除中”的代码片段已经退出“当前可删资源”集合；
    // delete 写口也应复用同一套可见性语义，避免重复点击删除时得到和其它写口不一致的结果。
    @Test
    void shouldRejectDeletingSnippetThatIsAlreadyDeleting() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setDeletionTimestamp(java.time.Instant.now());
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));

        ServerWebInputException error = assertThrows(
            ServerWebInputException.class,
            () -> endpoint.deleteSnippet("snippet-a", deletePayload(7L)).block()
        );

        assertEquals("未找到要删除的代码片段", error.getReason());
        verify(lifecycleService, never()).markForDeletion(any(TransformationSnippet.class));
    }

    // why: 代码片段删除成功的真实语义是“进入 finalizer 生命周期”，
    // 测试要锁这个领域动作，而不是误验一个根本不会发生的物理 delete 调用。
    @Test
    void shouldDeleteSnippetWithMatchingVersion() {
        TransformationSnippet snippet = snippet("snippet-a", "<div>ok</div>", false);
        snippet.getMetadata().setVersion(7L);
        when(client.fetch(TransformationSnippet.class, "snippet-a")).thenReturn(Mono.just(snippet));
        when(lifecycleService.markForDeletion(any(TransformationSnippet.class)))
            .thenReturn(Mono.empty());

        endpoint.deleteSnippet("snippet-a", deletePayload(7L)).block();

        verify(lifecycleService).markForDeletion(snippet);
        verify(snippetRuntimeStore).invalidateAndWarmUpAsync();
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

    private TransformationSnippetEndpoint.EnabledPayload enabledPayload(boolean enabled,
        long version) {
        TransformationSnippetEndpoint.EnabledPayload payload =
            new TransformationSnippetEndpoint.EnabledPayload();
        payload.setEnabled(enabled);
        Metadata metadata = new Metadata();
        metadata.setVersion(version);
        payload.setMetadata(metadata);
        return payload;
    }

    private TransformationSnippetEndpoint.DeletePayload deletePayload(long version) {
        TransformationSnippetEndpoint.DeletePayload payload =
            new TransformationSnippetEndpoint.DeletePayload();
        Metadata metadata = new Metadata();
        metadata.setVersion(version);
        payload.setMetadata(metadata);
        return payload;
    }
}

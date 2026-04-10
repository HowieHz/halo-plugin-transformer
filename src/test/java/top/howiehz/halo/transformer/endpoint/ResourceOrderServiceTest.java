package top.howiehz.halo.transformer.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import top.howiehz.halo.transformer.scheme.ResourceOrder;
import top.howiehz.halo.transformer.scheme.TransformationSnippet;

@ExtendWith(MockitoExtension.class)
class ResourceOrderServiceTest {
    @Mock
    private ReactiveExtensionClient client;

    private ResourceOrderService service;

    @BeforeEach
    void setUp() {
        service = new ResourceOrderService(client);
    }

    // why: 控制台快照读取现在应只消费 watch-driven 可见资源快照；
    // 否则每次列表刷新都会在请求线程回源整表扫描，和 runtime store 的设计直接冲突。
    @Test
    void shouldBuildCollectionSnapshotWithoutListingResourcesOnRequestPath() {
        ResourceOrder storedOrder = new ResourceOrder();
        Metadata metadata = new Metadata();
        metadata.setVersion(7L);
        storedOrder.setMetadata(metadata);
        storedOrder.setOrders(new LinkedHashMap<>(Map.of("snippet-a", 3)));
        when(client.fetch(ResourceOrder.class, ResourceOrderService.SNIPPET_ORDER_NAME))
            .thenReturn(Mono.just(storedOrder));

        ResourceOrderService.ResourceCollectionSnapshot<TransformationSnippet> snapshot =
            service.buildCollectionSnapshot(
                    ResourceOrderService.SNIPPET_ORDER_NAME,
                    () -> List.of(snippet("snippet-a", "Alpha")),
                    TransformationSnippet::getName)
                .block();

        assertEquals(List.of("snippet-a"),
            snapshot.resources().stream().map(item -> item.getMetadata().getName()).toList());
        assertEquals(Map.of("snippet-a", 1), snapshot.orders());
        assertEquals(7L, snapshot.orderVersion());
        verify(client, never()).list(any(), any(), any());
    }

    // why: 拖拽保存排序的有效资源集合也必须来自同一份内存快照；
    // 这样删除后的资源会自然从写模型视野中消失，而不是每次保存都再去回源 list 全表。
    @Test
    void shouldSaveOrderAgainstVisibleSnapshotWithoutListingResources() {
        ResourceOrder storedOrder = new ResourceOrder();
        Metadata persistedMetadata = new Metadata();
        persistedMetadata.setName(ResourceOrderService.SNIPPET_ORDER_NAME);
        persistedMetadata.setVersion(5L);
        storedOrder.setMetadata(persistedMetadata);
        storedOrder.setOrders(new LinkedHashMap<>());
        when(client.fetch(ResourceOrder.class, ResourceOrderService.SNIPPET_ORDER_NAME))
            .thenReturn(Mono.just(storedOrder));
        when(client.update(any(ResourceOrder.class))).thenAnswer(
            invocation -> Mono.just(invocation.getArgument(0)));

        ResourceOrderEndpoint.OrderPayload payload = new ResourceOrderEndpoint.OrderPayload();
        payload.setOrders(new LinkedHashMap<>(Map.of("snippet-a", 9, "snippet-missing", 1)));
        Metadata incomingMetadata = new Metadata();
        incomingMetadata.setVersion(5L);
        payload.setMetadata(incomingMetadata);

        ResourceOrderService.OrderState orderState = service.saveOrder(
                payload,
                ResourceOrderService.SNIPPET_ORDER_NAME,
                "代码片段",
                () -> List.of(snippet("snippet-a", "Alpha")),
                TransformationSnippet::getName)
            .block();

        ArgumentCaptor<ResourceOrder> orderCaptor = ArgumentCaptor.forClass(ResourceOrder.class);
        verify(client).update(orderCaptor.capture());
        verify(client, never()).list(any(), any(), any());
        assertEquals(Map.of("snippet-a", 1), orderState.orders());
        assertEquals(5L, orderState.version());
        assertEquals(Map.of("snippet-a", 1), orderCaptor.getValue().getOrders());
    }

    private TransformationSnippet snippet(String id, String name) {
        TransformationSnippet snippet = new TransformationSnippet();
        Metadata metadata = new Metadata();
        metadata.setName(id);
        snippet.setMetadata(metadata);
        snippet.setName(name);
        return snippet;
    }
}

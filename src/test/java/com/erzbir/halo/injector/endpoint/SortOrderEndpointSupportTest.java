package com.erzbir.halo.injector.endpoint;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebInputException;
import run.halo.app.extension.Metadata;
import com.erzbir.halo.injector.scheme.InjectionRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SortOrderEndpointSupportTest {
    private final SortOrderEndpointSupport support = new SortOrderEndpointSupport(null);

    // why: 专用排序接口只接受最小必要字段，合法请求必须能顺利通过，避免误伤正常拖拽排序。
    @Test
    void shouldAllowValidSortOrderRequest() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();
        request.setItems(java.util.List.of(item("rule-a", 1), item("rule-b", 2)));

        assertDoesNotThrow(() -> support.validate(request, "注入规则").block());
    }

    // why: 空排序列表没有明确目标，后端必须在入库前直接拒绝，避免产生“拖了但没生效”的歧义。
    @Test
    void shouldRejectEmptySortOrderRequest() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();

        assertThrows(ServerWebInputException.class, () -> support.validate(request, "注入规则").block());
    }

    // why: 排序接口按资源 ID 精确更新，重复 ID 会让最终顺序不确定，必须在请求入口就拦下。
    @Test
    void shouldRejectDuplicateIdsInSortOrderRequest() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();
        request.setItems(java.util.List.of(item("rule-a", 1), item("rule-a", 2)));

        assertThrows(ServerWebInputException.class, () -> support.validate(request, "注入规则").block());
    }

    // why: 同一个顺序值若被多个资源占用，最终排序会冲突；写入前必须保证 sortOrder 唯一。
    @Test
    void shouldRejectDuplicateSortOrdersInSortOrderRequest() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();
        request.setItems(java.util.List.of(item("rule-a", 1), item("rule-b", 1)));

        assertThrows(ServerWebInputException.class, () -> support.validate(request, "注入规则").block());
    }

    // why: 排序语义是“重排当前整组资源”，请求若漏掉某条资源，会留下旧顺序残影，必须在写入前直接拒绝。
    @Test
    void shouldRejectWhenSortOrderRequestDoesNotCoverAllResources() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();
        request.setItems(java.util.List.of(item("rule-a", 1)));

        ServerWebInputException error = assertThrows(ServerWebInputException.class,
                () -> support.validateSnapshotAndArrange(List.of(resource("rule-a"), resource("rule-b")),
                        request, "注入规则"));

        assertEquals("注入规则排序项必须覆盖当前全部资源", error.getReason());
    }

    // why: 请求里若带了当前快照中不存在的资源 ID，说明排序基线已经过时，继续写入会把结果拖成半新半旧。
    @Test
    void shouldRejectWhenSortOrderRequestContainsUnknownResource() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();
        request.setItems(java.util.List.of(item("rule-a", 1), item("rule-c", 2)));

        ServerWebInputException error = assertThrows(ServerWebInputException.class,
                () -> support.validateSnapshotAndArrange(List.of(resource("rule-a"), resource("rule-b")),
                        request, "注入规则"));

        assertEquals("注入规则不存在：rule-c", error.getReason());
    }

    // why: 当前快照与请求全集一致时，后端应按请求顺序收敛出完整写入批次，后续才能在统一快照上顺序更新。
    @Test
    void shouldArrangeResourcesByRequestedOrder() {
        SortOrderUpdateRequest request = new SortOrderUpdateRequest();
        request.setItems(java.util.List.of(item("rule-b", 1), item("rule-a", 2)));

        List<InjectionRule> ordered = support.validateSnapshotAndArrange(
                List.of(resource("rule-a"), resource("rule-b")),
                request,
                "注入规则"
        );

        assertEquals(List.of("rule-b", "rule-a"),
                ordered.stream().map(rule -> rule.getMetadata().getName()).toList());
    }

    private SortOrderUpdateRequest.Item item(String id, int sortOrder) {
        SortOrderUpdateRequest.Item item = new SortOrderUpdateRequest.Item();
        item.setId(id);
        item.setSortOrder(sortOrder);
        return item;
    }

    private InjectionRule resource(String name) {
        InjectionRule rule = new InjectionRule();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        rule.setMetadata(metadata);
        return rule;
    }
}

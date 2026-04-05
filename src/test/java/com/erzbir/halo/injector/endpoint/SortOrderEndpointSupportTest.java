package com.erzbir.halo.injector.endpoint;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ServerWebInputException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    private SortOrderUpdateRequest.Item item(String id, int sortOrder) {
        SortOrderUpdateRequest.Item item = new SortOrderUpdateRequest.Item();
        item.setId(id);
        item.setSortOrder(sortOrder);
        return item;
    }
}

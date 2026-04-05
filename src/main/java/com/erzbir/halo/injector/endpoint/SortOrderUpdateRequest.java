package com.erzbir.halo.injector.endpoint;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * why: 左侧排序只需要资源 ID 与排序值，不该复用完整资源写入模型，
 * 这样可以把“顺序调整”与“内容编辑校验”彻底解耦。
 */
@Data
public class SortOrderUpdateRequest {
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        private String id;
        private Integer sortOrder;
    }
}

package com.erzbir.halo.injector.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(kind = "ResourceOrder", group = "injector.erzbir.com", version = "v1alpha1",
        singular = "resourceOrder", plural = "resourceOrders")
public class ResourceOrder extends AbstractExtension {
    /**
     * why: 排序映射单独存放，避免左侧列表拖拽继续污染代码块 / 注入规则本体；
     * 这里只有“显式排过序”的项，未出现的资源默认按 0 处理。
     */
    private Map<String, Integer> orders = new LinkedHashMap<>();
}

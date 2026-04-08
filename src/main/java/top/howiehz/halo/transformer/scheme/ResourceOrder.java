package top.howiehz.halo.transformer.scheme;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(kind = "ResourceOrder", group = "transformer.howiehz.top", version = "v1alpha1",
    singular = "resourceOrder", plural = "resourceOrders")
public class ResourceOrder extends AbstractExtension {
    /**
     * why: 排序映射单独存放，避免左侧列表拖拽继续污染代码片段 / 转换规则本体；
     * 这里只有“显式排过序”的项，未出现的资源默认按 0 处理。
     */
    private Map<String, Integer> orders = new LinkedHashMap<>();
}


package top.howiehz.halo.transformer.endpoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConsoleOrderedItemList<T>(
    boolean first,
    boolean hasNext,
    boolean hasPrevious,
    boolean last,
    int page,
    int size,
    int totalPages,
    java.util.List<T> items,
    int total,
    Map<String, Integer> orders,
    Long orderVersion
) {
    static <T> ConsoleOrderedItemList<T> of(ConsoleItemList<T> list, Map<String, Integer> orders,
        Long orderVersion) {
        return new ConsoleOrderedItemList<>(
            list.first(),
            list.hasNext(),
            list.hasPrevious(),
            list.last(),
            list.page(),
            list.size(),
            list.totalPages(),
            list.items(),
            list.total(),
            Collections.unmodifiableMap(new LinkedHashMap<>(orders)),
            orderVersion
        );
    }
}

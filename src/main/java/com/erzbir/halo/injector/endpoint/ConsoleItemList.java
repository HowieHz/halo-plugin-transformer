package com.erzbir.halo.injector.endpoint;

import java.util.List;

public record ConsoleItemList<T>(
        boolean first,
        boolean hasNext,
        boolean hasPrevious,
        boolean last,
        int page,
        int size,
        int totalPages,
        List<T> items,
        int total
) {
    static <T> ConsoleItemList<T> of(List<T> items) {
        List<T> safeItems = List.copyOf(items);
        return new ConsoleItemList<>(
                true,
                false,
                false,
                true,
                0,
                safeItems.size(),
                1,
                safeItems,
                safeItems.size()
        );
    }
}

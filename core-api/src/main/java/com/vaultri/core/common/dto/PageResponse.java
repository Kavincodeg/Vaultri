package com.vaultri.core.common.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / (double) size);
        return new PageResponse<>(items, page, size, totalElements, totalPages);
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    public static <T, R> PageResponse<R> from(Page<T> page, List<R> items) {
        return new PageResponse<>(
                items,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}

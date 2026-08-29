package com.muster.common;

/** 分页参数统一钳制：page≥1、1≤size≤200。 */
public record PageParams(int page, int size) {

    private static final int MAX_SIZE = 200;

    public static PageParams clamp(int page, int size) {
        return new PageParams(Math.max(1, page), Math.min(Math.max(size, 1), MAX_SIZE));
    }
}

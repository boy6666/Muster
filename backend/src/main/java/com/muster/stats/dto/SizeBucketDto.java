package com.muster.stats.dto;

public record SizeBucketDto(long size, long count, boolean overLimit) {
}

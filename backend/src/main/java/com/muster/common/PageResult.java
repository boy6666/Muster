package com.muster.common;

import java.util.List;

public record PageResult<T>(long total, List<T> records) {
}

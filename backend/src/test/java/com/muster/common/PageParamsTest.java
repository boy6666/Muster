package com.muster.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 分页参数钳制：page≥1、1≤size≤200，防止负数 size 触发 MyBatis-Plus 无限制取全表。 */
class PageParamsTest {

    @Test
    void clampsNegativePageAndSize() {
        var p = PageParams.clamp(-1, -5);
        assertThat(p.page()).isEqualTo(1);
        assertThat(p.size()).isEqualTo(1);
    }

    @Test
    void clampsOversizedSize() {
        var p = PageParams.clamp(3, 100_000);
        assertThat(p.page()).isEqualTo(3);
        assertThat(p.size()).isEqualTo(200);
    }

    @Test
    void keepsNormalValuesUntouched() {
        var p = PageParams.clamp(2, 20);
        assertThat(p.page()).isEqualTo(2);
        assertThat(p.size()).isEqualTo(20);
    }
}

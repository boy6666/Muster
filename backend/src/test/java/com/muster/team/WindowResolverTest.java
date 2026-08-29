package com.muster.team;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class WindowResolverTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 1, 18, 0);

    @Test
    void beforeStartIsNotStarted() {
        var now = START.minusSeconds(1);
        assertThat(WindowResolver.resolve(START, END, false, now)).isEqualTo(WindowStatus.NOT_STARTED);
    }

    @Test
    void insideWindowIsActive() {
        var now = START.plusHours(2);
        assertThat(WindowResolver.resolve(START, END, false, now)).isEqualTo(WindowStatus.ACTIVE);
    }

    @Test
    void afterEndIsEnded() {
        var now = END.plusSeconds(1);
        assertThat(WindowResolver.resolve(START, END, false, now)).isEqualTo(WindowStatus.ENDED);
    }

    @Test
    void manualEndWinsOverActiveWindow() {
        var now = START.plusHours(2);
        assertThat(WindowResolver.resolve(START, END, true, now)).isEqualTo(WindowStatus.ENDED);
    }

    @Test
    void exactlyAtStartIsActive() {
        assertThat(WindowResolver.resolve(START, END, false, START)).isEqualTo(WindowStatus.ACTIVE);
    }

    @Test
    void exactlyAtEndIsActive() {
        assertThat(WindowResolver.resolve(START, END, false, END)).isEqualTo(WindowStatus.ACTIVE);
    }
}

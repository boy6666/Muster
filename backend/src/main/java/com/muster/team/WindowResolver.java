package com.muster.team;

import java.time.LocalDateTime;

public final class WindowResolver {

    private WindowResolver() {
    }

    public static WindowStatus resolve(LocalDateTime start, LocalDateTime end, boolean manuallyEnded,
                                       LocalDateTime now) {
        if (manuallyEnded || now.isAfter(end)) {
            return WindowStatus.ENDED;
        }
        if (now.isBefore(start)) {
            return WindowStatus.NOT_STARTED;
        }
        return WindowStatus.ACTIVE;
    }
}

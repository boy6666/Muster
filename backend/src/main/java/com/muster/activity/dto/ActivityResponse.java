package com.muster.activity.dto;

import com.muster.activity.Activity;

import java.time.LocalDateTime;

public record ActivityResponse(
        Long id,
        String name,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer groupSizeLimit,
        String qrToken,
        boolean exported,
        boolean manuallyEnded,
        String windowStatus) {

    public static ActivityResponse from(Activity a, String windowStatus) {
        return new ActivityResponse(a.getId(), a.getName(), a.getStartTime(), a.getEndTime(),
                a.getGroupSizeLimit(), a.getQrToken(),
                Boolean.TRUE.equals(a.getExported()), Boolean.TRUE.equals(a.getManuallyEnded()),
                windowStatus);
    }
}

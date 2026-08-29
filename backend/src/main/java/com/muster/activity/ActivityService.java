package com.muster.activity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.muster.activity.dto.ActivityCreateRequest;
import com.muster.activity.dto.ActivityUpdateRequest;
import com.muster.common.ApiException;
import com.muster.common.ErrorCode;
import com.muster.team.WindowResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String formBaseUrl;
    private final com.muster.audit.OpLogService opLogService;

    public ActivityService(ActivityMapper activityMapper, JdbcTemplate jdbc, Clock clock,
                           @Value("${muster.form-base-url}") String formBaseUrl,
                           com.muster.audit.OpLogService opLogService) {
        this.activityMapper = activityMapper;
        this.jdbc = jdbc;
        this.clock = clock;
        this.formBaseUrl = formBaseUrl;
        this.opLogService = opLogService;
    }

    public Activity current() {
        return activityMapper.selectOne(new LambdaQueryWrapper<Activity>()
                .orderByAsc(Activity::getId)
                .last("LIMIT 1"));
    }

    public Activity requireCurrent() {
        Activity activity = current();
        if (activity == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "尚未创建活动");
        }
        return activity;
    }

    public Activity currentByToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        return activityMapper.selectOne(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getQrToken, token));
    }

    public Activity create(ActivityCreateRequest request) {
        Activity existing = current();
        if (existing != null) {
            if (!Boolean.TRUE.equals(existing.getExported())) {
                throw new ApiException(ErrorCode.ARCHIVE_REQUIRED, "请先导出归档包");
            }
            throw new ApiException(ErrorCode.CONFLICT, "请先删除旧活动");
        }
        validateRange(request.startTime(), request.endTime());

        Activity activity = new Activity();
        activity.setName(request.name());
        activity.setStartTime(request.startTime());
        activity.setEndTime(request.endTime());
        activity.setGroupSizeLimit(request.groupSizeLimit());
        activity.setQrToken(UUID.randomUUID().toString().replace("-", ""));
        activity.setCreatedAt(LocalDateTime.now(clock));
        activityMapper.insert(activity);
        opLogService.record("ACTIVITY_CREATE", request.name());
        return activity;
    }

    public void update(ActivityUpdateRequest request) {
        Activity activity = requireCurrent();
        if (request.startTime() != null || request.endTime() != null) {
            if (!"NOT_STARTED".equals(windowStatus(activity))) {
                throw new ApiException(ErrorCode.WINDOW_CLOSED, "活动已开始，不能修改时间");
            }
            LocalDateTime newStart = request.startTime() != null ? request.startTime() : activity.getStartTime();
            LocalDateTime newEnd = request.endTime() != null ? request.endTime() : activity.getEndTime();
            validateRange(newStart, newEnd);
            activity.setStartTime(newStart);
            activity.setEndTime(newEnd);
        }
        if (request.name() != null) {
            activity.setName(request.name());
        }
        if (request.groupSizeLimit() != null) {
            activity.setGroupSizeLimit(request.groupSizeLimit());
        }
        activityMapper.updateById(activity);
        opLogService.record("ACTIVITY_UPDATE", activity.getName());
    }

    public void end() {
        Activity activity = requireCurrent();
        if ("ENDED".equals(windowStatus(activity))) {
            throw new ApiException(ErrorCode.CONFLICT, "活动已结束");
        }
        activity.setManuallyEnded(true);
        activityMapper.updateById(activity);
        opLogService.record("ACTIVITY_END", activity.getName());
    }

    @Transactional
    public void delete() {
        Activity activity = requireCurrent();
        if (!Boolean.TRUE.equals(activity.getExported())) {
            throw new ApiException(ErrorCode.ARCHIVE_REQUIRED, "请先导出归档包");
        }
        jdbc.update("DELETE FROM team_member WHERE team_id IN (SELECT id FROM team WHERE activity_id = ?)",
                activity.getId());
        jdbc.update("DELETE FROM team WHERE activity_id = ?", activity.getId());
        jdbc.update("DELETE FROM team_event WHERE activity_id = ?", activity.getId());
        jdbc.update("DELETE FROM person WHERE activity_id = ?", activity.getId());
        activityMapper.deleteById(activity.getId());
        opLogService.record("ACTIVITY_DELETE", activity.getName());
    }

    public String formUrl(Activity activity) {
        return formBaseUrl + "/form/" + activity.getQrToken();
    }

    /** 窗口判定委托 WindowResolver 统一实现。 */
    public String windowStatus(Activity activity) {
        return WindowResolver.resolve(activity.getStartTime(), activity.getEndTime(),
                Boolean.TRUE.equals(activity.getManuallyEnded()), LocalDateTime.now(clock)).name();
    }

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ApiException(ErrorCode.VALIDATION, "开始时间必须早于结束时间");
        }
    }
}

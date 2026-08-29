package com.muster.stats;

import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.common.ApiException;
import com.muster.common.ErrorCode;
import com.muster.roster.ExcelService;
import com.muster.roster.dto.ArchiveDetailRow;
import com.muster.roster.dto.JoinedRow;
import com.muster.roster.dto.MissingRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ExportService {

    private final ActivityService activityService;
    private final ExportMapper exportMapper;
    private final ExcelService excelService;
    private final com.muster.activity.ActivityMapper activityMapper;
    private final com.muster.audit.OpLogService opLogService;

    public ExportService(ActivityService activityService, ExportMapper exportMapper, ExcelService excelService,
                         com.muster.activity.ActivityMapper activityMapper,
                         com.muster.audit.OpLogService opLogService) {
        this.activityService = activityService;
        this.exportMapper = exportMapper;
        this.excelService = excelService;
        this.activityMapper = activityMapper;
        this.opLogService = opLogService;
    }

    public byte[] export(String type) {
        Activity activity = activityService.requireCurrent();
        if ("JOINED".equalsIgnoreCase(type)) {
            return excelService.writeJoined(toJoinedRows(exportMapper.selectJoined(activity.getId())));
        }
        if ("MISSING".equalsIgnoreCase(type)) {
            return excelService.writeMissing(exportMapper.selectMissing(activity.getId()));
        }
        throw new ApiException(ErrorCode.VALIDATION, "type 必须为 JOINED 或 MISSING");
    }

    @Transactional
    public byte[] archive() {
        Activity activity = activityService.requireCurrent();
        byte[] bytes = excelService.writeArchive(
                toJoinedRows(exportMapper.selectJoined(activity.getId())),
                exportMapper.selectMissing(activity.getId()),
                exportMapper.selectArchiveDetail(activity.getId()));
        activity.setExported(true);
        activityMapper.updateById(activity);
        opLogService.record("ACTIVITY_ARCHIVE", activity.getName());
        return bytes;
    }

    private List<JoinedRow> toJoinedRows(List<JoinedRow> rows) {
        return rows.stream()
                .map(r -> new JoinedRow(r.name(), r.phone(), r.department(), r.teamName(),
                        r.submittedAt() == null ? "" : r.submittedAt()))
                .toList();
    }
}

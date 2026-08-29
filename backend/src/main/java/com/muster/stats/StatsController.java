package com.muster.stats;

import com.muster.common.ApiException;
import com.muster.stats.dto.StatsDto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class StatsController {

    private final StatsService statsService;
    private final ExportService exportService;

    public StatsController(StatsService statsService, ExportService exportService) {
        this.statsService = statsService;
        this.exportService = exportService;
    }

    @GetMapping("/api/stats")
    public StatsDto stats() {
        return statsService.current();
    }

    @GetMapping("/api/stats/export")
    public ResponseEntity<byte[]> export(@RequestParam String type) {
        if (!"JOINED".equalsIgnoreCase(type) && !"MISSING".equalsIgnoreCase(type)) {
            throw new ApiException(com.muster.common.ErrorCode.VALIDATION, "type 必须为 JOINED 或 MISSING");
        }
        byte[] bytes = exportService.export(type);
        String filename = "JOINED".equalsIgnoreCase(type) ? "已参加.xlsx" : "未参加.xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }
}

package com.muster.activity;

import com.muster.activity.dto.ActivityCreateRequest;
import com.muster.activity.dto.ActivityResponse;
import com.muster.activity.dto.ActivityUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService activityService;
    private final com.muster.stats.ExportService exportService;

    public ActivityController(ActivityService activityService, com.muster.stats.ExportService exportService) {
        this.activityService = activityService;
        this.exportService = exportService;
    }

    @GetMapping
    public ActivityResponse current() {
        Activity activity = activityService.current();
        return activity == null ? null : ActivityResponse.from(activity, activityService.windowStatus(activity));
    }

    @PostMapping
    public ActivityResponse create(@Valid @RequestBody ActivityCreateRequest request) {
        Activity activity = activityService.create(request);
        return ActivityResponse.from(activity, activityService.windowStatus(activity));
    }

    @PutMapping
    public ActivityResponse update(@Valid @RequestBody ActivityUpdateRequest request) {
        activityService.update(request);
        Activity activity = activityService.requireCurrent();
        return ActivityResponse.from(activity, activityService.windowStatus(activity));
    }

    @PostMapping("/end")
    public Map<String, Object> end() {
        activityService.end();
        return Map.of("ok", true);
    }

    @DeleteMapping
    public Map<String, Object> delete() {
        activityService.delete();
        return Map.of("ok", true);
    }

    @GetMapping("/form-url")
    public Map<String, String> formUrl() {
        return Map.of("url", activityService.formUrl(activityService.requireCurrent()));
    }

    @org.springframework.web.bind.annotation.PostMapping("/export/archive")
    public org.springframework.http.ResponseEntity<byte[]> exportArchive() {
        byte[] bytes = exportService.archive();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename("归档包.xlsx", java.nio.charset.StandardCharsets.UTF_8)
                .build());
        return org.springframework.http.ResponseEntity.ok().headers(headers).body(bytes);
    }
}

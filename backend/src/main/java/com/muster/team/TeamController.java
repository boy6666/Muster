package com.muster.team;

import com.muster.common.PageResult;
import com.muster.team.dto.ReviewRequest;
import com.muster.team.dto.TeamAdminResponse;
import com.muster.team.dto.TeamDetail;
import com.muster.team.dto.TeamSubmitRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    public PageResult<TeamAdminResponse> page(@RequestParam(defaultValue = "") String status,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return teamService.page(status, page, size);
    }

    @GetMapping("/{id}")
    public TeamDetail detail(@PathVariable Long id) {
        return teamService.teamDetailById(id);
    }

    @PutMapping("/{id}/review")
    public Map<String, Object> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        teamService.review(id, request);
        return Map.of("ok", true);
    }

    @PutMapping("/{id}/members")
    public TeamDetail editMembers(@PathVariable Long id, @Valid @RequestBody TeamSubmitRequest request) {
        return teamService.editByAdmin(id, request);
    }
}

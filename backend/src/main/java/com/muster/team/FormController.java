package com.muster.team;

import com.muster.team.dto.FormInfo;
import com.muster.team.dto.TeamDetail;
import com.muster.team.dto.TeamSubmitRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/form/{token}")
public class FormController {

    private final TeamService teamService;

    public FormController(TeamService teamService) {
        this.teamService = teamService;
    }

    @GetMapping
    public FormInfo info(@PathVariable String token) {
        return teamService.formInfo(token);
    }

    @PostMapping("/teams")
    public TeamDetail submit(@PathVariable String token, @Valid @RequestBody TeamSubmitRequest request) {
        return teamService.submit(token, request);
    }

    @GetMapping("/teams/{teamId}")
    public TeamDetail team(@PathVariable String token, @PathVariable Long teamId) {
        return teamService.teamDetail(token, teamId);
    }
}

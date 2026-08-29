package com.muster.team;

import com.muster.team.dto.FormInfo;
import com.muster.team.dto.TeamDetail;
import com.muster.team.dto.TeamSubmitRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @GetMapping("/person")
    public com.muster.team.dto.FormPersonView person(@PathVariable String token,
                                                     @org.springframework.web.bind.annotation.RequestParam String phone) {
        return teamService.personByPhone(token, phone);
    }

    @PostMapping("/teams")
    public TeamDetail submit(@PathVariable String token, @Valid @RequestBody TeamSubmitRequest request) {
        return teamService.submit(token, request);
    }

    @PutMapping("/teams/{teamId}")
    public TeamDetail edit(@PathVariable String token, @PathVariable Long teamId,
                           @org.springframework.web.bind.annotation.RequestParam(required = false) String cap,
                           @Valid @RequestBody TeamSubmitRequest request) {
        return teamService.editByLeader(token, teamId, cap, request);
    }

    @GetMapping("/teams/{teamId}")
    public TeamDetail team(@PathVariable String token, @PathVariable Long teamId,
                           @org.springframework.web.bind.annotation.RequestParam(required = false) String cap) {
        return teamService.teamDetail(token, teamId, cap);
    }
}

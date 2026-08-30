package com.muster.team;

import com.muster.team.dto.FormInfo;
import com.muster.team.dto.FormPersonView;
import com.muster.team.dto.FormTeamView;
import com.muster.team.dto.LeaderVerifyRequest;
import com.muster.team.dto.TeamDetail;
import com.muster.team.dto.TeamMemberRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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
    public FormPersonView person(@PathVariable String token, @RequestParam String employeeId) {
        return teamService.personByEmployeeId(token, employeeId);
    }

    @GetMapping("/my-team")
    public FormTeamView myTeam(@PathVariable String token, @RequestParam String employeeId) {
        return teamService.myTeam(token, employeeId);
    }

    @PostMapping("/teams")
    public TeamDetail createDraft(@PathVariable String token, @RequestBody TeamMemberRequest request) {
        return teamService.createDraft(token, request);
    }

    /** 保存：只更新组内成员，不改变状态、不触发审核。 */
    @PutMapping("/teams/{teamId}")
    public TeamDetail save(@PathVariable String token, @PathVariable Long teamId,
                           @RequestParam(required = false) String cap,
                           @RequestBody TeamMemberRequest request) {
        return teamService.saveByLeader(token, teamId, cap, request);
    }

    /** 提交审核：首次提交 body 带组长手机号验证身份；重提交 body 可为空、凭 ?cap=。 */
    @PostMapping("/teams/{teamId}/submit")
    public TeamDetail submit(@PathVariable String token, @PathVariable Long teamId,
                             @RequestParam(required = false) String cap,
                             @RequestBody(required = false) LeaderVerifyRequest request) {
        return teamService.submitForReview(token, teamId, cap, request);
    }

    /** 换机验证：组长凭手机号换取 capToken。 */
    @PostMapping("/teams/{teamId}/verify")
    public TeamDetail verify(@PathVariable String token, @PathVariable Long teamId,
                             @RequestBody LeaderVerifyRequest request) {
        return teamService.verifyLeader(token, teamId, request);
    }

    @DeleteMapping("/teams/{teamId}")
    public Map<String, Object> delete(@PathVariable String token, @PathVariable Long teamId,
                                      @RequestParam(required = false) String cap) {
        teamService.deleteByLeader(token, teamId, cap);
        return Map.of("ok", true);
    }

    @GetMapping("/teams/{teamId}")
    public TeamDetail team(@PathVariable String token, @PathVariable Long teamId,
                           @RequestParam(required = false) String cap) {
        return teamService.teamDetail(token, teamId, cap);
    }
}

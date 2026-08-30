package com.muster.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.roster.PersonMapper;
import com.muster.stats.dto.StatsDto;
import com.muster.team.Team;
import com.muster.team.TeamMapper;
import com.muster.team.TeamMember;
import com.muster.team.TeamMemberMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StatsService {

    private final ActivityService activityService;
    private final PersonMapper personMapper;
    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;

    public StatsService(ActivityService activityService, PersonMapper personMapper,
                        TeamMapper teamMapper, TeamMemberMapper teamMemberMapper) {
        this.activityService = activityService;
        this.personMapper = personMapper;
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    public StatsDto current() {
        Activity activity = activityService.current();
        if (activity == null) {
            return new StatsDto(0, 0, 0, 0, 0);
        }
        List<Team> teams = teamMapper.selectList(new LambdaQueryWrapper<Team>()
                .eq(Team::getActivityId, activity.getId()));
        long teamCount = teams.size();
        long pendingTeamCount = teams.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .count();
        // 已报名 = 非草稿组的成员（提交即算，驳回不回落）；分组数含草稿
        List<Long> nonDraftIds = teams.stream()
                .filter(t -> !"DRAFT".equals(t.getStatus()))
                .map(Team::getId)
                .toList();
        long registered = nonDraftIds.isEmpty() ? 0
                : teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                        .in(TeamMember::getTeamId, nonDraftIds));
        long total = personMapper.selectCount(new LambdaQueryWrapper<com.muster.roster.Person>()
                .eq(com.muster.roster.Person::getActivityId, activity.getId()));
        return new StatsDto(total, registered, total - registered, teamCount, pendingTeamCount);
    }
}

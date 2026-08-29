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
        long joined = teams.isEmpty() ? 0 : teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>()
                .in(TeamMember::getTeamId, teams.stream().map(Team::getId).toList()));
        long total = personMapper.selectCount(new LambdaQueryWrapper<com.muster.roster.Person>()
                .eq(com.muster.roster.Person::getActivityId, activity.getId()));
        return new StatsDto(total, joined, total - joined, teamCount, pendingTeamCount);
    }
}

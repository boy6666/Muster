package com.muster.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.roster.PersonMapper;
import com.muster.stats.dto.SizeBucketDto;
import com.muster.stats.dto.StatsDto;
import com.muster.team.Team;
import com.muster.team.TeamMapper;
import com.muster.team.TeamMember;
import com.muster.team.TeamMemberMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    /** 组人数分布：与分组数口径一致（含 DRAFT）；overLimit = size > 每组上限；按 size 升序。 */
    public List<SizeBucketDto> distribution() {
        Activity activity = activityService.current();
        if (activity == null) {
            return List.of();
        }
        List<Team> teams = teamMapper.selectList(new LambdaQueryWrapper<Team>()
                .eq(Team::getActivityId, activity.getId()));
        if (teams.isEmpty()) {
            return List.of();
        }
        List<Long> teamIds = teams.stream().map(Team::getId).toList();
        Map<Long, Long> memberCounts = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                        .in(TeamMember::getTeamId, teamIds))
                .stream()
                .collect(Collectors.groupingBy(TeamMember::getTeamId, Collectors.counting()));
        int limit = activity.getGroupSizeLimit() == null ? Integer.MAX_VALUE : activity.getGroupSizeLimit();
        Map<Long, Long> sizeHistogram = teams.stream()
                .collect(Collectors.groupingBy(t -> memberCounts.getOrDefault(t.getId(), 0L),
                        Collectors.counting()));
        return sizeHistogram.entrySet().stream()
                .map(e -> new SizeBucketDto(e.getKey(), e.getValue(), e.getKey() > limit))
                .sorted(Comparator.comparingLong(SizeBucketDto::size))
                .toList();
    }
}

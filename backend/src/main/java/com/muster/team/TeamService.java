package com.muster.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.common.ApiException;
import com.muster.common.ErrorCode;
import com.muster.common.PhoneValidator;
import com.muster.roster.Person;
import com.muster.roster.PersonMapper;
import com.muster.team.dto.ConflictView;
import com.muster.team.dto.FormInfo;
import com.muster.team.dto.TeamDetail;
import com.muster.team.dto.TeamMemberView;
import com.muster.team.dto.TeamSubmitRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TeamService {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final PersonMapper personMapper;
    private final ActivityService activityService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public TeamService(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper, PersonMapper personMapper,
                       ActivityService activityService, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.personMapper = personMapper;
        this.activityService = activityService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    public FormInfo formInfo(String token) {
        Activity activity = requireActivityByToken(token);
        return new FormInfo(activity.getName(), activity.getStartTime(), activity.getEndTime(),
                activity.getGroupSizeLimit(), window(activity).name());
    }

    @Transactional
    public TeamDetail submit(String token, TeamSubmitRequest request) {
        Activity activity = requireActivityByToken(token);
        requireActiveWindow(activity);

        List<String> phones = request.memberPhoneList() == null ? List.of() : request.memberPhoneList().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .distinct()
                .toList();
        if (phones.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "请至少选择一名成员");
        }
        for (String phone : phones) {
            if (!PhoneValidator.valid(phone)) {
                throw new ApiException(ErrorCode.VALIDATION, "手机号格式不正确：" + phone);
            }
        }
        Map<String, Person> roster = personMapper.selectList(new LambdaQueryWrapper<Person>()
                        .eq(Person::getActivityId, activity.getId())
                        .in(Person::getPhone, phones)).stream()
                .collect(Collectors.toMap(Person::getPhone, Function.identity()));
        for (String phone : phones) {
            if (!roster.containsKey(phone)) {
                throw new ApiException(ErrorCode.PERSON_NOT_FOUND, "未在花名册中：" + phone);
            }
        }
        checkConflicts(activity, roster, phones, null);

        Team team = insertTeamWithRetry(activity);
        for (String phone : phones) {
            Person person = roster.get(phone);
            try {
                TeamMember membership = new TeamMember();
                membership.setTeamId(team.getId());
                membership.setPersonId(person.getId());
                membership.setCreatedAt(LocalDateTime.now(clock));
                teamMemberMapper.insert(membership);
            } catch (DuplicateKeyException e) {
                throw new ApiException(ErrorCode.CONFLICT, "有人刚被其他组报走，请刷新后重试");
            }
        }
        eventPublisher.publishEvent(new StatsChangedEvent(activity.getId()));
        return detail(team);
    }

    /**
     * @param excludeTeamId 编辑场景排除本组后重新校验；新建时传 null。
     */
    void checkConflicts(Activity activity, Map<String, Person> rosterByPhone, List<String> phones,
                        Long excludeTeamId) {
        List<Long> personIds = phones.stream().map(p -> rosterByPhone.get(p).getId()).toList();
        List<TeamMember> memberships = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .in(TeamMember::getPersonId, personIds));
        List<TeamMember> conflicts = memberships.stream()
                .filter(m -> excludeTeamId == null || !excludeTeamId.equals(m.getTeamId()))
                .toList();
        if (conflicts.isEmpty()) {
            return;
        }
        Map<Long, Team> teamsById = teamMapper.selectList(new LambdaQueryWrapper<Team>()
                        .in(Team::getId, conflicts.stream().map(TeamMember::getTeamId).toList())).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        List<ConflictView> views = conflicts.stream().map(m -> {
            Person person = rosterByPhone.values().stream()
                    .filter(p -> p.getId().equals(m.getPersonId()))
                    .findFirst().orElseThrow();
            return new ConflictView(person.getPhone(), person.getName(), teamsById.get(m.getTeamId()).getName());
        }).toList();
        String summary = views.stream()
                .map(v -> v.name() + "(" + v.phone() + ")→" + v.teamName())
                .collect(Collectors.joining("、"));
        throw new ApiException(ErrorCode.CONFLICT, "以下成员已在其他组：" + summary, views);
    }

    public TeamDetail teamDetail(String token, Long teamId) {
        Activity activity = requireActivityByToken(token);
        Team team = teamMapper.selectById(teamId);
        if (team == null || !team.getActivityId().equals(activity.getId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "组不存在");
        }
        return detail(team);
    }

    public TeamDetail detail(Team team) {
        Activity activity = activityService.requireCurrent();
        List<TeamMember> memberships = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, team.getId())
                .orderByAsc(TeamMember::getId));
        Map<Long, Person> persons = memberships.isEmpty() ? Map.of() : personMapper.selectList(
                        new LambdaQueryWrapper<Person>().in(Person::getId,
                                memberships.stream().map(TeamMember::getPersonId).toList())).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<TeamMemberView> members = memberships.stream().map(m -> {
            Person person = persons.get(m.getPersonId());
            return new TeamMemberView(person.getName(), person.getPhone(), person.getDepartment());
        }).toList();
        boolean overLimit = members.size() > activity.getGroupSizeLimit();
        return new TeamDetail(team.getId(), team.getName(), team.getStatus(), team.getRejectReason(),
                overLimit, team.getSubmittedAt(), members);
    }

    Team insertTeamWithRetry(Activity activity) {
        for (int attempt = 0; attempt < 3; attempt++) {
            long count = teamMapper.selectCount(new LambdaQueryWrapper<Team>()
                    .eq(Team::getActivityId, activity.getId()));
            Team team = new Team();
            team.setActivityId(activity.getId());
            team.setName("组" + (count + 1));
            team.setStatus("PENDING");
            team.setSubmittedAt(LocalDateTime.now(clock));
            try {
                teamMapper.insert(team);
                return team;
            } catch (DuplicateKeyException e) {
                // 组名撞唯一键（并发提交），换下一个名字重试
            }
        }
        throw new ApiException(ErrorCode.CONFLICT, "组名生成冲突，请重试");
    }

    private Activity requireActivityByToken(String token) {
        Activity activity = activityService.currentByToken(token);
        if (activity == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "活动不存在");
        }
        return activity;
    }

    private void requireActiveWindow(Activity activity) {
        if (!WindowStatus.ACTIVE.equals(currentWindow(activity))) {
            throw new ApiException(ErrorCode.WINDOW_CLOSED, "活动未开始或已结束");
        }
    }

    private WindowStatus currentWindow(Activity activity) {
        return WindowResolver.resolve(activity.getStartTime(), activity.getEndTime(),
                Boolean.TRUE.equals(activity.getManuallyEnded()), LocalDateTime.now(clock));
    }

    public WindowStatus window(Activity activity) {
        return currentWindow(activity);
    }
}

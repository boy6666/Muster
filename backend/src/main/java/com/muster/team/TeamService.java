package com.muster.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.common.ApiException;
import com.muster.common.EmployeeIdValidator;
import com.muster.common.ErrorCode;
import com.muster.common.PageParams;
import com.muster.common.PageResult;
import com.muster.common.PhoneValidator;
import com.muster.roster.Person;
import com.muster.roster.PersonMapper;
import com.muster.team.dto.ConflictView;
import com.muster.team.dto.FormInfo;
import com.muster.team.dto.FormPersonView;
import com.muster.team.dto.FormTeamView;
import com.muster.team.dto.LeaderVerifyRequest;
import com.muster.team.dto.ReviewRequest;
import com.muster.team.dto.TeamAdminResponse;
import com.muster.team.dto.TeamDetail;
import com.muster.team.dto.TeamMemberRequest;
import com.muster.team.dto.TeamMemberView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TeamService {

    private final TeamMapper teamMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamEventMapper teamEventMapper;
    private final PersonMapper personMapper;
    private final ActivityService activityService;
    private final ApplicationEventPublisher eventPublisher;
    private final com.muster.audit.OpLogService opLogService;
    private final Clock clock;

    public TeamService(TeamMapper teamMapper, TeamMemberMapper teamMemberMapper, TeamEventMapper teamEventMapper,
                       PersonMapper personMapper, ActivityService activityService,
                       ApplicationEventPublisher eventPublisher, com.muster.audit.OpLogService opLogService,
                       Clock clock) {
        this.teamMapper = teamMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.teamEventMapper = teamEventMapper;
        this.personMapper = personMapper;
        this.activityService = activityService;
        this.eventPublisher = eventPublisher;
        this.opLogService = opLogService;
        this.clock = clock;
    }

    public FormInfo formInfo(String token) {
        Activity activity = requireActivityByToken(token);
        return new FormInfo(activity.getName(), activity.getStartTime(), activity.getEndTime(),
                activity.getGroupSizeLimit(), window(activity).name());
    }

    /** 报名表自动回显：仅允许完整员工编号精确查询，不做模糊搜索（防扫库）。 */
    public FormPersonView personByEmployeeId(String token, String employeeId) {
        Activity activity = requireActivityByToken(token);
        Person person = requireRosterPerson(activity, employeeId);
        Team team = teamOfPerson(activity.getId(), person.getId());
        return new FormPersonView(person.getEmployeeId(), person.getName(), person.getPhone(), person.getDepartment(),
                team == null ? null : team.getId(),
                team != null && person.getId().equals(team.getLeaderPersonId()));
    }

    /** "我的组"入口：成员与组长都凭自己的员工编号查看本组（不泄露 capToken）。 */
    public FormTeamView myTeam(String token, String employeeId) {
        Activity activity = requireActivityByToken(token);
        Person person = requireRosterPerson(activity, employeeId);
        Team team = teamOfPerson(activity.getId(), person.getId());
        if (team == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "您还没有加入任何组");
        }
        return formTeamView(activity, team, person.getId().equals(team.getLeaderPersonId()));
    }

    /** 组长建组（草稿）：DRAFT 不计入已报名，提交后才进入审核。 */
    @Transactional
    public TeamDetail createDraft(String token, TeamMemberRequest request) {
        Activity activity = requireActivityByToken(token);
        requireActiveWindow(activity);
        ResolvedMembers rm = resolveMembers(activity, request, true);
        checkConflicts(activity, rm, null);
        Team team = insertTeamWithRetry(activity, "DRAFT", null);
        insertMembers(team, rm);
        insertEvent(team, activity.getId(), "CREATED", "建组 " + rm.employeeIds().size() + " 人");
        eventPublisher.publishEvent(new StatsChangedEvent(activity.getId()));
        return detail(team);
    }

    /** 组长保存：仅更新组内成员，状态不变（DRAFT 留 DRAFT，REJECTED 留 REJECTED 且保留理由）。 */
    @Transactional
    public TeamDetail saveByLeader(String token, Long teamId, String cap, TeamMemberRequest request) {
        Activity activity = requireActivityByToken(token);
        Team team = requireTeamOfActivity(teamId, activity.getId());
        requireCap(team, cap);
        requireLeaderEditable(team);
        requireActiveWindow(activity);
        ResolvedMembers rm = resolveMembers(activity, request, true);
        checkConflicts(activity, rm, team.getId());
        teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
        insertMembers(team, rm);
        insertEvent(team, activity.getId(), "SAVED", "保存 " + rm.employeeIds().size() + " 人");
        return detail(team);
    }

    /**
     * 提交审核：首次提交（submittedAt 为空）必须凭组长手机号验证身份——新设备上没有 cap 也能提交；
     * 重提交凭 cap 或组长手机号任一，都没有按 404 处理（不泄露组是否存在）。
     */
    @Transactional
    public TeamDetail submitForReview(String token, Long teamId, String cap, LeaderVerifyRequest verify) {
        Activity activity = requireActivityByToken(token);
        Team team = requireTeamOfActivity(teamId, activity.getId());
        requireLeaderEditable(team);
        requireActiveWindow(activity);
        String phone = verify == null ? null : verify.leaderPhone();
        if (team.getSubmittedAt() == null) {
            verifyLeaderPhone(team, phone);
        } else if (!capMatches(team, cap) && !leaderPhoneMatches(team, phone)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报名信息不存在");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        teamMapper.update(null, new LambdaUpdateWrapper<Team>()
                .eq(Team::getId, team.getId())
                .set(Team::getStatus, "PENDING")
                .set(Team::getRejectReason, null)
                .set(Team::getSubmittedAt, now)
                .set(Team::getUpdatedAt, now));
        team.setStatus("PENDING");
        team.setRejectReason(null);
        team.setSubmittedAt(now);
        insertEvent(team, activity.getId(), "SUBMITTED", "提交审核");
        eventPublisher.publishEvent(new StatsChangedEvent(activity.getId()));
        return detail(team);
    }

    /** 换机验证：DRAFT/REJECTED 组长凭手机号换取 capToken；PENDING/CONFIRMED 无需验证。 */
    @Transactional
    public TeamDetail verifyLeader(String token, Long teamId, LeaderVerifyRequest request) {
        Activity activity = requireActivityByToken(token);
        Team team = requireTeamOfActivity(teamId, activity.getId());
        if (!"DRAFT".equals(team.getStatus()) && !"REJECTED".equals(team.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "当前状态无需验证");
        }
        requireActiveWindow(activity);
        verifyLeaderPhone(team, request == null ? null : request.leaderPhone());
        return detail(team);
    }

    /** 组长删组：仅 DRAFT/REJECTED 可删（PENDING/CONFIRMED 锁定）。 */
    @Transactional
    public void deleteByLeader(String token, Long teamId, String cap) {
        Activity activity = requireActivityByToken(token);
        Team team = requireTeamOfActivity(teamId, activity.getId());
        requireCap(team, cap);
        requireLeaderEditable(team);
        requireActiveWindow(activity);
        deleteTeamRow(team);
        opLogService.record("TEAM_DELETE_LEADER", "组长删除 " + team.getName());
    }

    /** 管理员建组：不走报名流程，创建即 CONFIRMED（管理员代为录入）。 */
    @Transactional
    public TeamDetail createByAdmin(TeamMemberRequest request) {
        Activity activity = activityService.requireCurrent();
        requireActiveWindow(activity);
        ResolvedMembers rm = resolveMembers(activity, request, true);
        checkConflicts(activity, rm, null);
        Team team = insertTeamWithRetry(activity, "CONFIRMED", LocalDateTime.now(clock));
        insertMembers(team, rm);
        insertEvent(team, activity.getId(), "CREATED_BY_ADMIN", "管理员建组 " + rm.employeeIds().size() + " 人");
        opLogService.record("TEAM_CREATE_ADMIN", "管理员创建 " + team.getName());
        eventPublisher.publishEvent(new StatsChangedEvent(activity.getId()));
        return detail(team);
    }

    @Transactional
    public void deleteByAdmin(Long teamId) {
        Activity activity = activityService.requireCurrent();
        Team team = requireTeamOfActivity(teamId, activity.getId());
        deleteTeamRow(team);
        opLogService.record("TEAM_DELETE_ADMIN", "管理员删除 " + team.getName());
    }

    /** 管理员改组：整体替换成员，状态直接置 CONFIRMED；组长可省略（沿用原组长，不在新名单则取首位成员）。 */
    @Transactional
    public TeamDetail editByAdmin(Long teamId, TeamMemberRequest request) {
        Activity activity = activityService.requireCurrent();
        Team team = requireTeamOfActivity(teamId, activity.getId());
        requireActiveWindow(activity);
        ResolvedMembers rm = resolveMembers(activity, request, false);
        checkConflicts(activity, rm, team.getId());
        teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
        insertMembers(team, rm);
        if (rm.leader() == null) {
            boolean leaderStillInTeam = team.getLeaderPersonId() != null && rm.roster().values().stream()
                    .anyMatch(p -> p.getId().equals(team.getLeaderPersonId()));
            if (!leaderStillInTeam) {
                Person first = rm.roster().get(rm.employeeIds().get(0));
                setLeader(team.getId(), first.getId());
                team.setLeaderPersonId(first.getId());
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        teamMapper.update(null, new LambdaUpdateWrapper<Team>()
                .eq(Team::getId, team.getId())
                .set(Team::getStatus, "CONFIRMED")
                .set(Team::getRejectReason, null)
                .set(Team::getSubmittedAt, now)
                .set(Team::getUpdatedAt, now));
        team.setStatus("CONFIRMED");
        team.setRejectReason(null);
        team.setSubmittedAt(now);
        insertEvent(team, activity.getId(), "EDITED_BY_ADMIN", "管理员改为 " + rm.employeeIds().size() + " 人");
        opLogService.record("TEAM_EDIT_ADMIN", team.getName());
        eventPublisher.publishEvent(new StatsChangedEvent(activity.getId()));
        return detail(team);
    }

    private void insertEvent(Team team, Long activityId, String type, String detail) {
        TeamEvent event = new TeamEvent();
        event.setTeamId(team.getId());
        event.setActivityId(activityId);
        event.setType(type);
        event.setDetail(detail);
        event.setCreatedAt(LocalDateTime.now(clock));
        teamEventMapper.insert(event);
    }

    private record ResolvedMembers(List<String> employeeIds, Map<String, Person> roster, Person leader) {
    }

    private ResolvedMembers resolveMembers(Activity activity, TeamMemberRequest request, boolean requireLeader) {
        if (request == null || request.memberEmployeeIdList() == null) {
            throw new ApiException(ErrorCode.VALIDATION, "组员列表不能为空");
        }
        List<String> ids = request.memberEmployeeIdList().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "组员列表不能为空");
        }
        for (String id : ids) {
            if (!EmployeeIdValidator.isValid(id)) {
                throw new ApiException(ErrorCode.VALIDATION, "员工编号格式不正确：" + id);
            }
        }
        String leaderId = request.leaderEmployeeId() == null ? "" : request.leaderEmployeeId().trim();
        if (!leaderId.isEmpty() && !ids.contains(leaderId)) {
            throw new ApiException(ErrorCode.VALIDATION, "组长必须在组员列表中");
        }
        if (leaderId.isEmpty() && requireLeader) {
            throw new ApiException(ErrorCode.VALIDATION, "请先填写组长员工编号");
        }
        Map<String, Person> roster = rosterByEmployeeIds(activity, ids);
        return new ResolvedMembers(ids, roster, leaderId.isEmpty() ? null : roster.get(leaderId));
    }

    private Map<String, Person> rosterByEmployeeIds(Activity activity, List<String> employeeIds) {
        Map<String, Person> byId = personMapper.selectList(new LambdaQueryWrapper<Person>()
                        .eq(Person::getActivityId, activity.getId())
                        .in(Person::getEmployeeId, employeeIds)).stream()
                .collect(Collectors.toMap(Person::getEmployeeId, Function.identity()));
        List<String> missing = employeeIds.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.PERSON_NOT_FOUND, "未在花名册中：" + String.join("、", missing));
        }
        return byId;
    }

    /**
     * @param excludeTeamId 编辑场景排除本组后重新校验；新建时传 null。
     */
    private void checkConflicts(Activity activity, ResolvedMembers rm, Long excludeTeamId) {
        List<Long> personIds = rm.roster().values().stream().map(Person::getId).toList();
        List<TeamMember> taken = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .in(TeamMember::getPersonId, personIds));
        if (excludeTeamId != null) {
            taken = taken.stream().filter(m -> !excludeTeamId.equals(m.getTeamId())).toList();
        }
        if (taken.isEmpty()) {
            return;
        }
        Map<Long, Team> teamsById = teamMapper.selectList(new LambdaQueryWrapper<Team>()
                        .in(Team::getId, taken.stream().map(TeamMember::getTeamId).toList())).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        Map<Long, Person> persons = rm.roster().values().stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<ConflictView> views = new ArrayList<>();
        for (TeamMember m : taken) {
            Team team = teamsById.get(m.getTeamId());
            Person person = persons.get(m.getPersonId());
            if (team != null && person != null) {
                views.add(new ConflictView(person.getEmployeeId(), person.getName(), team.getName()));
            }
        }
        if (views.isEmpty()) {
            return;
        }
        String summary = views.stream()
                .map(v -> v.name() + "(" + v.employeeId() + ")→" + v.teamName())
                .collect(Collectors.joining("、"));
        throw new ApiException(ErrorCode.CONFLICT, "以下成员已在其他组：" + summary + "；请先调整后再保存", views);
    }

    private void insertMembers(Team team, ResolvedMembers rm) {
        for (String employeeId : rm.employeeIds()) {
            Person person = rm.roster().get(employeeId);
            try {
                TeamMember membership = new TeamMember();
                membership.setTeamId(team.getId());
                membership.setPersonId(person.getId());
                membership.setCreatedAt(LocalDateTime.now(clock));
                teamMemberMapper.insert(membership);
            } catch (DuplicateKeyException e) {
                // 校验和插入之间成员被其他组抢走（check-then-act 竞态），uk_person 唯一键兜底
                throw new ApiException(ErrorCode.CONFLICT, "有人刚被其他组报走，请刷新后重试");
            }
        }
        if (rm.leader() != null) {
            setLeader(team.getId(), rm.leader().getId());
            team.setLeaderPersonId(rm.leader().getId());
        }
    }

    private void setLeader(Long teamId, Long personId) {
        teamMapper.update(null, new LambdaUpdateWrapper<Team>()
                .eq(Team::getId, teamId)
                .set(Team::getLeaderPersonId, personId));
    }

    private void deleteTeamRow(Team team) {
        teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
        teamEventMapper.delete(new LambdaQueryWrapper<TeamEvent>().eq(TeamEvent::getTeamId, team.getId()));
        teamMapper.deleteById(team.getId());
        eventPublisher.publishEvent(new StatsChangedEvent(team.getActivityId()));
    }

    private void requireLeaderEditable(Team team) {
        if ("PENDING".equals(team.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "审核中，不能修改或删除");
        }
        if ("CONFIRMED".equals(team.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "已通过审核，组信息已锁定");
        }
    }

    private void verifyLeaderPhone(Team team, String phone) {
        if (phone == null || !PhoneValidator.valid(phone.trim())) {
            throw new ApiException(ErrorCode.VALIDATION, "请输入组长的 11 位手机号");
        }
        if (team.getLeaderPersonId() == null) {
            throw new ApiException(ErrorCode.VALIDATION, "该组未设置组长");
        }
        Person leader = personMapper.selectById(team.getLeaderPersonId());
        if (leader == null || !leader.getPhone().equals(phone.trim())) {
            throw new ApiException(ErrorCode.VALIDATION, "组长手机号不正确");
        }
    }

    private boolean capMatches(Team team, String cap) {
        return team.getCapToken() != null && cap != null && cap.equals(team.getCapToken());
    }

    private boolean leaderPhoneMatches(Team team, String phone) {
        if (phone == null || !PhoneValidator.valid(phone.trim()) || team.getLeaderPersonId() == null) {
            return false;
        }
        Person leader = personMapper.selectById(team.getLeaderPersonId());
        return leader != null && leader.getPhone().equals(phone.trim());
    }

    public TeamDetail teamDetail(String token, Long teamId, String cap) {
        Activity activity = requireActivityByToken(token);
        Team team = requireTeamOfActivity(teamId, activity.getId());
        requireCap(team, cap);
        return detail(team);
    }

    /** 管理端组详情。 */
    public TeamDetail teamDetailById(Long teamId) {
        Activity activity = activityService.requireCurrent();
        Team team = requireTeamOfActivity(teamId, activity.getId());
        return detail(team);
    }

    /** 人工审核：不受窗口限制。PASS → CONFIRMED；REJECT → REJECTED 且必须带理由。 */
    @Transactional
    public void review(Long teamId, ReviewRequest request) {
        Activity activity = activityService.requireCurrent();
        Team team = requireTeamOfActivity(teamId, activity.getId());
        if ("PASS".equalsIgnoreCase(request.action())) {
            teamMapper.update(null, new LambdaUpdateWrapper<Team>()
                    .eq(Team::getId, teamId)
                    .set(Team::getStatus, "CONFIRMED")
                    .set(Team::getRejectReason, null)
                    .set(Team::getUpdatedAt, LocalDateTime.now(clock)));
            insertEvent(team, activity.getId(), "PASSED", null);
        } else if ("REJECT".equalsIgnoreCase(request.action())) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION, "驳回必须填写理由");
            }
            teamMapper.update(null, new LambdaUpdateWrapper<Team>()
                    .eq(Team::getId, teamId)
                    .set(Team::getStatus, "REJECTED")
                    .set(Team::getRejectReason, request.reason().trim())
                    .set(Team::getUpdatedAt, LocalDateTime.now(clock)));
            insertEvent(team, activity.getId(), "REJECTED", request.reason().trim());
        } else {
            throw new ApiException(ErrorCode.VALIDATION, "action 必须为 PASS 或 REJECT");
        }
        opLogService.record("TEAM_REVIEW", team.getName() + " " + request.action().toUpperCase()
                + (request.reason() == null || request.reason().isBlank() ? "" : "：" + request.reason().trim()));
        eventPublisher.publishEvent(new StatsChangedEvent(activity.getId()));
    }

    /** 组生命周期流水：提交/改组/审核全部按时间正序返回。 */
    public List<com.muster.team.dto.TeamEventView> events(Long teamId) {
        Activity activity = activityService.requireCurrent();
        Team team = requireTeamOfActivity(teamId, activity.getId());
        return teamEventMapper.selectList(new LambdaQueryWrapper<TeamEvent>()
                        .eq(TeamEvent::getTeamId, team.getId())
                        .orderByAsc(TeamEvent::getId)).stream()
                .map(e -> new com.muster.team.dto.TeamEventView(e.getId(), e.getType(), e.getDetail(), e.getCreatedAt()))
                .toList();
    }

    public PageResult<TeamAdminResponse> page(String status, int page, int size) {
        Activity activity = activityService.requireCurrent();
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<Team>()
                .eq(Team::getActivityId, activity.getId())
                .orderByAsc(Team::getId);
        if (status != null && !status.isBlank()) {
            wrapper.eq(Team::getStatus, status.trim().toUpperCase());
        }
        PageParams pp = PageParams.clamp(page, size);
        Page<Team> result = teamMapper.selectPage(Page.of(pp.page(), pp.size()), wrapper);
        List<Long> leaderIds = result.getRecords().stream().map(Team::getLeaderPersonId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Person> leaders = leaderIds.isEmpty() ? Map.of() : personMapper.selectList(
                        new LambdaQueryWrapper<Person>().in(Person::getId, leaderIds)).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<TeamAdminResponse> records = result.getRecords().stream()
                .map(team -> toAdminResponse(activity, team, leaders)).toList();
        return new PageResult<>(result.getTotal(), records);
    }

    private TeamAdminResponse toAdminResponse(Activity activity, Team team, Map<Long, Person> leaders) {
        int memberCount = Math.toIntExact(teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId())));
        Integer limit = activity.getGroupSizeLimit();
        boolean overLimit = limit != null && memberCount > limit;
        Person leader = team.getLeaderPersonId() == null ? null : leaders.get(team.getLeaderPersonId());
        return new TeamAdminResponse(team.getId(), team.getName(), team.getStatus(), memberCount, overLimit,
                leader == null ? null : leader.getName(), team.getRejectReason(), team.getSubmittedAt());
    }

    public TeamDetail detail(Team team) {
        Activity activity = activityService.requireCurrent();
        List<TeamMemberView> members = buildMembers(team, activity);
        return new TeamDetail(team.getId(), team.getName(), team.getStatus(), team.getRejectReason(),
                team.getCapToken(), overLimit(activity, members.size()),
                team.getSubmittedAt(), members);
    }

    private boolean overLimit(Activity activity, int memberCount) {
        Integer limit = activity.getGroupSizeLimit();
        return limit != null && memberCount > limit;
    }

    private FormTeamView formTeamView(Activity activity, Team team, boolean isLeader) {
        long memberCount = teamMemberMapper.selectCount(
                new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
        return new FormTeamView(team.getId(), team.getName(), team.getStatus(), team.getRejectReason(),
                overLimit(activity, Math.toIntExact(memberCount)), team.getSubmittedAt(), isLeader,
                buildMembers(team, activity));
    }

    private List<TeamMemberView> buildMembers(Team team, Activity activity) {
        List<TeamMember> memberships = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getTeamId, team.getId())
                .orderByAsc(TeamMember::getId));
        if (memberships.isEmpty()) {
            return List.of();
        }
        Map<Long, Person> persons = personMapper.selectList(new LambdaQueryWrapper<Person>()
                        .in(Person::getId, memberships.stream().map(TeamMember::getPersonId).toList())).stream()
                .collect(Collectors.toMap(Person::getId, Function.identity()));
        List<TeamMemberView> views = new ArrayList<>();
        for (TeamMember m : memberships) {
            Person person = persons.get(m.getPersonId());
            if (person == null) {
                // 历史库无外键时可能残留孤儿成员行（人员已被删），占位兜底避免详情 500
                views.add(new TeamMemberView("", "（已删除成员）", "", "", false));
            } else {
                views.add(new TeamMemberView(person.getEmployeeId(), person.getName(), person.getPhone(),
                        person.getDepartment(), person.getId().equals(team.getLeaderPersonId())));
            }
        }
        return views;
    }

    Team insertTeamWithRetry(Activity activity, String status, LocalDateTime submittedAt) {
        long count = teamMapper.selectCount(new LambdaQueryWrapper<Team>()
                .eq(Team::getActivityId, activity.getId()));
        for (int attempt = 0; attempt < 3; attempt++) {
            Team team = new Team();
            team.setActivityId(activity.getId());
            // 乐观基数 + 重试序号：REPEATABLE READ 下重试读到的是同一快照，
            // 若仍按 count+1 计算会三次撞同一个名字（uk_activity_name 唯一键兜底）。
            team.setName("组" + (count + 1 + attempt));
            team.setStatus(status);
            team.setCapToken(UUID.randomUUID().toString());
            team.setSubmittedAt(submittedAt);
            try {
                teamMapper.insert(team);
                return team;
            } catch (DuplicateKeyException e) {
                // 组名撞唯一键（并发提交），换下一个名字重试
            }
        }
        throw new ApiException(ErrorCode.CONFLICT, "组名生成冲突，请重试");
    }

    private Person requireRosterPerson(Activity activity, String rawEmployeeId) {
        String id = rawEmployeeId == null ? "" : rawEmployeeId.trim();
        if (id.isEmpty() || id.length() > 32) {
            throw new ApiException(ErrorCode.VALIDATION, "请输入完整员工编号");
        }
        Person person = personMapper.selectOne(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId())
                .eq(Person::getEmployeeId, id));
        if (person != null) {
            return person;
        }
        Long prefixHits = personMapper.selectCount(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId())
                .likeRight(Person::getEmployeeId, id));
        if (prefixHits != null && prefixHits > 0) {
            // 是已有编号的前缀 → 视为没输完，引导补全（同手机号"完整 11 位才回显"的防扫库口径）
            throw new ApiException(ErrorCode.VALIDATION, "请输入完整员工编号");
        }
        throw new ApiException(ErrorCode.PERSON_NOT_FOUND, "花名册中没有该员工编号");
    }

    private Team teamOfPerson(Long activityId, Long personId) {
        TeamMember membership = teamMemberMapper.selectOne(new LambdaQueryWrapper<TeamMember>()
                .eq(TeamMember::getPersonId, personId)
                .last("LIMIT 1"));
        if (membership == null) {
            return null;
        }
        Team team = teamMapper.selectById(membership.getTeamId());
        return team != null && team.getActivityId().equals(activityId) ? team : null;
    }

    /** 二维码 token 是共享的；组级操作必须再校验发放的 capToken，不匹配按 404 处理（不泄露组是否存在）。 */
    private void requireCap(Team team, String cap) {
        if (!capMatches(team, cap)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报名信息不存在");
        }
    }

    private Team requireTeamOfActivity(Long teamId, Long activityId) {
        Team team = teamId == null ? null : teamMapper.selectById(teamId);
        if (team == null || !team.getActivityId().equals(activityId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "组不存在");
        }
        return team;
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

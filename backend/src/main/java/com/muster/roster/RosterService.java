package com.muster.roster;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.common.ApiException;
import com.muster.common.EmployeeIdValidator;
import com.muster.common.ErrorCode;
import com.muster.common.PageParams;
import com.muster.common.PageResult;
import com.muster.common.PhoneValidator;
import com.muster.roster.dto.PersonCreateRequest;
import com.muster.roster.dto.PersonResponse;
import com.muster.roster.dto.PersonRow;
import com.muster.team.Team;
import com.muster.team.TeamMapper;
import com.muster.team.TeamMember;
import com.muster.team.TeamMemberMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RosterService {

    private final PersonMapper personMapper;
    private final ActivityService activityService;
    private final ExcelService excelService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.muster.audit.OpLogService opLogService;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamMapper teamMapper;

    public RosterService(PersonMapper personMapper, ActivityService activityService, ExcelService excelService,
                         JdbcTemplate jdbc, Clock clock, com.muster.audit.OpLogService opLogService,
                         TeamMemberMapper teamMemberMapper, TeamMapper teamMapper) {
        this.personMapper = personMapper;
        this.activityService = activityService;
        this.excelService = excelService;
        this.jdbc = jdbc;
        this.clock = clock;
        this.opLogService = opLogService;
        this.teamMemberMapper = teamMemberMapper;
        this.teamMapper = teamMapper;
    }

    @Transactional
    public int importPersons(InputStream inputStream) {
        Activity activity = activityService.requireCurrent();
        List<PersonRow> rows = excelService.readPersons(inputStream).stream()
                .filter(r -> !(r.employeeId().isEmpty() && r.name().isEmpty()
                        && r.phone().isEmpty() && r.department().isEmpty()))
                .toList();
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "文件中没有可导入的数据行");
        }
        for (PersonRow row : rows) {
            if (row.employeeId().isEmpty() || !EmployeeIdValidator.isValid(row.employeeId())
                    || row.name().isEmpty() || row.department().isEmpty() || !PhoneValidator.valid(row.phone())) {
                throw new ApiException(ErrorCode.VALIDATION,
                        "第 " + row.rowNo() + " 行不合法：员工编号须为 1-32 位非空白字符，"
                                + "姓名/部门不能为空，手机号须为 11 位有效手机号");
            }
        }
        Map<String, List<PersonRow>> byEmployee = rows.stream()
                .collect(Collectors.groupingBy(PersonRow::employeeId));
        String inFileEmployeeDupes = byEmployee.values().stream()
                .filter(list -> list.size() > 1)
                .map(list -> "第 " + list.get(0).rowNo() + "/" + list.get(1).rowNo() + " 行")
                .collect(Collectors.joining("、"));
        if (!inFileEmployeeDupes.isEmpty()) {
            throw new ApiException(ErrorCode.DUPLICATE, "文件内员工编号重复：" + inFileEmployeeDupes);
        }
        Map<String, List<PersonRow>> byPhone = rows.stream()
                .collect(Collectors.groupingBy(PersonRow::phone));
        String inFilePhoneDupes = byPhone.values().stream()
                .filter(list -> list.size() > 1)
                .map(list -> "第 " + list.get(0).rowNo() + "/" + list.get(1).rowNo() + " 行")
                .collect(Collectors.joining("、"));
        if (!inFilePhoneDupes.isEmpty()) {
            throw new ApiException(ErrorCode.PHONE_DUPLICATE, "文件内手机号重复：" + inFilePhoneDupes);
        }
        List<String> employeeIds = rows.stream().map(PersonRow::employeeId).toList();
        List<String> phones = rows.stream().map(PersonRow::phone).toList();
        Set<String> existingEmployees = new HashSet<>();
        Set<String> existingPhones = new HashSet<>();
        for (Person p : personMapper.selectList(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId())
                .and(w -> w.in(Person::getEmployeeId, employeeIds).or().in(Person::getPhone, phones)))) {
            existingEmployees.add(p.getEmployeeId());
            existingPhones.add(p.getPhone());
        }
        for (PersonRow row : rows) {
            if (existingEmployees.contains(row.employeeId())) {
                throw new ApiException(ErrorCode.DUPLICATE, "员工编号已在花名册中：" + row.employeeId());
            }
            if (existingPhones.contains(row.phone())) {
                throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + row.phone());
            }
        }
        for (PersonRow row : rows) {
            insert(activity.getId(), row.employeeId(), row.name(), row.phone(), row.department());
        }
        opLogService.record("ROSTER_IMPORT", "导入 " + rows.size() + " 人");
        return rows.size();
    }

    public PageResult<PersonResponse> search(String keyword, int page, int size) {
        Activity activity = activityService.requireCurrent();
        LambdaQueryWrapper<Person> wrapper = new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId())
                .orderByAsc(Person::getId);
        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            wrapper.and(w -> w.like(Person::getEmployeeId, kw)
                    .or().like(Person::getName, kw)
                    .or().like(Person::getPhone, kw)
                    .or().like(Person::getDepartment, kw));
        }
        PageParams pp = PageParams.clamp(page, size);
        Page<Person> result = personMapper.selectPage(Page.of(pp.page(), pp.size()), wrapper);
        List<PersonResponse> records = enrich(result.getRecords());
        return new PageResult<>(result.getTotal(), records);
    }

    /** 批量补齐组别/组长/参加状态，避免逐人查库。 */
    private List<PersonResponse> enrich(List<Person> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        var personIds = records.stream().map(Person::getId).toList();
        var memberships = teamMemberMapper.selectList(
                new LambdaQueryWrapper<TeamMember>().in(TeamMember::getPersonId, personIds));
        var teamIds = memberships.stream().map(TeamMember::getTeamId).distinct().toList();
        Map<Long, Team> teams = teamIds.isEmpty() ? Map.of() : teamMapper.selectBatchIds(teamIds).stream()
                .collect(Collectors.toMap(Team::getId, t -> t));
        var leaderIds = teams.values().stream().map(Team::getLeaderPersonId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Person> leaders = leaderIds.isEmpty() ? Map.of() : personMapper.selectBatchIds(leaderIds).stream()
                .collect(Collectors.toMap(Person::getId, p -> p));
        Map<Long, Team> teamByPerson = memberships.stream()
                .filter(m -> teams.containsKey(m.getTeamId()))
                .collect(Collectors.toMap(TeamMember::getPersonId, m -> teams.get(m.getTeamId())));
        return records.stream().map(r -> {
            Team team = teamByPerson.get(r.getId());
            String leaderName = team != null && team.getLeaderPersonId() != null
                    && leaders.containsKey(team.getLeaderPersonId())
                    ? leaders.get(team.getLeaderPersonId()).getName() : null;
            return new PersonResponse(r.getId(), r.getEmployeeId(), r.getName(), r.getPhone(), r.getDepartment(),
                    team == null ? null : team.getId(),
                    team == null ? null : team.getName(),
                    leaderName,
                    team != null && r.getId().equals(team.getLeaderPersonId()),
                    team != null && "CONFIRMED".equals(team.getStatus()));
        }).toList();
    }

    public PersonResponse add(PersonCreateRequest request) {
        Activity activity = activityService.requireCurrent();
        String employeeId = request.employeeId().trim();
        if (!EmployeeIdValidator.isValid(employeeId)) {
            throw new ApiException(ErrorCode.VALIDATION, "员工编号须为 1-32 位非空白字符");
        }
        if (countByEmployee(activity.getId(), employeeId) > 0) {
            throw new ApiException(ErrorCode.DUPLICATE, "员工编号已在花名册中：" + employeeId);
        }
        String phone = request.phone().trim();
        if (!PhoneValidator.valid(phone)) {
            throw new ApiException(ErrorCode.VALIDATION, "手机号须为 11 位有效手机号");
        }
        if (countByPhone(activity.getId(), phone) > 0) {
            throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + phone);
        }
        Person person = insert(activity.getId(), employeeId, request.name().trim(), phone, request.department().trim());
        opLogService.record("ROSTER_ADD", employeeId + " " + request.name().trim() + " " + phone);
        return PersonResponse.from(person);
    }

    public PersonResponse update(Long id, PersonCreateRequest request) {
        Activity activity = activityService.requireCurrent();
        Person person = personMapper.selectById(id);
        if (person == null || !person.getActivityId().equals(activity.getId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "人员不存在");
        }
        String employeeId = request.employeeId().trim();
        String name = request.name().trim();
        String phone = request.phone().trim();
        String department = request.department().trim();
        if (!EmployeeIdValidator.isValid(employeeId)) {
            throw new ApiException(ErrorCode.VALIDATION, "员工编号须为 1-32 位非空白字符");
        }
        if (name.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "姓名不能为空");
        }
        if (!PhoneValidator.valid(phone)) {
            throw new ApiException(ErrorCode.VALIDATION, "手机号须为 11 位有效手机号");
        }
        if (department.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "部门不能为空");
        }
        if (personMapper.selectCount(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId())
                .eq(Person::getEmployeeId, employeeId)
                .ne(Person::getId, id)) > 0) {
            throw new ApiException(ErrorCode.DUPLICATE, "员工编号已在花名册中：" + employeeId);
        }
        if (personMapper.selectCount(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId())
                .eq(Person::getPhone, phone)
                .ne(Person::getId, id)) > 0) {
            throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + phone);
        }
        person.setEmployeeId(employeeId);
        person.setName(name);
        person.setPhone(phone);
        person.setDepartment(department);
        personMapper.updateById(person);
        opLogService.record("ROSTER_EDIT", employeeId + " " + name + " " + phone);
        return PersonResponse.from(person);
    }

    @Transactional
    public int clear() {
        Activity activity = activityService.requireCurrent();
        Long teamCount = jdbc.queryForObject("SELECT COUNT(*) FROM team WHERE activity_id = ?",
                Long.class, activity.getId());
        if (teamCount != null && teamCount > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "存在分组，请先删除所有分组再清空名单");
        }
        int deleted = personMapper.delete(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activity.getId()));
        opLogService.record("ROSTER_CLEAR", "清空花名册，删除 " + deleted + " 人");
        return deleted;
    }

    @Transactional
    public void delete(Long personId) {
        activityService.requireCurrent();
        Person person = personMapper.selectById(personId);
        if (person == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "人员不存在");
        }
        jdbc.update("DELETE FROM team_member WHERE person_id = ?", personId);
        personMapper.deleteById(personId);
        opLogService.record("ROSTER_DELETE", person.getName() + " " + person.getPhone());
    }

    private long countByPhone(Long activityId, String phone) {
        return personMapper.selectCount(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activityId)
                .eq(Person::getPhone, phone));
    }

    private long countByEmployee(Long activityId, String employeeId) {
        return personMapper.selectCount(new LambdaQueryWrapper<Person>()
                .eq(Person::getActivityId, activityId)
                .eq(Person::getEmployeeId, employeeId));
    }

    private Person insert(Long activityId, String employeeId, String name, String phone, String department) {
        Person person = new Person();
        person.setActivityId(activityId);
        person.setEmployeeId(employeeId);
        person.setName(name);
        person.setPhone(phone);
        person.setDepartment(department);
        person.setCreatedAt(LocalDateTime.now(clock));
        try {
            personMapper.insert(person);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 预查与插入之间的并发窗口由 uk_activity_phone / uk_activity_employee 唯一键兜底
            if (countByPhone(activityId, phone) > 0) {
                throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + phone);
            }
            throw new ApiException(ErrorCode.DUPLICATE, "员工编号已在花名册中：" + employeeId);
        }
        return person;
    }
}

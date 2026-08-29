package com.muster.roster;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.muster.activity.Activity;
import com.muster.activity.ActivityService;
import com.muster.common.ApiException;
import com.muster.common.ErrorCode;
import com.muster.common.PageResult;
import com.muster.common.PhoneValidator;
import com.muster.roster.dto.PersonCreateRequest;
import com.muster.roster.dto.PersonResponse;
import com.muster.roster.dto.PersonRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RosterService {

    private final PersonMapper personMapper;
    private final ActivityService activityService;
    private final ExcelService excelService;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.muster.audit.OpLogService opLogService;

    public RosterService(PersonMapper personMapper, ActivityService activityService, ExcelService excelService,
                         JdbcTemplate jdbc, Clock clock, com.muster.audit.OpLogService opLogService) {
        this.personMapper = personMapper;
        this.activityService = activityService;
        this.excelService = excelService;
        this.jdbc = jdbc;
        this.clock = clock;
        this.opLogService = opLogService;
    }

    @Transactional
    public int importPersons(InputStream inputStream) {
        Activity activity = activityService.requireCurrent();
        List<PersonRow> rows = excelService.readPersons(inputStream).stream()
                .filter(r -> !(r.name().isEmpty() && r.phone().isEmpty() && r.department().isEmpty()))
                .toList();
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "文件中没有可导入的数据行");
        }
        for (PersonRow row : rows) {
            if (row.name().isEmpty() || row.department().isEmpty() || !PhoneValidator.valid(row.phone())) {
                throw new ApiException(ErrorCode.VALIDATION,
                        "第 " + row.rowNo() + " 行不合法：姓名/部门不能为空，手机号须为 11 位有效手机号");
            }
        }
        Map<String, List<PersonRow>> byPhone = rows.stream()
                .collect(Collectors.groupingBy(PersonRow::phone));
        String inFileDuplicates = byPhone.values().stream()
                .filter(list -> list.size() > 1)
                .map(list -> "第 " + list.get(0).rowNo() + "/" + list.get(1).rowNo() + " 行")
                .collect(Collectors.joining("、"));
        if (!inFileDuplicates.isEmpty()) {
            throw new ApiException(ErrorCode.PHONE_DUPLICATE, "文件内手机号重复：" + inFileDuplicates);
        }
        for (PersonRow row : rows) {
            if (countByPhone(activity.getId(), row.phone()) > 0) {
                throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + row.phone());
            }
        }
        for (PersonRow row : rows) {
            insert(activity.getId(), row.name(), row.phone(), row.department());
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
            wrapper.and(w -> w.like(Person::getName, kw)
                    .or().like(Person::getPhone, kw)
                    .or().like(Person::getDepartment, kw));
        }
        Page<Person> result = personMapper.selectPage(Page.of(page, size), wrapper);
        List<PersonResponse> records = result.getRecords().stream().map(PersonResponse::from).toList();
        return new PageResult<>(result.getTotal(), records);
    }

    public PersonResponse add(PersonCreateRequest request) {
        Activity activity = activityService.requireCurrent();
        String phone = request.phone().trim();
        if (!PhoneValidator.valid(phone)) {
            throw new ApiException(ErrorCode.VALIDATION, "手机号须为 11 位有效手机号");
        }
        if (countByPhone(activity.getId(), phone) > 0) {
            throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + phone);
        }
        Person person = insert(activity.getId(), request.name().trim(), phone, request.department().trim());
        opLogService.record("ROSTER_ADD", request.name().trim() + " " + phone);
        return PersonResponse.from(person);
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

    private Person insert(Long activityId, String name, String phone, String department) {
        Person person = new Person();
        person.setActivityId(activityId);
        person.setName(name);
        person.setPhone(phone);
        person.setDepartment(department);
        person.setCreatedAt(LocalDateTime.now(clock));
        try {
            personMapper.insert(person);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 预查与插入之间的并发窗口由 uk_activity_phone 唯一键兜底
            throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已在花名册中：" + phone);
        }
        return person;
    }
}

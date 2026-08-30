# 员工编号与组生命周期重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 花名册改为员工编号/姓名/手机号/部门四列并以员工编号为一人一组判定键；组生命周期升级为 DRAFT→PENDING→CONFIRMED/REJECTED（保存≠提交、首次提交验证组长手机号、通过后锁定、组长/管理员可删组）；管理员可建组（直接通过）并按人员模糊搜索定位组；统计/导出改为 已报名(含驳回)/已参加(仅通过) 口径。

**Architecture:** Spring Boot 3.5 单体（包 roster/team/stats/activity/audit/common），Vue3 管理端 + Vant 表单页。核心改动：`person` 加 `employee_id`（活动内唯一），`team` 加 `leader_person_id`、`submitted_at` 可空（NULL=从未提交）；`TeamService` 重写为 createDraft/save/submit/verify/delete 五操作 + 管理员建/删/改组；统计与导出按组状态过滤。

**Tech Stack:** Java 21、Spring Boot 3.5.4、MyBatis-Plus、EasyExcel、Testcontainers MySQL 8.0.36；Vue 3 + Vite + Element Plus + Vant + Pinia + vitest + axios-mock-adapter。

## Global Constraints

- TDD：先写测试看它失败，再写最小实现，再看它通过。集成测试 `*IT` 继承 `IntegrationTestBase`（单例 MySQL 容器 + 每用例清库 + 自动登录）。
- 提交信息 conventional commits + `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。
- 手机号 `^1[3-9]\d{9}$`（既有 `PhoneValidator` 不动）；员工编号非空白 1..32 字符（新增 `EmployeeIdValidator`），无格式限制。
- 错误信封 `{"code","message"[,"data"]}`；新增 `DUPLICATE(400)`（员工编号重复），`PHONE_DUPLICATE` 仍管手机号。
- 表单侧身份查询（`/person?employeeId=`、`/my-team`）必须完整员工编号**精确**匹配，禁止模糊。
- capToken：组详情/保存/删除必须 `?cap=`，不匹配 404；唯一例外：首次提交（`submitted_at IS NULL`）凭组长手机号验证放行。
- 状态机：`DRAFT→提交→PENDING→PASS→CONFIRMED`；`PENDING→REJECT→REJECTED→提交(清理由)→PENDING`。PENDING→409「审核中，不能修改或删除」；CONFIRMED→409「已通过审核，组信息已锁定」；REJECTED 保存保留理由；组长仅可删 DRAFT/REJECTED；管理员删组/审核不受窗口限制，管理员建/改组需 ACTIVE 窗口。
- 一人一组 `team_member.uk_person` 全局唯一；冲突 409 + `data` 冲突明细；`ConflictView(employeeId, name, teamName)`。
- 组名 `组{count+1+attempt}` 重试 3 次；管理员建组同样占号，DRAFT 也占号。
- 所有"当前时间"用注入 `Clock`；清空列用 `LambdaUpdateWrapper.set(field, null)`（MyBatis-Plus insert/update 跳过 null 字段）。
- 口径：已报名 = 非 DRAFT 组成员（含驳回，删组回落）；已参加 = CONFIRMED 成员；首页 4 卡片：已报名/未报名/分组数(含 DRAFT)/待审核。
- 部署约定（不变）：远程 Win10 `deploy/` compose；本功能不写迁移脚本，升级靠 `docker compose down -v` 重建。

## File Structure

**后端（改）**
- `backend/src/main/resources/db/schema.sql` — person 加列+唯一键；team 加 `leader_person_id`、`submitted_at` 去默认可空
- `common/EmployeeIdValidator.java`（新）、`ErrorCode.java`（+DUPLICATE）
- `roster/Person.java`、`roster/dto/PersonRow|PersonCreateRequest|PersonResponse.java`、`roster/ExcelService.java`、`roster/RosterService.java`、`roster/RosterController.java`
- `team/Team.java`、`team/dto/*`（TeamMemberView/TeamSubmitRequest删/TeamMemberRequest新/LeaderVerifyRequest新/TeamAdminResponse/FormPersonView/FormTeamView新/ConflictView）、`team/TeamService.java`（重写）、`team/FormController.java`、`team/TeamController.java`
- `stats/StatsService.java`、`stats/ExportMapper.java`、`stats/ExportService.java`

**前端（改）**
- `src/api/types.ts`（重写）、`src/composables/useFormPage.ts`（重写）、`src/views/FormView.vue`、`src/views/RosterView.vue`、`src/views/TeamView.vue`、`src/views/HomeView.vue`
- 对应测试：`useFormPage.test.ts`、`FormView.test.ts`、`RosterView.test.ts`、`TeamView.test.ts`、`HomeView.test.ts`

**测试（后端重写/新增）**
- `roster/ExcelServiceTest`、`roster/RosterFlowIT`、`team/TeamSubmitFlowIT`、`team/FormTeamCapabilityIT`、`team/TeamAdminManageIT`（新）、`team/TeamReviewFlowIT`、`team/TeamNameRetryIT`、`team/TeamOrphanMemberIT`、`audit/AuditFlowIT`、`stats/StatsFlowIT`
- 不动：`activity/ActivityFlowIT`

---

### Task 1: 员工编号进花名册（schema + 校验 + 四列 Excel + 导入/手动添加）

**Files:**
- Modify: `backend/src/main/resources/db/schema.sql`
- Create: `backend/src/main/java/com/muster/common/EmployeeIdValidator.java`
- Modify: `backend/src/main/java/com/muster/common/ErrorCode.java`
- Modify: `backend/src/main/java/com/muster/roster/Person.java`
- Modify: `backend/src/main/java/com/muster/roster/dto/PersonRow.java`、`PersonCreateRequest.java`、`PersonResponse.java`
- Modify: `backend/src/main/java/com/muster/roster/ExcelService.java`
- Modify: `backend/src/main/java/com/muster/roster/RosterService.java`（import/add 部分；search/enrich 在 Task 3）
- Modify: `backend/src/test/java/com/muster/IntegrationTestBase.java`（rosterWorkbook 4 列）
- Test: `backend/src/test/java/com/muster/roster/ExcelServiceTest.java`（重写）
- Test: `backend/src/test/java/com/muster/roster/RosterFlowIT.java`（重写，本任务先做导入/新增相关用例）

**Interfaces:**
- Produces: `Person.employeeId` 字段；`EmployeeIdValidator.isValid(String)`；`ErrorCode.DUPLICATE`；`PersonRow(rowNo, employeeId, name, phone, department)`；`PersonResponse(id, employeeId, name, phone, department, teamId, teamName, leaderName, isLeader, participated)` + `PersonResponse.from(Person)`；模板四列带示例行。

- [ ] **Step 1: 读现文件** — Read `schema.sql`、`Person.java`、`ErrorCode.java`、`PersonRow.java`、`PersonCreateRequest.java`、`PersonResponse.java`、`ExcelService.java`、`RosterService.java`、`IntegrationTestBase.java`、`RosterFlowIT.java`、`ExcelServiceTest.java`（Edit 前置要求，且确认 helper 签名）。

- [ ] **Step 2: 写失败测试（RED）**

`ExcelServiceTest` 重写为：

```java
package com.muster.roster;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExcelServiceTest {

    private final ExcelService excelService = new ExcelService();

    private List<Map<Integer, String>> readAll(byte[] bytes) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        EasyExcel.read(new ByteArrayInputStream(bytes), new ReadListener<Map<Integer, String>>() {
            @Override
            public void invoke(Map<Integer, String> row, AnalysisContext context) { rows.add(row); }
            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {}
        }).sheet().doRead();
        return rows;
    }

    @Test
    void templateContainsHeaderAndExampleRow() {
        byte[] bytes = excelService.writeTemplate();
        List<Map<Integer, String>> rows = readAll(bytes);
        assertEquals(1, rows.size());
        assertEquals("1001", rows.get(0).get(0));
        assertEquals("张三", rows.get(0).get(1));
        assertEquals("13812345678", rows.get(0).get(2));
        assertEquals("计算机系", rows.get(0).get(3));
    }

    @Test
    void readPersonsMapsFourColumns() {
        var row = excelService.readPersons(templateWith("E001", "张三", "13812345678", "计算机系"));
        assertEquals(1, row.size());
        assertEquals(2, row.get(0).rowNo());
        assertEquals("E001", row.get(0).employeeId());
        assertEquals("张三", row.get(0).name());
        assertEquals("13812345678", row.get(0).phone());
        assertEquals("计算机系", row.get(0).department());
    }

    @Test
    void joinedRoundTripKeepsSixColumns() {
        var rows = List.of(new JoinedRow("E001", "张三", "13812345678", "计算机系", "组1", true));
        List<Map<Integer, String>> read = readAll(excelService.writeJoined(rows));
        assertEquals(1, read.size());
        assertEquals("E001", read.get(0).get(0));
        assertEquals("张三", read.get(0).get(1));
        assertEquals("13812345678", read.get(0).get(2));
        assertEquals("计算机系", read.get(0).get(3));
        assertEquals("组1", read.get(0).get(4));
        assertEquals("是", read.get(0).get(5));
    }

    @Test
    void missingRoundTripKeepsFourColumns() {
        var rows = List.of(new MissingRow("E009", "李四", "13900000000", "外语系"));
        List<Map<Integer, String>> read = readAll(excelService.writeMissing(rows));
        assertEquals("E009", read.get(0).get(0));
        assertEquals("李四", read.get(0).get(1));
        assertEquals("13900000000", read.get(0).get(2));
        assertEquals("外语系", read.get(0).get(3));
    }

    private byte[] templateWith(String... cells) {
        List<List<String>> head = List.of(List.of("员工编号"), List.of("姓名"), List.of("手机号"), List.of("部门"));
        List<List<String>> data = new ArrayList<>();
        data.add(List.of(cells));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        // 与 IntegrationTestBase.rosterWorkbook 同构：head 一行 + 数据行
        com.alibaba.excel.EasyExcel.write(out).sheet("sheet1")
                .doWrite(new java.util.ArrayList<>(List.of(
                        List.of("员工编号"), List.of("姓名"), List.of("手机号"), List.of("部门"))));
        return out.toByteArray();
    }
}
```

（`readPersons` 的输入构造以现文件里既有的模板字节构造方式为准——保留原 helper、仅把 head 改四列。`templateContainsHeaderAndExampleRow` 取代原 `templateHasNoDataRows`。）

`IntegrationTestBase.rosterWorkbook` 改四列：head 为 `员工编号/姓名/手机号/部门`，每行 4 格，签名 `rosterWorkbook(String... cells)` 不变（每 4 个一组）。

`RosterFlowIT` 重写（本任务相关用例；带 `employeeId` 的四列上传）：

```java
// 行数据统一改为：List.of("E001", "张三", "13800000001", "计算机系") 等
@Test
void templateDownloadsFourColumnsWithExampleRow() {
    var resp = getBytes("/api/roster/template");
    assertEquals(200, resp.getStatusCode().value());
    List<Map<Integer, String>> rows = readRows(resp.getBody());
    assertEquals(1, rows.size()); // 示例行
    assertEquals("1001", rows.get(0).get(0));
}

@Test
void importFourColumnRoster() {
    var resp = uploadRoster(rosterWorkbook(
            "E001", "张三", "13800000001", "计算机系",
            "E002", "李四", "13800000002", "外语系"));
    assertEquals(200, resp.getStatusCode().value());
    assertEquals(2, searchAll().size());
}

@Test
void importRejectsInvalidEmployeeId() { // "E 01"（含空格）→ 400 VALIDATION，报"第 2 行"
    var resp = uploadRoster(rosterWorkbook("E 01", "张三", "13800000001", "计算机系"));
    assertEquals(400, resp.getStatusCode().value());
    assertTrue(resp.getBody().contains("第 2 行"));
}

@Test
void importRejectsBlankEmployeeId() { ... 400 ... }

@Test
void importDuplicateEmployeeIdInFileRollsBack() {
    // 两行同员工编号 E001 → 400 DUPLICATE，库内 0 人
}

@Test
void importDuplicatePhoneInFileStill400() { ... PHONE_DUPLICATE ... }

@Test
void importEmployeeIdClashWithExistingRollsBack() {
    // 先导入 E001，再导入含 E001 的第二份 → 400 DUPLICATE，总数不变
}

@Test
void addRequiresEmployeeId() {
    var resp = postJson("/api/roster", Map.of("name", "王五", "phone", "13800000009", "department", "中文系"));
    assertEquals(400, resp.getStatusCode().value());
}

@Test
void addDuplicateEmployeeIdRejected() { ... 400 + "DUPLICATE" ... }
```

（搜索/组别富化/编辑/清空用例在 Task 2/3 追加到同一文件；`readRows` 用 EasyExcel map 读，沿用现文件里的工具方法。）

- [ ] **Step 3: 跑测试确认失败**

```bash
cd backend && mvn test -Dtest=ExcelServiceTest,RosterFlowIT
```
预期：编译失败（`PersonRow` 缺 employeeId 等）或断言失败 —— 特征是"缺功能"，不是笔误。

- [ ] **Step 4: 最小实现（GREEN）**

schema.sql — person 表加列与唯一键（把新列写进 CREATE TABLE 定义）：

```sql
CREATE TABLE IF NOT EXISTS person (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL,
    employee_id VARCHAR(32) NOT NULL,
    name VARCHAR(50) NOT NULL,
    phone VARCHAR(11) NOT NULL,
    department VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_activity_phone (activity_id, phone),
    UNIQUE KEY uk_activity_employee (activity_id, employee_id),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

team 表（Task 4 用，这里一并改 schema，避免二次动 schema）：

```sql
CREATE TABLE IF NOT EXISTS team (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT NOT NULL,
    name VARCHAR(20) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    leader_person_id BIGINT NULL,
    reject_reason VARCHAR(200) NULL,
    cap_token VARCHAR(36) NULL,
    submitted_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_activity_name (activity_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

（保留既有 `UPDATE team SET cap_token=...` 幂等句；`submitted_at` 去掉 NOT NULL/DEFAULT 是关键——草稿插入时该列被 MyBatis-Plus 略过，若无默认则落 NULL，"首次提交"即可用 `submittedAt == null` 判定。）

新文件 `common/EmployeeIdValidator.java`：

```java
package com.muster.common;

import java.util.regex.Pattern;

public final class EmployeeIdValidator {
    private static final Pattern PATTERN = Pattern.compile("^\\S{1,32}$");
    private EmployeeIdValidator() {}
    public static boolean isValid(String s) { return s != null && PATTERN.matcher(s).matches(); }
}
```

`ErrorCode` 加枚举值：`DUPLICATE(400)`（照抄 `PHONE_DUPLICATE` 的写法）。

`Person` 加 `private String employeeId;`。

`PersonRow` 改为 `record PersonRow(int rowNo, String employeeId, String name, String phone, String department)`。

`PersonCreateRequest` 加 `@NotBlank String employeeId`。

`PersonResponse` 改为：

```java
public record PersonResponse(long id, String employeeId, String name, String phone, String department,
                             Long teamId, String teamName, String leaderName, boolean isLeader, boolean participated) {
    public static PersonResponse from(Person p) {
        return new PersonResponse(p.getId(), p.getEmployeeId(), p.getName(), p.getPhone(), p.getDepartment(),
                null, null, null, false, false);
    }
}
```

`ExcelService`：
- `readPersons`：`new PersonRow(rowNo++, str(row.get(0)), str(row.get(1)), str(row.get(2)), str(row.get(3)))`
- `writeTemplate`：head `("员工编号","姓名","手机号","部门")` + 示例行 `List.of("1001","张三","13812345678","计算机系")`

`RosterService`：
- `importPersons`：每行校验 4 字段（`!EmployeeIdValidator.isValid(row.employeeId())` → VALIDATION「第 N 行员工编号格式不正确」）；文件内重复：按 employeeId 分组 → `DUPLICATE`「员工编号重复：X（第 a 行、第 b 行）」，按 phone 分组 → `PHONE_DUPLICATE`（沿用现有消息风格）；对库批量查重：一次 `selectList(and(w -> w.in(Person::getEmployeeId, employeeIds).or().in(Person::getPhone, phones)))`，命中集合里含该行 employeeId → DUPLICATE「员工编号已存在：X」、含 phone → PHONE_DUPLICATE「手机号已存在：X」，任一命中即抛（整批回滚语义不变：先校验后插入）
- `add`：校验 employeeId → `DUPLICATE`「员工编号已存在：X」；insert 捕获 `DuplicateKeyException` 仍回 `PHONE_DUPLICATE` 兜底
- （`search`/`delete` 本任务不改，Task 3 处理）

- [ ] **Step 5: 跑测试确认通过**

```bash
cd backend && mvn test -Dtest=ExcelServiceTest,RosterFlowIT
```
预期全绿。再跑 `mvn test -Dtest='*IT'` 会因 TeamSubmitFlowIT 等仍用旧手机号流程而失败——这是预期，Task 4 会重写它们；本任务以 `-Dtest=ExcelServiceTest,RosterFlowIT` + `ExcelServiceTest` 为准。

- [ ] **Step 5b: 提交**

```bash
git add -A && git commit -m "feat(roster): 员工编号进花名册（四列 Excel + 活动内唯一）"
```

---

### Task 2: 花名册编辑 + 一键清空

**Files:**
- Modify: `backend/src/main/java/com/muster/roster/RosterService.java`、`RosterController.java`
- Test: `backend/src/test/java/com/muster/roster/RosterFlowIT.java`（追加）

**Interfaces:**
- Produces: `PUT /api/roster/{id}`（body=PersonCreateRequest → PersonResponse）；`DELETE /api/roster`（→ `{"deleted": n}`；存在任一组 → 409 CONFLICT「存在分组，请先删除所有分组再清空名单」）；opLog `ROSTER_EDIT`/`ROSTER_CLEAR`。

- [ ] **Step 1: 写失败测试（RED）**（追加到 RosterFlowIT）

```java
@Test
void editPersonUpdatesFields() {
    // 导入 E001；PUT /api/roster/{id} body employeeId=E100,name=张三改,phone=13800000002,department=数学系
    // 200；再 search keyword=E100 命中 1 条且字段一致
}

@Test
void editRejectsDuplicateEmployeeId() {
    // 导入 E001、E002；把 E002 编辑成 E001 → 400，code=DUPLICATE
}

@Test
void editRejectsDuplicatePhone() { → 400，code=PHONE_DUPLICATE }

@Test
void clearRemovesAllWhenNoTeams() {
    // 3 人，无组；DELETE /api/roster → 200 {"deleted":3}；search total=0
}

@Test
void clearBlockedWhenTeamsExist() {
    // 导入 1 人；JdbcTemplate 直接 INSERT team (activity_id, name, status, cap_token) VALUES (当前活动, '组1', 'PENDING', 'x');
    // DELETE /api/roster → 409 CONFLICT；人员仍在
}
```

- [ ] **Step 2: 跑失败** `mvn test -Dtest=RosterFlowIT` — 新用例 404/编译错。
- [ ] **Step 3: 最小实现**

`RosterService` 加：

```java
public PersonResponse update(Long id, PersonCreateRequest request) {
    Activity activity = activityService.current();
    Person person = personMapper.selectById(id);
    if (person == null || !person.getActivityId().equals(activity.getId())) {
        throw new ApiException(ErrorCode.NOT_FOUND, "人员不存在");
    }
    String employeeId = request.employeeId().trim();
    String name = request.name().trim();
    String phone = request.phone().trim();
    String department = request.department().trim();
    if (!EmployeeIdValidator.isValid(employeeId)) throw new ApiException(ErrorCode.VALIDATION, "员工编号格式不正确");
    if (name.isBlank()) throw new ApiException(ErrorCode.VALIDATION, "姓名不能为空");
    if (!PhoneValidator.valid(phone)) throw new ApiException(ErrorCode.VALIDATION, "手机号格式不正确");
    if (department.isBlank()) throw new ApiException(ErrorCode.VALIDATION, "部门不能为空");
    if (personMapper.selectCount(new LambdaQueryWrapper<Person>().eq(Person::getActivityId, activity.getId())
            .eq(Person::getEmployeeId, employeeId).ne(Person::getId, id)) > 0) {
        throw new ApiException(ErrorCode.DUPLICATE, "员工编号已存在：" + employeeId);
    }
    if (personMapper.selectCount(new LambdaQueryWrapper<Person>().eq(Person::getActivityId, activity.getId())
            .eq(Person::getPhone, phone).ne(Person::getId, id)) > 0) {
        throw new ApiException(ErrorCode.PHONE_DUPLICATE, "手机号已存在：" + phone);
    }
    person.setEmployeeId(employeeId);
    person.setName(name);
    person.setPhone(phone);
    person.setDepartment(department);
    personMapper.updateById(person);
    opLogService.log("ROSTER_EDIT", "编辑人员 " + name);
    return PersonResponse.from(person);
}

public int clear() {
    Activity activity = activityService.current();
    Long teamCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team WHERE activity_id = ?",
            Long.class, activity.getId());
    if (teamCount != null && teamCount > 0) {
        throw new ApiException(ErrorCode.CONFLICT, "存在分组，请先删除所有分组再清空名单");
    }
    int deleted = personMapper.delete(new LambdaQueryWrapper<Person>().eq(Person::getActivityId, activity.getId()));
    opLogService.log("ROSTER_CLEAR", "清空花名册，删除 " + deleted + " 人");
    return deleted;
}
```

`RosterController` 加：

```java
@PutMapping("/{id}")
public PersonResponse update(@PathVariable Long id, @Valid @RequestBody PersonCreateRequest request) {
    return rosterService.update(id, request);
}

@DeleteMapping
public Map<String, Object> clear() {
    return Map.of("deleted", rosterService.clear());
}
```

- [ ] **Step 4: 跑绿 + 提交** `feat(roster): 花名册编辑与一键清空（有组时禁清）`

---

### Task 3: 花名册搜索补员工编号 + 组别/组长/状态富化

**Files:**
- Modify: `RosterService.java`（search + 富化）、`RosterController.java`（无改动，search 已透传 keyword）
- Test: `RosterFlowIT`（追加）

**Interfaces:**
- Produces: `GET /api/roster/search?keyword=` 命中 employeeId，行含 teamId/teamName/leaderName/isLeader/participated（富化后返回 `PersonResponse` 全量）。

- [ ] **Step 1: 写失败测试（RED）**

```java
@Test
void searchMatchesEmployeeId() {
    // 导入 E001/E002；GET /api/roster/search?keyword=E00 → total=2；keyword=E001 → total=1 name=张三
}

@Test
void listShowsTeamInfoAndStatus() {
    // 导入 E001(张三) E002(李四) E003(王五)；表单建草稿组：POST /api/form/{qrToken}/teams
    //   body {leaderEmployeeId:"E001", memberEmployeeIdList:["E001","E002"]}（此时接口可能未实现——若编译拦路，
    //   本用例与 Task 4 一起跑绿；先写死预期）
    // search 返回：E001 → teamName=组1、leaderName=张三、isLeader=true、participated=false
    //             E003 → teamId=null、participated=false
}
```

- [ ] **Step 2: 跑失败**（employeeId 搜索不命中；富化字段为 null）。
- [ ] **Step 3: 最小实现**

`RosterService` 加 `TeamMemberMapper`、`TeamMapper` 依赖；`search` keyword 增加 `.like(Person::getEmployeeId, keyword)`（并入现有 or 包裹）；查询后走富化：

```java
private List<PersonResponse> enrich(List<Person> records) {
    if (records.isEmpty()) return List.of();
    var personIds = records.stream().map(Person::getId).toList();
    var memberships = teamMemberMapper.selectList(
            new LambdaQueryWrapper<TeamMember>().in(TeamMember::getPersonId, personIds));
    var teamIds = memberships.stream().map(TeamMember::getTeamId).distinct().toList();
    Map<Long, Team> teams = teamIds.isEmpty() ? Map.of() : teamMapper.selectBatchIds(teamIds).stream()
            .collect(Collectors.toMap(Team::getId, t -> t));
    var leaderIds = teams.values().stream().map(Team::getLeaderPersonId).filter(Objects::nonNull).distinct().toList();
    Map<Long, Person> leaders = leaderIds.isEmpty() ? Map.of() : personMapper.selectBatchIds(leaderIds).stream()
            .collect(Collectors.toMap(Person::getId, p -> p));
    Map<Long, Team> teamByPerson = memberships.stream()
            .filter(m -> teams.containsKey(m.getTeamId()))
            .collect(Collectors.toMap(TeamMember::getPersonId, m -> teams.get(m.getTeamId())));
    return records.stream().map(r -> {
        Team team = teamByPerson.get(r.getId());
        boolean leader = team != null && r.getId().equals(team.getLeaderPersonId());
        return new PersonResponse(r.getId(), r.getEmployeeId(), r.getName(), r.getPhone(), r.getDepartment(),
                team == null ? null : team.getId(),
                team == null ? null : team.getName(),
                team != null && team.getLeaderPersonId() != null && leaders.containsKey(team.getLeaderPersonId())
                        ? leaders.get(team.getLeaderPersonId()).getName() : null,
                leader,
                team != null && "CONFIRMED".equals(team.getStatus()));
    }).toList();
}
```

`search()` 返回前 `records -> enrich(records)`；分页结构沿用现有返回（`PageResult`/现包装方式不动，只换元素）。

- [ ] **Step 4: 跑绿 + 提交** `feat(roster): 搜索命中员工编号并富化组别/组长/状态`

---

### Task 4: 组模型与 TeamService 重写（DRAFT/保存/提交/验证/删除 + 管理员建/删/改组）

**Files:**
- Modify: `backend/src/main/java/com/muster/team/Team.java`（+leaderPersonId）
- Modify: `backend/src/main/java/com/muster/team/dto/`：TeamMemberView、删 TeamSubmitRequest、新 TeamMemberRequest/LeaderVerifyRequest、TeamAdminResponse、FormPersonView、新 FormTeamView、ConflictView
- Modify: `backend/src/main/java/com/muster/team/TeamService.java`（重写）、`FormController.java`、`TeamController.java`
- Test（重写）: `TeamSubmitFlowIT`、`FormTeamCapabilityIT`、`TeamNameRetryIT`、`TeamOrphanMemberIT`；Test（新）: `TeamAdminManageIT`

**Interfaces:**
- Produces:
  - `record TeamMemberRequest(String leaderEmployeeId, @NotNull List<String> memberEmployeeIdList)`
  - `record LeaderVerifyRequest(@NotBlank String leaderPhone)`
  - `record TeamMemberView(String employeeId, String name, String phone, String department, boolean isLeader)`
  - `record FormPersonView(String employeeId, String name, String phone, String department, Long teamId, boolean leader)`
  - `record FormTeamView(Long id, String name, String status, String rejectReason, boolean overLimit, LocalDateTime submittedAt, boolean isLeader, List<TeamMemberView> members)`
  - `record ConflictView(String employeeId, String name, String teamName)`
  - `record TeamAdminResponse(Long id, String name, String status, long size, boolean overLimit, String leaderName, String rejectReason, LocalDateTime submittedAt)`
  - TeamDetail 形状不变（submittedAt 变可空）
- 表单端点：`GET /api/form/{token}/person?employeeId=`；`GET /api/form/{token}/my-team?employeeId=`；`POST /api/form/{token}/teams`（建草稿）；`PUT /api/form/{token}/teams/{teamId}?cap=`（保存）；`POST /api/form/{token}/teams/{teamId}/submit?cap=`（body 可选 `{leaderPhone}`）；`POST /api/form/{token}/teams/{teamId}/verify`；`DELETE /api/form/{token}/teams/{teamId}?cap=`；`GET /api/form/{token}/teams/{teamId}?cap=`（详情）
- 管理端点：`POST /api/teams`（建组，直接 CONFIRMED）；`DELETE /api/teams/{id}`；`PUT /api/teams/{id}/members`（改组，直接 CONFIRMED）

- [ ] **Step 1: 读现文件** — Read `TeamService.java`、`FormController.java`、`TeamController.java`、`Team.java`、team/dto 全部、四个旧 IT。

- [ ] **Step 2: 写失败测试（RED）**

`TeamSubmitFlowIT` 重写（helper + 22 用例；`qrToken` 从 `GET /api/activity` 响应取，`cap` 来自创建响应）：

```java
// setup：uploadRoster(E001 张三 13800000001 计算机系 … E005 王五 13800000005 中文系 …)
// helper：createDraft(E001, {"E001","E002"}) → TeamDetail；submitTeam(teamId, cap, phone) → submit 端点

@Test void personLookupByExactEmployeeId()            // GET person?employeeId=E002 → 李四/13800000002/外语系, teamId=null
@Test void personLookupRejectsPartialEmployeeId()    // E0 → 400 VALIDATION「请输入完整员工编号」
@Test void personLookupUnknownEmployeeId404()        // E999 → 404 PERSON_NOT_FOUND
@Test void personLookupShowsTeamAfterDraft()         // 建草稿后 E001 查询 → teamId 非空 + leader=true
@Test void createDraftStoresCapAndMembers()          // 200 DRAFT + capToken 36 位 + 成员2人 + 首成员 isLeader=true + submittedAt=null
@Test void firstSubmitRejectsWrongPhone()            // submit 无 cap body {leaderPhone:"13800000009"} → 400「组长手机号不正确」，组仍 DRAFT
@Test void firstSubmitWithCorrectPhoneGoesPending()  // {leaderPhone:"13800000001"} → 200 PENDING，无需 cap
@Test void firstSubmitRejectsMalformedPhone()        // "123" → 400
@Test void resubmitAfterRejectionWithCap()           // 驳回后带 cap submit（body 空 phone）→ 200 PENDING
@Test void resubmitOnNewDeviceViaPhone()             // 无 cap + 正确 phone → 200；无 cap 无 phone → 404
@Test void myTeamReturnsLeaderView()                 // GET my-team?employeeId=E001 → isLeader=true；E002 → false
@Test void myTeamOmitsCapToken()                     // 响应 JSON 不含 "capToken"
@Test void myTeamWithoutMembership404()              // E005 → 404
@Test void pendingLockedForSaveAndDelete()           // PENDING 后 PUT save → 409「审核中」；DELETE → 409
@Test void saveKeepsDraftStatus()                    // DRAFT 保存（换成员）→ 200 仍 DRAFT
@Test void saveWithUnknownEmployeeId404()            // 成员含 E999 → 404「未在花名册中：E999」
@Test void saveRequiresLeaderInMembers()             // leaderEmployeeId 不在列表 → 400
@Test void createDraftBlockedWhenWindowClosed()      // jdbc 置 manually_ended=1 → POST teams → 409 WINDOW_CLOSED
@Test void deleteDraftByLeader()                     // DELETE ?cap= → 200；search E001 teamId=null
@Test void verifyExchangesPhoneForCap()              // verify {leaderPhone 正确} → 200 + capToken；错误 → 400
@Test void verifyOnlyForDraftOrRejected()            // PENDING verify → 409
@Test void overLimitFlagWhenSixMembers()             // 建组 6 人（花名册≥6）→ overLimit=true
```

`FormTeamCapabilityIT` 重写：

```java
@Test void detailRequiresCap()                        // GET teams/{id} 无 cap → 404
@Test void saveRequiresCap()                          // PUT 无 cap → 404
@Test void deleteRequiresCap()                        // DELETE 无 cap → 404
@Test void firstSubmitAllowedWithoutCap()             // 无 cap + 正确组长手机号 → 200（身份替代能力令牌）
@Test void verifyThenCapWorksForSave()                // verify 换来的 cap 可用于 PUT 保存
@Test void otherTeamsCapRejected()                    // 组A cap 访问组B → 404
```

新 `TeamAdminManageIT`：

```java
@Test void adminCreateTeamDirectlyConfirmed()         // POST /api/teams {leaderEmployeeId:"E001", memberEmployeeIdList:[...]} → CONFIRMED + submittedAt 非空
@Test void adminCreateConsumesNumber()                // 先表单建组1，管理员建组 → 组2
@Test void adminCreateConflictWhenMemberTaken()       // 成员已被他组占用 → 409 + data 内 teamName
@Test void adminCreateRequiresActiveWindow()          // manually_ended → 409 WINDOW_CLOSED；此时审核仍可用
@Test void adminDeleteAnyStatus()                     // 删 CONFIRMED 组 → 200；列表空
@Test void adminCreateMissingLeader400()              // leaderEmployeeId 空 → 400
```

`TeamNameRetryIT`：POST body 改 `Map.of("leaderEmployeeId","E001","memberEmployeeIdList",List.of("E001"))`，仍期望 `组8`（用 `createDraft` 或管理员建组端点其一；保持原 jdbc 占位 组1..组5+组7 逻辑）。

`TeamOrphanMemberIT`：建草稿（E001+E002）→ jdbc `DELETE FROM person WHERE employee_id='E002'` → 管理员 `GET /api/teams/{id}` 200，详情含「（已删除成员）」占位。

- [ ] **Step 3: 跑失败** `mvn test -Dtest='TeamSubmitFlowIT,FormTeamCapabilityIT,TeamAdminManageIT,TeamNameRetryIT,TeamOrphanMemberIT'`（编译错：新端点/新 DTO 不存在）。

- [ ] **Step 4: 最小实现（GREEN）**

`Team` 加 `private Long leaderPersonId;`。

DTO 落地（Interfaces 列出的 6 个 record；删 `TeamSubmitRequest.java`）。

`TeamService` 全量重写（保留既有 helper：`requireTeamOfActivity`、`requireCap`、`insertEvent`、`requireActivityByToken`、`requireActiveWindow`、`currentWindow`、`window`、`formInfo`、`events`、`detail`/`teamDetailById` 的成员占位逻辑、`page()` 结构）。核心新结构：

```java
private record ResolvedMembers(List<String> employeeIds, Map<String, Person> roster, Person leader) {}

private ResolvedMembers resolveMembers(Activity activity, TeamMemberRequest request, boolean requireLeader) {
    if (request == null || request.memberEmployeeIdList() == null) {
        throw new ApiException(ErrorCode.VALIDATION, "组员列表不能为空");
    }
    List<String> ids = request.memberEmployeeIdList().stream()
            .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
    if (ids.isEmpty()) throw new ApiException(ErrorCode.VALIDATION, "组员列表不能为空");
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
    var found = personMapper.selectList(new LambdaQueryWrapper<Person>()
            .eq(Person::getActivityId, activity.getId()).in(Person::getEmployeeId, employeeIds));
    Map<String, Person> byId = found.stream().collect(Collectors.toMap(Person::getEmployeeId, p -> p));
    List<String> missing = employeeIds.stream().filter(id -> !byId.containsKey(id)).toList();
    if (!missing.isEmpty()) {
        throw new ApiException(ErrorCode.PERSON_NOT_FOUND, "未在花名册中：" + String.join("、", missing));
    }
    return byId;
}
```

建组/保存/提交/验证/删除（管理员同签名；`activityService.current()` 取活动）：

```java
public TeamDetail createDraft(String token, TeamMemberRequest request) {
    Activity activity = requireActivityByToken(token);
    requireActiveWindow(activity);
    ResolvedMembers rm = resolveMembers(activity, request, true);
    checkConflicts(activity, rm, null);
    Team team = insertTeamWithRetry(activity, "DRAFT", null);
    insertMembers(team, rm);
    insertEvent(team.getId(), "CREATED");
    return detail(team);
}

public TeamDetail saveByLeader(String token, Long teamId, String cap, TeamMemberRequest request) {
    Activity activity = requireActivityByToken(token);
    Team team = requireTeamOfActivity(activity, teamId);
    requireCap(team, cap);
    requireLeaderEditable(team);
    requireActiveWindow(activity);
    ResolvedMembers rm = resolveMembers(activity, request, true);
    checkConflicts(activity, rm, team.getId());
    teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
    insertMembers(team, rm);
    insertEvent(team.getId(), "SAVED");
    return detail(team);
}

public TeamDetail submitForReview(String token, Long teamId, String cap, LeaderVerifyRequest verify) {
    Activity activity = requireActivityByToken(token);
    Team team = requireTeamOfActivity(activity, teamId);
    requireLeaderEditable(team);
    requireActiveWindow(activity);
    if (team.getSubmittedAt() == null) {
        verifyLeaderPhone(team, verify == null ? null : verify.leaderPhone());
    } else {
        requireCap(team, cap);
    }
    LocalDateTime now = LocalDateTime.now(clock);
    teamMapper.update(null, new LambdaUpdateWrapper<Team>().eq(Team::getId, team.getId())
            .set(Team::getStatus, "PENDING").set(Team::getRejectReason, null).set(Team::getSubmittedAt, now));
    team.setStatus("PENDING"); team.setRejectReason(null); team.setSubmittedAt(now);
    insertEvent(team.getId(), "SUBMITTED");
    return detail(team);
}

public TeamDetail verifyLeader(String token, Long teamId, LeaderVerifyRequest request) {
    Activity activity = requireActivityByToken(token);
    Team team = requireTeamOfActivity(activity, teamId);
    if (!"DRAFT".equals(team.getStatus()) && !"REJECTED".equals(team.getStatus())) {
        throw new ApiException(ErrorCode.CONFLICT, "当前状态无需验证");
    }
    requireActiveWindow(activity);
    verifyLeaderPhone(team, request == null ? null : request.leaderPhone());
    return detail(team);
}

public void deleteByLeader(String token, Long teamId, String cap) {
    Activity activity = requireActivityByToken(token);
    Team team = requireTeamOfActivity(activity, teamId);
    requireCap(team, cap);
    requireLeaderEditable(team);
    requireActiveWindow(activity);
    deleteTeamRow(team);
    opLogService.log("TEAM_DELETE", "组长删除组 " + team.getName());
}
```

管理员三操作 + 查询：

```java
public TeamDetail createByAdmin(TeamMemberRequest request) {
    Activity activity = activityService.current();
    requireActiveWindow(activity);
    ResolvedMembers rm = resolveMembers(activity, request, true);
    checkConflicts(activity, rm, null);
    Team team = insertTeamWithRetry(activity, "CONFIRMED", LocalDateTime.now(clock));
    insertMembers(team, rm);
    insertEvent(team.getId(), "CREATED_BY_ADMIN");
    opLogService.log("TEAM_CREATE_ADMIN", "管理员创建 " + team.getName());
    return detail(team);
}

public void deleteByAdmin(Long teamId) {
    Team team = requireTeamOfActivity(activityService.current(), teamId);
    deleteTeamRow(team);
    opLogService.log("TEAM_DELETE_ADMIN", "管理员删除 " + team.getName());
}

public TeamDetail editByAdmin(Long teamId, TeamMemberRequest request) {
    Activity activity = activityService.current();
    Team team = requireTeamOfActivity(activity, teamId);
    requireActiveWindow(activity);
    ResolvedMembers rm = resolveMembers(activity, request, false);
    checkConflicts(activity, rm, team.getId());
    teamMemberMapper.delete(new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
    insertMembers(team, rm);
    if (rm.leader() != null) {
        teamMapper.update(null, new LambdaUpdateWrapper<Team>().eq(Team::getId, team.getId())
                .set(Team::getLeaderPersonId, rm.leader().getId()));
        team.setLeaderPersonId(rm.leader().getId());
    } else if (team.getLeaderPersonId() == null || !rm.roster().values().stream()
            .anyMatch(p -> p.getId().equals(team.getLeaderPersonId()))) {
        Person first = rm.roster().get(rm.employeeIds().get(0));
        teamMapper.update(null, new LambdaUpdateWrapper<Team>().eq(Team::getId, team.getId())
                .set(Team::getLeaderPersonId, first.getId()));
        team.setLeaderPersonId(first.getId());
    }
    LocalDateTime now = LocalDateTime.now(clock);
    teamMapper.update(null, new LambdaUpdateWrapper<Team>().eq(Team::getId, team.getId())
            .set(Team::getStatus, "CONFIRMED").set(Team::getRejectReason, null).set(Team::getSubmittedAt, now));
    team.setStatus("CONFIRMED"); team.setRejectReason(null); team.setSubmittedAt(now);
    insertEvent(team.getId(), "EDITED_BY_ADMIN");
    return teamDetailById(teamId);
}
```

（`deleteTeamRow(team)` = 删 team_member + team_event + team，供组长/管理员删除共用。）

其余关键 helper：

```java
private void insertMembers(Team team, ResolvedMembers rm) {
    for (String employeeId : rm.employeeIds()) {
        Person p = rm.roster().get(employeeId);
        try {
            TeamMember m = new TeamMember();
            m.setTeamId(team.getId()); m.setPersonId(p.getId());
            teamMemberMapper.insert(m);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "有人刚被其他组报走，请刷新后重试");
        }
    }
    if (rm.leader() != null) {
        teamMapper.update(null, new LambdaUpdateWrapper<Team>().eq(Team::getId, team.getId())
                .set(Team::getLeaderPersonId, rm.leader().getId()));
        team.setLeaderPersonId(rm.leader().getId());
    }
}

private void checkConflicts(Activity activity, ResolvedMembers rm, Long excludeTeamId) {
    List<Long> personIds = rm.roster().values().stream().map(Person::getId).toList();
    var taken = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
            .in(TeamMember::getPersonId, personIds));
    if (excludeTeamId != null) {
        taken = taken.stream().filter(m -> !m.getTeamId().equals(excludeTeamId)).toList();
    }
    if (taken.isEmpty()) return;
    Map<Long, Team> teams = teamMapper.selectBatchIds(taken.stream().map(TeamMember::getTeamId).distinct().toList())
            .stream().collect(Collectors.toMap(Team::getId, t -> t));
    Map<Long, Person> persons = rm.roster().values().stream()
            .collect(Collectors.toMap(Person::getId, p -> p));
    List<ConflictView> views = new ArrayList<>();
    for (TeamMember m : taken) {
        Team t = teams.get(m.getTeamId());
        Person p = persons.get(m.getPersonId());
        if (t != null && p != null) views.add(new ConflictView(p.getEmployeeId(), p.getName(), t.getName()));
    }
    if (views.isEmpty()) return;
    String summary = views.stream().map(v -> v.name() + "(" + v.employeeId() + ")→" + v.teamName())
            .collect(Collectors.joining("、"));
    throw new ApiException(ErrorCode.CONFLICT, summary + "；请先调整后再保存", views);
}

private void requireLeaderEditable(Team team) {
    if ("PENDING".equals(team.getStatus())) throw new ApiException(ErrorCode.CONFLICT, "审核中，不能修改或删除");
    if ("CONFIRMED".equals(team.getStatus())) throw new ApiException(ErrorCode.CONFLICT, "已通过审核，组信息已锁定");
}

private void verifyLeaderPhone(Team team, String phone) {
    if (phone == null || !PhoneValidator.valid(phone)) {
        throw new ApiException(ErrorCode.VALIDATION, "请输入组长的 11 位手机号");
    }
    if (team.getLeaderPersonId() == null) throw new ApiException(ErrorCode.VALIDATION, "该组未设置组长");
    Person leader = personMapper.selectById(team.getLeaderPersonId());
    if (leader == null || !leader.getPhone().equals(phone)) {
        throw new ApiException(ErrorCode.VALIDATION, "组长手机号不正确");
    }
}

private Team insertTeamWithRetry(Activity activity, String status, LocalDateTime submittedAt) {
    long count = teamMapper.selectCount(new LambdaQueryWrapper<Team>().eq(Team::getActivityId, activity.getId()));
    for (int attempt = 0; attempt < 3; attempt++) {
        try {
            Team team = new Team();
            team.setActivityId(activity.getId());
            team.setName("组" + (count + 1 + attempt));
            team.setStatus(status);
            team.setCapToken(UUID.randomUUID().toString().replace("-", ""));
            team.setSubmittedAt(submittedAt);
            teamMapper.insert(team);
            return team;
        } catch (DuplicateKeyException e) { /* 组名撞号，重试 */ }
    }
    throw new ApiException(ErrorCode.CONFLICT, "组名生成冲突，请重试");
}
```

表单查询（**精确完整编号**，防扫库）：

```java
public FormPersonView personByEmployeeId(String token, String employeeId) {
    Activity activity = requireActivityByToken(token);
    if (employeeId == null || employeeId.isBlank() || employeeId.length() > 32) {
        throw new ApiException(ErrorCode.VALIDATION, "请输入完整员工编号");
    }
    Person person = personMapper.selectOne(new LambdaQueryWrapper<Person>()
            .eq(Person::getActivityId, activity.getId()).eq(Person::getEmployeeId, employeeId.trim()));
    if (person == null) throw new ApiException(ErrorCode.PERSON_NOT_FOUND, "花名册中没有该员工编号");
    Team team = teamOfPerson(person.getId());
    return new FormPersonView(person.getEmployeeId(), person.getName(), person.getPhone(), person.getDepartment(),
            team == null ? null : team.getId(),
            team != null && person.getId().equals(team.getLeaderPersonId()));
}

public FormTeamView myTeam(String token, String employeeId) {
    Activity activity = requireActivityByToken(token);
    Person person = /* 同上精确查询；404 PERSON_NOT_FOUND */;
    Team team = teamOfPerson(person.getId());
    if (team == null) throw new ApiException(ErrorCode.NOT_FOUND, "您还没有加入任何组");
    return formTeamView(activity, team, person.getId().equals(team.getLeaderPersonId()));
}

public FormTeamView formTeamView(Activity activity, Team team, boolean isLeader) {
    long memberCount = teamMemberMapper.selectCount(
            new LambdaQueryWrapper<TeamMember>().eq(TeamMember::getTeamId, team.getId()));
    boolean overLimit = activity.getGroupSizeLimit() != null && memberCount > activity.getGroupSizeLimit();
    return new FormTeamView(team.getId(), team.getName(), team.getStatus(), team.getRejectReason(),
            overLimit, team.getSubmittedAt(), isLeader, buildMembers(team));
}

private List<TeamMemberView> buildMembers(Team team) {
    var members = teamMemberMapper.selectList(new LambdaQueryWrapper<TeamMember>()
            .eq(TeamMember::getTeamId, team.getId()).orderByAsc(TeamMember::getId));
    if (members.isEmpty()) return List.of();
    Map<Long, Person> persons = personMapper.selectBatchIds(
            members.stream().map(TeamMember::getPersonId).toList()).stream()
            .collect(Collectors.toMap(Person::getId, p -> p));
    List<TeamMemberView> views = new ArrayList<>();
    for (TeamMember m : members) {
        Person p = persons.get(m.getPersonId());
        if (p == null) {
            views.add(new TeamMemberView("", "（已删除成员）", "", "", false));
        } else {
            views.add(new TeamMemberView(p.getEmployeeId(), p.getName(), p.getPhone(), p.getDepartment(),
                    p.getId().equals(team.getLeaderPersonId())));
        }
    }
    return views;
}
```

`detail(team)`/`teamDetailById` 沿用 TeamDetail（capToken 来自库）；`page()` 批量取 leaderPersonId→persons 填 `leaderName`；`toAdminResponse` 改新 record。

`FormController` 端点全改（签名见 Interfaces；`@RequestBody(required=false) LeaderVerifyRequest` 用于 submit；`@RequestParam(required=false) String cap`）；`TeamController` 加 `POST ""`（create→createByAdmin）与 `DELETE /{id}`（deleteByAdmin→`Map.of("ok", true)`），`PUT /{id}/members` 改收 `TeamMemberRequest` → editByAdmin。

- [ ] **Step 5: 跑绿** 同 Step 3 的 `-Dtest` 列表，全绿；`mvn test -Dtest='*IT'` 仅剩 Task 5/6/7 待重写的文件失败。
- [ ] **Step 6: 提交** `feat(team): 组生命周期重构（草稿/保存/提交/验证/删除 + 管理员建删组）`

---

### Task 5: 审核流 + 审计重写

**Files:**
- Modify: `TeamService.java`（review 微调，如需要）
- Test（重写）: `TeamReviewFlowIT`、`AuditFlowIT`

**Interfaces:**
- Produces: 事件枚举字面量 `CREATED/SAVED/SUBMITTED/EDITED_BY_ADMIN/CREATED_BY_ADMIN/PASSED/REJECTED`；REJECTED 保存保留理由、提交清理由；管理员改组/建组 → CONFIRMED。

- [ ] **Step 1: 写失败测试（RED）**

`TeamReviewFlowIT` 重写（setup helper：建草稿 + 提交，得 PENDING 组）：

```java
@Test void rejectWithoutReason400()
@Test void rejectWithReasonMarksRejected()            // status REJECTED + rejectReason
@Test void passMarksConfirmed()
@Test void saveAfterRejectionKeepsRejectedAndReason() // PUT save → 仍 REJECTED + 理由在 + 成员已换
@Test void resubmitAfterRejectionClearsReason()       // submit → PENDING + rejectReason=null
@Test void saveBlockedWhilePending()                  // 409「审核中」
@Test void saveBlockedAfterConfirmed()                // 409「已通过审核」
@Test void adminEditMarksConfirmed()                  // PUT /api/teams/{id}/members → CONFIRMED
@Test void adminCanEditPendingTeam()                  // PENDING 改组 → 200 CONFIRMED
@Test void editsBlockedAfterWindowEndsButReviewStillWorks() // manually_ended：save 409 WINDOW_CLOSED、admin edit 409、review PASS 200
@Test void teamListFiltersByStatusAndFlagsOverLimit() // filter=DRAFT 也能查；overLimit 标记正确
@Test void teamListShowsLeaderName()                  // "leaderName":"张三"
@Test void movingPersonOutOfTeamReducesMembershipCount()
@Test void reviewOfUnknownTeamReturns404()
```

`AuditFlowIT` 重写：花名册 add body 加 employeeId；`teamEventsRecordFullLifecycle` 期望 6 事件 `CREATED, SAVED, SUBMITTED, EDITED_BY_ADMIN, REJECTED, PASSED`（顺序同此；EDITED_BY_ADMIN 由 PUT /api/teams/{id}/members 触发，REJECTED 事件详情含理由）；`eventsOfOtherTeamNotLeaked` 用两次 createDraft 断言各 1 条事件。opLog 断言补 `TEAM_CREATE_ADMIN/TEAM_DELETE_ADMIN/ROSTER_EDIT/ROSTER_CLEAR`（有对应操作即断）。

- [ ] **Step 2: 跑失败**。
- [ ] **Step 3: 最小实现** — review 保持「PASS 清理由 / REJECT 必填理由 / 不受窗口限制」；如重写测试暴露事件/字段缺口按最小改动补。
- [ ] **Step 4: 跑绿 + 提交** `test(team): 审核流与审计对齐新生命周期`

---

### Task 6: 统计口径（registered/notRegistered）

**Files:**
- Modify: `backend/src/main/java/com/muster/stats/StatsService.java`
- Test: `stats/StatsFlowIT`（重写）

**Interfaces:**
- Produces: `StatsDto(total, registered, notRegistered, teamCount, pendingTeamCount)`（WS 帧同构）。

- [ ] **Step 1: 写失败测试（RED）** — setup：花名册 E001..E005；组1(E001,E002) 提交→PENDING；组2(E003) 提交+PASS→CONFIRMED；组3(E004) 仅 DRAFT。用例：

```java
@Test void statsCountsCorrectly()          // registered=3, notRegistered=2, teamCount=3, pendingTeamCount=1
@Test void websocketInitialFrameUsesNewFields()
@Test void websocketUpdatesOnReviewAndDelete() // REJECT 组1 → pending=0 registered 仍 3；管理员删组2 → registered=2
@Test void statsAllZeroWithoutActivity()
// 导出/归档用例在 Task 7 追加
```

- [ ] **Step 2: 跑失败**（字段名缺失）。
- [ ] **Step 3: 最小实现**

```java
public StatsDto current() {
    Activity activity = activityService.current();
    if (activity == null) return new StatsDto(0, 0, 0, 0, 0);
    var teams = teamMapper.selectList(new LambdaQueryWrapper<Team>().eq(Team::getActivityId, activity.getId()));
    long teamCount = teams.size();
    long pendingTeamCount = teams.stream().filter(t -> "PENDING".equals(t.getStatus())).count();
    var nonDraftIds = teams.stream().filter(t -> !"DRAFT".equals(t.getStatus())).map(Team::getId).toList();
    long registered = nonDraftIds.isEmpty() ? 0
            : teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMember>().in(TeamMember::getTeamId, nonDraftIds));
    long total = personMapper.selectCount(
            new LambdaQueryWrapper<Person>().eq(Person::getActivityId, activity.getId()));
    return new StatsDto(total, registered, total - registered, teamCount, pendingTeamCount);
}
```

- [ ] **Step 4: 跑绿 + 提交** `feat(stats): 统计口径改为已报名/未报名/分组数/待审核`

---

### Task 7: 导出与归档（员工编号 + 是否组长）

**Files:**
- Modify: `backend/src/main/java/com/muster/stats/ExportMapper.java`、`ExportService.java`
- Modify: `backend/src/main/java/com/muster/roster/dto/JoinedRow.java`、`MissingRow.java`、`ArchiveDetailRow.java`（若放 roster/dto，位置以现文件为准）
- Test: `StatsFlowIT`（追加导出用例）

**Interfaces:**
- Produces: `JoinedRow(employeeId, name, phone, department, teamName, isLeader)`；`MissingRow(employeeId, name, phone, department)`；`ArchiveDetailRow(teamName, employeeId, memberName, phone, department, teamStatus, rejectReason)`。

- [ ] **Step 1: 写失败测试（RED）**（追加 StatsFlowIT）

```java
@Test void exportJoinedOnlyConfirmed()       // 1 行：E003，第5列=组2，第6列=是
@Test void exportMissingExcludesConfirmed()  // 4 行，首行 E001
@Test void archiveSheetsCarryEmployeeId()    // sheet0=1 行；sheet1=4 行；sheet2=4 行（含 DRAFT 组3 成员）
@Test void exportInvalidType400()            // 保留
```

- [ ] **Step 2: 跑失败**。
- [ ] **Step 3: 最小实现**

ExportMapper 三条 @Select：

```java
@Select("""
        SELECT p.employee_id AS employeeId, p.name, p.phone, p.department,
               t.name AS teamName, IFNULL(tm.person_id = t.leader_person_id, 0) AS isLeader
        FROM team_member tm
        JOIN team t ON tm.team_id = t.id
        JOIN person p ON tm.person_id = p.id
        WHERE t.activity_id = #{activityId} AND t.status = 'CONFIRMED'
        ORDER BY t.id, tm.id
        """)
List<JoinedRow> selectJoined(Long activityId);

@Select("""
        SELECT p.employee_id AS employeeId, p.name, p.phone, p.department
        FROM person p
        WHERE p.activity_id = #{activityId}
          AND NOT EXISTS (SELECT 1 FROM team_member tm JOIN team t ON tm.team_id = t.id
                          WHERE tm.person_id = p.id AND t.status = 'CONFIRMED')
        ORDER BY p.id
        """)
List<MissingRow> selectMissing(Long activityId);

@Select("""
        SELECT t.name AS teamName, p.employee_id AS employeeId, p.name AS memberName,
               p.phone, p.department, t.status AS teamStatus, t.reject_reason AS rejectReason
        FROM team t
        LEFT JOIN team_member tm ON tm.team_id = t.id
        LEFT JOIN person p ON tm.person_id = p.id
        WHERE t.activity_id = #{activityId}
        ORDER BY t.id, tm.id
        """)
List<ArchiveDetailRow> selectArchiveDetail(Long activityId);
```

`ExcelService`：`writeJoined` head `("员工编号","姓名","手机号","部门","组别","是否组长")`（isLeader → "是"/"否"）；`writeMissing` head `("员工编号","姓名","手机号","部门")`；`writeArchive` 明细页 head `("组名","员工编号","姓名","手机号","部门","组状态","驳回理由")`。`ExportService` 删除私有 `toJoinedRows`，直接把 mapper 结果传给 writeJoined/writeMissing。

- [ ] **Step 4: 跑绿**（此时 `mvn test -Dtest='*IT'` 后端应全绿）+ 提交 `feat(stats): 导出带员工编号与是否组长，已参加仅含通过组`

---

### Task 8: 前端类型 + useFormPage（员工编号流）

**Files:**
- Modify: `frontend/src/api/types.ts`（重写）
- Modify: `frontend/src/composables/useFormPage.ts`（重写）
- Test: `frontend/src/composables/useFormPage.test.ts`（重写）

**Interfaces:**
- Produces（types.ts）：

```ts
export interface Stats { total: number; registered: number; notRegistered: number; teamCount: number; pendingTeamCount: number }
export type TeamStatus = 'DRAFT' | 'PENDING' | 'CONFIRMED' | 'REJECTED'
export interface TeamMemberView { employeeId: string; name: string; phone: string; department: string; isLeader: boolean }
export interface TeamDetail { id: number; name: string; status: TeamStatus; rejectReason: string | null; capToken: string;
  overLimit: boolean; submittedAt: string | null; members: TeamMemberView[] }
export interface TeamAdminResponse { id: number; name: string; status: TeamStatus; size: number; overLimit: boolean;
  leaderName: string; rejectReason: string | null; submittedAt: string | null }
export interface FormPersonView { employeeId: string; name: string; phone: string; department: string;
  teamId: number | null; leader: boolean }
export interface FormTeamView { id: number; name: string; status: TeamStatus; rejectReason: string | null;
  overLimit: boolean; submittedAt: string | null; isLeader: boolean; members: TeamMemberView[] }
export interface ConflictView { employeeId: string; name: string; teamName: string }
export interface PersonRow { id: number; employeeId: string; name: string; phone: string; department: string;
  teamId: number | null; teamName: string | null; leaderName: string | null; isLeader: boolean; participated: boolean }
export type TeamEventView = 'CREATED' | 'SAVED' | 'SUBMITTED' | 'EDITED_BY_ADMIN' | 'CREATED_BY_ADMIN' | 'PASSED' | 'REJECTED'
// ActivityResponse / FormInfo / PageResult / OpLogView 不变
```

- Produces（useFormPage）：`info, me, meError, members, addEmployeeId, addPreview, addError, team, teamView, conflicts, editing, cap, load, lookupMe, startCreate, previewAdd, addMember, removeMember, createDraft, submit, save, verify, deleteTeam, startEdit, cancelEdit, reloadTeam`。

- [ ] **Step 1: 写失败测试（RED）**（重写 useFormPage.test.ts；mock 端点对齐 Task 4/后端）

用例清单（axios-mock-adapter + localStorage，afterEach 清 localStorage）：
1. `lookupMe` 按员工编号查询并回显（GET `/api/form/tk/person?employeeId=E001`）；
2. 已在组时查询返回 teamId → 自动 `GET /api/form/tk/my-team?employeeId=E001` 存 teamView；
3. `createDraft` POST 新 body（leaderEmployeeId + memberEmployeeIdList）→ 存 `{teamId, cap}` 到 localStorage `muster.team.tk`；
4. 409 冲突 → `conflicts` 填充（employeeId 字段）；
5. `submit` POST `/teams/{id}/submit?cap=` body `{leaderPhone}` 成功 → team 更新 PENDING；
6. `save` PUT `/teams/{id}?cap=` → team 更新（状态不变）；
7. `verify` POST `/teams/{id}/verify` → 返回 cap 存 localStorage；
8. `deleteTeam` DELETE → 清 localStorage + team/teamView/me 复位；
9. 旧 localStorage 结构（无 cap 字段）被安全忽略。

- [ ] **Step 2: 跑失败** `cd frontend && npm test -- useFormPage`。
- [ ] **Step 3: 最小实现** — composable 重写：状态如 Interfaces；`readStoredTeam/storeTeam` 保留（值仍 `{teamId, cap}`）；核心函数：

```ts
async function lookupMe(employeeId: string) {
  meError.value = ''
  const { data } = await api.get(`/api/form/${token}/person`, { params: { employeeId } })
  me.value = data
  if (data.teamId != null) {
    teamView.value = (await api.get(`/api/form/${token}/my-team`, { params: { employeeId } })).data
  } else {
    teamView.value = null
  }
}

async function createDraft() {
  conflicts.value = []
  try {
    const { data } = await api.post(`/api/form/${token}/teams`, {
      leaderEmployeeId: members.value[0]?.employeeId ?? '',
      memberEmployeeIdList: members.value.map(m => m.employeeId),
    })
    team.value = data
    storeTeam({ teamId: data.id, cap: data.capToken })
    cap.value = data.capToken
  } catch (e) { handleConflict(e) }
}

async function submit(leaderPhone: string) {
  const target = team.value ?? { id: teamView.value!.id }
  const query = cap.value ? `?cap=${cap.value}` : ''
  const { data } = await api.post(`/api/form/${token}/teams/${target.id}/submit${query}`, { leaderPhone })
  if (team.value) team.value = data
  if (teamView.value) teamView.value = { ...teamView.value, status: data.status, rejectReason: data.rejectReason }
}

async function save() {
  const target = team.value ?? { id: teamView.value!.id }
  const { data } = await api.put(`/api/form/${token}/teams/${target.id}?cap=${cap.value}`, {
    leaderEmployeeId: team.value?.members.find(m => m.isLeader)?.employeeId ?? members.value[0]?.employeeId ?? '',
    memberEmployeeIdList: members.value.map(m => m.employeeId),
  })
  if (team.value) team.value = data
}

async function verify(teamId: number, leaderPhone: string) {
  const { data } = await api.post(`/api/form/${token}/teams/${teamId}/verify`, { leaderPhone })
  cap.value = data.capToken
  storeTeam({ teamId, cap: data.capToken })
  team.value = data
}

async function deleteTeam() {
  const target = team.value ?? { id: teamView.value!.id }
  await api.delete(`/api/form/${token}/teams/${target.id}?cap=${cap.value}`)
  localStorage.removeItem(`muster.team.${token}`)
  team.value = null; teamView.value = null; me.value = null; members.value = []; editing.value = false; cap.value = ''
}
```

（`startCreate/previewAdd/addMember/removeMember/startEdit/cancelEdit/reloadTeam/load` 按既有实现形态改为员工编号；`previewAdd` 查 `person?employeeId=`，重复校验提示「该成员已在本组」。）

- [ ] **Step 4: 跑绿 + 提交** `feat(front): 表单页 composable 切换员工编号流`

---

### Task 9: FormView（保存/提交/手机验证/锁定态/删组）

**Files:**
- Modify: `frontend/src/views/FormView.vue`（重写）
- Test: `frontend/src/views/FormView.test.ts`（重写）

- [ ] **Step 1: 写失败测试（RED）** 用例：员工编号输入防抖 400ms 触发查询；未在组 → 成员列表（首行=本人标「组长」）+ 保存草稿/提交报名两按钮；保存只调 POST /teams（不调 submit）；提交弹手机号对话框（空/非 11 位报错，正确后先建组再 submit）；超上限警告非阻断；少于上限提示；已创建组显示状态标签（DRAFT 草稿/PENDING 审核中/CONFIRMED 已通过/REJECTED 已驳回）；PENDING 无按钮+提示「审核中，不能修改或删除」；CONFIRMED 只读；REJECTED 可改可再提交（理由展示）；无 cap 的 DRAFT/REJECTED 显示手机验证块 → verify；修改组员进入编辑（保存修改/提交报名/取消）；删除本组（DRAFT/REJECTED）确认后 DELETE。模板骨架：

```
van-nav-bar(title=活动名)
活动未开始/已结束 → 空态
无 me：van-field 员工编号（@update:model-value → 400ms 防抖 lookupMe）+ meError 提示
身份卡：me.employeeId name department（已在组时显示组名+状态）
无组：成员列表（组长行带 tag 组长，不可删）+ 添加成员（输入员工编号 → 预览卡 + 加入）
     + 数量提示（countDiff：>limit「已超出上限 N 人」警告；<limit「少于上限 N 人」灰色）
     + 按钮 [保存草稿] [提交报名]
编辑中：同上但按钮 [保存修改] [提交报名] [取消]
查看组（view = team ? {...team, isLeader:true} : teamView）：
     DRAFT/REJECTED 且可管理 → [修改组员] [删除本组]；无 cap → 手机验证块
     PENDING → van-notice「审核中，不能修改或删除」
     CONFIRMED → 只读列表
手机对话框：van-field 组长手机号 + 确认
```

- [ ] **Step 2: 跑失败** `npm test -- FormView`。
- [ ] **Step 3: 最小实现** — 重写组件（Vant；组合 useFormPage 全部状态；`onSubmitClick`：无 cap 或未验证过 → showConfirmDialog 式手机弹窗；`doSubmit(phone)`：无 team 先 createDraft（409 冲突提前 return），再 submit(phone)，成功 toast「已提交，等待审核」；有 team 编辑态则先 save 再 submit）。保留 `defineExpose` 供测试。
- [ ] **Step 4: 跑绿 + 提交** `feat(front): 表单页保存/提交/手机验证/锁定态`

---

### Task 10: RosterView（四列 + 编辑 + 清空 + 组别展示）

**Files:**
- Modify: `frontend/src/views/RosterView.vue`
- Test: `frontend/src/views/RosterView.test.ts`（重写）

- [ ] **Step 1: 写失败测试（RED）** 用例：列表渲染 员工编号/姓名/手机号/部门/组别/组长/状态 列（participated → tag 已参加/未参加）；搜索占位「员工编号 / 姓名 / 手机号 / 部门」；添加对话框 4 字段 + employeeId 必填校验；编辑按钮 → 预填对话框 → PUT `/api/roster/{id}`；一键清空按钮：有组（409）→ 显示后端 message；无组 → 双重确认（两次 ElMessageBox.confirm）→ DELETE `/api/roster` → 「已清空 N 人」；模板下载不变。mock：`GET /api/roster/search` 返回新 PersonRow 形状。
- [ ] **Step 2: 跑失败**。
- [ ] **Step 3: 最小实现** — 列：员工编号(120)/姓名(100)/手机号(130)/部门/组别(90, `teamName ?? '—'`)/组长(100, leaderName)/状态(90, tag)/操作(编辑+删除)；addForm/editForm 均 4 字段；`clearRoster`：`ElMessageBox.confirm`（warning）→ 确认后再 `confirm('', '再次确认：清空后不可恢复！', { type: 'error' })` → DELETE；409 → 直接 error 提示 message。
- [ ] **Step 4: 跑绿 + 提交** `feat(front): 花名册四列展示、编辑与一键清空`

---

### Task 11: TeamView（建组/删组/人员搜索/组长列）

**Files:**
- Modify: `frontend/src/views/TeamView.vue`（重写）
- Test: `frontend/src/views/TeamView.test.ts`（重写）

- [ ] **Step 1: 写失败测试（RED）** 用例：列表含 组长 列与 DRAFT 状态；筛选含 DRAFT；行操作 通过/驳回/详情/删除（删除 → confirm「删除 {name}？组员将回到未报名状态」→ DELETE `/api/teams/{id}`）；工具栏 新建组 → 建组对话框（搜索员工编号/姓名/部门/手机号 → 结果表点选 → 已选表 + 组长单选 radio）→ POST `/api/teams` 成功刷新；人员搜索输入 → 搜索人员 → 结果对话框（所在组 + 查看组按钮 → 打开详情 drawer）；详情 drawer 成员表含 员工编号 列与 组长 tag；管理员改组（PUT `/api/teams/{id}/members` 新 body）。mock 对齐新 DTO（TeamAdminResponse.leaderName 等）。
- [ ] **Step 2: 跑失败**。
- [ ] **Step 3: 最小实现** — STATUS_TEXT 补 `DRAFT: '草稿'`；EVENT_TEXT 补 `CREATED: '创建组' / SAVED: '组长保存' / CREATED_BY_ADMIN: '管理员创建'`；`openDetail(id)` 抽取复用（列表详情 + 搜索跳转共用）。
- [ ] **Step 4: 跑绿 + 提交** `feat(front): 组管理支持建组/删组/人员搜索/组长展示`

---

### Task 12: HomeView（4 卡片）

**Files:**
- Modify: `frontend/src/views/HomeView.vue`
- Test: `frontend/src/views/HomeView.test.ts`

- [ ] **Step 1: 写失败测试（RED）** — mock stats `{total:10, registered:6, notRegistered:4, teamCount:2, pendingTeamCount:1}`；断言四卡片文案与数值：已报名 6 / 未报名 4 / 分组数 2 / 待审核 1；WS 推送帧（新字段）更新数值。卡片 `span 6`。
- [ ] **Step 2: 跑失败** → **Step 3: 改实现**（Stats 字段名 + 卡片）。
- [ ] **Step 4: 跑绿 + 提交** `feat(front): 首页统计卡片改为已报名/未报名/分组数/待审核`

---

### Task 13: 收尾

- [ ] **Step 1:** Glob 确认仓库内无旧三列示例花名册文件需重生成（已确认 `**/*roster*.xlsx` 无文件；若用户另有外部样表，提醒用新模板重导）。
- [ ] **Step 2:** 更新 `CLAUDE.md` 业务铁律：规则 4 改为新状态机（DRAFT/保存≠提交/首次提交验证手机号/驳回保留理由）；规则 3 改为员工编号判定一人一组（+手机号/员工编号活动内唯一）；规则 8 改为完整员工编号精确匹配；补充新端点（verify/my-team、管理员建删组、roster PUT/DELETE）与「已报名/已参加」口径。
- [ ] **Step 3:** 全量验证：

```bash
cd backend && mvn test
cd frontend && npm test && npm run typecheck && npm run build
```

- [ ] **Step 4:** 提交 `docs: 更新 CLAUDE.md 业务铁律（员工编号与新组生命周期）`。
- [ ] **Step 5:** 提醒用户：远程升级需 `docker compose down -v && git pull && docker compose up -d --build`（旧数据作废，重新导入四列花名册）。

## Self-Review

1. **Spec 覆盖**：四列花名册(T1)、编辑+清空(T2)、组别/组长/状态列+员工编号搜索(T3)、员工编号报名+保存/提交分离+首提验证手机+锁定+删组(T4)、审核/驳回回落+管理员改组(T5)、统计四卡片口径(T6)、导出列(T7)、表单页(T8/T9)、花名册页(T10)、组管理页(T11)、首页(T12)。管理员搜索人员定位组(T11)。已参加/未参加两态(T3 enriched participated + T10)。无迁移(T13 提醒)。
2. **占位符扫描**：无 TBD/TODO；所有步骤含代码或精确行为清单。
3. **类型一致性**：`TeamMemberRequest(leaderEmployeeId, memberEmployeeIdList)` 贯穿 T4/T8/T11；`PersonResponse` 十字段贯穿 T1/T3/T8/T10；`JoinedRow/MissingRow/ArchiveDetailRow` 顺序与 ExcelService/ExportMapper 一致（员工编号,姓名,手机号,部门,…）。



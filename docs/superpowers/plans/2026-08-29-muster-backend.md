# Muster·点将台 — 后端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现公司活动分组报名系统的全部后端业务（严格单活动、花名册 Excel、扫码报名、人工审核、实时统计、xlsx 导出），REST API 供前端消费。（严格单活动、花名册 Excel、扫码报名、人工审核、实时统计、xlsx 导出），REST API 供前端消费。

**Architecture:** 单体 Spring Boot 应用。业务规则集中在 Service 层，Controller 薄；管理员 API 与组长 API（`/api/form/{token}/**`，免登录）分离；统计变更通过 Spring 事件广播到 WebSocket。唯一性并发冲突交给 MySQL 唯一索引兜底。

**Tech Stack:** Java 21 · Spring Boot 3.5.4 · MyBatis-Plus 3.5.7 · MySQL 8 · spring-boot-starter-security + jjwt 0.12.6 · spring-boot-starter-websocket · EasyExcel 4.0.3 · JUnit 5 + Mockito + Testcontainers(MySQL 8) · Maven 3.9

## Global Constraints（来自 grilling 共识，逐条不可违背）

- 严格单活动：系统内同时至多 1 条 `activity` 记录；建新活动前旧活动必须已导出（`exported=true`）且被删除
- 组提交即算已参加；驳回不使统计回落；组一经提交永久存在（只改不改删）
- 审核三态 `PENDING / CONFIRMED / REJECTED`，仅为标记，不影响统计；驳回必须填理由
- 手机号 11 位（`^1[3-9]\d{9}$`），活动内唯一键；Excel 导入重复手机号整批报错并指出行号，不静默去重
- 名单外人员不能报名（`PERSON_NOT_FOUND`）；后台先加入花名册才能被搜到
- 组名自动生成 `组1、组2…`；每组上限为活动统一数字，允许超出（不报错，仅标记 overLimit）
- 组长编辑组员后组回到 `PENDING` 并清空驳回理由；管理员编辑后组置为 `CONFIRMED`
- 所有编辑（组长/管理员）仅限活动窗口 `ACTIVE`；审核（通过/驳回）不受窗口限制
- 窗口判定：`manuallyEnded || now>end → ENDED`；`now<start → NOT_STARTED`；否则 `ACTIVE`
- 管理员登录才可访问 `/api/**`（除 `/api/auth/login`、`/api/form/**`、`/ws/**`、`/actuator/health`）
- 时区 Asia/Shanghai；提交时间为 `DATETIME`
- 前端表单页路由约定：`/m/{qrToken}`；后端只返回完整 URL，不生成二维码图片
- 提交消息用中文（面向管理员/组长的文案）

---

### Task 0: 项目脚手架与集成测试基座

**Files:**
- Create: `.gitignore`, `README.md`, `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`, `backend/src/main/resources/db/schema.sql`
- Create: `backend/src/main/java/com/muster/MusterApplication.java`
- Create: `backend/src/main/java/com/muster/config/{ClockConfig,MybatisPlusConfig,SecurityConfig,AdminSeeder}.java`
- Create: `backend/src/test/java/com/muster/IntegrationTestBase.java`, `backend/src/test/java/com/muster/smoke/SmokeTest.java`

**Interfaces (Produces):**
- `IntegrationTestBase`：所有集成测试的父类（单例 Testcontainers MySQL + 每测试清库重置 admin/admin123 + `login()` 返回 JWT + `loginAs()`、`jsonPost/jsonGet/jsonPut/jsonDelete` 助手）
- 表结构：`admin_user / activity / person / team / team_member`

- [ ] **Step 1: git init 与 .gitignore**

```bash
cd /e/college_information/manager && git init -b main
```

`.gitignore`：

```gitignore
target/
node_modules/
dist/
.idea/
*.iml
.vscode/
logs/
*.log
.env
deploy/.env
.flattened-pom.xml
```

- [ ] **Step 2: pom.xml（依赖一次到位）**

`backend/pom.xml`：parent `spring-boot-starter-parent:3.5.4`；`<java.version>21</java.version>`；依赖：`spring-boot-starter-web`、`spring-boot-starter-security`、`spring-boot-starter-validation`、`spring-boot-starter-websocket`、`spring-boot-starter-actuator`、`com.baomidou:mybatis-plus-spring-boot3-starter:3.5.7`、`com.mysql:mysql-connector-j`（runtime）、`io.jsonwebtoken:jjwt-api:0.12.6` + `jjwt-impl`/`jjwt-jackson`（runtime）、`com.alibaba:easyexcel:4.0.3`、`org.projectlombok:lombok`（provided）、test：`spring-boot-starter-test`、`org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`（BOM 1.20.6，import 进 dependencyManagement）。`spring-boot-maven-plugin`。

- [ ] **Step 3: schema.sql + application.yml**

`db/schema.sql`（幂等，`CREATE TABLE IF NOT EXISTS`）：

```sql
CREATE TABLE IF NOT EXISTS admin_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  start_time DATETIME NOT NULL,
  end_time DATETIME NOT NULL,
  group_size_limit INT NOT NULL,
  qr_token VARCHAR(32) NOT NULL UNIQUE,
  exported TINYINT(1) NOT NULL DEFAULT 0,
  manually_ended TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS person (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  name VARCHAR(50) NOT NULL,
  phone VARCHAR(11) NOT NULL,
  department VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_activity_phone (activity_id, phone),
  KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS team (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  activity_id BIGINT NOT NULL,
  name VARCHAR(20) NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
  reject_reason VARCHAR(200) NULL,
  submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_activity_name (activity_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS team_member (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  team_id BIGINT NOT NULL,
  person_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_person (person_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`application.yml` 要点：数据源 `jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:muster}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai`，用户 `${DB_USER:root}` 密码 `${DB_PASSWORD:root}`；`spring.sql.init.mode: always`；multipart 上限 10MB；`server.port: 8080`；`muster.jwt-secret: ${JWT_SECRET:muster-dev-secret-0123456789abcdef0123456789abcdef}`；`muster.form-base-url: ${FORM_BASE_URL:http://localhost:5173}`；mybatis-plus 驼峰映射 + `id-type: auto`；management 暴露 health。

- [ ] **Step 4: 主类与配置类**

`MusterApplication`（@SpringBootApplication）。`ClockConfig`：`@Bean Clock clock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }`。`MybatisPlusConfig`：`MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)`。`SecurityConfig`：最终形态一次到位——csrf 关、无状态、permitAll(`/api/auth/login`,`/api/form/**`,`/ws/**`,`/actuator/health`)、其余 authenticated、401 返回 `{"code":"UNAUTHORIZED","message":"未登录"}`、`PasswordEncoder` 为 BCrypt。`AdminSeeder`（CommandLineRunner）：`admin_user` 为空则插入 `admin` / `encoder.encode("admin123")`。

- [ ] **Step 5: 集成测试基座与冒烟测试**

`IntegrationTestBase`：静态单例 `MySQLContainer<>("mysql:8.0.36")` + `@DynamicPropertySource` 覆盖数据源；`@BeforeEach` 依次 `DELETE FROM team_member/team/person/activity/admin_user` 并重插 admin；`login()` 用 TestRestTemplate POST `/api/auth/login` 存 token。`SmokeTest extends IntegrationTestBase`：`GET /actuator/health → 200`。

- [ ] **Step 6: 首次构建与测试**

Run: `cd backend && mvn -q test`
Expected: 1 test, 0 failures（首次会拉取大量依赖与 mysql:8.0.36 镜像）

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "chore: scaffold spring boot backend with schema and testcontainers base

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 1: 统一错误结构 + JWT 认证

**Files:**
- Create: `common/{ErrorCode,ApiException,ApiError}.java`, `config/GlobalExceptionHandler.java`, `config/JwtAuthFilter.java`, `auth/{JwtService,AuthService,AuthController,AdminUser,AdminUserMapper}.java`, `auth/dto/{LoginRequest,LoginResponse,ChangePasswordRequest}.java`
- Test: `auth/JwtServiceTest.java`（纯单元）， `auth/AuthFlowIT.java`（集成）

**Interfaces (Produces):**
- `ErrorCode` 枚举：`VALIDATION(400) UNAUTHORIZED(401) FORBIDDEN(403) NOT_FOUND(404) CONFLICT(409) ARCHIVE_REQUIRED(409) WINDOW_CLOSED(409) PERSON_NOT_FOUND(404) PHONE_DUPLICATE(400)`
- `ApiException(ErrorCode, String message)`；错误响应体 `{"code":"...","message":"..."}`（GlobalExceptionHandler 统一产出）
- `JwtService.issue(Long adminId, String username): String` / `parseUsername(String token): Optional<String>`（7 天有效）
- REST：`POST /api/auth/login` → `{"token","username"}`；`GET /api/auth/me` → `{"username"}`；`PUT /api/auth/password {oldPassword,newPassword}`

- [ ] **Step 1: 单元测试（红）** `JwtServiceTest`：签发后能解析出 username；篡改 token 返回 empty；过期 token（构造时用 -1s TTL 的测试构造器）返回 empty。

- [ ] **Step 2: 运行确认失败** Run: `mvn -q test -Dtest=JwtServiceTest` → 编译失败（类不存在）

- [ ] **Step 3: 实现 JwtService（绿）** jjwt：`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`（secret ≥32 字节）；解析 catch `JwtException|IllegalArgumentException` 返回 empty。

- [ ] **Step 4: 单元测试通过** Run: `mvn -q test -Dtest=JwtServiceTest` → PASS

- [ ] **Step 5: 集成测试（红）** `AuthFlowIT`：未带 token 访问 `/api/activity` → 401 `UNAUTHORIZED`；`admin/admin123` 登录成功得 token；错误密码 → 401；`/api/auth/me` 返回 admin；改密后旧密码登录失败、新密码成功。

- [ ] **Step 6: 实现 ErrorCode/ApiException/GlobalExceptionHandler/JwtAuthFilter/AuthController/AuthService（绿）** JwtAuthFilter：`Authorization: Bearer <t>` → `parseUsername` → 注入 `UsernamePasswordAuthenticationToken(username, null, List.of())`；AuthService：encoder.matches 校验；改密校验旧密码，`newPassword` ≥6 位否则 VALIDATION。

- [ ] **Step 7: 集成测试通过 + Commit** Run: `mvn -q test` → 全绿；`git commit -m "feat: jwt auth with unified error envelope ..."`

---

### Task 2: 活动生命周期（严格单活动）

**Files:**
- Create: `activity/{Activity,ActivityMapper,ActivityService,ActivityController}.java`, `activity/dto/{ActivityCreateRequest,ActivityUpdateRequest,ActivityResponse}.java`, `common/PageResult.java`
- Test: `activity/ActivityFlowIT.java`

**Interfaces (Produces):**
- `ActivityService.current(): Activity`（null 表示无活动）、`requireCurrent(): Activity`、`create(req): Activity`、`update(req): void`、`end(): void`、`delete(): void`、`formUrl(Activity): String`
- REST：`GET /api/activity` → 活动或 `{"data":null}` 风格（直接返回 null 序列化为 null）；`POST /api/activity {name,startTime,endTime,groupSizeLimit}`；`PUT /api/activity {name?,startTime?,endTime?,groupSizeLimit?}`；`POST /api/activity/end`；`DELETE /api/activity`；`GET /api/activity/form-url` → `{"url":"..."}`
- 校验规则：start<end 否则 VALIDATION；已存在活动时 create：`!exported → ARCHIVE_REQUIRED("请先导出归档包")`，`exported → CONFLICT("请先删除旧活动")`；times 仅在 `NOT_STARTED` 可改；`end()` 仅 ACTIVE 可调；`delete()` 要求 `exported=true`，按 `team_member→team→person→activity` 顺序删；`qr_token = UUID 32 位无横线`

- [ ] **Step 1: 集成测试（红）** 用例：①create→GET 返回一致（name/limit/times，exported=false）②已有活动再 create → 409 ARCHIVE_REQUIRED ③jdbc 置 exported=1 后 create → 409 CONFLICT ④NOT_STARTED 时 PUT 改 times 成功 ⑤POST /end 后 GET 正常 ⑥未导出 DELETE → 409；置 exported 后 DELETE → 200，且 GET /api/activity 为 null ⑦start≥end → 400 ⑧无 token → 401。

- [ ] **Step 2: 运行确认失败** Run: `mvn -q test -Dtest=ActivityFlowIT` → 404（无路由）

- [ ] **Step 3: 实现实体/Mapper/Service/Controller（绿）** window 判定委托 `WindowResolver`（Task 4 引入，本任务内联私有方法，Task 4 抽出复用）。

- [ ] **Step 4: 测试通过 + Commit** Run: `mvn -q test` → 全绿；commit `feat: strict single-activity lifecycle`

---

### Task 3: 花名册（Excel 导入 / 模糊搜索 / 增删）

**Files:**
- Create: `roster/{Person,PersonMapper,RosterService,RosterController,ExcelService}.java`, `roster/dto/{PersonCreateRequest,PersonResponse,JoinedRow,MissingRow,ArchiveDetailRow}.java`, `common/PhoneValidator.java`
- Test: `roster/PhoneValidatorTest.java`, `roster/ExcelServiceTest.java`（纯单元）, `roster/RosterFlowIT.java`

**Interfaces (Produces):**
- `PhoneValidator.valid(String): boolean`（`^1[3-9]\d{9}$`）
- `ExcelService.readPersons(InputStream): List<PersonRow>`（`PersonRow{int rowNo; String name; String phone; String department}`，表头 `姓名|手机号|部门`）；`writeTemplate(): byte[]`；`writeJoined(List<JoinedRow>): byte[]`；`writeMissing(List<MissingRow>): byte[]`（列：`姓名,手机号,部门` + JoinedRow 增 `组名,提交时间`）
- REST：`POST /api/roster/import`（multipart `file`）→ `{"imported":n}`；`GET /api/roster?keyword=&page=1&size=20` → `{total,records}`（keyword 对 name/phone/department LIKE）；`POST /api/roster`；`DELETE /api/roster/{id}`；`GET /api/roster/template` → xlsx
- 校验：行字段全空跳过；name/department 空白或 phone 非法 → VALIDATION 带行号；文件内重复手机号 → PHONE_DUPLICATE 带行号清单；与库中重复 → PHONE_DUPLICATE；导入整体事务（有错全不进库）；删除的人若在组内，先删 `team_member` 再删人

- [ ] **Step 1: 单元测试（红）** PhoneValidator：`13812345678` true；`238...`/`1381234567`(10位)/`null` false。ExcelService：构造行→writeTemplate→readPersons 空列表；writeJoined 后 read 回断言列值；带非法行的字节流 read 断言 rowNo 正确（表头占第 1 行，数据从第 2 行计）。

- [ ] **Step 2: 运行确认失败 → Step 3: 实现 → Step 4: 通过**（EasyExcel `doReadSync`，`head(PersonRow.class)`，rowNo 用 `ListUtils`/读时计数器 +1 偏移表头）

- [ ] **Step 5: 集成测试（红）** 前置：create 活动。①POST template 内容回传 import → imported=3，GET 列表 3 条 ②keyword=手机号片段/姓名片段/部门片段 模糊命中 ③含重复行的文件 → 400 PHONE_DUPLICATE 且库里 0 条（事务回滚）④含库内已有手机号 → 400 ⑤POST /api/roster 添加；重复手机号 → 400 ⑥jdbc 造 team+team_member 后 DELETE 该人 → team_member 无此行 ⑦GET /api/roster/template → 200 xlsx content-type。

- [ ] **Step 6: 实现并跑绿 + Commit** commit `feat: roster excel import, fuzzy search, add/delete`

---

### Task 4: 报名表单与组长提交（核心）

**Files:**
- Create: `team/{Team,TeamMember,TeamMapper,TeamMemberMapper,TeamService,FormController,WindowResolver,StatsChangedEvent}.java`, `team/dto/{FormInfo,TeamSubmitRequest,TeamDetail,TeamMemberView,ConflictView}.java`
- Test: `team/WindowResolverTest.java`（纯单元）, `team/TeamSubmitFlowIT.java`

**Interfaces (Produces):**
- `WindowResolver.resolve(start, end, manuallyEnded, now): WindowStatus`（`NOT_STARTED/ACTIVE/ENDED`）
- REST（免登录）：`GET /api/form/{token}` → `{name,startTime,endTime,groupSizeLimit,windowStatus}`；`POST /api/form/{token}/teams {memberPhoneList:[...]}` → TeamDetail；`GET /api/form/{token}/teams/{teamId}` → TeamDetail
- `TeamService.submit(token, req)` 规则：活动存在否则 NOT_FOUND；窗口 ACTIVE 否则 WINDOW_CLOSED；号码去重、逐个校验格式（VALIDATION）与在册（PERSON_NOT_FOUND 带 phone）；与其它已提交组冲突 → CONFLICT 带 `ConflictView{phone,name,teamName}` 列表；人数>上限不报错（前端负责警告）；组名 `组{count+1}`（`uk_activity_name` 冲突重试至多 3 次）；`uk_person` 冲突 → CONFLICT("有人刚被其他组报走");发 `StatsChangedEvent`
- `TeamDetail{id,name,status,rejectReason,overLimit,submittedAt,members:[{name,phone,department}]}`

- [ ] **Step 1: 单元测试（红）** WindowResolver 6 分支：结束前/中/后、手动结束优先、边界 now==start、now==end。

- [ ] **Step 2: 实现 WindowResolver（绿）→ Step 3: 单测通过**

- [ ] **Step 4: 集成测试（红）** 前置：活动 ACTIVE（startTime=now-1h, endTime=now+1h）+ 5 人花名册。①正确 token GET form 信息 ②错 token → 404 ③submit 3 人 → TeamDetail：name=组1、status=PENDING、members 3 条 ④重复提交同一人 → 409 冲突含组名"组1" ⑤未在册手机号 → 404 PERSON_NOT_FOUND ⑥非法格式 → 400 ⑦窗口未开始/已结束（另建活动调时间）→ 409 WINDOW_CLOSED ⑧空 memberPhoneList → 400 ⑨超上限提交成功且 overLimit=true ⑩提交后 GET my-team 一致。

- [ ] **Step 5: 实现 TeamService/FormController（绿）→ Step 6: 全绿 + Commit** commit `feat: qr form info and leader team submission`

---

### Task 5: 组编辑与人工审核

**Files:**
- Modify: `team/TeamService.java`, `team/FormController.java`
- Create: `team/TeamController.java`, `team/dto/{TeamAdminResponse,ReviewRequest,TeamPageQuery}.java`
- Test: `team/TeamReviewFlowIT.java`

**Interfaces (Produces):**
- 组长编辑：`PUT /api/form/{token}/teams/{teamId} {memberPhoneList}` → 窗口必须 ACTIVE；冲突检查**排除本组**；整体替换成员；status→PENDING；rejectReason→null；发事件
- 管理员：`GET /api/teams?status=&page=1&size=20` → `{total,records:[{id,name,status,size,overLimit,rejectReason,submittedAt}]}`；`GET /api/teams/{id}` → TeamDetail；`PUT /api/teams/{id}/review {action:"PASS"|"REJECT",reason?}`（REJECT 必须 reason 非空否则 VALIDATION；PASS→CONFIRMED 清 reason；不受窗口限制）；`PUT /api/teams/{id}/members {memberPhoneList}`（窗口 ACTIVE；同组长编辑但 status→CONFIRMED）

- [ ] **Step 1: 集成测试（红）** 前置：ACTIVE 活动 + 8 人 + 组1(3人PENDING)。①组长编辑换 1 人 → 成员生效、status 仍 PENDING、他人组员未被误伤 ②编辑含在其它组的人 → 409 ③编辑含本组已有的人（保留）→ 成功 ④REJECT 无 reason → 400；REJECT 带 reason → status=REJECTED ⑤PASS → CONFIRMED ⑥REJECTED 后组长重交（编辑）→ status 回 PENDING 且 reason 清空 ⑦管理员编辑 → status=CONFIRMED ⑧窗口结束后编辑（组长/管理员）→ 409，审核仍可 ⑨列表分页 status=REJECTED 过滤正确、overLimit 标记正确 ⑩统计联动：编辑把人移出组后 joined 减少（GET /api/stats 若已有——若本任务先于 Task 6，用 jdbc 计数断言替代）。

- [ ] **Step 2: 实现并跑绿 + Commit** commit `feat: team edit and manual review`

---

### Task 6: 实时统计 + WebSocket + 导出

**Files:**
- Create: `stats/{StatsService,StatsController,StatsWebSocketHandler,StatsWebSocketConfig,ExportService,ExportMapper}.java`, `stats/dto/StatsDto.java`
- Modify: `activity/ActivityController.java`（+`POST /api/activity/export/archive`）
- Test: `stats/StatsFlowIT.java`（含 JDK HttpClient WebSocket 客户端）

**Interfaces (Produces):**
- `GET /api/stats` → `{total,joined,notJoined,teamCount,pendingTeamCount}`（无活动全 0；joined=team_member 行数；teamCount=组数；pendingTeamCount=PENDING 组数）
- WebSocket：`/ws/stats?token=<jwt>` 握手校验 token，失败拒绝连接；连接即推当前统计；`StatsChangedEvent` → 重算并广播同一 JSON
- `GET /api/stats/export?type=JOINED|MISSING` → xlsx（JOINED 列：姓名,手机号,部门,组名,提交时间；MISSING：姓名,手机号,部门；文件名 `已参加.xlsx/未参加.xlsx` URL 编码）
- `POST /api/activity/export/archive` → 三 sheet xlsx（已参加/未参加/分组明细[组名,组员姓名,手机号,部门,组状态,驳回理由]），并置 `exported=true`
- 自定义 SQL（ExportMapper）：JOINED = team_member join person join team（按 activity）；MISSING = person 反连接 team_member；ARCHIVE 明细同理

- [ ] **Step 1: 集成测试（红）** 前置：活动 + 5 人 + 组1(2人 PENDING) + 组2(1人 CONFIRMED)。①GET stats → total=5, joined=3, notJoined=2, teamCount=2, pendingTeamCount=1 ②WS 连接 `/ws/stats?token=<管理员token>` 收到首帧与 stats 相等；调 review 后 5s 内收到新帧（pendingTeamCount 变化）③坏 token WS 握手失败 ④export JOINED → 读回 workbook 行数=3 且首行组名正确 ⑤export MISSING → 2 行 ⑥archive → 3 个 sheet 且 exported 变 true ⑦无活动时 stats 全 0。

- [ ] **Step 2: 实现（绿）→ Step 3: 全绿 + Commit** commit `feat: realtime stats, websocket push, excel exports`

---

### Task 7: 自检、文档与推送

**Files:**
- Create: `README.md`（完善）、`CLAUDE.md`、`docs/api.md`（接口清单）
- Modify: 评审发现的问题文件

- [ ] **Step 1: 全量测试** Run: `cd backend && mvn test` → 全绿
- [ ] **Step 2: 本机冒烟** docker 起 MySQL（或本地 3306 库 `muster`）→ `mvn spring-boot:run` → curl 走通：登录→建活动→导模板→导入→form 提交→stats→导出
- [ ] **Step 3: 代码评审** 运行 `/code-review`，修复确认的问题后重跑全量测试
- [ ] **Step 4: 文档** README（本地运行/接口概览/后续 Docker 部署占位）、CLAUDE.md（项目结构与业务铁律）
- [ ] **Step 5: 推送** `git remote add origin https://github.com/boy6666/Muster.git && git push -u origin main`（需 gh/凭据已登录；未登录则最后统一推）

---

## Self-Review 结论

- 规格覆盖：共识 15 条中，1(仅管理员登录)→Task1；2/3/4(单活动/强制导出/窗口)→Task2+6(archive)；5/6/7(Excel/唯一键/模糊搜索/增删)→Task3；8/9/10(组名/超限/手机号精确/一人一组)→Task4；11(提交即参加/驳回不回落/改组重交)→Task4+5；12/13(实时统计/导出)→Task6；14/15(技术/部署)→Global Constraints+后续部署计划。无缺口。
- 类型一致性：`WindowStatus`/`TeamDetail`/`StatsDto`/`PersonRow`/`JoinedRow`/`MissingRow`/`ArchiveDetailRow` 各任务引用一致；`WindowResolver` 在 Task4 定义（Task2 内联私有方法，Task4 重构复用——已在 Task2 注明）。
- 占位符扫描：无 TBD/“类似 Task N”；代码块均为实际签名与规则。

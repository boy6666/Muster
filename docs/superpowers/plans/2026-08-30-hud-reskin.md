# HUD 全站换肤(自绘组件)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `muster-hud-prototype` 原型把 Muster 前端(登录/后台/移动端)换成 HUD 玻璃拟态浅色风,彻底移除 Element Plus 与 Vant,组件全自绘;首页新增 倒计时横幅/参加率环/组人数分布/实时事件流(含两个后端小改造)。

**Architecture:** 后端加 `GET /api/stats/distribution` 与 `/ws/stats` 帧扩展 `recentEvents`;前端建 `styles/tokens.css` 设计令牌 + `components/ui/` 自绘原语(UiModal/UiDrawer/UiPagination/toast/confirm),7 个视图逐个重写模板(逻辑与 API 调用不变),最后删依赖。

**Tech Stack:** Spring Boot 3.5 / MyBatis-Plus(Testcontainers IT);Vue 3 + vitest + axios-mock-adapter;纯手写 CSS(无 UI 库)。

## Global Constraints

- 视觉唯一来源:`docs` 之外的 `E:\college_information\ocr\open-design\.od\projects\muster-hud-prototype\index.html`(下称「原型」);色板/组件类名以原型 CSS 为准。
- **数据口径不变**:4 统计卡片、4 列花名册、组长列、DRAFT 态、员工编号建组、保存/提交分离——原型里过时的业务字段一律不搬。
- 登录页不放真实数字(不加公开端点);事件流只到组级粒度(team_event)。
- 分布口径与 teamCount 一致(含 DRAFT)。
- 63 个前端测试逐视图重写(TDD 先红后绿);composable/api 层测试不动。
- 提交信息 conventional commits + `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`;每任务一提交。
- 后端集成测试继承 `IntegrationTestBase`;WS 测试参考 `StatsFlowIT` 既有模式(JDK HttpClient WS 客户端 `onText` 后必须 `request(1)`)。

---

### Task 1: 后端 GET /api/stats/distribution(组人数分布)

**Files:**
- Create: `backend/src/main/java/com/muster/stats/dto/SizeBucketDto.java`
- Modify: `backend/src/main/java/com/muster/stats/StatsService.java`、`StatsController.java`
- Test: `backend/src/test/java/com/muster/stats/DistributionIT.java`(新建,继承 IntegrationTestBase)

**Interfaces:**
- Produces: `GET /api/stats/distribution` → `[{size:long, count:long, overLimit:boolean}]` 按 size 升序;无活动 → `[]`;overLimit = size > groupSizeLimit;含 DRAFT 组。

- [ ] **Step 1: RED — 写集成测试**

```java
// DistributionIT.java 骨架:参考 TeamSubmitFlowIT 的建活动/导花名册/建组方式
// 场景:活动 limit=5;建 5 个组,人数 3,3,4,5,6(用 PUT /api/teams/{id}/members 或表单提交流程造)
// GET /api/stats/distribution → 断言 JSON 数组:
//   [{size:3,count:2,overLimit:false},{size:4,count:1,...},{size:5,...},{size:6,count:1,overLimit:true}]
// 另一用例:无活动时返回 []
```

- [ ] **Step 2:** `cd /e/college_information/manager/backend && mvn test -Dtest=DistributionIT` → 404 失败。
- [ ] **Step 3: GREEN** — `SizeBucketDto(long size, long count, boolean overLimit)`;StatsService.distribution():查当前活动 teams(含 DRAFT)→ teamMemberMapper 按 teamId 查成员 → 按 members.size() 分组计数 → overLimit 对照 activity.groupSizeLimit → 按 size 升序。Controller 加 `@GetMapping("/distribution")`。
- [ ] **Step 4:** `mvn test -Dtest=DistributionIT` → PASS;`mvn test` 全绿。
- [ ] **Step 5:** Commit `feat(back): 组人数分布接口 GET /api/stats/distribution`。

### Task 2: 后端 /ws/stats 帧扩展 recentEvents

**Files:**
- Create: `backend/src/main/java/com/muster/stats/dto/StatsFrameDto.java`、`RecentEventDto.java`
- Modify: `backend/src/main/java/com/muster/stats/StatsWebSocketHandler.java`
- Test: `backend/src/test/java/com/muster/stats/StatsFlowIT.java`(追加用例)

**Interfaces:**
- Produces: WS 帧改为扁平 `{total, registered, notRegistered, teamCount, pendingTeamCount, recentEvents:[{teamId, teamName, type, detail, createdAt}]}`;recentEvents 为当前活动最近 20 条 team_event(id 倒序),teamName 由 TeamMapper 批量查;无活动 → 空数组。

- [ ] **Step 1: RED — StatsFlowIT 追加**:WS 连接后(参考既有用例的 HttpClient WS 模式)提交一个组,收到的帧 JSON 断言含 `recentEvents`,首条 `type=SUBMITTED` 且 `teamName` 正确。
- [ ] **Step 2:** 跑该 IT → 失败(帧无 recentEvents 字段)。
- [ ] **Step 3: GREEN** — `RecentEventDto(long teamId, String teamName, String type, String detail, LocalDateTime createdAt)`;`StatsFrameDto(total, registered, notRegistered, teamCount, pendingTeamCount, List<RecentEventDto> recentEvents)`;Handler 私有方法 `buildFrame()`:StatsService.current() + TeamEventMapper 查 `activity_id=? order by id desc limit 20` → collect teamIds → TeamMapper.selectBatchIds 映射组名(缺组显示「已删除组」)。
- [ ] **Step 4:** IT PASS + `mvn test` 全绿。
- [ ] **Step 5:** Commit `feat(back): /ws/stats 帧附带最近组事件 recentEvents`。

### Task 3: 前端设计令牌 + 自绘 UI 原语

**Files:**
- Create: `frontend/src/styles/tokens.css`、`frontend/src/components/ui/UiModal.vue`、`UiDrawer.vue`、`UiPagination.vue`、`toast.ts`、`confirm.ts`、`ToastHost.vue`
- Modify: `frontend/src/main.ts`(只加 `import './styles/tokens.css'`,EP/Vant 引入留到 Task 11 删)
- Test: `frontend/src/components/ui/ui.test.ts`

**Interfaces:**
- Produces(后续所有视图依赖):
  - CSS 类(全局):`.panel` `.corner` `.tbl` `.btn`( `.primary/.danger/.ghost/.sm` )`.tag`( `.ok/.warn/.err/.info/.dim` )`.chip`( `.active` )`.input` `.link`( `.ok/.err` )`.mono` `.pagi` `.alert`( `.warn` )`.tl/.tl-item` `.bg-fx`
  - `toast.success(msg) / toast.error(msg) / toast.warning(msg)`(App.vue 挂 `<ToastHost/>`)
  - `confirm(message, title?, type?): Promise<void>`——取消时 reject(保持 `try{await confirm(...)}catch{return}` 既有写法)
  - `<UiModal v-model:visible title width>` slots default/footer;`<UiDrawer v-model:visible title size>` slots default/footer;`<UiPagination :total :page :size @change>`

- [ ] **Step 1: tokens.css** — 从原型 CSS 抽取:设计令牌 :root 变量、`.bg-fx`(网格+光晕+扫描线)、`.panel/.corner`(HUD 角标)、`.tbl`、`.btn` 系、`.tag` 系、`.chip` 系、`.input`、`.link`、`.mono`、`.alert`、`.tl` 时间线、`.drawer/.mask`、modal 遮罩卡、`.toast`。伪元素/动画原样照搬。全局 `body{background:var(--bg-0)}`。
- [ ] **Step 2: RED — ui.test.ts**:①UiModal:visible=false 不渲染;true 渲染标题+slot,点遮罩 emit update:visible false;②confirm():resolve on 确认按钮,reject on 取消;③toast:success 后 ToastHost 文本出现「已保存」;④UiPagination:点第 2 页 emit change(2)。
- [ ] **Step 3:** 跑测试 → RED。
- [ ] **Step 4: GREEN** — 实现五件组件:
  - UiModal:`<Teleport to="body">` + `.mask`(click→close)+ `.panel.corner` 卡(标题栏+关闭×+default slot+footer slot);transition 淡入。
  - ToastHost:`reactive` 数组存 `{id,type,msg}`,3s 自动移除;`toast.ts` 导出 push 函数;ToastHost 渲染 `.toast.show` 堆叠。
  - confirm.ts:动态挂载一个共享 UiModal(确认/取消按钮),Promise resolve/reject;type 决定确认按钮 class(danger/error→红)。
  - UiDrawer:`.mask` + 右侧 `.drawer`(原型已有样式),footer slot。
  - UiPagination:`.pagi` + 页码(‹ 1 … n ›,超过 7 页省略号),active 高亮,点页码 emit change。
- [ ] **Step 5:** 测试 PASS;`npm run typecheck` 干净。
- [ ] **Step 6:** Commit `feat(front): HUD 设计令牌与自绘 UI 原语(弹窗/抽屉/分页/toast/confirm)`。

### Task 4: App 骨架 — ToastHost/bg-fx + AdminLayout + LoginView

**Files:**
- Modify: `frontend/src/App.vue`(根加 `<ToastHost/>`,路由外壳加 `.bg-fx` 背景层)、`AdminLayout.vue`(全重写)、`ChangePasswordDialog.vue`(改用 UiModal)、`router.ts`(5 个子路由加 `meta:{title:'实时统计'|...}`)、`LoginView.vue`(全重写)
- Test: 重写 `LoginView.test.ts`;新建 `AdminLayout.test.ts`

- [ ] **Step 1: RED — LoginView.test.ts 重写**(保留既有断言点:提交调 store.login、成功跳 /admin/home、失败显示后端 message;选择器改为 `.input`/`.btn`):mock store.login resolve → router.push;reject → 错误文本渲染;页面含「点将台」「接入控制台」。
- [ ] **Step 2: RED — AdminLayout.test.ts**:登录态挂载(带 router+pinia)渲染 5 个导航项与 brand;点「退出登录」清 token 跳 /login;topbar 含 LIVE 与时钟。
- [ ] **Step 3: GREEN — LoginView**:`.login-wrap` 双栏;hero 区:渐变大标题 `MUSTER · 点将台`、副标题、特性 meta(不放真实数字:实时统计 / 智能分组 / 一键归档 三格,数字位放 `✓`);右栏 `.panel.corner.login-card`:两个 `.input`(v-model username/password)+ `.btn.primary`「接 入 控 制 台」;逻辑照旧(store.login + router.push + error)。
- [ ] **Step 4: GREEN — AdminLayout**:grid 225px/1fr;`.side`:brand(六边形 SVG+点将台/MUSTER CONSOLE)+nav(inline SVG 图标,active 高亮,`router-link`)+side-foot(SYSTEM v1.0 · 单活动模式 READY);`.topbar`:crumb(`route.meta.title`)+LIVE 徽标+时钟(每秒 setInterval,显示 HH:MM:SS)+admin chip(点击开合小菜单:修改密码/退出登录);ChangePasswordDialog → UiModal(旧/新密码 + 提交调原 API,成功 toast + 关闭);退出清 store 跳 /login。App.vue 挂 ToastHost。
- [ ] **Step 5:** 两测试 PASS;全量 `npm test` 确认其他视图仍绿(本任务不动它们)。
- [ ] **Step 6:** Commit `feat(front): HUD 登录页与后台骨架(侧栏/顶栏/时钟/LIVE)`。

### Task 5: HomeView 重写(横幅/四卡/事件流/参加率环/分布图)

**Files:**
- Modify: `frontend/src/views/HomeView.vue`(全重写)、`frontend/src/api/types.ts`(Stats 加 `recentEvents?: RecentEvent[]`;新增 `RecentEvent`、`SizeBucket`)
- Test: 重写 `HomeView.test.ts`

**Interfaces:**
- Consumes: Task 1 `GET /api/stats/distribution` → `SizeBucket[]`;Task 2 WS 帧 `recentEvents`。

- [ ] **Step 1: RED** — 用例:①ACTIVE 渲染横幅(活动名/进行中 tag/每组上限 X 人 tag/「距结束 HH:MM:SS」——mock `/api/activity` 返回带 endTime 的活动,倒计时用 vi.useFakeCookies 不可行则注入 now?简单做法:横幅断言含「距结束」,具体时分秒不强断言);②四卡:已报名 6/未报名 4/分组数 2/待审核 1;③事件流:mock stats 含 `recentEvents:[{teamId:1,teamName:'组2',type:'SUBMITTED',detail:'提交 2 人',createdAt:'2026-08-30T10:00:00'}]` → 渲染「组2」「提交报名」「提交 2 人」;④参加率环:legend 含「已参加 6 / 未参加 4 / 60%」;⑤分布:mock distribution `[{size:3,count:2,overLimit:false},{size:6,count:1,overLimit:true}]` → 渲染「3人 · ×2」「6人 · ×1」与「超出 5 人上限」警示;⑥WS 帧更新数字(沿用 FakeWebSocket);⑦无活动占位保留;⑧导出按钮保留。
- [ ] **Step 2:** RED 确认。
- [ ] **Step 3: GREEN** — 模板:banner(`.panel.corner` + live-dot + 活动名 + tags + `cd-mini` 倒计时,秒级 setInterval 由 endTime 算差值,ENDED/无活动隐藏);stat-grid 4 个 `.panel.corner.stat`(mono 大数字,待审核 amber);grid-32 左:实时事件流 feed(`v-for recentEvents` 倒序,EVENT_TEXT 映射:{CREATED:'创建组',SAVED:'组长保存',SUBMITTED:'提交报名',EDITED_BY_ADMIN:'管理员修改',CREATED_BY_ADMIN:'管理员创建',PASSED:'审核通过',REJECTED:'驳回'});右:参加率环(原型 SVG donut,dasharray 按 registered/total 算,总人数 0 时 0%)+ 导出按钮 + 组人数分布 bars(`.bar`,overLimit 桶 `.over`,底部 ⚠ 警示行)。逻辑:沿用 useStats + activity 拉取 + onUnmounted stop。
- [ ] **Step 4:** PASS + 全量绿 + typecheck。
- [ ] **Step 5:** Commit `feat(front): 首页 HUD 化(倒计时横幅/事件流/参加率环/组人数分布)`。

### Task 6: ActivityView 重写(native datetime-local)

**Files:**
- Modify: `frontend/src/views/ActivityView.vue`(全重写,QRCode 逻辑保留)
- Test: 重写 `ActivityView.test.ts`

- [ ] **Step 1: RED** — 用例:①无活动渲染创建表单(名称/开始/结束/上限),填后 POST body 为 `{name, startTime:'…T18:00:00', endTime:…, groupSizeLimit}`(datetime-local 值 `2026-09-03T18:00` → 提交时补 `:00` 秒);②有活动渲染 kv 信息 + 状态 tag + 归档 tag;③修改打开 UiModal 预填(timeEditable=false 时 datetime disabled),保存 PUT;④手动结束 confirm 后 POST /end;⑤删除活动 confirm 后 DELETE;⑥导出归档 downloadFile;⑦表单 URL + canvas 存在。confirm 用 `vi.spyOn(confirmModule,'confirm')` 或 mock 其 resolve。
- [ ] **Step 2:** RED → **GREEN**:创建/编辑表单用 `.panel` + `.f-label` + `.input`(datetime:`type="datetime-local"`,值互转 `slice(0,16)` ↔ 补秒);按钮区照原型(✎修改/■手动结束/⇩导出归档包/删除活动 ghost);右侧 `.qr-box`(canvas + url-line mono);消息→toast,确认→confirm()。
- [ ] **Step 3:** PASS + 全量绿。
- [ ] **Step 4:** Commit `feat(front): 活动管理页 HUD 化(原生时间选择/二维码面板)`。

### Task 7: RosterView 重写

**Files:**
- Modify: `frontend/src/views/RosterView.vue`(全重写)
- Test: 重写 `RosterView.test.ts`

- [ ] **Step 1: RED** — 迁移既有 7 用例断言点(文案与行为不变,选择器换):列表四列+组别/组长/状态 tag(已参加 ok/未参加 dim)+操作(编辑/删除 link);搜索参数;添加校验(员工编号必填不发请求)/POST 四字段;编辑预填 PUT;一键清空双 confirm → DELETE → 「已清空 5 人」;409 仅错误提示(toast 计数断言)。删除按钮文案提示改 confirm(`删除 {name}？已入组的成员将一并移除`)。
- [ ] **Step 2:** RED → **GREEN**:toolbar(查询/下载模板/导入 Excel/添加人员/一键清空 danger);`.tbl` 表格;添加/编辑 UiModal(四个 `.f-label`+.input,校验逻辑照旧);导入 UiModal(原生 input file + 错误行);UiPagination。toast/confirm 替换 ElMessage/ElMessageBox。
- [ ] **Step 3:** PASS + 全量绿。
- [ ] **Step 4:** Commit `feat(front): 花名册页 HUD 化(自绘表格/弹窗/分页)`。

### Task 8: TeamView 重写(chips 筛选/抽屉/建组改组人员搜索)

**Files:**
- Modify: `frontend/src/views/TeamView.vue`(全重写,逻辑函数照搬)
- Test: 重写 `TeamView.test.ts`

- [ ] **Step 1: RED** — 迁移既有 9 用例 + chips:①列表渲染组长列与 DRAFT;②chips 筛选草稿(点「草稿」chip → load 参数 status=DRAFT);③驳回不填理由不发;④通过 PUT /review;⑤删除组 confirm「组员将回到未报名状态」→ DELETE;⑥详情抽屉员工编号列+组长 tag+事件时间线(创建组/提交报名);⑦管理员改组 PUT body `{leaderEmployeeId, memberEmployeeIdList}`;⑧新建组流程 POST;⑨人员搜索 → 查看组开抽屉。新增:⑩chips 渲染各状态计数(挂载时并行 GET /api/teams?status=X&size=1 取 total×4 + 全量);⑪PENDING 抽屉 footer 显示 通过/驳回 按钮并可用。
- [ ] **Step 2:** RED → **GREEN**:chips 用 `.chip`(active 态)替换 el-select,计数 span mono;表格/抽屉/三个弹窗全换自绘;详情 footer:`v-if="detail.status==='PENDING'"` 渲染 `.btn.ok`通过 + `.btn.danger`驳回 + `.btn`管理员改组,否则只渲染管理员改组;radio 组长列用原生 `<input type="radio" name="leader">` 样式化。
- [ ] **Step 3:** PASS + 全量绿。
- [ ] **Step 4:** Commit `feat(front): 组管理页 HUD 化(chips 筛选/抽屉/建组/人员搜索)`。

### Task 9: AuditView 重写

**Files:**
- Modify: `frontend/src/views/AuditView.vue`(全重写)
- Test: 重写 `AuditView.test.ts`

- [ ] **Step 1: RED** — 用例:①chips 渲染(全部操作 + ACTION_TEXT 项),点「组审核」→ load 参数 action=TEAM_REVIEW;②log-row 渲染 时间(mono)/操作人/操作 tag/详情;③分页 change 重载。
- [ ] **Step 2:** RED → **GREEN**:chips 替换 select;`.log-row` 三段式(照原型),action → tag 颜色映射(组审核 warn/err、管理员改组 info、花名册 info、活动 ok、归档 dim);UiPagination。
- [ ] **Step 3:** PASS + 全量绿。
- [ ] **Step 4:** Commit `feat(front): 审计日志页 HUD 化(chips 筛选/流水样式)`。

### Task 10: FormView 重写(去 Vant,移动端 HUD)

**Files:**
- Modify: `frontend/src/views/FormView.vue`(全重写;`useFormPage` composable 不动)
- Test: 重写 `FormView.test.ts`

- [ ] **Step 1: RED** — 迁移既有 13 用例断言点(identify/保存草稿/提交/手机验证/锁定/删除/超上限提示等;`vi.mock('vue-router')` 保留):选择器变化——`van-dialog__confirm` → 自绘确认弹窗 `.modal-confirm` 主按钮;窗口态/身份/编辑/视图四段文案保持;删掉 Vant NavBar 泄漏的 `afterAll` 350ms drain(不再需要,保留无害则留)。
- [ ] **Step 2:** RED → **GREEN**:模板结构:顶部标题条(活动名 · 分组报名);窗口空态;身份段(`.p-group` 卡:员工编号 input + 查询);视图段(我的组卡:状态 p-tag、驳回理由 `.alert.err`、成员 p-cell + 组长 tag、PENDING 提示条、manageable 时 [换机验证|修改组员|提交报名|删除本组]);编辑段(冲突 alert、成员列表+移除、添加 input+预览 tag、超/欠员提示、保存草稿/提交报名/取消);手机验证用自绘小 UiModal(组长手机号 + 确认);删除用 confirm()。所有 Vant 组件替换为 tokens.css 的移动端类(原型 `.p-*` 系列一并入 tokens.css)。
- [ ] **Step 3:** PASS + 全量绿 + typecheck。
- [ ] **Step 4:** Commit `feat(front): 报名表单去 Vant 化(HUD 移动端自绘)`。

### Task 11: 依赖清理 + 全量验证 + 文档

**Files:**
- Modify: `frontend/package.json`(删 element-plus、vant)、`frontend/src/main.ts`(删两组件库 import 与 css)
- Modify: `CLAUDE.md`(代码约定补:前端自绘 UI,tokens.css 为视觉唯一来源;业务铁律补:`GET /api/stats/distribution`、WS 帧含 recentEvents)

- [ ] **Step 1:** `grep -rn "element-plus\|vant" frontend/src/` → 清零(类型引用一并删)。
- [ ] **Step 2:** `npm uninstall element-plus vant`;`npm test && npm run typecheck && npm run build` → 构建无 >500kB 警告。
- [ ] **Step 3:** `cd backend && mvn test` → 全绿。
- [ ] **Step 4:** CLAUDE.md 更新 + Commit `feat(front): 移除 Element Plus/Vant,前端全自绘` + push。
- [ ] **Step 5:** 提醒用户:本次无表结构变更,远程升级 `git pull && docker compose up -d --build` 即可,**数据保留**。

## Self-Review

1. **共识覆盖**:全站换肤(T4-T10)、视觉照原型数据按新口径(全局约束)、彻底移除 EP/Vant(T3/T11)、首页四新组件(T1/T2/T5)、登录页不放数字(T4)、事件流扩展 /ws/stats(T2)、分布含 DRAFT(T1)、chips 计数(T8)、PENDING 抽屉 footer 审核按钮(T8)、63 测试重写(各任务 RED)。
2. **占位符**:无 TBD;各步骤含确切行为/代码。
3. **类型一致**:`confirm(message, title?, type?)`、`toast.success/error/warning`、`UiModal/UiDrawer v-model:visible`、`UiPagination @change`、`SizeBucket{size,count,overLimit}`、`RecentEvent{teamId,teamName,type,detail,createdAt}` 贯穿 T1/T2/T5 与各视图。

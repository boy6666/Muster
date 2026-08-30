# CLAUDE.md

Muster · 点将台 — 公司活动分组报名系统。仓库布局：`backend/`（Spring Boot 3.5 · Java 21）、`frontend/`（Vue 3 · Element Plus 管理端 + Vant 报名表单）、`deploy/`（建设中）、`docs/`。

## 命令

```bash
cd backend && mvn test          # 全量测试（单元 + 集成；Testcontainers 需要 Docker）
cd backend && mvn spring-boot:run   # 本地运行 :8080（需 MySQL 8，schema 自动创建）
```

默认管理员 `admin / admin123`（首次启动 data.sql 自动创建）。环境变量：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/JWT_SECRET/FORM_BASE_URL`。

## 业务铁律（改动前必读）

1. **严格单活动**：全库至多一个活动。未导出归档包不能新建/删除（`ARCHIVE_REQUIRED`）。
2. **活动窗口**：`manuallyEnded || now>endTime → ENDED`；`now<startTime → NOT_STARTED`；否则 `ACTIVE`（边界 now==start/end 视为 ACTIVE）。组长/管理员改组仅限 ACTIVE；审核（PASS/REJECT）不受窗口限制。
3. **一人一组**：按**员工编号**判定身份；员工编号（非空 1..32）与手机号 `^1[3-9]\d{9}$` 均活动内唯一（花名册 + 组成员共用唯一约束）。冲突返回 409 并在 `data` 中带冲突明细。
4. **组生命周期**：建组 → `DRAFT`；**保存 ≠ 提交**（保存仅存组员，状态不变）；提交 → `PENDING`，**首次提交须先 `POST /api/form/{token}/teams/{id}/verify` 用组长手机号换取 `capToken`**；`PENDING` 锁定（不可改不可删）；PASS → `CONFIRMED`；REJECT → 组留 `REJECTED` + 理由（**不回落**），组长可改组重提（重提交清理由）；组长可删自己的 `DRAFT/REJECTED` 组（组员回到未报名）；管理员建组/改组 → 直接 `CONFIRMED`，管理员可删任意状态组。组接口 body 统一 `{leaderEmployeeId, memberEmployeeIdList}`（管理员改组 leader 可省略）。
5. **组名自动生成**：`组{N+1}`，依赖 `uk_activity_name` 唯一键，冲突重试至多 3 次。
6. **超上限不拦截**：人数超过 `groupSizeLimit` 允许提交，仅打 `overLimit` 标记（前端负责提示，少于下限同样仅提示）。
7. **花名册锁定活动**：Excel 导入/网页增删改（`POST/PUT/DELETE /api/roster*`）均挂在当前活动下，四列 员工编号/姓名/手机号/部门；存在报名组时一键清空 409 `ARCHIVE_REQUIRED`。
8. **表单查询仅精确员工编号**：`GET /api/form/{token}/person?employeeId=` 必须是**完整**员工编号才查询，不做模糊搜索（防止扫库）；组员列表 `GET .../my-team?employeeId=` 同理。
9. **组级能力令牌**：二维码 token 全体共享，组详情/改组/删除必须携带提交时发放的 `capToken`（`?cap=`），不匹配按 404 处理（不泄露组是否存在）；分页参数统一走 `PageParams.clamp`（page≥1、1≤size≤200）。
10. **已报名/已参加口径**：已报名 = 非 `DRAFT` 组的成员（驳回组也算，组被删即退出）；已参加 = `CONFIRMED` 组成员。首页四卡片 已报名/未报名/分组数/待审核；花名册状态列 已参加/未参加。

## 代码约定

- 统一错误信封：`{"code","message"[,"data"]}`，见 `common/ErrorCode` + `config/GlobalExceptionHandler`；未知异常必须 `log.error` 落日志。
- 所有"当前时间"用注入的 `Clock`（`LocalDateTime.now(clock)`），测试里可替换。
- MyBatis-Plus：清空列用 `LambdaUpdateWrapper.set(field, null)`（`updateById` 会跳过 null 字段）。
- 集成测试命名 `*IT`（surefire 已配置包含），统一继承 `IntegrationTestBase`（单例 MySQL 容器 + 每用例清库 + 自动登录）；二进制响应（xlsx）必须用 `getBytes/postBytes` 接 `byte[]`。
- TDD：先写测试看它失败，再写实现。提交信息用 conventional commits + `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`。

## 已知坑

- JDK HttpClient WebSocket 客户端：覆写 `Listener.onText` 后必须 `webSocket.request(1)`，否则只收到首帧。
- EasyExcel 需要 commons-io ≥2.16（pom 已显式钉住 2.16.1）。
- Windows 本机 3306 常被占用：冒烟用 `docker run -p 33061:3306` + `DB_PORT=33061`。

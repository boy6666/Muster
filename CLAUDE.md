# CLAUDE.md

Muster · 点将台 — 校园活动分组报名系统。仓库布局：`backend/`（Spring Boot 3.5 · Java 21）、`frontend/`（建设中）、`deploy/`（建设中）、`docs/`。

## 命令

```bash
cd backend && mvn test          # 全量测试（单元 + 集成；Testcontainers 需要 Docker）
cd backend && mvn spring-boot:run   # 本地运行 :8080（需 MySQL 8，schema 自动创建）
```

默认管理员 `admin / admin123`（首次启动 data.sql 自动创建）。环境变量：`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD/JWT_SECRET/FORM_BASE_URL`。

## 业务铁律（改动前必读）

1. **严格单活动**：全库至多一个活动。未导出归档包不能新建/删除（`ARCHIVE_REQUIRED`）。
2. **活动窗口**：`manuallyEnded || now>endTime → ENDED`；`now<startTime → NOT_STARTED`；否则 `ACTIVE`（边界 now==start/end 视为 ACTIVE）。组长/管理员改组仅限 ACTIVE；审核（PASS/REJECT）不受窗口限制。
3. **一人一组**：手机号 `^1[3-9]\d{9}$`，活动内唯一（花名册 + 组成员共用唯一约束）。冲突返回 409 并在 `data` 中带冲突明细。
4. **提交即算已参加**：组提交 → `PENDING`，成员计入 joined；驳回**不回落**（组留 `REJECTED` + 理由），组长保留改组权（改组 → 回 `PENDING` 并清理由），管理员改组 → 直接 `CONFIRMED`。
5. **组名自动生成**：`组{N+1}`，依赖 `uk_activity_name` 唯一键，冲突重试至多 3 次。
6. **超上限不拦截**：人数超过 `groupSizeLimit` 允许提交，仅打 `overLimit` 标记（前端负责提示）。
7. **花名册锁定活动**：Excel 导入/网页增删均挂在当前活动下；活动运行中允许增删。
8. **表单自动回显仅精确手机号**：`GET /api/form/{token}/person?phone=` 必须是完整 11 位才查询，不做模糊搜索（防止扫库）。

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

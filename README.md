# Muster · 点将台

校园活动分组报名系统：严格单活动、花名册 Excel 导入、扫码报名（组长统一提交）、全部人工审核、实时统计（WebSocket 推送）、xlsx 导出与归档。

- 技术栈：Spring Boot 3.5 · Java 21 · MyBatis-Plus · MySQL 8 · Vue 3（建设中）· Docker（建设中）
- 接口文档：[`docs/api.md`](docs/api.md)
- 后端：`backend/`　|　前端：`frontend/`（建设中）　|　部署：`deploy/`（建设中）

## 核心玩法

1. 管理员登录 → 创建活动（名称/起止时间/每组人数上限）→ 上传花名册 xlsx（姓名/手机号/部门）。
2. 系统生成报名表单二维码链接（`GET /api/activity/form-url`）；组长扫码进入表单，输入完整手机号自动回显成员信息，勾选组员统一提交（超上限允许提交但会标记）。
3. 一人一活动只能在一组；提交即算已参加，驳回不回落，组长可继续改组重新送审。
4. 管理端实时大屏：参与总人数 / 已参加 / 未参加 / 组数 / 待审核组数（`/ws/stats` WebSocket 推送，改组或审核即刻刷新）。
5. 审核 PASS/REJECT（驳回必填理由）；随时导出已参加/未参加名单；活动收尾导出三 sheet 归档包（已参加/未参加/分组明细）后才可删除活动、开始下一场。

## 后端本地运行

```bash
cd backend
# 需要 MySQL 8（默认 localhost:3306，root/root，库名 muster，表结构启动时自动创建）
mvn spring-boot:run
```

- 服务端口：`8080`
- 默认管理员：`admin / admin123`（首次启动自动创建）
- 环境变量：`DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD / JWT_SECRET / FORM_BASE_URL`

## 测试

```bash
cd backend && mvn test
```

81 个用例（单元 + 集成）。集成测试使用 Testcontainers 自动拉起 MySQL 8 容器，需要本机 Docker。

## 部署（建设中）

计划提供 `deploy/` 下 docker-compose（app + MySQL），并通过 Cloudflare 代理的自有域名对外发布。

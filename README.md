# Muster · 点将台

校园活动分组报名系统：严格单活动、花名册 Excel 导入、扫码报名（组长统一提交）、全部人工审核、实时统计（WebSocket 推送）、xlsx 导出与归档。

- 技术栈：Spring Boot 3.5 · Java 21 · MyBatis-Plus · MySQL 8 · Vue 3 + Element Plus（管理端）+ Vant（移动 H5）· Docker
- 接口文档：[`docs/api.md`](docs/api.md)
- 后端：`backend/`　|　前端：`frontend/`　|　部署：`deploy/`

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
- 默认管理员：`admin / admin123`（首次启动自动创建，请登录后立即修改密码）
- 环境变量：`DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD / JWT_SECRET / FORM_BASE_URL`
- ⚠️ 生产部署必须设置随机 `JWT_SECRET`（未设置时后端会打印启动警告，令牌存在被伪造风险）

## 前端本地运行

```bash
cd frontend
npm install
npm run dev          # 开发服务器 http://localhost:5173，/api 与 /ws 代理到 localhost:8080
```

- 生产构建：`npm run build`（产物在 `frontend/dist/`）
- 类型检查：`npm run typecheck`
- 页面：`/login` 登录；`/admin/home` 实时统计与导出；`/admin/activity` 活动管理与二维码；`/admin/roster` 花名册；`/admin/teams` 组审核；`/admin/audit` 审计日志；`/form/:token` 手机端报名 H5

## 测试

```bash
# 后端（89 个用例，Testcontainers 需要本机 Docker）
cd backend && mvn test

# 前端（41 个用例，Vitest + jsdom，无需后端）
cd frontend && npm test
```

## 部署（Docker Compose）

```bash
cd deploy
cp .env.example .env      # 必改：DB_PASSWORD、JWT_SECRET；按需改 FORM_BASE_URL、HTTP_PORT
docker compose up -d --build
```

- 架构：`mysql:8`（named volume `mysql-data` 存数据，healthcheck 就绪后才启动后端）+ `backend`（Spring Boot jar）+ `frontend`（nginx 托管静态资源并反代 `/api`、`/ws`）
- 访问：`http://<主机>:${HTTP_PORT:-80}/` 进入管理后台；报名表单地址为 `FORM_BASE_URL/form/<二维码token>`（外网部署时把 `FORM_BASE_URL` 设为 Cloudflare 代理的域名，如 `https://muster.example.com`）
- 默认管理员 `admin / admin123`，**首次登录后请立即在右上角「修改密码」中更改**
- 常用命令：

```bash
docker compose logs -f backend    # 后端日志
docker compose ps                 # 状态
docker compose down               # 停止（数据保留在 mysql-data 卷）
```

首次启动后端会自动建表（schema.sql 幂等）并创建默认管理员。详细说明见 [`deploy/README.md`](deploy/README.md)。

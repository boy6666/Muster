# Muster · 点将台

校园活动分组报名系统（严格单活动 / 花名册 Excel / 扫码报名 / 人工审核 / 实时统计 / xlsx 导出）。

- 技术栈：Spring Boot 3.5 · Java 21 · MyBatis-Plus · MySQL 8 · Vue 3（建设中）· Docker（建设中）
- 计划文档：`docs/superpowers/plans/`
- 后端：`backend/`　|　前端：`frontend/`（建设中）　|　部署：`deploy/`（建设中）

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

集成测试使用 Testcontainers 自动拉起 MySQL 8 容器，需要本机 Docker。

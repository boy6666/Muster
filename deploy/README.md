# 部署

```bash
cd deploy
cp .env.example .env      # 修改 DB_PASSWORD、JWT_SECRET、FORM_BASE_URL
docker compose up -d --build
```

- 前台入口：`http://<主机>/`（管理后台），报名表单：`<FORM_BASE_URL>/form/<二维码token>`
- 默认管理员 `admin / admin123`，首次登录后请立即在右上角「修改密码」中更改
- 数据落在 named volume `mysql-data`，升级镜像不丢数据
- 后端日志：`docker compose logs -f backend`

首次启动后端会自动建表（schema.sql 幂等）并创建默认管理员。

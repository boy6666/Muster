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

**升级注意**：schema.sql 只做 `CREATE TABLE IF NOT EXISTS`，不会对已存在的表补列。跨大版本升级（如旧库缺 `team.cap_token` 列）需先执行 `docker compose down -v` 重建卷或手工 `ALTER TABLE`。

## 服务器部署（云主机 / VPS）

仓库为公开仓库，服务器上无需凭证即可拉取：

```bash
# 1. 安装 Docker（Ubuntu/Debian 为例，其他发行版见 docs.docker.com）
curl -fsSL https://get.docker.com | sh

# 2. 拉代码
git clone https://github.com/boy6666/Muster.git && cd Muster/deploy

# 3. 配置
cp .env.example .env
#   DB_PASSWORD / JWT_SECRET 必改：openssl rand -hex 32 各生成一个
#   FORM_BASE_URL 填参与者扫码访问的公网地址，如 https://muster.wyc.best/
#   走 Tunnel 或本机反代时设 BIND_ADDR=127.0.0.1（不对公网开放 8091）
#   HTTP_PORT 按需（默认 80）

# 4. 构建并启动（首次构建需拉取 mysql/maven/node/nginx 基镜像，在服务器上构建）
docker compose up -d --build
```

### 对外暴露（二选一）

**A. Cloudflare Tunnel（推荐，无需开端口、自动 HTTPS）**

```bash
cloudflared tunnel login
cloudflared tunnel create muster
cloudflared tunnel route dns muster muster.wyc.best
cloudflared tunnel run --url http://localhost:8091 muster   # 建议配成 systemd 服务
```

隧道路由指向 `HTTP_PORT`，源站保持 HTTP，TLS 由 Cloudflare 终结。此时安全组只需放行 SSH。

**B. 公网 IP 直连**：DNS A 记录指向服务器，再用 Caddy / certbot-nginx 做 HTTPS 反代到 `HTTP_PORT`，并把 `BIND_ADDR=127.0.0.1` 收进本机。安全组放行 80/443。

### 日常运维

```bash
docker compose logs -f backend        # 后端日志
docker compose up -d --build          # 升级（git pull 后执行；数据在 mysql-data 卷，不丢）
docker compose exec mysql mysqldump -uroot -p"$DB_PASSWORD" muster > backup.sql   # 备份
```

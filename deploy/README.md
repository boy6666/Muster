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

## 服务器部署（Windows 10 / Linux 均可）

Windows 服务器：装好 Docker Desktop（酒店系统已在用即装好）+ git；Linux：`curl -fsSL https://get.docker.com | sh`。仓库为公开仓库，无需凭证：

```bash
# 1. 拉代码（Win10 用 PowerShell / Git Bash 均可）
git clone https://github.com/boy6666/Muster.git
cd Muster/deploy

# 2. 配置
cp .env.example .env
#   DB_PASSWORD / JWT_SECRET 必改：openssl rand -hex 32 各生成一个（Git Bash）或任意强随机串
#   FORM_BASE_URL 填参与者扫码访问的公网地址，如 https://muster.wyc.best/
#   BIND_ADDR=127.0.0.1（走 Tunnel / 本机 nginx，不对公网开放）
#   HTTP_PORT=8092（3306 被 hotel-mysql 占用无关；前端容器端口，避开 hotel 的 8080/8090）

# 3. 构建并启动（首次构建需拉取 mysql/maven/node/nginx 基镜像）
docker compose up -d --build
```

### 本机 nginx 反代（与 hotel.conf 同一套 nginx）

```bash
# 把仓库里 deploy/nginx-muster.conf 复制为 nginx conf 目录下的 muster.conf，
# 并在 nginx.conf 的 http{} 末尾 include（和 hotel.conf 那行并排）：
#   include D:/software/nginx-1.22.0-web/conf/muster.conf;
# 然后 nginx -t 校验并 (re)load
```

### 对外暴露（二选一）

**A. Cloudflare Tunnel（推荐，无需开端口、自动 HTTPS）**

服务器上已有隧道在跑（如 hotel.wyc.best 就走这条）时**不要新建隧道**，往现有隧道加一条映射即可：

- **仪表盘托管**（cloudflared 服务启动参数带 `--token ey...`）：登录 [one.dash.cloudflare.com](https://one.dash.cloudflare.com) → Networks → Tunnels → 点**现有**那条隧道 → Public Hostname → Add a public hostname：Subdomain `muster`、Domain `wyc.best`、Service `HTTP://localhost:8091` → Save（自动加 DNS 记录）。
- **配置文件托管**（服务启动参数是 `tunnel run <名字>`）：编辑 `C:\Users\<用户>\.cloudflared\config.yml`，在 `ingress:` 里 hotel 那条之后加：

  ```yaml
    - hostname: muster.wyc.best
      service: http://localhost:8091
  ```

  保持最后一条 `- service: http_status:404` 不动，然后 `cloudflared tunnel route dns <隧道名> muster.wyc.best`（只新增一条 CNAME），再重启 cloudflared 服务。

以上只新增 hostname 映射和一条 CNAME，域名下其他服务不受影响；全程无需开放任何入站端口，TLS 由 Cloudflare 终结（源站保持 HTTP）。全新服务器才需要 `cloudflared tunnel login / create / run` 那套初始化。

**B. 公网 IP 直连**：DNS A 记录指向服务器，再用 Caddy / certbot-nginx 做 HTTPS 反代到 `HTTP_PORT`，并把 `BIND_ADDR=127.0.0.1` 收进本机。安全组放行 80/443。

### 日常运维

```bash
docker compose logs -f backend        # 后端日志
docker compose up -d --build          # 升级（git pull 后执行；数据在 mysql-data 卷，不丢）
docker compose exec mysql mysqldump -uroot -p"$DB_PASSWORD" muster > backup.sql   # 备份
```

**Navicat 连库**：compose 已把 MySQL 映射到主机 `127.0.0.1:3307`（3306 被同机 hotel-mysql 占用）。连接参数：主机 `127.0.0.1`、端口 `3307`、用户 `root`、密码 = `deploy/.env` 的 `DB_PASSWORD`、默认库 `muster`。Navicat 装在另一台电脑上时，用 Navicat 自带的「SSH 通道」先登录服务器、再连 `127.0.0.1:3307`。

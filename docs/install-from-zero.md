# 从零安装：TeslaMate + bridge 一体部署

> 实测环境：阿里云 ECS cn-hangzhou（Ubuntu 22.04，4C 3.4G），Docker 27.x，2026-06-10  
> 所有命令均为实跑过的命令，不是示例性伪代码。

---

## 前置条件

- 一台 Linux 服务器（Ubuntu 22.04 / Debian 12 均可；4C/4G 以上推荐，2C/2G 勉强够用）
- 已安装 Docker Engine 24+ 和 Docker Compose v2（`docker compose` 不是 `docker-compose`）
- 服务器能访问外网拉 Docker 镜像（中国大陆见下文 [镜像加速](#中国大陆镜像加速)）
- 本地或云端一个 Tesla 账号（TeslaMate 授权用）

如果只想先体验 demo 数据（不授权 Tesla 账号），跳过 TeslaMate 安装步骤，直接看 [场景A · demo 快速体验](#场景a--demo-快速体验无-teslamate)。

---

## 一、隔离部署目录（推荐）

```bash
mkdir -p /opt/bridge-e2e
cd /opt/bridge-e2e
```

生产场景建议换成 `/opt/teslamate`。

---

## 二、安装 TeslaMate（官方 docker compose 方式）

### 2.1 写 docker compose 配置

参考 [TeslaMate 官方文档](https://docs.teslamate.org/docs/installation/docker)。最小化四件套：
`teslamate` + `postgres` + `grafana` + `mosquitto`（MQTT broker）。

官方示例 `docker-compose.yml`：

```yaml
services:
  teslamate:
    image: teslamate/teslamate:latest
    restart: always
    environment:
      - ENCRYPTION_KEY=<随机字符串，一旦设置不能改>
      - DATABASE_URL=postgresql://teslamate:<DB密码>@database/teslamate
      - MQTT_HOST=mosquitto
    ports:
      - 4000:4000
    cap_drop:
      - all

  database:
    image: postgres:16
    restart: always
    environment:
      - POSTGRES_USER=teslamate
      - POSTGRES_PASSWORD=<DB密码>
      - POSTGRES_DB=teslamate
    volumes:
      - teslamate-db:/var/lib/postgresql/data

  grafana:
    image: teslamate/grafana:latest
    restart: always
    environment:
      - DATABASE_USER=teslamate
      - DATABASE_PASS=<DB密码>
      - DATABASE_NAME=teslamate
      - DATABASE_HOST=database
    ports:
      - 3000:3000
    volumes:
      - teslamate-grafana:/var/lib/grafana

  mosquitto:
    image: eclipse-mosquitto:2
    restart: always
    command: mosquitto -c /mosquitto-no-auth.conf
    ports:
      - 1883:1883
    volumes:
      - teslamate-mosquitto:/mosquitto/data

volumes:
  teslamate-db:
  teslamate-grafana:
  teslamate-mosquitto:
```

### 2.2 启动 TeslaMate

```bash
docker compose up -d
docker compose logs -f teslamate   # 等待 Ctrl+C，看到 "Running TeslaMate" 即可
```

第一次启动 TeslaMate 会做数据库 migration（约 2-3 分钟）。

### 2.3 授权 Tesla 账号

浏览器打开 `http://<服务器IP>:4000`，点「Sign in with Tesla」完成 OAuth。
授权后 TeslaMate 开始轮询车辆状态，行程数据逐渐入库。

### 2.4 查找 car_id

TeslaMate 为每台车分配一个整数 ID（从 1 开始）。bridge 需要用这个 ID。

**方法一：TeslaMate UI**

打开 `http://<服务器IP>:4000/cars`，URL 里或页面里能看到车辆编号。

**方法二：psql 直查**

```bash
# 找到 postgres 容器名
docker ps | grep postgres

# 进容器查询（容器名视你的 compose 而定，通常是 teslamate-database-1）
docker exec -it <postgres容器名> psql -U teslamate -d teslamate \
  -c "SELECT id, name, vin FROM cars;"
```

记住 `id` 列的值，下面安装 bridge 时会用到。

---

## 三、安装 bridge

### 3.1 克隆仓库

```bash
git clone https://github.com/jakezai250808-sudo/teslamate-llm-bridge.git
cd teslamate-llm-bridge
```

### 3.2 配置 .env

```bash
cp .env.example .env
```

编辑 `.env`，**至少需要改这两项**（不只是密码，host 也必须改）：

```dotenv
# ⚠️ 关键：TeslaMate PG 在容器内，bridge 容器内 localhost 访问不到宿主机。
# Linux（docker0 网桥）：填 172.17.0.1 或 172.18.0.1（用 ip addr show docker0 确认）
# Mac Docker Desktop：填 host.docker.internal
# 如果把 bridge 加入了 TeslaMate 的同一 compose network，可填 postgres 的 service 名
TM_DB_HOST=172.17.0.1        # ← 改这里！不要留 localhost

TM_DB_PORT=5432
TM_DB_NAME=teslamate
TM_DB_USER=teslamate     # 推荐改成受限只读账号，见 3.2 下方
TM_DB_PASS=<TeslaMate PG 的密码>   # ← 必填

# 可选：限定只暴露哪几台车的数据（逗号分隔的 car_id）
CAR_IDS=1

# 可选：API Bearer token 认证（公网暴露时强烈建议设置）
API_TOKEN=
```

**如何确认 docker0 网桥 IP：**

```bash
ip addr show docker0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
# 通常返回 172.17.0.1 或 172.18.0.1
```

> 🔒 **推荐：用受限只读账号连库（强烈建议）。** bridge 只做只读查询，别用默认的
> `teslamate` 超级用户。执行 [`docs/teslamate-readonly-role.sql`](teslamate-readonly-role.sql)
> 建一个最小权限只读角色——能读行车数据、**读不到 `tokens` 表**（你的加密 Tesla 凭据），
> 然后把 `TM_DB_USER` / `TM_DB_PASS` 指向它。详见
> [install-existing-teslamate.md 的「受限只读账号」](install-existing-teslamate.md#推荐受限只读账号)。

### 3.3 启动 bridge（方案一：docker compose，推荐）

```bash
docker compose --profile prod up -d --build
```

首次 `--build` 触发：
- 拉 `eclipse-temurin:21-jdk-jammy` 基础镜像（~300MB，国内约 5-10 分钟）
- 容器内 `apt-get install maven` + `mvn -DskipTests package`（下载 Spring Boot 全量依赖，**国内首次约 10-20 分钟**，见 [镜像加速](#中国大陆镜像加速)）

等待到：

```bash
docker compose --profile prod logs bridge | grep "Tomcat started"
# 看到 "Tomcat started on port 8770" 即可
```

验证：

```bash
curl http://localhost:8770/actuator/health
# 期望：{"status":"UP"}
```

### 3.4 启动 bridge（方案二：预构建 jar，国内首选）

如果 docker build 因网络超时失败，用预构建 jar 方式：

```bash
# 在本地（Mac/有外网的机器）构建
cd bridge
mvn -DskipTests package
# target/ 下生成 teslamate-llm-bridge-*.jar（约 25MB fat jar）

# 上传到 OSS 或 SCP 到服务器
# 以 aliyun OSS 为例：
aliyun oss cp target/teslamate-llm-bridge-*.jar oss://<你的bucket>/bridge/app.jar

# 服务器端拉下来运行
ossutil cp oss://<你的bucket>/bridge/app.jar /opt/bridge-e2e/app.jar

docker run -d \
  --name e2e-bridge \
  --restart unless-stopped \
  -p 8770:8770 \
  -e TM_DB_HOST=172.18.0.1 \
  -e TM_DB_PORT=5432 \
  -e TM_DB_NAME=teslamate \
  -e TM_DB_USER=teslamate \
  -e TM_DB_PASS=<密码> \
  -e CAR_IDS=1 \
  -e API_TOKEN="" \
  -v /opt/bridge-e2e/app.jar:/app/app.jar \
  eclipse-temurin:21-jre-jammy \
  java -jar /app/app.jar
```

JVM 冷启动约 18 秒，之后 `curl http://localhost:8770/actuator/health` 返回 `{"status":"UP"}`。

---

## 四、验证

```bash
# 列出所有玩法（应返回 7 个）
curl http://localhost:8770/api/v1/plays

# 跑驾驶人格（换成你的 car_id）
curl "http://localhost:8770/api/v1/cars/1/play/driving-personality"
```

刚安装 TeslaMate 且没有行程数据时，`driving-personality` 会返回：

```json
{"play":"driving-personality","scored":false,"reason":"insufficient_data","drive_count":0}
```

这是正常的——`scored: false` 说明数据不足（需要 30 天内至少 5 次行程）。参考 [demo 快速体验](#场景a--demo-快速体验无-teslamate) 灌入模拟数据验证玩法功能。

---

## 场景A · demo 快速体验（无 TeslaMate）

不想授权 Tesla 账号、或服务器刚装好没有真实数据，用一条命令起 demo 模式：

```bash
cd teslamate-llm-bridge
docker compose --profile demo up -d --build
```

demo 模式会：
- 启动本地 `postgres:16-alpine`，注入 TeslaMate 官方 100 条 migration 后的完整 schema
- 加载 `ops/demo/demo-seed.sql`：78 条行程、14 次充电，45 天窗口，上海路线，Model Y LR，`car_id=99`
- 启动 bridge 指向该 PG，只暴露 car_id=99

验证：

```bash
# 驾驶人格
curl "http://localhost:8770/api/v1/cars/99/play/driving-personality"
# 期望：scored: true，code: FNLE，persona.name: "午夜高速战神"

# 月度报告
curl "http://localhost:8770/api/v1/cars/99/play/monthly-wrapped"
# 期望：scored: true，total_km: 1222

# 充电人格
curl "http://localhost:8770/api/v1/cars/99/play/charging-habit"
```

---

## 中国大陆镜像加速

### Docker 镜像加速

```bash
# /etc/docker/daemon.json
{
  "registry-mirrors": [
    "https://docker.nju.edu.cn",
    "https://dockerproxy.com"
  ]
}
# 修改后重启 Docker
sudo systemctl daemon-reload && sudo systemctl restart docker
```

### Maven Central 加速（容器内 build 加速，可选）

如果需要在国内服务器 build 而不走方案二（预构建 jar），可在容器 build 前将阿里云 Maven 镜像配置写到 `bridge/settings.xml`，Dockerfile 里 `mvn` 命令加 `-s bridge/settings.xml`。具体见 [Dockerfile.prebuilt](../Dockerfile.prebuilt)（若存在）或自行添加：

```xml
<!-- bridge/settings.xml -->
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

---

## Schema 兼容性说明

demo 种子数据（`ops/demo/demo-seed.sql`）专为 `ops/teslamate-official-schema.sql`（TeslaMate v3.1.0，100 条 migration 结果）设计。

如果你的 TeslaMate 版本较老（< v1.27），某些字段（如 `drives.outside_temp_avg`）可能缺失，bridge 会在玩法请求时返回 `scored: false` 并注明原因，不会报 500。升级 TeslaMate 至最新版可解决。

---

## 停止与清理

```bash
# demo 模式
docker compose --profile demo down

# prod 模式
docker compose --profile prod down

# 完全清理（含 volume）
docker compose --profile demo down -v
```

---

## 下一步

- [接入 Claude / MCP](connect-claude-mcp.md)
- [接入 ChatGPT Actions](connect-chatgpt.md)
- [接入 Coze 扣子](connect-coze.md)
- [生成分享图](image-generation.md)

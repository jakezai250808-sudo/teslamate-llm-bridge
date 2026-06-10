# 已有 TeslaMate：补装 bridge

> 适用场景：TeslaMate 已在运行，补装 teslamate-llm-bridge 接入 LLM 平台。  
> 实测环境：阿里云 ECS cn-hangzhou，TeslaMate PG 在 Docker 容器内，2026-06-10。  
> 所有命令均为实跑过的命令，不是示例性伪代码。

---

## 前置条件

确认以下信息再开始，**每项都要填对**（这是最常见的卡点来源）：

| 信息 | 怎么查 | 示例值 |
|---|---|---|
| TeslaMate PG 的监听地址 | `docker inspect <postgres容器名> \| grep IPAddress` 或 `docker ps \| grep postgres` 查端口 | 宿主机端口 `5532`，容器 IP `172.18.0.2` |
| TeslaMate PG 密码 | TeslaMate `docker-compose.yml` 的 `POSTGRES_PASSWORD` 环境变量 | — |
| TeslaMate 数据库名 | 同上 `POSTGRES_DB`，默认 `teslamate` | `teslamate` |
| car_id | 见下方 [查 car_id](#一查-car_id) | `1` |

---

## 一、查 car_id

bridge 每个玩法请求都需要 TeslaMate 的 `car_id`（整数），对应你在 TeslaMate 里的哪台车。

**方法一：TeslaMate Web UI**

打开 `http://<服务器IP>:4000/cars`，页面或 URL 里能看到车辆编号。

**方法二：psql 直接查**

找到 TeslaMate postgres 容器名：

```bash
docker ps | grep postgres
# 示例：teslamate-database-1
```

进容器执行查询：

```bash
docker exec -it <postgres容器名> psql -U teslamate -d teslamate \
  -c "SELECT id, name, vin FROM cars;"
```

输出示例：

```
 id |   name    |        vin        
----+-----------+-------------------
  1 | 我的Model3 | LRW3E7EA7LC108382
```

记住 `id` 列的值（本例为 `1`）。

> **没有 psql 工具？** 如果服务器没暴露 PG 端口：`docker exec -it <postgres容器名> psql ...` 走的是容器内自带的 psql，无需在宿主机安装任何工具。

---

## 二、确认 TM_DB_HOST 填什么

这是补装中**最容易填错的一项**，`.env.example` 默认值 `localhost` 在容器化场景里通常是错的。

### 判断逻辑

**情况 A：TeslaMate PG 容器对外暴露了宿主机端口（最常见）**

例如 `docker ps` 看到 `0.0.0.0:5432->5432/tcp` 或 `0.0.0.0:5532->5432/tcp`。

此时 bridge 容器内访问 `localhost` 访问的是 bridge 容器自身，访问不到宿主机。

正确填法（Linux）：

```bash
# 查 docker0 网桥 IP
ip addr show docker0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
# 通常是 172.17.0.1 或 172.18.0.1
```

把这个 IP 填进 `TM_DB_HOST`。

**情况 B：bridge 和 TeslaMate PG 在同一 docker compose network**

如果你把 bridge service 加入了 TeslaMate 的 compose 文件（或同一网络），可以直接用 postgres 的 service 名：

```dotenv
TM_DB_HOST=database    # TeslaMate compose 里的 postgres service 名
```

**情况 C：Mac Docker Desktop**

```dotenv
TM_DB_HOST=host.docker.internal
```

**情况 D：TeslaMate PG 是独立裸机或云数据库（非容器）**

填裸机 IP 或云 RDS 连接地址即可，这种场景 `localhost` 才是对的（如果 bridge 和 PG 在同一台机器上）。

---

## 三、克隆仓库

```bash
git clone https://github.com/teslamate-llm-bridge/teslamate-llm-bridge.git
cd teslamate-llm-bridge
```

---

## 四、配置 .env

```bash
cp .env.example .env
```

**必须修改的两项**（注释里说"only MUST set is TM_DB_PASS"——那条说明不完整，**host 也必须确认**）：

```dotenv
# 见上方第二节的判断逻辑——不要留 localhost
TM_DB_HOST=172.17.0.1

TM_DB_PORT=5432          # 宿主机映射出来的端口，不是容器内端口
TM_DB_NAME=teslamate
TM_DB_USER=teslamate
TM_DB_PASS=<TeslaMate PG 密码>   # 必填

# 可选：逗号分隔，限定暴露哪些 car_id
CAR_IDS=1

# 可选：公网暴露时强烈建议设置 Bearer token
API_TOKEN=
```

---

## 五、启动 bridge

### 方案一：docker compose（推荐）

```bash
docker compose --profile prod up -d --build
```

**关于首次 build 时间**：

- `--build` 触发容器内 `apt-get install maven` + `mvn -DskipTests package`。
- 国内环境（非 Maven CN mirror）**首次 build 需要 10-20 分钟**，不是文档示例里写的"10-30 秒"（那是缓存命中的时间）。
- 耐心等待，或使用下方方案二（预构建 jar）绕开。

观察启动进度：

```bash
docker compose --profile prod logs -f bridge
# 等到看到 "Tomcat started on port 8770" 或 "Started TeslamateLlmBridgeApplication"
```

### 方案二：预构建 jar（国内首选，跳过容器内 Maven 下载）

在有外网的机器（本地 Mac 等）构建：

```bash
cd bridge
mvn -DskipTests package
# 生成 target/teslamate-llm-bridge-*.jar（约 25MB）
```

将 jar 传到服务器后运行：

```bash
# 在服务器上：
docker run -d \
  --name teslabridge \
  --restart unless-stopped \
  -p 8770:8770 \
  -e TM_DB_HOST=172.17.0.1 \
  -e TM_DB_PORT=5432 \
  -e TM_DB_NAME=teslamate \
  -e TM_DB_USER=teslamate \
  -e TM_DB_PASS=<密码> \
  -e CAR_IDS=1 \
  -e API_TOKEN="" \
  -v /opt/teslabridge/app.jar:/app/app.jar \
  eclipse-temurin:21-jre-jammy \
  java -jar /app/app.jar
```

JVM 冷启动约 18 秒，之后：

```bash
docker logs teslabridge | grep "Tomcat started"
```

---

## 六、验证

```bash
# 健康检查
curl http://localhost:8770/actuator/health
# 期望：{"status":"UP"}

# 列出玩法
curl http://localhost:8770/api/v1/plays
# 期望：返回 7 个玩法的数组

# 跑驾驶人格（用你的 car_id）
curl "http://localhost:8770/api/v1/cars/1/play/driving-personality"
```

TeslaMate 刚装不久、行程数据少时，`driving-personality` 正常返回：

```json
{"play":"driving-personality","scored":false,"reason":"insufficient_data","drive_count":2}
```

`scored: false` 是正确行为，说明 30 天内行程不足 5 次。数据积累后会自动变成 `scored: true`。

验证 7 个玩法均可调用（含 demo 车 car_id=99 可用的 monthly-wrapped）：

```bash
# 实测通过（场景B验证结果）
curl "http://localhost:8770/api/v1/cars/1/play/monthly-wrapped"
curl "http://localhost:8770/api/v1/cars/1/play/charging-habit"
```

---

## 七、公网暴露与安全

如需从外部（ChatGPT Actions / Coze）访问 bridge：

```bash
# 生成随机 API token
echo "API_TOKEN=$(openssl rand -hex 32)" >> .env

# 用 force-recreate 让 bridge 读到新 env（restart 不行！）
docker compose --profile prod up -d --force-recreate bridge
```

> **注意**：必须用 `--force-recreate`，`docker compose restart` 不会重新读取 `.env` 文件，token 不会生效。

---

## 常见问题排查

### bridge 启动失败，日志显示"Connection refused"

大概率是 `TM_DB_HOST` 填错了（localhost 问题）。见第二节重新判断。

也可以用 `docker exec` 进 bridge 容器测试连通性：

```bash
docker exec -it <bridge容器名> sh -c "nc -zv 172.17.0.1 5432"
# 成功：Connection to 172.17.0.1 5432 port [tcp/postgresql] succeeded!
```

### `/api/v1/plays` 返回空数组

bridge 启动时从 classpath 加载 plays。如果自定义了 `PLAYS_DIR`，确认目录挂载正确：

```bash
docker exec <bridge容器名> ls /plays-custom/
```

### `scored: false` reason `insufficient_data`

正常。TeslaMate 需要积累一段时间数据（30 天内 ≥ 5 次行程）才会给出评分。用 demo 数据测试功能完整性，见 [从零安装 - demo 快速体验](install-from-zero.md#场景a--demo-快速体验无-teslamate)。

### 403 Unauthorized

`.env` 设置了 `API_TOKEN`，请求时需加 Bearer token：

```bash
curl -H "Authorization: Bearer <你的token>" http://localhost:8770/api/v1/plays
```

---

## 下一步

- [接入 Claude / MCP](connect-claude-mcp.md)
- [接入 ChatGPT Actions](connect-chatgpt.md)
- [接入 Coze 扣子](connect-coze.md)
- [生成分享图](image-generation.md)

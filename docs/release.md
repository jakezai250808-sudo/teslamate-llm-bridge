# 发版与镜像发布（GHCR）

预构建 Docker 镜像发布到 **GitHub Container Registry**：

```
ghcr.io/jakezai250808-sudo/teslamate-llm-bridge
```

由 [`.github/workflows/release-image.yml`](../.github/workflows/release-image.yml) 自动构建并推送，**多架构（linux/amd64 + linux/arm64）**，仅用内置 `GITHUB_TOKEN`，无需任何外部密钥。

---

## 用户：拉取使用

镜像与从源码 build 的等价（端口 8770，入口同 jar）。把 compose 里 `build:` 换成 `image:` 即可跳过本地构建（国内/弱网首选）：

```yaml
services:
  bridge:
    image: ghcr.io/jakezai250808-sudo/teslamate-llm-bridge:latest
    # 其余 env / ports 同 docker-compose.yml
```

可用 tag：
- `latest` — 最新正式版
- `1.2.3` / `1.2` / `1` — 语义化版本（major/minor/patch 各级别）
- `edge` — 手动触发的最新预览构建
- `sha-<commit>` — 按提交固定

---

## 维护者：怎么发一个版本

1. 确保 `main` CI 绿、要发布的内容已合入。
2. （建议）同步 `bridge/pom.xml` 的 `<version>` 到要发的版本号。
3. 打并推送一个 `vX.Y.Z` tag：
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```
4. workflow 自动触发：在 runner 上 `mvn package` 出 jar → 用 [`Dockerfile.release`](../Dockerfile.release) 打多架构镜像 → 推送 `0.1.0` / `0.1` / `0` / `latest` 等 tag 到 GHCR。
5. 首次发布后，到 GitHub 仓库 **Packages** 里把该 package 的可见性按需设为 public（默认随仓库私有）。

### 只想测一下不发正式版

在 Actions 里手动跑 **Release image (GHCR)**（`workflow_dispatch`），会推 `:edge` + `:sha-xxxx`，不动 `latest`。

---

## 设计说明

- **为什么单独一个 `Dockerfile.release`**：根目录 `Dockerfile` 在镜像内从源码 build（给用户自助构建用）；发布走「runner 上 build 一次 jar → 只打包 jar」。Spring Boot fat jar 是纯 JVM 字节码、跨架构通用，所以一份 jar 同时供 amd64/arm64，多架构镜像无需 QEMU 模拟 Maven 构建，构建快且稳。
- **凭据**：`GITHUB_TOKEN` + `permissions: packages: write` 足够推 GHCR，无需 PAT 或外部 secret。

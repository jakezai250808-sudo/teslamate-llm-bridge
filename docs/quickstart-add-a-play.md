# Quickstart：10 分钟加一个 play

想给项目加一个新玩法？你**不需要写一行 Java 或 Python**——一个 play 就是一个目录里的两个 YAML 文件。本指南带你 10 分钟跑通从零到 PR。

> 想要逐字段的完整规范，看 [`AGENTS.md`](../AGENTS.md)（9 步详解，为 AI agent 写的）和 [`docs/play-manifest-spec.md`](play-manifest-spec.md)（规范正文）。本页是给**人类贡献者**的轻量上手版。

---

## 一个 play 长什么样

```
plays/<your-play-name>/
├── play.yaml       # 必填：SQL + 计算管线 + 输出字段
├── fixtures.yaml   # 必填：测试用例（没有它 CI 直接挂）
└── creative-prompt.md  # 可选：生图模板，第一版可以不写
```

数据流一句话：**一条只读 SQL 聚合出一行数字 → compute 管线把数字算成分数/档位/文案 → output 暴露给 LLM 平台**。

---

## 0. 准备（一次性，约 1 分钟）

```bash
pip install --break-system-packages pyyaml   # 校验脚本只依赖它
```

> macOS 自带 Python 是 externally-managed，所以加 `--break-system-packages`；不想动系统就先 `python3 -m venv /tmp/v && source /tmp/v/bin/activate`。

---

## 1. 想清楚一句话概念（约 1 分钟）

能用一句话说清输出，就能做成 play。例：
- 「窗口内夜间行驶占比，0–100 分 + 人格称号」→ 夜猫子指数
- 「累计爬升米数，换算成几座东方明珠 + 登山人格」→ 电动登山家

先去 `plays/` 扫一眼别撞车，名字用 kebab-case（`^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$`，且**等于目录名**）。

```bash
cp -r plays/_template plays/<your-play-name>   # 从带注释的模板起步
```

---

## 2. 写 SQL（约 3 分钟）

跑在 TeslaMate 只读库上，**聚合成恰好一行**。硬规则（违反了引擎加载时直接跳过）：

- 以 `SELECT` 或 `WITH` 开头，**全程不能有 `;`**。
- 必须有真实的租户过滤 `car_id = :car_id`（`d.car_id = :car_id` 也行）。
- 禁止写操作：`INSERT / UPDATE / DELETE / DROP / ALTER / CREATE / TRUNCATE / GRANT / COPY / DO / CALL / pg_sleep`。
- 只有 4 个绑定参数：`:car_id`、`:tz`（恒为 `Asia/Shanghai`）、`:start`、`:end`。窗口用 `start_date >= :start AND start_date < :end`。
- `SUM/AVG/MAX` 空输入返 NULL，**必须 `COALESCE(..., 0)`**；`COUNT(*)` 空集自动返 0，不用包。
- 取本地小时/日期要**双重时区转换**（TeslaMate 存的是 naive UTC）：`(start_date AT TIME ZONE 'UTC') AT TIME ZONE :tz`。单写一次 `AT TIME ZONE :tz` 会整体偏 8 小时。

可读的表（v1 只读 scope）：

| 表 | 常用列 |
|---|---|
| `drives` | `car_id, start_date, end_date, distance, duration_min, speed_max, power_max, ascent, descent` |
| `charging_processes` | `car_id, start_date, start_battery_level, end_battery_level, charge_energy_added, duration_min` |
| `positions` | `car_id, date, latitude, longitude, elevation, battery_level, speed, power`（大表，务必聚合 + 按时间窗过滤） |

```yaml
sql: |-
  SELECT
    COUNT(*) AS drive_count,
    COALESCE(SUM(ascent), 0) AS total_ascent_m
  FROM drives
  WHERE car_id = :car_id
    AND start_date >= :start
    AND start_date < :end
```

---

## 3. 写 compute 管线（约 3 分钟）

有序步骤，每步**只能是四选一**：

| 类型 | 用途 | 关键约束 |
|---|---|---|
| `expr` | 算术 | 只有 `+ - * / ( )` 和 `GREATEST(a,b)` / `LEAST(a,b)` / `ROUND(x)`；除零返 0。**没有比较、没有条件**——条件逻辑去 `level`。 |
| `level` | 阈值分档 | 从上往下，第一个 `lt` 严格大于输入的命中；**最后一项必须省略 `lt`** 当兜底，否则加载失败。 |
| `template` | 字符串拼接 | 只支持 `${变量}`，可用之前的 var / SQL 列 / 内置 `window_days`。 |
| `lookup` | 查表 | 配合顶层 `tables:`，**`default:` 必填**（保证全覆盖）。产出一个对象。 |

夹分数到 0–100 用 `LEAST(GREATEST(x, 0), 100)` 这类写法。

```yaml
compute:
  - var: pearl_count
    expr: "ROUND(total_ascent_m / 468)"          # 东方明珠塔当量
  - var: persona_name
    level:
      input: total_ascent_m
      thresholds:
        - { lt: 500,  label: "平原巡航员" }
        - { lt: 3776, label: "周末登山客" }
        - { label: "云端征服者" }                 # 末项省 lt = 兜底
  - var: summary
    template: "累计爬升 ${total_ascent_m} 米，你是「${persona_name}」。"
```

`output.fields` 列出要暴露给平台的字段（`{ name, from, type }`，`type ∈ number|string|object`）——headline 数字 + 档位 + summary + 让结果可解释的原始计数。

文案风格：主要受众是中国 Tesla 车主，**中文标签/摘要**为主，`description` 用对 LLM 友好的话术。**自嘲可以，羞辱不行。**

---

## 4. 写 fixtures（约 2 分钟，多数 PR 栽在这）

fixtures 让 play 不连数据库也能测：CI 把每个 `row` 喂进 `min_sample` + compute，断言 `expect`。要求：

- 顶层 key 是 `fixtures:`（不是 `cases:`）。
- **≥ 2 个用例命中不同 `level` 分支**（最好每个分支都覆盖）。
- **≥ 1 个 `expect_unscored: true`** 用例（低于 `min_sample`，不带 `expect:`）。
- **每个期望值都自己手算**，并把算式写进 YAML 注释——这正是 fixtures 的意义。算出来跟直觉不符，是你的 `expr` 错了，改 expr 不要改 fixture。

```yaml
fixtures:
  - name: summit
    row: { drive_count: 60, total_ascent_m: 9200 }
    expect:
      pearl_count: 20            # ROUND(9200/468) = ROUND(19.66) = 20
      persona_name: "云端征服者"  # 9200 ≥ 3776 → 兜底
  - name: too-few-drives
    row: { drive_count: 3, total_ascent_m: 120 }
    expect_unscored: true        # drive_count 3 < min_sample → 不评分
```

---

## 5. 本地校验（与 CI 等价，约 30 秒）

```bash
# 1) 引擎镜像校验 + 跑所有 fixture（纯 Python，不连库、不要 docker）
python3 tools/validate_plays.py            # 必须全 PASS

# 2) JSON Schema 校验（draft 2020-12）
python3 -c "import sys,json,yaml; print(json.dumps(yaml.safe_load(open('plays/<your-play-name>/play.yaml'))))" > /tmp/play.json
npx --yes ajv-cli@5 validate --spec=draft2020 -s plays/play.schema.json -d /tmp/play.json
```

> `mvn test`（Testcontainers）和真实 demo 库 E2E 需要 Docker，没装也没关系——CI 会跑。本地把上面两步跑绿就够提 PR。

---

## 6. 提 PR

```bash
git checkout -b play/<your-play-name>
git add plays/<your-play-name>
git commit -m "play: <your-play-name> — <一句话描述>"
git push -u origin play/<your-play-name>
```

- 标题：`play: <name> — <一句话描述>`。**一个 PR 只放一个 play**，别顺手改别的 play / schema / spec。
- 第一次提 PR 需要签 CLA：在 PR 里评论一行
  `I have read the CLA Document and I hereby sign the CLA`（机器人会自动标记通过，一次性）。详见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

PR checklist（CI 会卡）：

- [ ] `python3 tools/validate_plays.py` 全 PASS
- [ ] schema 校验通过（draft 2020-12）
- [ ] SQL：单条 `SELECT/WITH`、真实 `car_id = :car_id`、无黑名单词、聚合一行
- [ ] `level` 有兜底项；`lookup` 覆盖所有 label 且有 `default`
- [ ] fixtures：覆盖各 level 分支 + 一个 unscored，值都手算过

---

## 完整参考

- 范例 play：[`plays/driving-personality/`](../plays/driving-personality/)（16 型人格，最完整的参照）
- 逐字段规范：[`docs/play-manifest-spec.md`](play-manifest-spec.md)
- AI agent 详细操作手册：[`AGENTS.md`](../AGENTS.md)
- 生图（接口二）：[`docs/image-generation.md`](image-generation.md)

有问题就开 issue。Happy hacking ⛰️

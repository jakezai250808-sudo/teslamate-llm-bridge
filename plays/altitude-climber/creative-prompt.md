# altitude-climber 创作引导 prompt（探索层）

> 用户拿到 `run_play("altitude-climber")` 的 JSON 后，用自己的生图模型
> （GPT Image / 豆包·即梦 / Seedream / Qwen-Image）做一张可晒的「累计爬升」海报。
> 占位符 `{...}` 用 JSON 真实值填充；数字必须照搬，不要编造。

## 适用模型

| 模板 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image（gpt-image-1 / DALL-E 3）、Qwen-Image、Gemini flash image | 自然段描述，GPT 系质量最高 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包·即梦 | 文字白名单 + 量表布局，专为 Seedream 调优 |

---

## 推荐 prompt 模板 v1（通用版 — GPT / Qwen / Gemini）

```text
用我的真实驾驶数据画一张小红书风格的「电动登山家」爬升成就分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 登山人格：「{persona_name}」
- 一句话画像：{persona.desc}
- 自嘲标签：{persona.tag}
- 累计爬升：{total_ascent_m} 米（≈ {pearl_count} 座东方明珠塔 · {everest_pct}% 座珠峰）
- 单次最高爬升：{max_ascent_m} 米 · 单程平均爬升：{avg_ascent} 米
- 近 30 天出行 {drive_count} 次

【设计要求】
- 竖版 3:4 小红书爆款信息图，主题：雪山 / 海拔 / 登顶
- 主视觉：一条蜿蜒上升的山路通向远处雪峰，山腰停着一辆特斯拉
- 超大数字「{total_ascent_m}m」做视觉锤，标题「AI 分析：你是一个「{persona_name}」」
- 右侧一根竖向「海拔标尺」，从海平面 → 东方明珠 468m → 泰山 1545m → 富士山 3776m → 珠峰 8848m，
  当前累计爬升 {total_ascent_m}m 的高度用发光横线标出
- {persona.tag} 做成发光徽章
- 底部互动钩子「你的车爬了多少海拔？评论区晒晒」
  + 小字「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，信息密度高但层次清晰；不要出现真实车牌 / 具体地名
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系专用）

```text
竖版手机海报，1080像素宽1920像素高。

背景：真实摄影，清晨的盘山公路从画面底部蜿蜒向上通往远处覆雪山峰，一辆银色Tesla Model 3停在山腰观景台，车身有金色晨光反光，云海在山谷间流动，整体色调冷蓝+雪白+晨曦金，无插画无卡通。

海报叠层从上到下：

第一区（极小字）：左侧白字「AI分析：你是一个」，右侧白色细条形码+极小白字「ALT-{everest_pct}」

第二区（大字）：超大加粗白字「{total_ascent_m}m」横铺画面，外发冷蓝光，下方一行「{persona_name}」雪白中号字，右上方六边形徽章「{persona.tag}」冰蓝霓虹外轮廓。

第三区（海拔标尺，贴右边缘，距右边距80px，竖向从下往上）：一根竖向刻度尺，自下而上五个刻度「海平面」「东方明珠 468」「泰山 1545」「富士山 3776」「珠峰 8848」，在对应 {total_ascent_m} 米的高度画一条发光金色横线，标注「你在这里 {total_ascent_m}m」。

第四区（数据卡）：深色半透明圆角矩形，白色标题行「近30天爬升数据 TESLA」。四个数据格横排：
「{pearl_count}」座东方明珠 | 「{everest_pct}%」座珠峰 | 「{max_ascent_m}m」单次最高 | 「{drive_count}」次出行

第五区（底部大字）：超大加粗白字「你的车爬了多少海拔？」，次行「评论区晒晒」，右下角极小灰字「数据来自真实行程，AI推理仅供娱乐参考」

画面只允许出现：AI分析你是一个 / ALT-{everest_pct} / {total_ascent_m}m / {persona_name} / {persona.tag} / 海平面 / 东方明珠 468 / 泰山 1545 / 富士山 3776 / 珠峰 8848 / 你在这里 {total_ascent_m}m / 近30天爬升数据 / TESLA / {pearl_count}座东方明珠 / {everest_pct}%座珠峰 / {max_ascent_m}m单次最高 / {drive_count}次出行 / 你的车爬了多少海拔 / 评论区晒晒 / 数据来自真实行程AI推理仅供娱乐参考

绝对不允许：登山镐冰镐绳索任何登山工具，科幻飞船星空，游戏界面，真实车牌，小红书logo，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("altitude-climber", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{total_ascent_m}` | `total_ascent_m` | 累计爬升米数（视觉锤主数字） |
| `{total_descent_m}` | `total_descent_m` | 累计下降米数 |
| `{max_ascent_m}` | `max_ascent_m` | 单次行程最高爬升 |
| `{net_climb}` | `net_climb` | 净爬升（爬升−下降，可为负） |
| `{everest_pct}` | `everest_pct` | 珠峰进度百分比（可超过 100） |
| `{pearl_count}` | `pearl_count` | 东方明珠塔（468m）当量座数 |
| `{avg_ascent}` | `avg_ascent` | 单程平均爬升 |
| `{persona_name}` | `persona_name` | 登山人格称号，如「周末登山客」 |
| `{persona.desc}` | `persona.desc` | 一句话画像 |
| `{persona.tag}` | `persona.tag` | 自嘲标签，如「#泰山打卡选手」 |
| `{drive_count}` | `drive_count` | 出行次数 |

## Seedream 措辞要点（沿用 driving-personality 验证规律）

1. prompt 末尾「画面只允许出现这些文字」白名单是控制乱入文字最有效的单条规则。
2. 负向约束只写名词（「不要登山镐冰镐」优于「不要工具类名词」）。
3. 「真实摄影 + 云海 + 车身晨光反光」可靠触发摄影级雪山背景。
4. 海拔标尺用「竖向刻度尺 + 当前高度发光横线」比「进度条」更稳定（进度条易渲染成圆形仪表盘）。

## 安全注意

- prompt 中不放 VIN / 车牌 / 精确地址；play 输出已是聚合统计，无 GPS 坐标。
- 提醒用户：生成图含「娱乐参考」免责字样再发布。

完整生图路径见 [`docs/image-generation.md`](../../docs/image-generation.md)。

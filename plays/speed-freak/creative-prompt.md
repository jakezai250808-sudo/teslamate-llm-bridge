# speed-freak 创作引导 prompt（探索层）

> 两阶段漏斗的第一阶段：用户拿到 play JSON 结果后，用自己的生图模型
> （GPT Image / 豆包生图 / 即梦）自由创作分享图。本文件是推荐 prompt 模板。

## 给 LLM 平台的使用方式

LLM（ChatGPT / 豆包 bot）调用 `runPlay(speed-freak)` 拿到 JSON 后，
应主动建议用户：「要把你的速度狂人指数做成一张分享图吗？」用户同意后，
把下面模板的 `{占位符}` 用 JSON 里的真实值填充，调用生图能力。

## 适用模型说明

| 模板版本 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image、Qwen-Image、Gemini flash image | 自然段描述 + 设计语言词 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包生图 | 白名单约束 + 元语言翻译 |

---

## 推荐 prompt 模板 v1（通用版）

```text
用我的真实驾驶数据画一张小红书风格的速度狂人指数分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 速度狂人指数：{speed_score}/100「{persona.name}」
- 人格描述：{persona.desc}
- 自嘲标签：{persona.tag}
- 最快速度：{top_speed}km/h · 平均速度：{avg_speed}km/h
- {fast_ratio}% 行程开到 120km/h 以上 · {fury_ratio}% 行程突破 150km/h
- 统计 {total_drives} 次行程

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围：速度感——动态模糊公路背景、速度线、仪表盘元素
- 超大字速度狂人指数 {speed_score} 作为视觉锤，标题「AI 分析：你是「{persona.name}」」
- 速度分布四档条形图（城市<80 / 高速80-119 / 快速120-149 / 极速150+）
- {persona.tag} 做成发光徽章
- 底部互动钩子「你的速度狂人指数是多少？评论区聊聊」
  + 小字「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，信息密度高但层次清晰
- 不要出现真实车牌 / 具体地名 / 具体道路编号
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系模型专用）

```text
竖版手机海报，1536像素宽2048像素高。

背景：真实摄影，Tesla Model 3在高速公路上，动态模糊速度线，远处隧道口灯光拉成光带，路面干燥有热气蒸腾感，整体色调深灰+橙红速度线+白光，无插画无卡通。

海报叠层从上到下：

第一行（极小字）：左侧白字「AI分析：你是」，右侧白色细条形码+极小白字「SPD-{speed_score}-3Y」

第二区（大字）：超大加粗斜体数字「{speed_score}」横铺画面，数字颜色左橙右红渐变，外发白光，磨砂纹理感。下方一行「{persona.name}」白色中号字。右上方六边形徽章「{persona.tag}」橙色霓虹外轮廓。

第三区（数据卡）：深色半透明圆角矩形，内有白色标题行「速度狂人指数 SPEED FREAK」。五个数据格横排：
「{top_speed}km/h」最快速度 | 「{avg_speed}km/h」平均速度 | 「{fast_ratio}%」上120 | 「{fury_ratio}%」破150 | 「{total_drives}」次行程

第四区（速度分布卡）：深色半透明圆角矩形，白色标题「速度分布四档」。
四行量表，每行：档位名 — 水平色条 — 占比：
行一：城市巡航 <80km/h — 绿色填充 — 「{city_ratio}%」
行二：高速巡航 80-119 — 蓝色填充 — 「{highway_ratio}%」
行三：快速行驶 120-149 — 橙色填充 — 「{fast_ratio}%」
行四：极速狂飙 150+ — 红色填充 — 「{fury_ratio}%」

第五区（底部大字）：超大加粗白字「你的速度狂人指数是多少？」，次行「评论区聊聊」，右下角极小灰字「数据来自真实行程，AI推理仅供娱乐参考」

画面只允许出现：AI分析你是 / SPD-{speed_score}-3Y / {speed_score} / {persona.name} / {persona.tag} / 速度狂人指数SPEEDFREAK / {top_speed}km/h最快速度 / {avg_speed}km/h平均速度 / {fast_ratio}%上120 / {fury_ratio}%破150 / {total_drives}次行程 / 速度分布四档 / 城市巡航 / 高速巡航 / 快速行驶 / 极速狂飙 / 你的速度狂人指数是多少 / 评论区聊聊 / 数据来自真实行程AI推理仅供娱乐参考

绝对不允许：事故碰撞任何危险场景，警车救护车，车祸，超速罚单，真实车牌，小红书logo，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("speed-freak", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{speed_score}` | `speed_score` | 速度狂人指数 0-100 |
| `{persona.name}` | `persona.name` | 人格名，如「赛道在逃选手」 |
| `{persona.desc}` | `persona.desc` | 人格描述 |
| `{persona.tag}` | `persona.tag` | 自嘲标签 |
| `{top_speed}` | `top_speed` | 最快速度 km/h |
| `{avg_speed}` | `avg_speed` | 平均速度 km/h |
| `{fast_ratio}` | `fast_ratio` | 120+ km/h 行程占比 |
| `{fury_ratio}` | `fury_ratio` | 150+ km/h 行程占比 |
| `{total_drives}` | `total_drives` | 总行程数 |
| `{city_ratio}` | `city_drives / total_drives × 100` 取整 | 城市占比（需 LLM 计算，city_drives 在 SQL 返回但未 output——如需精确可用 highway_drives 反推） |
| `{highway_ratio}` | `highway_drives / total_drives × 100` 取整 | 高速占比（同上） |

> **注意**：`city_drives` 和 `highway_drives` 在 SQL 中返回但未放入 `output.fields`（避免 output 冗余）。LLM 填充 v2 模板时，若需要精确四档占比，可以从 JSON 的 `total_drives` 和 `fast_ratio`/`fury_ratio` 反推：city + highway = 100 - fast_ratio - fury_ratio。

---

## 验证记录

| 模型 | 状态 | 备注 |
|---|---|---|
| GPT Image（gpt-image-1） | 待测 | v1 模板 |
| Seedream-4.0 | 待测 | v2 模板 |

# punch-king 创作引导 prompt（探索层）

> 两阶段漏斗的第一阶段：用户拿到 play JSON 结果后，用自己的生图模型
> （GPT Image / 豆包生图 / 即梦）自由创作分享图。本文件是推荐 prompt 模板。

## 给 LLM 平台的使用方式

LLM（ChatGPT / 豆包 bot）调用 `runPlay(punch-king)` 拿到 JSON 后，
应主动建议用户：「要把你的地板电指数做成一张分享图吗？」用户同意后，
把下面模板的 `{占位符}` 用 JSON 里的真实值填充，调用生图能力。

## 适用模型说明

| 模板版本 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image、Qwen-Image、Gemini flash image | 自然段描述 + 设计语言词 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包生图 | 白名单约束 + 元语言翻译 |

---

## 推荐 prompt 模板 v1（通用版）

```text
用我的真实驾驶数据画一张小红书风格的地板电王者分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 地板电指数：{punch_score}/100「{persona.name}」
- 人格描述：{persona.desc}
- 自嘲标签：{persona.tag}
- 峰值功率：{peak_power}kW · 平均功率：{avg_power}kW
- {punch_ratio}% 行程踩到 150kW 以上 · {full_send_ratio}% 行程突破 250kW
- 统计 {total_drives} 次行程

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围：力量感——电机扭矩曲线、能量脉冲波纹、深色背景+电光蓝紫
- 超大字体地板电指数 {punch_score} 作为视觉锤，标题「AI 分析：你是「{persona.name}」」
- 功率分布四档条形图（佛系<100 / 日常100-149 / 推背150-249 / 全油门250+）
- {persona.tag} 做成发光徽章
- 底部互动钩子「你的地板电指数是多少？评论区聊聊」
  + 小字「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，信息密度高但层次清晰
- 不要出现真实车牌 / 具体地名
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系模型专用）

```text
竖版手机海报，1536像素宽2048像素高。

背景：真实摄影，Tesla Model 3从隧道中驶出，尾灯拉出红色光带，隧道壁有蓝色LED灯带反射在车身上，路面干燥，整体色调深灰+电光蓝紫+橙红尾灯光，无插画无卡通。

海报叠层从上到下：

第一行（极小字）：左侧白字「AI分析：你是」，右侧白色细条形码+极小白字「PNC-{punch_score}-P3D」

第二区（大字）：超大加粗斜体数字「{punch_score}」横铺画面，数字颜色左蓝右紫渐变，外发白光，磨砂纹理感。下方一行「{persona.name}」白色中号字。右上方六边形徽章「{persona.tag}」蓝紫霓虹外轮廓。

第三区（数据卡）：深色半透明圆角矩形，内有白色标题行「地板电王者 PUNCH KING」。五个数据格横排：
「{peak_power}kW」峰值功率 | 「{avg_power}kW」平均功率 | 「{punch_ratio}%」踩150kW+ | 「{full_send_ratio}%」突破250kW | 「{total_drives}」次行程

第四区（功率分布卡）：深色半透明圆角矩形，白色标题「功率分布四档」。
四行量表，每行：档位名 — 水平色条 — 占比：
行一：佛系滑行 <100kW — 绿色填充 — 「{chill_ratio}%」
行二：日常驾驶 100-149 — 蓝色填充 — 「{normal_ratio}%」
行三：推背模式 150-249 — 紫色填充 — 「{punch_ratio}%」
行四：全油门 250kW+ — 红色填充 — 「{full_send_ratio}%」

第五区（底部大字）：超大加粗白字「你的地板电指数是多少？」，次行「评论区聊聊」，右下角极小灰字「数据来自真实行程，AI推理仅供娱乐参考」

画面只允许出现：AI分析你是 / PNC-{punch_score}-P3D / {punch_score} / {persona.name} / {persona.tag} / 地板电王者PUNCHKING / {peak_power}kW峰值功率 / {avg_power}kW平均功率 / {punch_ratio}%踩150kW / {full_send_ratio}%突破250kW / {total_drives}次行程 / 功率分布四档 / 佛系滑行 / 日常驾驶 / 推背模式 / 全油门 / 你的地板电指数是多少 / 评论区聊聊 / 数据来自真实行程AI推理仅供娱乐参考

绝对不允许：事故碰撞任何危险场景，轮胎冒烟烧胎，漂移，飙车竞速，真实车牌，小红书logo，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("punch-king", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{punch_score}` | `punch_score` | 地板电指数 0-100 |
| `{persona.name}` | `persona.name` | 人格名，如「全油门战士」 |
| `{persona.desc}` | `persona.desc` | 人格描述 |
| `{persona.tag}` | `persona.tag` | 自嘲标签 |
| `{peak_power}` | `peak_power` | 峰值功率 kW |
| `{avg_power}` | `avg_power` | 平均功率 kW |
| `{punch_ratio}` | `punch_ratio` | 150+ kW 行程占比 |
| `{full_send_ratio}` | `full_send_ratio` | 250+ kW 行程占比 |
| `{total_drives}` | `total_drives` | 总行程数 |
| `{chill_ratio}` | `chill_drives / total_drives × 100` 取整 | 佛系占比（需 LLM 从 SQL 返回列计算） |
| `{normal_ratio}` | `normal_drives / total_drives × 100` 取整 | 日常占比（同上） |

> **注意**：`chill_drives` 和 `normal_drives` 在 SQL 中返回但未放入 `output.fields`。LLM 填充 v2 模板时，可以从 `total_drives` 和 `punch_ratio`/`full_send_ratio` 反推：chill + normal = 100 - punch_ratio - full_send_ratio。

---

## 验证记录

| 模型 | 状态 | 备注 |
|---|---|---|
| GPT Image（gpt-image-1） | 待测 | v1 模板 |
| Seedream-4.0 | 待测 | v2 模板 |

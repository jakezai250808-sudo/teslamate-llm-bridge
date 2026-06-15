# g-force-hunter 创作引导 prompt（探索层）

> 用户拿到 play JSON 结果后，用自己的生图模型自由创作分享图。

## 给 LLM 平台的使用方式

LLM 调用 `runPlay(g-force-hunter)` 拿到 JSON 后，
应主动建议用户：「要把你的推背 G 值做成一张分享图吗？」

## 适用模型说明

| 模板版本 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image、Qwen-Image、Gemini flash image | 自然段描述 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包生图 | 白名单约束 |

---

## 推荐 prompt 模板 v1（通用版）

```text
用我的真实驾驶数据画一张小红书风格的推背G值猎人分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 最强推背：{max_g}G「{persona.name}」
- 推背指数：{g_score}/100
- 人格描述：{persona.desc}
- 自嘲标签：{persona.tag}
- 峰值功率：{peak_power}kW · 平均功率：{avg_power}kW
- {strong_ratio}% 行程超 200kW · {extreme_ratio}% 突破 300kW
- 统计 {total_drives} 次行程

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围：加速度感——动态模糊隧道、G 值仪表盘、速度线、深色背景+电光蓝紫
- 超大字体 {max_g}G 作为视觉锤，标题「AI 分析：你是「{persona.name}」」
- G 值仪表盘可视化（0→0.3→0.55→0.8→1.0G 四段刻度）
- {persona.tag} 做成发光徽章
- 底部互动钩子「你的最强推背是多少 G？评论区聊聊」
  + 小字「G 值由峰值功率简化估算，AI 推理仅供娱乐参考」
- 中文排版，不要出现真实车牌/地名
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系模型专用）

```text
竖版手机海报，1536像素宽2048像素高。

背景：真实摄影，Tesla Model 3从隧道中加速驶出，尾灯拉出红色光带，隧道壁蓝色LED灯带反射在车身上形成速度光纹，路面有轻微热气蒸腾，整体色调深灰+电光蓝紫+橙红尾灯光，无插画无卡通。

海报叠层从上到下：

第一行（极小字）：左侧白字「AI分析：你是」，右侧白色细条形码+极小白字「GF-{max_g}-P3D」

第二区（大字）：超大加粗斜体数字「{max_g}」横铺画面，数字颜色左蓝右紫渐变，外发白光，磨砂纹理感。右侧大字「G」白色半透明叠加。下方一行「{persona.name}」白色中号字。右上方六边形徽章「{persona.tag}」蓝紫霓虹外轮廓。

第三区（G值仪表盘）：深色半透明圆角矩形，白色标题行「推背G值 G-FORCE」。圆形仪表盘风格——四段刻度环 0→0.3→0.55→0.8→1.0G，当前 {max_g}G 段高亮发光，指针指向 {max_g}G。

第四区（数据卡）：深色半透明圆角矩形，白色标题「功率数据」。五个数据格横排：
「{peak_power}kW」峰值功率 | 「{avg_power}kW」平均功率 | 「{strong_ratio}%」超200kW | 「{extreme_ratio}%」破300kW | 「{total_drives}」次行程

第五区（底部大字）：超大加粗白字「你的最强推背是多少G？」，次行「评论区聊聊」，右下角极小灰字「G值由峰值功率简化估算，AI推理仅供娱乐参考」

画面只允许出现：AI分析你是 / GF-{max_g}-P3D / {max_g}G / {persona.name} / {persona.tag} / 推背G值GFORCE / {peak_power}kW峰值功率 / {avg_power}kW平均功率 / {strong_ratio}%超200kW / {extreme_ratio}%破300kW / {total_drives}次行程 / 0 / 0.3 / 0.55 / 0.8 / 1.0 / 你的最强推背是多少G / 评论区聊聊 / G值由峰值功率简化估算AI推理仅供娱乐参考

绝对不允许：事故碰撞任何危险场景，轮胎冒烟烧胎，漂移，飙车竞速，真实车牌，小红书logo，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("g-force-hunter", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{max_g}` | `max_g` | 估算最大推背 G 值 |
| `{g_score}` | `g_score` | 推背指数 0-100 |
| `{persona.name}` | `persona.name` | 人格名 |
| `{persona.desc}` | `persona.desc` | 人格描述 |
| `{persona.tag}` | `persona.tag` | 自嘲标签 |
| `{peak_power}` | `peak_power` | 峰值功率 kW |
| `{avg_power}` | `avg_power` | 平均功率 kW |
| `{strong_ratio}` | `strong_ratio` | 200+ kW 占比 |
| `{extreme_ratio}` | `extreme_ratio` | 300+ kW 占比 |
| `{total_drives}` | `total_drives` | 总行程数 |

所有占位符均对应 `output.fields` 中的真实字段。

---

## 验证记录

| 模型 | 状态 | 备注 |
|---|---|---|
| GPT Image（gpt-image-1） | 待测 | v1 模板 |
| Seedream-4.0 | 待测 | v2 模板 |

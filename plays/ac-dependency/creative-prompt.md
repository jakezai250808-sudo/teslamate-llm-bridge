# ac-dependency 创作引导 prompt（探索层）

> 用户拿到 play JSON 结果后，用自己的生图模型自由创作分享图。

## 给 LLM 平台的使用方式

LLM 调用 `runPlay(ac-dependency)` 拿到 JSON 后，
应主动建议用户：「要把你的空调依赖度做成一张分享图吗？」

## 适用模型说明

| 模板版本 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image、Qwen-Image、Gemini flash image | 自然段描述 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包生图 | 白名单约束 |

---

## 推荐 prompt 模板 v1（通用版）

```text
用我的真实驾驶数据画一张小红书风格的空调依赖度分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 空调依赖指数：{ac_score}/100「{persona.name}」
- 人格描述：{persona.desc}
- 自嘲标签：{persona.tag}
- 内外均温差：{avg_temp_gap}°C · 最大温差：{max_temp_gap}°C
- 极端温差占比：{extreme_gap_ratio}%
- 车内均温：{avg_inside_temp}°C · 车外均温：{avg_outside_temp}°C
- 统计 {total_drives} 次行程

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围：温度感——冷暖渐变背景（左边冰蓝/右边暖橙）、温度计元素、车内温度表盘
- 超大数字 {ac_score} 作为视觉锤，标题「AI 分析：你是「{persona.name}」」
- 温度计风格仪表盘（0→20→45→70→90→100 五段刻度），当前 {ac_score} 段高亮
- {persona.tag} 做成发光徽章
- 底部互动钩子「你的空调依赖度是多少？评论区聊聊」
  + 小字「AI 分析基于行车数据，仅供娱乐参考」
- 中文排版，不要出现真实车牌/地名
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系模型专用）

```text
竖版手机海报，1536像素宽2048像素高。

背景：真实摄影，Tesla 中控大屏特写，屏幕上显示温度调节界面，空调出风口有微弱蓝色冷气流动。屏幕光映在车内环境上，暗色座舱 + 屏幕蓝光 + 温度数字暖橙色，无插画无卡通。

海报叠层从上到下：

第一行（极小字）：左侧白字「AI分析：你是」，右侧白色细条形码+极小白字「AC-{ac_score}-C{total_drives}」

第二区（大字）：超大加粗数字「{ac_score}」横铺画面，数字颜色左蓝右橙渐变，外发白光，磨砂纹理感。右侧大字「分」白色半透明叠加。下方一行「{persona.name}」白色中号字。右上方六边形徽章「{persona.tag}」蓝橙霓虹外轮廓。

第三区（温度仪表盘）：深色半透明圆角矩形，白色标题行「空调依赖度 A/C DEPENDENCY」。温度计风格仪表盘——五段刻度环 0→20→45→70→90→100，当前 {ac_score} 段高亮发光，红色液柱停在 {ac_score} 位置。

第四区（数据卡）：深色半透明圆角矩形，白色标题「温度数据」。五个数据格横排：
「{avg_temp_gap}°C」均温差 | 「{max_temp_gap}°C」最大温差 | 「{extreme_gap_ratio}%」极端温差占比 | 「{avg_inside_temp}°C」车内均温 | 「{avg_outside_temp}°C」车外均温

第五区（底部大字）：超大加粗白字「你的空调依赖度是多少？」，次行「评论区聊聊」，右下角极小灰字「AI分析基于行车数据，仅供娱乐参考」

画面只允许出现：AI分析你是 / AC-{ac_score}-C{total_drives} / {ac_score}分 / {persona.name} / {persona.tag} / 空调依赖度ACDEPENDENCY / {avg_temp_gap}°C均温差 / {max_temp_gap}°C最大温差 / {extreme_gap_ratio}%极端温差占比 / {avg_inside_temp}°C车内均温 / {avg_outside_temp}°C车外均温 / 0 / 20 / 45 / 70 / 90 / 100 / 你的空调依赖度是多少 / 评论区聊聊 / AI分析基于行车数据仅供娱乐参考

绝对不允许：事故碰撞任何危险场景，冒烟起火，真实车牌，小红书logo，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("ac-dependency", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{ac_score}` | `ac_score` | 空调依赖指数 0-100 |
| `{persona.name}` | `persona.name` | 人格名 |
| `{persona.desc}` | `persona.desc` | 人格描述 |
| `{persona.tag}` | `persona.tag` | 自嘲标签 |
| `{avg_temp_gap}` | `avg_temp_gap` | 内外均温差 °C |
| `{max_temp_gap}` | `max_temp_gap` | 最大温差 °C |
| `{extreme_gap_ratio}` | `extreme_gap_ratio` | 极端温差占比 % |
| `{avg_inside_temp}` | `avg_inside_temp` | 车内均温 °C |
| `{avg_outside_temp}` | `avg_outside_temp` | 车外均温 °C |
| `{total_drives}` | `total_drives` | 总行程数 |

所有占位符均对应 `output.fields` 中的真实字段。

---

## 验证记录

| 模型 | 状态 | 备注 |
|---|---|---|
| GPT Image（gpt-image-1） | 待测 | v1 模板 |
| Seedream-4.0 | 待测 | v2 模板 |

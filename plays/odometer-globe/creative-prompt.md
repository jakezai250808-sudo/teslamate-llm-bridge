# odometer-globe 创作引导 prompt（探索层）

> 用户拿到 play JSON 结果后，用自己的生图模型自由创作分享图。

## 给 LLM 平台的使用方式

LLM 调用 `runPlay(odometer-globe)` 拿到 JSON 后，
应主动建议用户：「要把你的绕圈成就做成一张分享图吗？」

## 适用模型说明

| 模板版本 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image、Qwen-Image、Gemini flash image | 自然段描述 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包生图 | 白名单约束 |

---

## 推荐 prompt 模板 v1（通用版）

```text
用我的真实驾驶数据画一张小红书风格的绕圈成就分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 累计里程：{total_km} 公里
- 绕地球：{earth_laps} 圈 · 北京→拉萨：{beijing_lhasa} 趟 · 京沪：{beijing_shanghai} 趟
- 绕圈指数：{globe_score}/100「{persona.name}」
- 人格描述：{persona.desc}
- 自嘲标签：{persona.tag}
- 单程平均：{avg_km}km · 最远一程：{longest_km}km · 共 {total_drives} 次行程

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围：地球/公路旅行——世界地图叠层、公路线条、里程数字
- 超大字体 {total_km} 公里作为视觉锤，标题「AI 分析：你是「{persona.name}」」
- 三个地标当量换算卡片横排：🌍 {earth_laps} 圈 · 🏔️ {beijing_lhasa} 趟京藏 · 🚄 {beijing_shanghai} 趟京沪
- {persona.tag} 做成发光徽章
- 底部互动钩子「你绕地球几圈了？评论区聊聊」
  + 小字「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，不要出现真实车牌/地名
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系模型专用）

```text
竖版手机海报，1536像素宽2048像素高。

背景：真实摄影，Tesla Model 3停在公路观景台，远处是连绵山脉和蜿蜒公路，夕阳金黄色光线洒在车身上，路面延伸至地平线，整体色调暖金+深蓝渐变+白光，无插画无卡通。

海报叠层从上到下：

第一行（极小字）：左侧白字「AI分析：你是」，右侧白色细条形码+极小白字「ODO-{globe_score}-3Y」

第二区（大字）：超大加粗数字「{total_km}」横铺画面，数字颜色左金右蓝渐变，外发白光，磨砂纹理感。下方一行「公里」白色中号字 + 「{persona.name}」金色中号字。右上方六边形徽章「{persona.tag}」金色霓虹外轮廓。

第三区（当量卡）：深色半透明圆角矩形，白色标题行「绕圈成就 ODOMETER GLOBE」。三个当量数据格横排：
「🌍 {earth_laps}圈」绕地球 | 「🏔️ {beijing_lhasa}趟」北京→拉萨 | 「🚄 {beijing_shanghai}趟」北京→上海

第四区（数据卡）：深色半透明圆角矩形，白色标题「行程数据」。五个数据格横排：
「{total_km}km」累计里程 | 「{avg_km}km」单程平均 | 「{longest_km}km」最远一程 | 「{total_drives}」次行程 | 「{globe_score}/100」绕圈指数

第五区（底部大字）：超大加粗白字「你绕地球几圈了？」，次行「评论区聊聊」，右下角极小灰字「数据来自真实行程，AI推理仅供娱乐参考」

画面只允许出现：AI分析你是 / ODO-{globe_score}-3Y / {total_km}公里 / {persona.name} / {persona.tag} / 绕圈成就ODOMETERGLOBE / {earth_laps}圈绕地球 / {beijing_lhasa}趟北京拉萨 / {beijing_shanghai}趟北京上海 / {total_km}km累计里程 / {avg_km}km单程平均 / {longest_km}km最远一程 / {total_drives}次行程 / {globe_score}/100绕圈指数 / 你绕地球几圈了 / 评论区聊聊 / 数据来自真实行程AI推理仅供娱乐参考

绝对不允许：真实车牌，具体地名城市名，小红书logo，GPS坐标，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("odometer-globe", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{total_km}` | `total_km` | 累计里程 |
| `{earth_laps}` | `earth_laps` | 绕地球圈数 |
| `{beijing_lhasa}` | `beijing_lhasa` | 北京→拉萨趟数 |
| `{beijing_shanghai}` | `beijing_shanghai` | 北京→上海趟数 |
| `{globe_score}` | `globe_score` | 绕圈指数 0-100 |
| `{persona.name}` | `persona.name` | 成就名 |
| `{persona.desc}` | `persona.desc` | 成就描述 |
| `{persona.tag}` | `persona.tag` | 自嘲标签 |
| `{avg_km}` | `avg_km` | 单程平均公里数 |
| `{longest_km}` | `longest_km` | 最远一程公里数 |
| `{total_drives}` | `total_drives` | 总行程数 |

所有占位符均对应 `output.fields` 中的真实字段，无需 LLM 反推计算。

---

## 验证记录

| 模型 | 状态 | 备注 |
|---|---|---|
| GPT Image（gpt-image-1） | 待测 | v1 模板 |
| Seedream-4.0 | 待测 | v2 模板 |

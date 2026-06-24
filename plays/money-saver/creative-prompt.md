# money-saver 创作引导 prompt（探索层）

> 用户拿到 play JSON 结果后，用自己的生图模型自由创作分享图。

## 给 LLM 平台的使用方式

LLM 调用 `runPlay(money-saver)` 拿到 JSON 后，
应主动建议用户：「要把你的省钱成绩做成一张分享图吗？」

## 适用模型说明

| 模板版本 | 适用模型 | 特点 |
|---|---|---|
| **v1（通用版）** | GPT Image、Qwen-Image、Gemini flash image | 自然段描述 |
| **v2（Seedream 专用）** | 火山方舟 Seedream-4.0、豆包生图 | 白名单约束 |

---

## 推荐 prompt 模板 v1（通用版）

```text
用我的真实驾驶数据画一张小红书风格的电车省钱分享图（生成图片）：

【数据】来自我的特斯拉真实充电记录
- 省了 {money_saved} 元「{persona.name}」
- 省钱指数：{money_score}/100
- 人格描述：{persona.desc}
- 自嘲标签：{persona.tag}
- 累计充电：{total_kwh} kWh · 行驶 {total_km} km
- 等效油费 {gas_cost} 元 vs 充电费 {elec_cost} 元
- ≈{milk_tea_cups} 杯奶茶 / {gas_tanks} 箱油 / {movie_tickets} 张电影票
- 每公里成本 {cost_per_km} 分

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围：金线质感——深绿到金色渐变背景、钞票纹理、金币点缀
- 超大数字 {money_saved} 作为视觉锤，标题「AI 分析：你是「{persona.name}」」
- 生活化当量三格横排：{milk_tea_cups} 杯奶茶 / {gas_tanks} 箱油 / {movie_tickets} 张电影票
- {persona.tag} 做成发光徽章
- 底部互动钩子「你开电车省了多少钱？评论区晒晒」
  + 小字「AI 分析基于真实充电数据，仅供参考」
- 中文排版，不要出现真实车牌/地名
```

---

## 推荐 prompt 模板 v2（Seedream / 豆包系模型专用）

```text
竖版手机海报，1536像素宽2048像素高。

背景：真实摄影，Tesla 充电口特写，充电枪插入状态、LED 指示灯绿色呼吸光效，深色车身 + 充电桩白色背光 + 金币/钞票纹理叠层低透明度，无插画无卡通。

海报叠层从上到下：

第一行（极小字）：左侧白字「AI分析：你是」，右侧白色细条形码+极小白字「MS-{money_score}-{total_kwh}kWh」

第二区（大字）：超大加粗数字「¥{money_saved}」横铺画面，数字颜色绿金渐变，外发金光，磨砂纹理感。右侧大字「省了」白色半透明叠加。下方一行「{persona.name}」白色中号字。右上方六边形徽章「{persona.tag}」绿金霓虹外轮廓。

第三区（当量卡）：深色半透明圆角矩形，白色标题行「省了多少」。三格横排等宽：
「≈{milk_tea_cups}杯」奶茶杯图标位 | 「≈{gas_tanks}箱」油箱图标位 | 「≈{movie_tickets}张」电影票图标位
每格上方大数字下方小字「奶茶」「汽油」「电影票」

第四区（数据卡）：深色半透明圆角矩形，白色标题「充电数据」。五个数据格横排：
「{total_kwh}kWh」累计充电 | 「{total_km}km」行驶里程 | 「¥{gas_cost}」等效油费 | 「¥{elec_cost}」充电费 | 「{cost_per_km}分/km」每公里成本

第五区（底部大字）：超大加粗白字「你开电车省了多少钱？」，次行「评论区晒晒」，右下角极小灰字「AI分析基于真实充电数据，仅供参考」

画面只允许出现：AI分析你是 / MS-{money_score}-{total_kwh}kWh / ¥{money_saved}省了 / {persona.name} / {persona.tag} / 省了多少 / {milk_tea_cups}杯奶茶 / {gas_tanks}箱汽油 / {movie_tickets}张电影票 / 充电数据 / {total_kwh}kWh累计充电 / {total_km}km行驶里程 / ¥{gas_cost}等效油费 / ¥{elec_cost}充电费 / {cost_per_km}分每公里 / 你开电车省了多少钱 / 评论区晒晒 / AI分析基于真实充电数据仅供参考

绝对不允许：事故碰撞任何危险场景，充电起火，真实车牌，小红书logo，上述列表之外的任何文字
```

---

## 占位符填充说明

从 `run_play("money-saver", car_id=<n>)` 返回的 JSON 取值：

| 占位符 | JSON 字段 | 说明 |
|---|---|---|
| `{money_score}` | `money_score` | 省钱指数 0-100 |
| `{money_saved}` | `money_saved` | 省钱总额 元 |
| `{persona.name}` | `persona.name` | 人格名 |
| `{persona.desc}` | `persona.desc` | 人格描述 |
| `{persona.tag}` | `persona.tag` | 自嘲标签 |
| `{total_kwh}` | `total_kwh` | 累计充电 kWh |
| `{total_km}` | `total_km` | 行驶里程 km |
| `{gas_cost}` | `gas_cost` | 等效油费 元 |
| `{elec_cost}` | `elec_cost` | 实际充电费 元 |
| `{cost_per_km}` | `cost_per_km` | 每公里成本 分/km |
| `{milk_tea_cups}` | `milk_tea_cups` | 奶茶当量 |
| `{gas_tanks}` | `gas_tanks` | 油箱当量 |
| `{movie_tickets}` | `movie_tickets` | 电影票当量 |

所有占位符均对应 `output.fields` 中的真实字段。

---

## 验证记录

| 模型 | 状态 | 备注 |
|---|---|---|
| GPT Image（gpt-image-1） | 待测 | v1 模板 |
| Seedream-4.0 | 待测 | v2 模板 |

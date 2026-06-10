# driving-personality 创作引导 prompt（探索层）

> 两阶段漏斗的第一阶段：用户拿到 play JSON 结果后，用自己的生图模型
> （GPT Image / 豆包生图 / 即梦）自由创作分享图。本文件是推荐 prompt 模板 ——
> 好 prompt 决定好图的下限。爆款设计经筛选后由维护者固化成 `card.svg.tmpl`
> （第二阶段，见 `docs/play-creative-pipeline.md`）。

## 给 LLM 平台的使用方式

LLM（ChatGPT / 豆包 bot）调用 `runPlay(driving-personality)` 拿到 JSON 后，
应主动建议用户：「要把这个结果做成一张分享图吗？」用户同意后，把下面模板的
`{占位符}` 用 JSON 里的真实值填充，调用生图能力。

## 推荐 prompt 模板 v1（夜行系人格示例）

```text
用我的真实驾驶数据画一张小红书风格的驾驶人格分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 驾驶人格码：{code}「{persona.name}」
- 一句话画像：{persona.desc}
- 自嘲标签：{persona.tag}
- 四轴：动力 {vigor}/100 · 夜驾占比 {night_pct}% · 单程平均 {avg_drive_km}km · 出车率 {freq_pct}%
- 本月：{drive_count} 次出行 · 最快 {top_speed} km/h

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 主题氛围呼应人格（夜行系→深色夜景霓虹；通勤系→清晨城市；远征系→公路日落）
- 超大人格码 {code} 视觉锤，标题「AI 分析：你是一个典型的{persona.name}」
- 数据卡片展示真实数字（数字必须准确，不要编造）
- 四轴条形图可视化
- {persona.tag} 做成发光徽章
- 底部互动钩子「你的驾驶人格是什么？评论区聊聊」
  + 小字「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，信息密度高但层次清晰
- 不要出现真实车牌 / 具体地名
```

## 变体方向（用户可追加一句话切换）

| 追加指令 | 效果 |
|---|---|
| 「换成手账贴纸风」 | 浅色可爱风（参照 A/B 人格对比图的视觉系） |
| 「做成 A/B 对比版，我和家人各一个人格」 | 夫妻/家庭共驾互猜钩子（传播力最强变体） |
| 「赛博朋克风」 | 霓虹高对比 |
| 「极简风，只要人格码和一句话」 | 适合做头像 / 壁纸 |

## 验证记录

| 模型 | 状态 | 质量 | 备注 |
|---|---|---|---|
| GPT Image（gpt-image-1 / DALL-E 3，ChatGPT/Codex 订阅） | ✅ 双卡实测 | ★★★★★ | 中文数字全准，订阅内免费，当前最优 |
| 豆包生图（Seedream 2.0） | ✅ 实测 | ★★★★ | 免费额度，原生中英双语，小字布局稳定 |
| Qwen-Image-2.0（DashScope 国际版） | ✅ 实测 | ★★★★ | 商用 API 中文海报最强；新用户 100 张免费 |
| Gemini flash image（gemini-2.5-flash-image 等） | ⚠️ 有条件 | ★★★★ | 新 GCP project 免费层可用；旧 project 耗尽后全模型 429 |
| Pollinations.ai（Flux 底座） | ⚠️ 中文场景不推荐 | ★★ | 匿名可调；中文字符渲染弱，适合无文字纯图场景 |

完整说明与接入示例见 [`docs/image-generation.md`](../../docs/image-generation.md)。

## 安全注意

- prompt 中不放 VIN / 车牌 / 精确地址；数据已是聚合统计无 GPS 坐标
- 提醒用户：生成图含「娱乐参考」免责字样再发布

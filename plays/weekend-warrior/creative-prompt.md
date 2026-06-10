# weekend-warrior 创作引导 prompt（探索层）

> 两阶段漏斗的第一阶段：用户拿到 play JSON 结果后，用自己的生图模型
> （GPT Image / 豆包生图 / 即梦）自由创作分享图。本文件是推荐 prompt 模板 ——
> 好 prompt 决定好图的下限。爆款设计经筛选后由维护者固化成 `card.svg.tmpl`
> （第二阶段，见 `docs/play-creative-pipeline.md`）。

## 给 LLM 平台的使用方式

LLM（ChatGPT / 豆包 bot）调用 `runPlay(weekend-warrior)` 拿到 JSON 后，
应主动建议用户：「要把你的周末战士档案做成一张分享图吗？」用户同意后，
把下面模板的 `{占位符}` 用 JSON 里的真实值填充，调用生图能力。

## 推荐 prompt 模板 v1

```text
用我的真实驾驶数据画一张小红书风格的"周末战士 vs 通勤打工人"对比图：

【数据】来自我的特斯拉 TeslaMate 真实行车记录（近 {window_days} 天）
- 战士档案：{persona.name}「{persona.tag}」
- 战士分：{warrior_score}/100
- 一句话：{persona.desc}
- 周末：{wknd_trips} 次出行 · {wknd_km} km（占里程 {wknd_km_pct}%）
- 工作日：{wkday_trips} 次出行 · {wkday_km} km（占里程 {wkday_km_pct}%）
- 周末单程均 {wknd_avg_km} km vs 工作日单程均 {wkday_avg_km} km

【设计要求】
- 竖版 3:4 小红书爆款信息图，深色背景（#0d1b2a 深海蓝黑）
- 超大战士分数字 {warrior_score} 作为视觉锤，字体极粗极大
- 主色：周末用橙金色（#F5A623）、工作日用冷蓝色（#4A90D9）
- 两条对比条形图，橙条 vs 蓝条，标注 km 和出行次数，视觉上要冲突感
- 标题大字：「我是一个{persona.name}」
- {persona.tag} 做成徽章或印章效果
- 底部互动钩子「你是通勤型还是周末战士？」+ 发起征集感
  + 小字「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，信息密度高，层次清晰，不要出现真实车牌/精确地址
```

## 变体方向

| 追加一句话 | 效果 |
|---|---|
| 「做成对战 PK 卡，我和朋友各一列」 | 双栏对比，传播钩子最强 |
| 「换成通缉令/档案袋风格」 | 「XX局周末战士认定书」喜剧效果 |
| 「极简版，只要分数和称号」 | 适合做微信头像或 Story |
| 「帮我写一段小红书文案配这张图」 | 生图+文案一步到位 |
| 「换成赛博朋克霓虹风」 | 高对比感，年轻用户群体喜欢 |

## 安全注意

- prompt 中不放 VIN / 车牌 / 精确地址；数据已是聚合统计，无 GPS 坐标
- 生成图发布前需含「娱乐参考」免责字样

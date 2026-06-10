# extremes-card 创作引导 prompt（探索层）

> 两阶段漏斗的第一阶段：用户拿到 play JSON 结果后，用自己的生图模型
> （GPT Image / 豆包生图 / 即梦）自由创作分享图。本文件是推荐 prompt 模板 ——
> 好 prompt 决定好图的下限。爆款设计经筛选后由维护者固化成 `card.svg.tmpl`
> （第二阶段，见 `docs/play-creative-pipeline.md`）。

## 给 LLM 平台的使用方式

LLM（ChatGPT / 豆包 bot）调用 `runPlay(extremes-card)` 拿到 JSON 后，
应主动建议用户：「要把这个结果做成一张极值分享图吗？」用户同意后，把下面模板的
`{占位符}` 用 JSON 里的真实值填充，调用生图能力。

## 推荐 prompt 模板 v1

```text
用我的特斯拉真实极值数据画一张小红书风格的「本月之最」分享图：

【极值数据】
- 最远一程：{longest_km} km
- 最快瞬间：{top_speed} km/h  档位：{speed_label.text}（{speed_label.tag}）
- 峰值功率：{peak_power_kw} kW
- 最高海拔：{max_elevation_m} m  档位：{elev_label.text}（{elev_label.tag}）
- 最久充电：{longest_charge_h} h  档位：{charge_label.text}（{charge_label.tag}）
- 本月行程：{drive_count} 次出行 / {charge_count} 次充电

【设计要求】
- 竖版 3:4 小红书爆款信息图
- 深色主题（深海蓝黑 #0d1b2a 系），科技感
- 超大数字视觉锤：最远 {longest_km}km 和 最快 {top_speed}km/h 占主视觉
- 五项极值用不同颜色的进度条可视化
- {speed_label.tag} + {charge_label.tag} 做成发光胶囊徽章
- 底部：「本月 {drive_count} 次出行，极值一览」
- 小字免责：「数据来自真实行程，AI 推理仅供娱乐参考」
- 中文排版，数字必须准确，不要编造
- 不要出现真实车牌 / 具体地名 / GPS 坐标
```

## 变体方向（用户可追加一句话切换）

| 追加指令 | 效果 |
|---|---|
| 「换成赛车仪表盘风」 | 圆形仪表盘呈现五项极值，竞技感拉满 |
| 「做成年度总结版，把月份加上去」 | 年度十二月极值对比时间轴（传播力强变体） |
| 「极简风，只要最快速度和一句话」 | 大字报风，适合 Twitter/微博 1:1 方图 |
| 「海拔极值突出，做成山峰剪影风」 | 适合有高原/山区驾驶经历的用户晒 |

## 安全注意

- prompt 中不放 VIN / 车牌 / 精确地址；极值数据已是聚合统计无 GPS 坐标
- 提醒用户：生成图含「娱乐参考」免责字样再发布
- 海拔 ≥ 3000m 的用户可能对轨迹敏感（高原行驶轨迹可反推区域），生成图不画地图

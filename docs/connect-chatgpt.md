# Connect to ChatGPT Actions

This guide walks through adding teslamate-llm-bridge as a **ChatGPT Custom Action** so you can ask ChatGPT questions like "what's my driving personality?" and get a structured answer drawn from your real TeslaMate data.

## Prerequisites

- A running teslamate-llm-bridge instance reachable over HTTPS (ChatGPT cannot reach `localhost`). Options:
  - A publicly-exposed server (VPS, home server with port forwarding)
  - A tunnel such as [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) (free plan works)
  - [ngrok](https://ngrok.com/) for quick testing
- A ChatGPT Plus or Team account (Custom Actions require a paid plan)
- Your bridge's `API_TOKEN` (if you set one)

## Step 1 — note your public base URL

Example: `https://bridge.example.com` (no trailing slash, must be HTTPS).

## Step 2 — download the OpenAPI spec

The bridge serves its spec at:

```
GET https://<your-bridge>/openapi.json
```

Save the file locally, then edit the `servers[0].url` field to match your actual base URL:

```json
"servers": [
  {
    "url": "https://bridge.example.com"
  }
]
```

## Step 3 — create a GPT

1. Go to [https://chat.openai.com/gpts/editor](https://chat.openai.com/gpts/editor) and click **Create a GPT**.
2. Give it a name, e.g. "My Tesla".
3. Under **Instructions**, paste something like:

```
You help the user explore their Tesla driving data stored in TeslaMate.
When the user asks about their driving personality, habits, charging behaviour,
or anything that could be answered by a play, call the appropriate play endpoint.
Always mention the persona name and one-liner from the result.
Use car ID 1 unless the user specifies another.
```

4. Click **Configure** → **Create new action** → **Import from URL**.
5. Paste `https://<your-bridge>/openapi.json` and click **Import**.

## Step 4 — add authentication (if API_TOKEN is set)

1. Under **Authentication**, choose **API Key**.
2. Auth type: **Bearer**.
3. Paste your `API_TOKEN` value.

If you did not set `API_TOKEN`, skip this step (no auth required in local/dev mode).

## Step 5 — test

Ask the GPT: "What is my driving personality?" — it should call `runPlay` with `playName=driving-personality` and return your code + persona.

To get a share card image, ask: "Show me my driving personality card." The GPT will call `renderPlayCard` and display the 1080×1080 PNG inline.

## Step 6 — turn the result into a shareable social card with GPT Image

ChatGPT Plus / Team subscribers have GPT Image built in — the same subscription that gives you Custom Actions also lets you generate images in the same conversation window.

Once the GPT returns your `driving-personality` JSON (persona code + axes + summary), copy the template from
[`plays/driving-personality/creative-prompt.md`](../plays/driving-personality/creative-prompt.md),
fill in the `{placeholder}` values from the JSON, and send it as a follow-up message to the same GPT.
ChatGPT will call GPT Image and render a 小红书-style 1080×1080 share card with your actual data.

**Quick example** (after the GPT returns `code=CNSO, persona="深夜静音幽灵"`):

```
用我的真实驾驶数据画一张小红书风格的驾驶人格分享图（生成图片）：

【数据】来自我的特斯拉真实行车记录
- 驾驶人格码：CNSO「深夜静音幽灵」
- 一句话画像：很少出动，一动就是深夜悄悄滑过街角。
- 自嘲标签：#电机声都嫌吵
- 四轴：动力 20/100 · 夜驾占比 20% · 单程平均 4.7km · 出车率 52%
- 本月：25 次出行

【设计要求】竖版 3:4 小红书爆款信息图，深色夜景霓虹主题，
超大 CNSO 视觉锤，数据卡片展示真实数字，四轴条形图可视化，
底部加「数据来自真实行程，AI 推理仅供娱乐参考」
```

The full prompt template (with all placeholders) and model quality comparison are in
[`docs/image-generation.md`](image-generation.md).

> **No GPT Image?** If you are on a plan without image generation, see `docs/image-generation.md`
> for alternatives: 豆包生图 (Seedream), Qwen-Image-2.0, Gemini flash, or the bridge's built-in
> SVG card (`renderPlayCard`) which is always available at zero cost.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Action import fails | `servers[0].url` in the spec still points to `localhost` |
| 401 errors | API_TOKEN in the GPT auth does not match the env var |
| 404 on car ID | `CAR_IDS` env is set and your car ID is not in the list |
| "Not enough data" (`scored: false`) | Fewer data points than `min_sample`; try a longer `start_date` window |
| PNG not displayed | ChatGPT renders image/png inline — verify the card endpoint returns HTTP 200 with content-type `image/png` |

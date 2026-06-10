# Connect to Coze

This guide adds teslamate-llm-bridge as a **Coze plugin** so your Coze bot can call play endpoints to answer questions about your Tesla driving data.

## Prerequisites

- A running teslamate-llm-bridge instance reachable over HTTPS (Coze cloud cannot reach `localhost`). Use Cloudflare Tunnel or similar.
- A [Coze](https://www.coze.com) account (or [扣子](https://www.coze.cn) for China)
- Your bridge's `API_TOKEN` (if you set one)

## Step 1 — note your public base URL

Example: `https://bridge.example.com`

## Step 2 — create a Coze plugin

1. In the Coze dashboard, go to **Library** → **Tools** → **Create Tool** → **Plugin**.
2. Choose **OpenAPI Import**.
3. Paste your spec URL: `https://<your-bridge>/openapi.json`
   Or upload the file directly (download from the URL, update `servers[0].url`, then upload).
4. Coze will parse the spec and show three tools: `listPlays`, `runPlay`, `renderPlayCard`.

## Step 3 — configure authentication

If you set `API_TOKEN`:

1. In the plugin settings, under **Authentication**, choose **API Key**.
2. Header: `Authorization`, value: `Bearer <your-token>`.

If you did not set `API_TOKEN`, no auth config is needed.

## Step 4 — add to a bot

1. Create or open a bot.
2. Under **Plugins**, add your new teslamate plugin.
3. In the bot **System Prompt**, add context:

```
You help the user understand their Tesla driving data.
Available plays: use listPlays to discover what's loaded.
Use car ID 1 by default unless the user specifies another.
When the user asks about driving habits, personality, or charging patterns, call the relevant play.
Always show the persona name and one-liner from driving-personality results.
```

## Step 5 — test

Send the bot: "测测我的驾驶人格" (or "what's my driving personality?")

The bot should call `runPlay` with `playName=driving-personality` and display the result.

For a share card: "给我生成驾驶人格卡片" — Coze renders `image/png` responses inline.

## Coze CN (扣子) notes

- 扣子 (`coze.cn`) can import OpenAPI specs the same way as `coze.com`.
- The bridge URL must be HTTPS and reachable from Coze's servers (mainland CN accessible URL required for `coze.cn`).
- Chinese play titles and persona copy render correctly — the bridge bundles CJK fonts for card rendering.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Plugin import fails | Spec URL not reachable from Coze (check HTTPS, firewall) |
| Tool calls return 401 | Auth header not configured, or token mismatch |
| `scored: false` | Not enough TeslaMate data for the time window; try `start_date` further back |
| Card not displayed | Verify `renderPlayCard` returns `image/png`; Coze displays binary image responses inline |

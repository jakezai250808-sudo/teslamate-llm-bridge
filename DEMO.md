# Demo

## Quick demo (automated)

`bin/demo.sh` runs the full flow end-to-end: starts the bridge, waits for health, lists plays, runs `driving-personality`, downloads the share card, and opens it.

```bash
# Requires: Docker running + .env configured
bash bin/demo.sh --car-id 1
```

The script prints structured JSON at each step and opens the 1080×1080 PNG card at the end.

## Recording a screencast with asciinema

```bash
# Install asciinema (macOS)
brew install asciinema

# Record
asciinema rec demo.cast --command 'bash bin/demo.sh --car-id 1'

# Play back locally
asciinema play demo.cast

# Upload to asciinema.org for sharing
asciinema upload demo.cast
```

### Recording tips

- Run `docker compose up -d` once before recording so images are pre-pulled — avoids a slow pull during the cast.
- If your terminal is wide, the JSON output will be readable inline. A 120-column terminal works well.
- The card opens in your system viewer; use a second terminal split or switch windows to show it alongside the cast.
- For a focused cast, set `--idle-time-limit 2` to compress pauses: `asciinema rec --idle-time-limit 2 demo.cast ...`

## What to highlight in a demo

1. **Zero config for local use** — `cp .env.example .env`, fill in DB creds, `docker compose up`.
2. **Instant results** — `driving-personality` scores in milliseconds on existing TeslaMate data.
3. **Shareable card** — the 1080×1080 PNG drops straight into WeChat / Twitter / Instagram.
4. **Multi-platform** — same play runs via ChatGPT Actions, Claude MCP, and Coze with no changes.
5. **Extensible** — add a new play by dropping a YAML + fixtures into `plays/`. No Java or Python needed.

## Gallery placeholder

> Add a screenshot of the `driving-personality` card here before publishing.
> Example path: `docs/gallery/driving-personality-sample.png`
>
> ```markdown
> ![driving-personality card](docs/gallery/driving-personality-sample.png)
> ```

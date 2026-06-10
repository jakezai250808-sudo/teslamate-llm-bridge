# Demo Mode

## Quick start (no TeslaMate required)

```bash
docker compose --profile demo up -d
```

This starts `postgres-demo` (PostgreSQL 16) and `bridge-demo` (the play engine) connected to it.
Seed data loads automatically on first boot (~10 seconds). After that:

```bash
# Health check
curl http://localhost:8770/actuator/health
# {"status":"UP"}

# List available plays
curl http://localhost:8770/api/v1/plays

# Run a play (demo car_id=99)
curl "http://localhost:8770/api/v1/cars/99/play/driving-personality"

# Download share card PNG
curl "http://localhost:8770/api/v1/cars/99/play/driving-personality/card.png" -o /tmp/card.png
open /tmp/card.png
```

Stop:
```bash
docker compose --profile demo down
```

---

## Automated end-to-end demo script

`bin/demo.sh` walks the full flow automatically:

```bash
# With a real TeslaMate DB (default car_id=1):
bash bin/demo.sh --car-id 1

# With demo mode (car_id=99, starts demo profile automatically):
bash bin/demo.sh --demo
```

The `--demo` flag starts the demo profile, waits for health, runs `driving-personality`, downloads the PNG card, and opens it.

---

## Demo dataset description

### Owner profile

| Field | Value |
|---|---|
| Fictitious owner | Demo 先生 (Mr. Demo) |
| Car model | Model Y Long Range |
| VIN | `DEMO0000000000001` (fictitious) |
| `car_id` | 99 |
| Data window | 2026-04-27 ~ 2026-06-10 (45 days) |
| Driving center | Fictitious grid around Shanghai People's Square (31.23, 121.47) |

### Story line

- **Daily commuter:** weekday 8 am to fictitious Lujiazui office (~14 km), 6 pm home — gentle A-bucket driving.
- **Weekend explorer:** long weekend trips to fictitious Jiading/郊野 (55-100 km), peak speed 142 km/h, Grade B-bucket.
- **Range anxiety:** average charge start SOC ~14%, mostly charges at home to 97%.
- **Night owl:** ~20% of trips depart after 22:00 CST (UTC 14:00+), including two A-bucket gentle night runs on 06-09.
- **Busiest day:** 2026-05-17 (Saturday), 67.3 km round trip to the park.
- **Peak elevation:** 2026-05-25 mountain trip, max elevation 312 m.

### Data statistics

| Table | Rows | Notes |
|---|---|---|
| cars | 1 | id=99, efficiency=0.160 kWh/km |
| car_settings | 1 | id=99 |
| geofences | 3 | Home / Office / Supercharger (fictitious Pudong coords) |
| addresses | 5 | Matching geofences + 2 remote destinations |
| drives | 78 | 45 days: 46+ weekday commutes + 28 weekend/long trips + 2 A-bucket night runs |
| positions | ~444 | 6 samples/drive (generate_series), speed>0 >250 rows |
| charging_processes | 14 | 9 home AC + 5 supercharger DC; 6 full charges (≥97%) |
| charges | 56 | 4 detail samples per session |
| states | 5 | online/asleep transitions |
| updates | 2 | OTA version records |

### Play coverage matrix (30-day window 2026-05-11 ~ 2026-06-10)

| Play | Key metrics | Expected result |
|---|---|---|
| driving-personality | drives=52, night~17%, punch~27%, avg_km~23.5, freq~83% | Persona code **FNLE** (午夜高速战神) |
| ab-couple-souls | total=52, a=26, b=26, median_speed=86 km/h | Balanced split; A 均衡通勤手 vs B 节奏感玩家 |
| charging-habit | charge=10, full_pct=50%, home_pct=100%, anxiety=87 | Persona **HFG** — 续航焦虑症晚期 |
| efficiency-report | 30d ~243 kWh / ~1222 km, baseline 160 Wh/km | efficiency_ratio ~124% → **Grade D** (重度耗电体) |
| extremes-card | top_speed=142, peak_power=180 kW, max_elevation=310 m | 高速巡航达人 / 丘陵探索家 |
| monthly-wrapped | drives=52, total=1222 km, busiest=05-25 (200 km), fav_hour=8 | 城际穿梭常旅客 |
| weekend-warrior | wknd=11 trips (364 km), wkday=41 trips (858 km) | warrior_score=26 → 周末偶尔出逃者 |

### v2 data refinements (vs. original draft)

| Issue | Before | After | Effect |
|---|---|---|---|
| charge_energy_added total | 635 kWh (unrealistic) | 340 kWh (×0.5355) | efficiency_ratio 30d: ~235% (Grade F bug) → ~124% (Grade D realistic) |
| Supercharger start SOC | 2-4% (anxiety inflated) | 15-20% | anxiety: 93 → 85.8 (stays H-tier, more realistic) |
| A-bucket night drives | 0 (all night drives were B-bucket) | 2 added (06-09 22:00 & 23:00 CST) | ab-couple-souls A-soul gets night drive events |

### Privacy statement

All GPS coordinates are on a fictitious grid centred on Shanghai People's Square (31.23, 121.47), with precision deliberately degraded to ~0.001° (~100 m). VIN, odometer, battery values are all synthetically generated and do not represent any real vehicle or owner.

---

## Recording a screencast with asciinema

```bash
brew install asciinema

# Record with demo mode
asciinema rec demo.cast --command 'bash bin/demo.sh --demo'

# Play back
asciinema play demo.cast

# Share
asciinema upload demo.cast
```

Tips:
- Run `docker compose --profile demo up -d` once before recording to pre-pull images.
- Use `--idle-time-limit 2` to compress pauses: `asciinema rec --idle-time-limit 2 demo.cast ...`
- Use a 120-column terminal for readable JSON output.

## What to highlight in a demo

1. **Zero config** — `docker compose --profile demo up -d`, no DB setup needed.
2. **Instant results** — play scores in milliseconds.
3. **Shareable card** — 1080×1080 PNG card ready for WeChat / Twitter / Instagram.
4. **Multi-platform** — same play runs via ChatGPT Actions, Claude MCP, and Coze.
5. **Extensible** — add a new play by dropping a YAML + fixtures into `plays/`. No Java or Python needed.

## Gallery

> Add a screenshot of the `driving-personality` card here before publishing.
> Example path: `docs/gallery/driving-personality-sample.png`
>
> ```markdown
> ![driving-personality card](docs/gallery/driving-personality-sample.png)
> ```

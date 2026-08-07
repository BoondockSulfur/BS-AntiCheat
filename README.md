# BSAntiCheat

A lightweight, false-positive-conscious anti-cheat for **Paper 1.21.x** — with full **Folia**
support. It covers movement, combat, world-interaction, inventory and packet-level cheats,
logs everything to SQLite, and can act through a configurable violation-level punishment
system. Built to be calibrated for *your* server rather than flag your legit players.

> **Design principle:** reliable detections on by default; the fiddly, false-positive-prone
> heuristics ship **off** by default and are opt-in. You should be able to install it and
> not have your builders, miners and PvPers flagged out of the box.

---

## Features

**Movement**
- Speed, Fly (vertical burst + sustained hover), Teleport, GroundSpoof, Elytra/Riptide speed
- Server-authoritative on-ground check (not the spoofable client flag)
- Lag compensation via a real **transaction-latency** system (ping/pong), not the coarse `getPing()`
- Grace windows for teleport, knockback, join/respawn/world-change and elytra landings

**Combat**
- Reach (measured to the hitbox surface), KillAura (aim angle + multi-target), AimSnap
  (robotic snap-back rotation), Criticals, AutoBlock

**World**
- Nuker, FastPlace, Scaffold (blind placement), FastBreak (per-block dig time vs. expected)

**Packet-level** *(requires PacketEvents)*
- AutoClicker — CPS from arm-swings (mining excluded), plus opt-in interval-consistency
  analysis that catches slow-but-metronomic clickers
- BadPackets, Timer, crash protection (oversized book/sign packets), packet-flood

**Vehicle** — Boat-Fly and per-type vehicle speed (via `VehicleMoveEvent`)

**XRay** — per-ore thresholds, ore/stone ratio, combined-rare-ore, restricted-world zones,
player-placed-ore exclusion

**Inventory** — InventoryMove, ChestStealer, FastUse, BowSpam, AutoTotem

> **Opt-in / off by default** (enable in `config.yml` if you want them): NoSlow, Jesus,
> Spider, Step, AutoBlock, Velocity/AntiKnockback, the KillAura rotation-GCD check and the
> AutoClicker consistency analysis. These are inherently false-positive-prone — calibrate
> them with `debug_mode` before switching them on.

**Infrastructure**
- Violation-level (VL) system with decay and configurable punishment **tiers** (console commands)
- Optional **setback** (teleport movement violators to their last valid position)
- SQLite logging with a plaintext fallback logger (no data loss during DB outages)
- Clickable, per-check alerts; **silent mode** to mute alert types
- Bilingual (English / German), auto-migrating config
- Discord webhook alerts, PlaceholderAPI, LuckPerms group whitelist, bStats, update checker

---

## Requirements & integrations

| | |
|---|---|
| **Server** | Paper 1.21.x (or Folia 1.21.x) |
| **Required for packet checks** | [PacketEvents](https://modrinth.com/plugin/packetevents) (install as a plugin) |
| **Optional** | LuckPerms (group whitelist), PlaceholderAPI, Geyser/Floodgate (Bedrock exemption), ViaVersion (legacy-client exemption) |

Without PacketEvents the plugin still runs — the packet-level checks (AutoClicker, BadPackets,
Timer, Crasher, PacketFlood) and the transaction-latency system simply stay disabled.

---

## Installation

1. Drop `BSAntiCheat-1.0.3.jar` into `plugins/`.
2. (Recommended) Install **PacketEvents** for the packet-level checks.
3. Start the server, then edit `plugins/BSAntiCheat/config.yml` and run `/bsac reload`.

---

## Configuration highlights

Everything lives in `config.yml`. A few things worth knowing:

- **`debug_mode`** — logs per-check debug values (CPS, angles, etc.) for calibration. Off in production.
- **`anticheat.punishments.enabled`** — master switch. When `false`, the plugin only alerts/logs
  (report-only). Define `tiers` as *VL threshold → console commands* (placeholders `%player%`,
  `%check%`, `%vl%`).
- **`anticheat.ops_bypass`** — exempt server OPs from all checks.
- **`anticheat.exempt_bedrock_players`** — exempt Geyser/Floodgate players (their client physics differ).
- **`anticheat.exempt_legacy_clients`** — exempt ViaVersion legacy clients (opt-in).
- **`anticheat.thresholds.*`** — per-check violation counts and thresholds, all tunable via `/bsac reload`.
- **`anticheat.transaction_interval_ticks`** — how often the latency system pings each player.
  Pure per-player packet overhead; raise it on high-population servers. Applied at startup.

**Calibration workflow:** set `debug_mode: true` and `punishments.enabled: false`, watch the
debug values while playing, tune `anticheat.thresholds.*`, apply with `/bsac reload` (no restart),
then turn punishments on when you're happy.

---

## Commands

| Command | Alias | Permission | Description |
|---|---|---|---|
| `/bsac <reload\|info\|version> [player]` | `/bsanticheat` | `bsanticheat.admin` | Reload config, show a player's VL, or version |
| `/movealerts [player\|clear\|clearall]` | `/mva` | `bsanticheat.admin` | Movement/speed/fly alerts |
| `/xrayalerts [player\|clear\|clearall]` | `/xra` | `bsanticheat.admin` | XRay alerts |
| `/acsilent [all\|xray\|movement\|list]` | `/acs` | `bsanticheat.admin` | Toggle alert notifications (silent mode) |
| `/acwhitelist <add\|remove\|list> [player]` | `/acwl` | `bsanticheat.manage` | Manage the whitelist (players / `group:<name>`) |
| `/xrayores <list\|available\|add\|remove> [ore]` | `/xro` | `bsanticheat.manage` | Manage XRay ore exclusions |

## Permissions

| Permission | Default | Description |
|---|---|---|
| `bsanticheat.admin` | op | Admin commands + alerts |
| `bsanticheat.manage` | op | Whitelist and ore-exclusion management |
| `bsanticheat.bypass` | false | Bypass all checks (never auto-granted to OPs — use `ops_bypass` for that) |

## PlaceholderAPI

- `%bsanticheat_total%` — a player's total violation level
- `%bsanticheat_vl_<check>%` — VL for a specific check (e.g. `%bsanticheat_vl_speed%`)

## Developer API

`ViolationEvent` fires whenever a player's VL for a check changes — hook it to build your own
handling on top of BSAntiCheat.

---

## Notes on accuracy

BSAntiCheat's enabled checks are heuristic and lag/transaction-compensated, tuned to avoid
flagging legitimate play (fast mining, elytra landings, jumping, ice boats, sweeping-edge
farms, high-ping players). Thresholds are deliberately generous — tighten them for your
server with `debug_mode` and the calibration workflow above. For maximum-precision combat
detection (prediction-engine level), deeper packet-timing analysis is planned.

## Building

```bash
mvn clean package    # → target/BSAntiCheat-1.0.3.jar
```

License: see [LICENSE](LICENSE).

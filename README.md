# BSAntiCheat

A lightweight, false-positive-conscious anti-cheat for **Paper 1.21.10+** — with full **Folia**
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
- Grace windows for teleport, knockback, join/respawn/world-change, elytra landings and
  riptide momentum, dismounting at speed, slime launches, and **piston displacement** —
  pistons move players without applying velocity, so nothing else would excuse them
- Exemptions for what looks like flight but is not: towering up (placing your own footing),
  and cobwebs, powder snow or honey walls, which slow a descent below the rate that counts
  as falling
- Hovering means *hanging* — vertical movement inside one tick of gravity. Being thrown
  upwards (wind charge, Wind Burst mace, a Breeze) is a climb, not a hover, and outlasts any
  knockback grace, so it is not counted
- Speed and vertical deltas are judged **per tick, not per packet** — a move event is not
  reliably one tick, and a slow connection delivers several ticks of travel in one event.
  The catch-up move after a packet gap (>400 ms of silence) is skipped entirely
- Block-reading checks stand down where the surrounding chunks are not in memory, rather than
  reading "no block found" as "nothing below the player"

**Combat**
- Reach (measured to the hitbox surface, honours the interaction-range attribute),
  KillAura (aim angle + multi-target), AimSnap (robotic snap-back rotation)

**World**
- Nuker, FastPlace, Scaffold (blind placement), FastBreak (per-block dig time vs. expected)

**Packet-level** *(requires PacketEvents)*
- AutoClicker — the click rate is the smaller of two estimates: how many swings arrived in the
  last second, and what the typical interval between them implies. A connection that bundles
  packets inflates the first; a short fast burst inflates the second; the minimum survives
  both, and sustained fast clicking raises both and is still caught. Plus an opt-in interval-consistency analysis that catches slow-but-metronomic
  clickers. A held mouse button is recognised by its **cadence** (one
  swing per server tick) rather than by its click count, so network jitter cannot push
  ordinary mining or swinging over the limit. The flip side is a blind spot the check cannot
  close: an autoclicker running at ~20 CPS produces the packet stream a held button produces,
  because that *is* one swing per tick. Above that rate the cadence separates them again
- BadPackets, Timer, crash protection (oversized book/sign packets), packet-flood

**Vehicle** — Boat-Fly and per-type vehicle speed (via `VehicleMoveEvent`)

**XRay** — per-ore thresholds, ore/stone ratio, combined-rare-ore, restricted-world zones,
player-placed-ore exclusion. Three things keep honest miners out of it:

- Only ore that was **still buried** when broken is counted. X-Ray reveals ore you cannot
  see, and reaching it means digging; emptying an open cave means taking ore off walls that
  were visible all along — which produces the same "lots of ore, little stone" statistics.
  Faces the player opened themselves do not count as visibility, so tunnelling straight to a
  concealed vein still counts against them.
- Ore must come from several **separate deposits**. One thick copper vein, or a vein-miner
  tool taking a whole vein at once, is a lucky find, not knowledge of where ore is.
- A player moving a lot of stone is visibly **searching** — the opposite of what X-Ray is
  for — and is judged by hit rate instead of by raw counts.

**Inventory** — InventoryMove, ChestStealer, FastUse, BowSpam, AutoTotem

> **Opt-in / off by default** (enable in `config.yml` if you want them): NoSlow, Jesus,
> Spider, Step, AutoBlock, Velocity/AntiKnockback, sustained ascent (a climb that does not
> decay the way gravity requires — the counterpart to hover only counting genuine hanging),
> the KillAura rotation-GCD check and the AutoClicker consistency analysis. These are inherently false-positive-prone — calibrate
> them with `debug_mode` before switching them on.
>
> **Criticals** is off for a different reason: the server only awards a critical hit to a
> player who is airborne and falling, so the check's condition cannot be met by a genuine
> vanilla crit — in practice it only fires on damage events synthesised by other plugins.
> Leave it off unless your server has no damage-modifying plugins.

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
| **Server** | Paper 1.21.10+ (or Folia). Built against the 1.21.10 API; running in production on Paper 26.1.2 |
| **Required for packet checks** | [PacketEvents](https://modrinth.com/plugin/packetevents) (install as a plugin) |
| **Optional** | LuckPerms (group whitelist), PlaceholderAPI, Geyser/Floodgate (Bedrock exemption), ViaVersion (legacy-client exemption) |

Without PacketEvents the plugin still runs — the packet-level checks (AutoClicker, BadPackets,
Timer, Crasher, PacketFlood) and the transaction-latency system simply stay disabled. This is
verified by loading the plugin against a classpath with and without PacketEvents, not just
asserted: until 1.0.3 a missing PacketEvents actually prevented the plugin from loading.

---

## Installation

1. Drop `BSAntiCheat-1.0.5.jar` into `plugins/`.
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

> **Editing `config.yml` on a running server:** the plugin only writes the file when it has
> changed something itself (whitelist/ore commands, or repairing an invalid value), so your
> edits are safe from being overwritten at shutdown. They still do not take effect until
> `/bsac reload` — or the next restart.

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
farms, towering up, piston elevators, cave mining, high-ping players). Thresholds are
deliberately generous — tighten them for your server with `debug_mode` and the calibration
workflow above. For maximum-precision combat detection (prediction-engine level), deeper
packet-timing analysis is planned.

Several of these exemptions exist because the check was wrong first: 1.0.4 was written from
a day of live alert data in which **every single alert was a false positive**, each traced
back to what the player was actually doing. If you find one, the alert text and
`debug_mode` are usually enough to reconstruct the same way.

## Building

```bash
mvn clean package    # → target/BSAntiCheat-1.0.5.jar
mvn test             # 96 tests
```

Builds on JDK 21 or later; the bytecode target is 21 regardless of the JDK used.

Tests cover the decision logic directly (interval statistics, deposit clustering, ore
visibility, latency slack) and run scenarios through real event sequences against
[MockBukkit](https://github.com/MockBukkit/MockBukkit) — a player walking, towering, being
shoved by a piston, emptying a cave, digging a tunnel. Each scenario is a pair: the false
positive that must stay silent, and the detection that must survive it.

Anything inside `PacketChecker` (AutoClicker, Timer, AimSnap) is unit tested but not covered
end to end — that path needs PacketEvents objects MockBukkit cannot supply.

License: see [LICENSE](LICENSE).

# Changelog

All notable changes to BSAntiCheat are documented in this file.

---

## [1.0.0] - 2026-07-02

First public release. Live-tested and false-positive-calibrated on a real server, verified
on both Paper and Folia.

### Detections
- **Movement:** Speed, Fly (vertical burst + sustained hover), Teleport, GroundSpoof,
  Elytra/Riptide speed ceiling.
- **Combat:** Reach (measured to the hitbox surface), KillAura (aim angle + multi-target),
  AimSnap (robotic snap-back rotation), Criticals, AutoBlock.
- **World:** Nuker, FastPlace, Scaffold, FastBreak (per-block dig time vs. `Block#getBreakSpeed`).
- **Vehicle:** Boat-Fly and per-type vehicle speed via `VehicleMoveEvent` (movement checks
  don't fire while riding, so this closed a real gap).
- **Packet-level** (needs PacketEvents): AutoClicker, BadPackets, Timer, crash protection
  (oversized book/sign packets are cancelled), packet-flood — the last two stay active even
  under server lag.
- **XRay:** per-ore thresholds, ore/stone ratio, combined-rare-ore, restricted-world zones,
  player-placed-ore exclusion.
- **Inventory (opt-in):** InventoryMove, ChestStealer, FastUse, BowSpam, AutoTotem.

### Accuracy & internals
- **No sampling:** every movement is checked (not every 10th), so a cheat can't hide between samples.
- **Server-authoritative ground check** (bounding-box vs. the world) instead of the spoofable
  client flag — hardens Fly/hover against NoFall/Fly spoofing.
- **Transaction-latency system:** a per-tick ping/pong measures true round-trip latency; movement,
  vehicle and elytra lag compensation use it instead of the coarse `getPing()`.
- Grace windows for teleport, knockback, join/respawn/world-change and **elytra landings**
  (residual glide momentum no longer flags as Speed).
- AutoClicker excludes mining swings; Reach measured to the hitbox surface; XRay resets its
  evidence after each flag (no VL spirals); Creative/Spectator exempt.

### Platform & integrations
- **Folia support** via a Paper/Folia scheduler abstraction — verified against a real Folia
  1.21.11 server with zero scheduler exceptions; behaviour on Paper is unchanged.
- **Bedrock exemption** (`exempt_bedrock_players`) via Geyser/Floodgate detection.
- **Legacy-client exemption** (`exempt_legacy_clients`, opt-in) via ViaVersion.
- LuckPerms group whitelist, PlaceholderAPI (`%bsanticheat_total%`, `%bsanticheat_vl_<check>%`),
  Discord webhook alerts, bStats, update checker.

### Enforcement & operations
- Violation-level system with decay and configurable punishment **tiers** (console commands),
  optional setback; report-only by default.
- **25+ calibration thresholds** in `config.yml`, all tunable live via `/bsac reload`.
- SQLite logging with a plaintext fallback logger; silent mode; bilingual (EN/DE); auto-migrating config.

### Defaults & notes
- The false-positive-prone movement micro-heuristics — **NoSlow, Jesus, Spider, Step** — and
  **Velocity/AntiKnockback** ship **off by default** (opt-in). They need further hardening;
  the reliable checks are enabled out of the box.
- `autoclicker_max_cps` defaults to 20 (skilled humans reach ~17–20; autoclickers exceed it).

### Notable fixes since the internal extraction
- Correct shading: sqlite-jdbc/slf4j are `provided` by Paper (jar shrank from ~11 MB to ~355 KB).
- Thorns damage no longer flags the victim as KillAura; sweep hits no longer cause bogus reach/angle flags.
- DatabaseManager: no silent log loss (batch overflow, failed inserts, shutdown drains fully).
- Thread-safety fixes (config/language published atomically); non-blocking command player lookups;
  Discord webhook timeouts.

---

## Origin

Extracted from the PerformanceAnalyzer plugin (v2.3.4) as a standalone anti-cheat: movement
and XRay detection with lag compensation, LuckPerms whitelist, Discord webhooks, SQLite
logging, silent mode and bilingual support. Everything above builds on that base.

---

## Links

- [GitHub Repository](https://github.com/BoondockSulfur/BSAntiCheat)

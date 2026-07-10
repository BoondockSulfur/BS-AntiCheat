# Changelog

All notable changes to BSAntiCheat are documented in this file.

---

## [1.0.1] - 2026-07-09

False-positive elimination release, driven by live alert data from a production server
(503 GroundSpoof / 314 Timer / 150 Speed / 62 Fly alerts — all confirmed false positives).

**Verified in production:** deployed 2026-07-09; the prior baseline of ~200 false alerts
per day (~1000 over 5 days) dropped to zero in the first 24 hours of normal play.

### Movement
- **GroundSpoof/Fly ground detection rewritten:** all ground tests now check the four
  hitbox-footprint corners (not just the centre column — sneaking over an edge no longer
  flags), include the block at foot level itself (standing on trapdoors, slabs, carpets,
  snow layers), and use real collision (`isPassable`) instead of `isSolid`, plus explicit
  support for powder snow, scaffolding tops, cobwebs, climbables and liquids.
- **Fly at ladders fixed:** climbing state now uses the game's own `isClimbing()` logic,
  and climbable blocks below the feet count as support (exiting the top of a ladder no
  longer accumulates hover violations).
- **Speed on ice fixed:** the ice multiplier now survives sprint-jumps (3-block down-scan
  plus a 2.5s ice-momentum memory) — ice-road running no longer flags.
- **Swift Sneak supported:** the sneak speed cap scales with the enchantment level
  (level 3 = 0.75x walking) instead of the fixed vanilla 0.3x.
- **Server-applied velocity grants knockback immunity** (`PlayerVelocityEvent`): projectile
  knockback (punch bows, snowballs), wind charges, fishing-rod pulls and jump-pad plugins
  no longer trigger Speed/Fly.
- **Slime-bounce grace:** high bounces are exempt during the whole rise (3s launch memory),
  not only while slime is within 2 blocks below.

### Combat
- **Reach and KillAura (angle) now require 3 suspicious hits within a 10s window**
  (configurable via `reach_violations`/`killaura_angle_violations`) instead of flagging a
  single hit, and Reach is ping-compensated (same sqrt scaling as movement, fed by the
  transaction-latency system).
- **AutoBlock default off:** vanilla allows attacking while an offhand shield is raised,
  so the check flags ordinary sword+shield play. Opt-in only.

### Packet & world
- **Timer clock-drift leak:** real time is counted at 101%, absorbing benign client clock
  drift (~0.5% fast clocks accumulated ~10ms/s and periodically crossed the 200ms limit).
- **FastBreak measures the expected dig time at dig start AND end and uses the lenient
  one** — landing from a jump or a haste beacon kicking in mid-dig no longer flags.
- **Scaffold angle measured to the clicked block** (nearest hitbox point) instead of the
  placed block's centre — placing a block at your own feet (95–113° off aim in live data)
  no longer counts.
- **Boat ice momentum:** the boat speed ceiling and Boat-Fly keep the ice multiplier for
  3s after ice contact — bumps/gaps/ramp launches on ice roads no longer flag.
- **InventoryMove exempts momentum and shoves:** sliding on ice with a GUI open and being
  pushed by nearby entities (mob crowds) no longer count.

### Infrastructure & calibration
- **Lag detection reacts to spikes:** `isLagging` now also checks the 5-second MSPT average
  (`getAverageTickTime`) — the 1-minute TPS average barely moves during short spikes,
  which are exactly what distorts movement deltas.
- Calibration from live data: `autoclicker_max_cps` 20 → 22 (legit butterfly clicking
  peaked at 21), `nuker_max_breaks_per_second` 15 → 25 (instamine reaches ~20/s),
  XRay coal 20 → 30 / iron 15 → 25 / copper 15 → 40 (1.18+ giant veins), XRay ratio check
  needs 60 mined stone (was 20) so cave miners aren't judged on tiny samples.

Note for existing installations: config values already present in your `config.yml` are
kept on update — apply the new calibration defaults (`autoclicker_max_cps`,
`nuker_max_breaks_per_second`, `xray_thresholds`, `autoblock_detection`) manually or
delete the keys so the new defaults merge in.

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

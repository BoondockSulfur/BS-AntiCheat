# Changelog

All notable changes to BSAntiCheat are documented in this file.

---

## [1.0.3] - 2026-08-05

Full code-review release: correctness fixes found by auditing every source file against
its own documentation, plus hot-path performance work and dead-code removal. No behaviour
change for the default configuration except where a check was previously too weak.

### Fixed — false positives
### Fixed — movement
- **Standing on entities is no longer GroundSpoof/Fly.** The ground scan only ever looked
  at blocks, so a player standing on a boat, a minecart, a horse or another player's head
  had "nothing below them" and was flagged. An entity check now runs — but only when the
  block scan came up empty, so it costs nothing during normal play.
- **Lily pads are no longer Jesus.** The water-surface test accepted any non-water block
  at foot level, so walking across lily pads (and standing on a boat on water) read as
  walking on water. The feet block must now be air specifically.
- **Jump Boost is exempt from the fly checks.** It raises both jump height and time spent
  rising, which fed the hover counter. The Step check had always excluded it.
- **Depth Strider is compensated.** Without it a player with Depth Strider III swims at
  roughly walking speed and pushes against the swimming cap.

### Fixed — inventory
- **InventoryMove had no knockback grace.** Any server-applied velocity (arrow or trident
  hit, wind charge, explosion, jump pad) moves a player with a GUI open without any key
  input. MovementChecker has always had this grace; this check was missing it.
- **InventoryMove ignored plugin-granted flight.** A player flying via EssentialsX `/fly`
  is in survival gamemode, so the creative exemption never applied — they drifted with a
  GUI open and were flagged. Now exempt, as is the Velocity check for the same reason.
- **ChestStealer had no lag guard.** After a lag spike the queued clicks all arrive in one
  tick and look like 0 ms intervals — an instant flag. Every other check already backed
  off under lag.

### Fixed — combat and vehicles
- **Reach honours the `entity_interaction_range` attribute.** Item plugins grant
  long-reach weapons by raising it; judging those hits against the flat config value
  flagged players for using their own gear.
- **Vehicle teleports are not speed violations.** `VehicleMoveEvent` carries no teleport
  flag, so a Multiverse portal or a plugin repositioning a ridden horse produced one
  enormous "movement". Implausible single-tick jumps now reset the streak instead.
- **A Speed potion on a mount raises its ceiling.** A fast horse under Speed II otherwise
  blew past the flat per-type limit.

### Changed
- **`autoclicker_min_signals` now defaults to 3 (was 2).** Markers 1 and 2 both measure
  jitter and rise together, so a human clicking steadily trips both — 2 was not the safe
  middle it appeared to be. Marker 3 (does the player ever pause?) is the independent
  signal. Documented in `config.yml`.

### Fixed — effects, items and attributes
- **Checks now read the game's own attributes instead of hand-rolled multipliers.** The
  Speed potion was compensated by a hard-coded formula, so every *other* legitimate way a
  player gets faster was invisible: attribute modifiers from custom gear and item plugins,
  datapacks, mount/armour buffs — and `EssentialsX /speed`, which bypasses the attribute
  system entirely via `setWalkSpeed()`. The speed checks now derive their ceiling from
  `movement_speed` and `getWalkSpeed()`. Verified numerically identical for potions
  (Speed I → 1.2x, Speed II → 1.4x, exactly as before), so nothing was loosened for
  vanilla play. Vanilla applies the sprint boost as a `movement_speed` modifier, which is
  divided back out so the separate sprint threshold is not inflated by 30%.
- **Sneaking uses the `sneaking_speed` attribute** — what Swift Sneak actually modifies —
  instead of a fixed 0.3, so any item or plugin granting faster crouching is respected.
  The enchantment is still read as a floor.
- **Jump strength scales the vertical fly allowance**, step height is taken from the
  `step_height` attribute, and **reduced `gravity` exempts the hover check** — hanging in
  the air without descending is exactly what that check flags, and low gravity makes it
  legitimate.
- **Body `scale` is respected**: a resized player has a proportionally wider hitbox (the
  ground scan now scans that width) and reaches proportionally further (reach ceiling).

- **Swimming reads `water_movement_efficiency`**, the attribute vanilla maps Depth Strider
  onto, so items and plugins granting the same effect without the enchantment are covered.
  The enchantment lookup remains as a floor.

### Changed — Criticals is now opt-in
- **`criticals_detection` now defaults to `false`.** Checking the vanilla rules against the
  implementation showed the condition ("critical **and** on the ground **and** fall distance
  zero") is a contradiction: the server only awards a critical when the player is airborne
  and falling, and `isCritical()` reports that same server-side decision. It can therefore
  never fire on legitimate vanilla combat — only on damage events synthesised by other
  plugins, i.e. it flags players for using custom gear. It also does not catch the real
  Criticals cheat, which fakes micro-falls so the crit is computed while the player is
  reported airborne. A working implementation needs per-tick vertical movement from the
  packet layer; until then the check is off rather than silently wrong. Consistent with the
  30 days of production data available, where it never fired once.

### Fixed — correctness
- **AutoClicker consistency analysis now actually exists.** `config.yml` documented seven
  options for it (`autoclicker_consistency`, `_min_samples`, `_min_cps`,
  `_max_deviation_ms`, `_max_cv`, `_max_outlier_ratio`, `_min_signals`) and the alert text
  was translated in both languages — but no code ever read them, so enabling it did
  nothing. Implemented as documented: three robotic markers (absolute jitter, jitter
  relative to click rate, share of human pauses), flagged when at least `min_signals`
  hold. Still opt-in; calibrate with `debug_mode`, which now logs sd/cv/outlier values.
- **XRay ore/stone ratio was measured against a stale stone count.** The stone deque was
  only trimmed to the time window when stone was broken or by the 5-minute cleanup task,
  so a player who mined stone and then switched to pure ore mining kept an inflated
  denominator for minutes — silently weakening the check. It is now trimmed at read time.
- **`/movealerts clear <player> --db` deleted only 3 of ~30 log types** (speed, fly,
  teleport), leaving every other check's rows behind while reporting success. It now
  deletes everything that is not XRay-owned, derived from a single shared constant so
  adding a check can no longer make it stale again.
- **Log deletion could hit the wrong player.** The queries built `LIKE` patterns from raw
  names, and `_` is a single-character wildcard in SQL — clearing alerts for `A_B` also
  matched `AxB`. Patterns are now escaped (`ESCAPE '\'`).
- **KillAura multi-target raised the violation level several times per incident.** Unlike
  every other check it did not clear its evidence after flagging, so each further hit in
  the window re-flagged and punishment tiers were reached far too fast.
- **Setback is Folia-safe.** It was the only synchronous `teleport()` left in the plugin
  and ran inside a `PlayerMoveEvent` handler despite `folia-supported: true`; it now uses
  `teleportAsync()`.
- **Setback no longer teleports to a stale position.** The grace windows (teleport,
  knockback, join, glide) returned early *before* the last-known-position bookkeeping, so
  a setback after a 2-second knockback window sent the player back to where they stood
  before it.
- **An explicitly granted `bsanticheat.bypass` now works for OPs.** All three checks were
  guarded by `!isOp()`, so granting the permission to an OP-admin silently did nothing.
  The permission defaults to `false`, so OPs still never receive it implicitly.

### Performance
- Ground detection does **one** scan per movement packet instead of two. The hover and
  GroundSpoof checks differ only in the depth they accept, so `supportDepth()` now returns
  the distance to the nearest support and both read it — ~35 fewer block lookups per
  movement packet per player. The slime/bubble-column lookups are shared the same way.
- Whitelist, restricted-world and XRay-exempt-world lookups are cached as sets. They were
  hit on every movement, hit and block break, and each call allocated a fresh `ArrayList`.
- **New `anticheat.transaction_interval_ticks`** (default `2`). The latency system sent a
  ping packet to every player every tick — 20 extra packets/second/player unconditionally.
  The default halves that; raise it further on high-population servers. Requires a restart.

### Changed
- Velocity's climbable check uses the game's `CLIMBABLE` tag instead of a hand-written
  material list that missed the `*_PLANT` vine variants.
- Alert numbers format with `Locale.ROOT`, so values no longer render as `4,20` on servers
  whose JVM runs in a comma-decimal locale.
- The Discord queue processor releases its slot under the same lock that starts it, and in
  a `finally` — an alert queued during hand-off could previously wait for the next alert,
  and an exception would have wedged the queue permanently.
- The PerformanceAnalyzer config migration resolves its path from the plugin data folder
  instead of the process working directory.
- Gson is declared in `pom.xml` (`provided`) instead of being used via a transitive
  paper-api dependency.
- Removed dead code: `db/TimeUnit`, nine unused accessors, a write-only tracking set in
  the movement alert manager, the unused German enum labels on `MovementType`, and the
  unused `general.prefix` language key. The five near-identical packet flag paths were
  merged into one.

### Documentation
- `config.yml` lists all ~30 `%check%` values for punishment tiers instead of 7, and
  documents that VL is tracked per check.
- README: corrected jar version, the inventory family is not opt-in, the opt-in list now
  matches the code, and the AutoClicker entry describes both signals.

---

## [1.0.2] - 2026-07-10

XRay calibration release: live data showed legitimate beacon (Haste II) + Efficiency V
deepslate branch mining still tripping the per-ore thresholds and the ore/stone ratio
(10.45% vs. the 10% limit).

- **Per-ore thresholds raised** for the deepslate-level ores: gold 10 → 15,
  redstone 10 → 20, lapis 8 → 15, diamond 6 → 10, emerald 4 → 6.
- **Ore/stone ratio 0.10 → 0.15** — cave/deepslate miners legitimately exceed 10%.
- **Combined rare-ore threshold 8 → 12** and now configurable
  (`xray_rare_combined_threshold`).
- **New `xray_exempt_worlds` option:** skip XRay entirely in listed worlds (resource/farm
  worlds that get reset). Prefer raised thresholds over full exemption — cheaters mine in
  exactly those worlds.
- **Threshold alerts now name the exceeded ores** (e.g. `[DIAMOND_ORE x11 (max 10)]`) in
  the alert and DB log — previously a logged alert couldn't be diagnosed or tuned against.

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

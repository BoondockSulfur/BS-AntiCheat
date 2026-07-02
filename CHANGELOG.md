# Changelog

All notable changes to BSAntiCheat will be documented in this file.

---

## [Unreleased]

### Changed
- Phase 1 movement pipeline: **sampling removed** — every PlayerMoveEvent is now checked
  instead of every 10th, so a cheat can no longer hide in skipped moves (regression-guarded
  by the `microspeed` harness scenario). Fly/hover now use a **server-authoritative**
  on-ground test (`isOnGroundServer`, bounding-box vs. the world) instead of the
  client-sent flag, hardening against NoFall/Fly spoofing. Consecutive-violation counters
  are now per-tick. Verified via the harness: all core cheat scenarios still flag, legit
  baseline stays clean.

### Added
- Elytra/Riptide speed-ceiling check (`elytra_detection`) — previously skipped entirely
- VehicleChecker (`vehicle_checks`): Boat-Fly and per-type vehicle speed checks via
  VehicleMoveEvent, closing the vehicle gap (PlayerMoveEvent doesn't fire while riding)
- Combat: Criticals (crit while on ground) and AutoBlock (attacking while shielding)
- World: FastBreak — per-block break time vs. the expected time from Block#getBreakSpeed
- InventoryChecker: InventoryMove, ChestStealer, FastUse, BowSpam, AutoTotem
- Packet: crash protection (oversized book/sign packets are cancelled) and packet-flood
  detection (`packetflood_max_per_second`); both stay active during server lag

### Fixed
- CombatChecker: Thorns damage no longer flags the victim as KillAura; sweep hits no
  longer cause bogus reach/angle flags; reach measured to hitbox surface (large mobs)
- XRayDetector: evidence resets after each flag (no more VL spirals from one crossing);
  Creative/Spectator exempt; snapshot reads (COW race); size limit evicts oldest only
- Shading: sqlite-jdbc/slf4j now provided by Paper (broken minimizeJar/relocation setup);
  jar shrinks from ~11 MB to ~320 KB
- DatabaseManager: no more silent log loss (batch overflow, failed inserts, shutdown
  drains the full queue into DB or fallback)
- FallbackLogger: synchronous flush during plugin disable (no IllegalPluginAccessException)
- Discord webhook: connect/read timeouts; real line breaks in embeds
- Commands: non-blocking player lookups; `--db` deletes run async
- Thread safety: config and language map are published atomically for async readers

---

## [1.0.0] - 2026-05-07

### Initial Release
- Extracted from PerformanceAnalyzer v2.3.4 as standalone plugin
- Movement detection (speed, fly, teleport) with lag compensation
- XRay detection (ore frequency, stone/ore ratio, per-ore thresholds)
- Knockback and teleport immunity to reduce false positives
- LuckPerms group-based whitelist support
- Discord webhook integration for alerts
- Silent mode for per-player alert muting
- SQLite database logging with auto-cleanup
- Bilingual support (English/German)
- Config auto-migration from PerformanceAnalyzer

---

## Links

- [GitHub Repository](https://github.com/BoondockSulfur/BSAntiCheat)
- [PerformanceAnalyzer](https://github.com/BoondockSulfur/PerformanceAnalyzer)

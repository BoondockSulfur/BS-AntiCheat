# Changelog

All notable changes to BSAntiCheat will be documented in this file.

---

## [Unreleased]

### Changed
- False-positive calibration pass (from live testing on a real server):
  - MovementChecker: 1.5s grace after gliding/riptiding ends, so elytra-landing momentum
    is no longer misread as a Speed violation.
  - AutoClicker: swings sent while breaking a block (tracked via PLAYER_DIGGING) no longer
    count as clicks, fixing the false positive on normal mining/wood-chopping.
  - Spider now requires meaningful sustained upward motion (>0.1/tick), not the tail of a
    normal jump next to a wall.
  - New defaults: NoSlow, Jesus, Spider, Step and Velocity/AntiKnockback ship **off by
    default** (opt-in) — the movement micro-heuristics are FP-prone and velocity's
    displacement measurement still needs rework. Spider/Step/NoSlow thresholds loosened for
    servers that do enable them.
- Phase 2 foundation: a **transaction/latency system** (`TransactionManager`) sends a play
  Ping to every player each tick and matches the Pong to measure true round-trip latency.
  Movement/vehicle/elytra lag compensation now uses this precise RTT instead of the coarse
  `getPing()` (falling back to `getPing()` until the first transaction completes). Verified
  end-to-end via the harness `transaction` scenario. Consumers (velocity, lag-compensated
  reach) build on this next.
- Phase 1 movement pipeline: **sampling removed** — every PlayerMoveEvent is now checked
  instead of every 10th, so a cheat can no longer hide in skipped moves (regression-guarded
  by the `microspeed` harness scenario). Fly/hover now use a **server-authoritative**
  on-ground test (`isOnGroundServer`, bounding-box vs. the world) instead of the
  client-sent flag, hardening against NoFall/Fly spoofing. Consecutive-violation counters
  are now per-tick. Verified via the harness: all core cheat scenarios still flag, legit
  baseline stays clean.

### Added
- Velocity/AntiKnockback check (`velocity_detection`, phase 2.3): flags clients that don't
  apply server-sent knockback (measured as displacement along the knockback direction a few
  ticks later, on the player's region thread). Guards: water/vehicle/gliding/riptide/
  climbable/wall exemptions and 3 consecutive full ignores. Harness-verified to flag a
  frozen (AntiKB) client; false-positive safety for a real applying client needs a live PvP
  test (mineflayer has no knockback physics to simulate it).
- ViaVersion legacy-client detection + optional exemption (`exempt_legacy_clients`, off by
  default; phase 4.2): reads a player's protocol version via the ViaVersion API (reflection,
  no hard dependency) and can exempt clients below `legacy_protocol_threshold`.
- Folia support (phase 4.3): a `util.Scheduler` abstraction over Paper's region/async
  scheduler API replaces every `Bukkit.getScheduler()` call — async work stays async,
  global tasks use the global region, and per-player packet flags run on the owning
  entity's region thread. `folia-supported: true`. Verified end-to-end against a real
  Folia 1.21.11 server (harness via `BSAC_SERVER_JAR=folia.jar`): clean enable, all core
  scenarios green, zero scheduler exceptions — identical to Paper. Behaviour on Paper is
  unchanged (the region API runs on the main thread there).
- Configurable calibration thresholds (`anticheat.thresholds.*`, phase 4.4): the consecutive
  violation counts and primary numeric thresholds for the movement-extra, vehicle, combat-extra
  and inventory checks are now tunable in config.yml (25 values) and applied via `/bsac reload`
  — no rebuild. Each defaults to its previous hardcoded value, so behaviour is unchanged.
- Bedrock exemption (`exempt_bedrock_players`, phase 4.1): a `GeyserHook` detects players
  connecting through Geyser/Floodgate (Floodgate API via reflection, UUID-prefix fallback,
  gated on a Geyser/Floodgate plugin being present) and exempts them from all checks —
  their client physics differ and would otherwise be flagged. Fails safe to "not Bedrock".
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

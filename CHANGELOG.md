# Changelog

All notable changes to BSAntiCheat are documented in this file.

---

## [1.0.4] - 2026-08-15

False-positive fixes derived from live alert data on the Rattenkolonie server. Over one
hour of ordinary play, one player produced 54 alerts across six checks — every single one
of them wrong. Each fix below is tied to the alert pattern that exposed it.

### Fixed — false positives

- **Towering up is no longer Fly.** Placing a block under your own feet and landing on it
  produces exactly the hover signature the check hunts for: the fresh block catches the
  player before gravity shows, so the fall the check waits for never comes, and at the apex
  of each jump the previous solid block has already dropped out of the support scan. A
  block placed within 3 blocks below and 1.5 sideways of the player's feet now suppresses
  the hover counter for 1.5s. The vertical-burst and GroundSpoof checks stay armed —
  pillaring produces neither a 3.5-block jump nor a player genuinely high above ground.
  *Live data: 31 hover alerts climbing Y 91→302, against 752 clay blocks placed and removed
  over the same Y range in the same minutes.*
- **Cobwebs, powder snow and honey walls are no longer Fly.** All three slow a descent below
  `-0.08` blocks/tick, which the hover check does not count as falling, so the counter keeps
  climbing while the player is in fact sinking. `isSupportive()` does accept cobweb and
  powder snow as footing, but `supportDepth()` only ever scans DOWNWARD from the feet — a web
  holding the player at body height above a cave, or a honey block on the wall beside them,
  is never seen, and `clearlyAirborne` becomes true. Both halves of the hover signature, from
  standing in a mineshaft. Slow Falling was already exempt; these do the same thing
  physically and now are too.
  *Live data: two hover alerts at Y −14, each within a few blocks of a cobweb the player
  broke in the same second.*
- **Riptide momentum is no longer Speed.** Vanilla clears `isRiptiding()` after the ~0.5s
  animation while the player is still travelling several blocks per tick, dropping them
  into the walking check at a 0.4 b/t cap. The post-glide grace is now 3s for riptide
  (elytra keeps 1.5s, where momentum drops fast).
  *Live data: SPEED alerts of 1.52 / 1.03 / 0.72 b/t — a decay curve — at the same second
  and coordinates as RIPTIDE alerts.*
- **Elytra/Riptide speed is measured over real elapsed time.** The check derived b/s from a
  single move event × 20, assuming one event equals one tick. It does not: a packet gap
  while flying over loading chunks delivers one event carrying several ticks of travel, and
  multiplying it by 20 invents speed that was never flown. Speed is now averaged over a
  250 ms window, so distance and elapsed time grow together and a gap is harmless.
  *Live data: a tidy 110→100 b/s "over-speed" curve from a routine rocket flight.*
- **Elytra and Riptide ceilings raised to what vanilla actually does.** Riptide III launches
  at 3 blocks/tick = 60 b/s by design, but the ceiling was 50; rocket-assisted elytra dives
  legitimately reach 100–120 b/s against a ceiling of 100. Now 75 and 140.

- **Holding the mouse button is no longer AutoClicker.** The held-button exclusion was keyed
  off the CPS count (accepted 18-22), and flagging began at 23 — two ranges meeting without
  overlap. But CPS is counted in a sliding window over packet ARRIVAL times: the client sends
  exactly one swing per tick (20/s), and network jitter bunches those arrivals so the window
  reads 21-23 while nothing about the clicking changed. Held swings then fell into the gap
  between "recognised as held" and "flagged".
  The exclusion now identifies a held button by the rate its swings arrive at — the median
  interval sits at one tick (50 ms) however much individual arrivals jitter — instead of by
  how many land in a window. A hand clicking at 23 CPS has a ~43.5 ms interval and is still
  not excluded, so closing the false positive did not open a hiding place. Median and MAD are
  used rather than mean and standard deviation, because a single pause between swings shifts
  a mean and explodes a deviation while leaving the median where it is.
  *Live data: four alerts, all reading exactly "23 CPS (Max: 22)", with no block broken at
  the time — swinging with nothing in reach, which the `START_DIGGING`-based mining
  exclusion never covered.*
- **`autoclicker_max_cps` raised from 22 to 25.** Butterfly clicking reaches 20-25 CPS by
  hand, so the old cap sat inside human range.

- **Pistons no longer read as Speed, Fly, GroundSpoof or InventoryMove.** A piston displaces
  a player without applying velocity — it fires no `PlayerVelocityEvent` — so the knockback
  immunity every other check relies on never engaged. The player is simply somewhere else
  next tick, which reads as movement they made themselves. Piston elevators, flying machines
  and door mechanisms all produce it. A new `PistonTracker` records where pistons fired into
  a bounded ring buffer; the checks consult it only on their would-flag paths, so a clock
  circuit costs nothing but an append.
- **Timer no longer fires on a stalled connection.** A gap in the packet stream means the
  client stopped sending; what follows is it flushing the backlog, and every queued packet
  credits a full tick with no real time attached, so the balance climbs by the whole backlog
  at once. The existing sustained-excursion rule does not help — an eight-second backlog is
  not paid off in a few hundred milliseconds either. A gap over 400 ms now discards the
  balance and leaves the catch-up unjudged for 3 s.
  *Live data: two TIMER alerts, one of 8284 ms, inside the minutes a player was timing out
  and reconnecting.*
- **InventoryMove no longer fires on momentum.** Vanilla friction needs several ticks to
  bring a sprint below the 0.15 threshold, and the player steers none of them. Judging now
  waits 1 s after the container opens, and skips airborne players entirely — walking off a
  ledge carries horizontal speed the whole way down with no key input behind it.
  *Live data: three alerts measuring 0.150 / 0.165 / 0.278 against a 0.15 threshold.*
- **Slime launchers no longer read as Speed.** The slime grace was computed inside the
  vertical block, so only the fly checks ever saw it — but a slime launcher throws a player
  sideways just as readily, and the bounce needs no key input either.
- **Dismounting at speed is no longer Speed.** Leaving a galloping horse or an ice boat hands
  the player its momentum; the grace that covers elytra landings now covers dismounts too.
- **Nuker and FastPlace require repeated windows.** Both flagged on a single one-second
  window over the limit. Plugins that break several blocks per action (vein miners, custom
  tools) fire a burst of events inside one tick, and instamining with Efficiency V and Haste
  reaches ~20 blocks/s by hand — close enough to the cap that one bundled window crosses it.
  A cheat holds the rate up across windows; a burst does not.
- **KillAura multi-target requires a streak.** Reach and aim angle each needed three
  suspicious hits; hitting several targets did not, so one burst flagged outright — and a
  crowded team fight legitimately puts three players within reach inside 250 ms.
- **AimSnap now requires the flick to be fast in time, not just in packet order.** The check
  compares three consecutive rotation packets, which should span ~2 ticks, but nothing bound
  how far apart they actually arrived. A packet gap handed it three rotations seconds apart —
  and turning to look at something and back is ordinary over a second. Capped at 150 ms.
- **FastUse reads the item's own use time.** The 600 ms floor assumed vanilla eat times;
  custom consumables that are legitimately quicker were flagged for being what they are. The
  floor is now the lower of the configured value and what the item actually needs.
- **Mining outside the overworld is accounted for.** `STONE_TYPES` — the spoil that forms the
  ratio's denominator and decides whether a player counts as searching — listed only
  overworld rock. Netherrack was absent, so in the nether the ratio check could never run at
  all and `stoneMined` stayed at zero however much was dug. Ancient debris has a threshold of
  3, is a rare ore, sits buried in netherrack (so it reads as hidden) and generates in
  scattered singles (so it passes the vein requirement): hunting it produced alerts with
  nothing able to account for the rock moved to find it. Netherrack, basalt, blackstone, soul
  sand/soil, end stone, sandstone and dripstone now count as spoil. The per-ore thresholds and the
  combined rare-ore count knew nothing about spoil, so a tunnel through ore-rich rock could
  pass a per-minute threshold on luck alone — copper (40), coal (30) and iron (25) all sit
  within reach of a good vein or two — with nothing in either check able to see the hundreds
  of blocks of stone that explain it. Someone moving that much stone is visibly searching,
  which is the opposite of what X-Ray is for. Above `XRAY_MIN_STONE_FOR_RATIO_CHECK` stone in
  the window, checks 1 and 3 now stand down and the ore-to-stone ratio decides; below it,
  they decide and the ratio stands down. The two cover disjoint cases instead of overlapping,
  and nothing falls between them. The broken-block record this relies on is size-capped like
  the placed-block one; at several hundred blocks a minute per player, waiting for the
  5-minute cleanup would let it reach tens of thousands of entries in between.
  *Trade-off, stated plainly: a player who digs spoil as camouflage now only has to beat the
  ratio. With ten hidden diamonds that means ~67 stone at the default 0.15 — they have to
  genuinely dig, but it is reachable. Live data from the source server shows every real
  tunnelling minute at a rare-ore ratio of 0.000 across up to 378 blocks of stone, so the
  0.15 default has a great deal of slack in it and is worth revisiting.*
- **X-Ray counts only ore that was hidden.** This is the flaw underneath all three X-Ray
  checks, and it inverts their central assumption. The detector treats "much ore, little
  stone" as suspicious — but X-Ray tells someone where ore is that they *cannot see*, and
  acting on that means **digging to it**, which produces stone. Clearing an open cave means
  taking ore off walls that were visible all along and digging *nothing*. The player who
  never touches stone is the one doing it legitimately; the ratio points the wrong way.
  Ore is now recorded with whether it was exposed to open space when broken, and the
  thresholds, the ore-to-stone ratio and the combined rare-ore count all judge hidden ore
  only. Faces the player opened themselves inside the window do not count as exposure, so a
  tunnel dug straight to a concealed vein still counts against them. Off via
  `xray_require_hidden: false`.
  *Live data: three X-Ray alerts against a player who had entered an untouched cave system —
  283 lava blocks, amethyst geodes and creeper explosions across the same coordinates, and
  no one had ever mined there. 45 ore against 12 stone: the signature of a cave, not a cheat.*
- **X-Ray counts deposits, not blocks.** The per-ore threshold looked only at how many ore
  blocks were broken in the window, which says nothing about how they were found: one thick
  vein produces the same number as a dozen scattered ones. Copper and redstone veins run past
  20 blocks, a lucky pair of overlapping diamond veins reaches ten, and any vein-miner style
  tool takes a whole vein in a single action — all of them crossed a threshold without anyone
  knowing anything they should not. What X-Ray actually provides is knowing where several
  SEPARATE deposits are without searching for them, so the ore must now also come from at
  least `xray_min_veins` distinct veins (blocks within 2 on every axis, linked transitively,
  count as one). The vein count is computed only for an ore that already crossed its
  threshold, so normal mining pays nothing for it.
  *Checked against live data: the three X-Ray alerts of 2026-08-15 drew from 4, 4 and 3
  distinct veins and still fire — this narrows what counts as evidence without blunting it.*
- **Vertical checks skip unloaded chunks.** A block scan in a chunk the server has not got in
  memory finds nothing and reports "airborne" for a player standing on solid ground — and
  reading it would force a synchronous chunk load from a movement handler, which Folia
  forbids outright.

### Added
- **A test suite (74 tests).** The project had none, so every fix in this release was
  verified by compiling it and reasoning about it — which is how several of them nearly
  shipped wrong. `mvn test` now runs JUnit 6 with MockBukkit, both as unit tests over the
  decision logic and as **scenarios driven through real event sequences** — a player walking,
  towering, being shoved by a piston, emptying a cave, digging a tunnel. That level matters
  because it is where the live false positives happened: no single sample was wrong, a
  counter simply never reset.

  Every scenario is written as a pair — the false positive that must fall silent, and the
  detection that must survive it — because an exemption is only worth having if what it
  exempts is still caught. Scenarios cover:
  - **AutoClicker** — the held-button cadence: jitter must not break the exclusion, and a
    hand clicking at 23 CPS must not slip into it.
  - **X-Ray deposits** — one thick vein counts once, scattered finds count separately. The
    suite replays real coordinates from the 2026-08-15 alerts, so a change that quietly
    stops detecting them fails the build rather than the server.
  - **X-Ray visibility** — ore on a cave wall is visible; ore behind a tunnel the player just
    dug is not. This is the distinction the whole detector now rests on.
  - **Fly exemptions** — cobwebs, powder snow and honey walls are exempt, open air is not.
  - **Pistons** — displacement is covered near the piston, not across the map or into
    another world, and a clock circuit cannot grow the buffer without bound.
  - **Latency slack**, which widens nearly every threshold in the plugin.

  Making this testable meant lifting six functions out of their event handlers into
  package-private, state-free forms (`isHeldButton`, `countVeins`, `wasVisible`,
  `isInFallSlowingBlock`, `median`, `medianAbsoluteDeviation`), and answering air and full
  blocks from the material alone in `isSupportive` before consulting `isPassable()` — which
  is also one fewer block-state lookup per ground scan. No behaviour changed.

  **Verified by mutation:** each of the twelve fixes in this release was broken on purpose
  and the suite confirmed to go red — the cobweb exemption, the pillar exemption, the piston
  and slime exemptions, the Nuker/FastPlace/KillAura streaks, the InventoryMove grace, and
  all four X-Ray rules (visibility, veins, the searching gate, netherrack as spoil). A green
  suite that cannot go red is worth nothing: two scenarios passed at first for the wrong
  reason and were only exposed this way — a cobweb placed at foot level registers as footing
  and never reaches the exemption, and a mock player defaults to not being on the ground,
  which silently disabled the InventoryMove check entirely.

  Not covered: anything inside `PacketChecker` end to end (AutoClicker, Timer, AimSnap),
  which needs PacketEvents objects MockBukkit cannot supply. Their decision logic is unit
  tested instead.
- `anticheat.speed_thresholds.elytra_bps` and `riptide_bps` — the two ceilings were compiled
  in, so a server whose item plugins grant faster flight had no way to adjust them.
- `anticheat.thresholds.nuker_violations`, `fastplace_violations` and
  `killaura_multi_violations` — the streak requirements added above.
- `anticheat.xray_min_veins` — how many separate deposits an over-threshold ore count must
  come from.
- `anticheat.xray_require_hidden` — count only ore that was still buried when broken.

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

### Fixed — the plugin now really is optional-dependency safe
- **BSAntiCheat did not load at all without PacketEvents**, despite `plugin.yml` listing it
  only as a `softdepend` and the README promising the packet checks would simply switch
  off. The main class named PacketEvents types directly (a `PacketListenerCommon` field,
  and passing `PacketChecker` where a `PacketListener` is expected), so the JVM had to
  resolve them while *linking* the main class — long before the runtime guard in
  `onEnable` could run. Anyone installing the plugin without PacketEvents got a startup
  error instead of the documented degradation. All PacketEvents references now live in a
  single `PacketIntegration` class; loading *that* is what fails on such a server, inside
  the caller's `try/catch(Throwable)`. Verified by loading the main class against a
  classpath with and without PacketEvents.

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

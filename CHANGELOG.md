# Changelog

All notable changes to BSAntiCheat are documented in this file.

---

## [1.1.0] - 2026-10-07

### Added
- Minecraft 26.1–26.3 support: new attack and punch packets (PacketEvents 2.14), spear reach, Lunge, wind charges, bouncy blocks and geysers, new mounts and physics attributes.
- New checks: NoFall, Sprint (hunger/blindness/omni-sprint), Mace smash, CrystalAura/AnchorAura, PingSpoof and extended BadPackets.

### Fixed
- Closed detection bypasses: ender pearl/chorus teleport grace, Timer pauses, packet bundling, oscillating or slowly sinking Fly/BoatFly, speed pulsing, dig-packet AutoClicker suppression, spoofed ground flag for InventoryMove, uncapped ping allowance.
- Fewer false positives: Jesus at shores and piers, flight/gamemode changes, fast-moving reach targets, vein-miner bursts (Nuker/FastPlace), team-fight KillAura, X-Ray in overgrown caves and dense veins, knockback in cobwebs/powder snow, riptide while gliding.
- Violation levels now survive a relog; punishment commands run in order on the correct Folia thread.
- PerformanceAnalyzer migration no longer imports Discord or silent-player settings into fresh installs; foreign keys from earlier imports are removed.
- Discord alerts share one rate limit per webhook and retry on HTTP 429; fallback log is flushed periodically.

### Changed
- Timer check uses the client tick-end packet; lag detection uses a short window (per region on Folia).
- Update notice shows clickable Modrinth and CurseForge links to operators on join.
- Validation for all numeric config values; `clear --db` deletes by player UUID.
- Console messages are English throughout; `/acsilent` category names come from the language files.
- Various smaller thread-safety, memory and performance fixes.

### API
- `ViolationEvent` now fires on the thread owning the player (region thread on Folia), one tick later than before.

## [1.0.6] - 2026-09-07

Live alerts from two production servers, worked case by case, and then a review pass over what
that work left behind. Every false-positive entry below started as an alert somebody looked at
and said "that is not what happened" — and in most of them the check was not merely mistuned
but measuring the wrong quantity. Three of the fixes
here replace a model rather than a threshold, and two proposals were built, measured against
the servers' own history, and then thrown away because the data did not support them.

### Fixed — false positives

- **Water movement is judged by water physics.** `isSwimming()` is only true in the horizontal
  swim *pose*. A player wading upright, surfacing, or carried by Dolphin's Grace is in water
  without ever entering it, fell through to WALKING, and was measured against the 0.4 dry-land
  cap — while the Depth Strider and Dolphin's Grace handling sat in a branch that was never
  reached. Live case: twelve SPEED alerts of 0.42–2.12 b/t, all labelled "walking", from a
  player with Depth Strider III and Dolphin's Grace.

  The fix applies the water cap as a **floor on the on-foot cap**, and deliberately does not
  reclassify the movement: the type gates far more than the speed limit — Step, Spider,
  GroundSpoof and the hover checks all require an on-foot type — so routing every in-water
  player to SWIMMING would have switched those off for anyone standing in a puddle. That was
  the first attempt, and it is the reason for the extra test that holds it shut.
- **Holding the drop key is not clicking.** Every item dropped rides on a PLAYER_DIGGING packet
  and is accompanied by an arm swing, so holding Q emits one per tick and read as 26 CPS. Only
  the *mining* actions of that packet were excluded. The drop actions now suppress the swing
  too, through a deliberately short 200 ms window — mining's five-second safety cap would have
  turned one dropped item into a five-second free pass for a real clicker.
- **A thick vein counts as one find, not as its block count.** The per-ore X-Ray threshold and
  the combined rare-ore count are block counts, and the hidden-ore test makes that worse rather
  than better: every block of a vein after the first is exposed by breaking its neighbour, so
  an entire deposit reads as hidden. The `xray_min_veins` gate was meant to prevent exactly
  this and could not, because the number it guarded stayed block-based. Live case: two fat
  deepslate veins produced 13 of 14 "hidden" diamonds and flagged an OP who had moved 3400
  blocks of stone that hour. Each deposit now contributes at most `xray_max_count_per_vein`.
- **Strip mining is recognised by its shape.** A new veto stands the counting checks down when
  the digging looks like a branch mine *and* the ore came out of the level it runs through.
  Calibrated against 4.5 months of this server's block log: honest strip miners hold one Y
  level to a standard deviation of 1.1–1.2, run 75–89 % of their digging as straight corridors,
  and take their ore from a 3.0–3.6 block band around them. The ore-band requirement is what
  keeps it from becoming a hiding place — without it a player could strip-mine honestly for ten
  minutes and have the veto cover the detours that were worth hiding.
- **The Timer check no longer punishes a bad connection.** A link recovering from a stall
  delivers its backlog faster than one packet per tick but never all at once, so no single gap
  reaches PACKET_GAP_MS and the stall grace never engages while the balance climbs the whole
  way. Live case: a player at 1275–1444 ms round trips reached a balance of 779 ms and was
  flagged. Neither the height nor the duration of that excursion separates it from a hack —
  what does is that a catch-up drains and **plateaus** while a hack keeps gaining. The balance
  must now still be growing at the end of the excursion, and the required duration is extended
  by the measured round trip. The window is stretched, never the limit: a cheat that answers
  transaction pings late to fake latency then only delays its own flag instead of escaping it.
- **A ballistic arc is not hovering.** The hover check counted any sample whose vertical speed
  stayed inside a ±0.08 band and never asked whether that speed was *changing* — but the apex
  of a slow arc sits inside the band for many samples in a row. Live case: dy ran +0.067 to
  −0.051 monotonically across all ten samples, with no potion, no gravity modifier and no
  teleport behind it (all three checked, not assumed). A hover *holds* its vertical speed;
  gravity keeps taking it away. `checkSustainedAscent` already judged motion this way — it asks
  whether a climb decays — and the hover check now does too.
- **Combat checks have a teleport and join grace.** Every other check family had one:
  MovementChecker keeps recentTeleport and recentJoin, PacketChecker keeps graceUntil. Combat
  had none, so a hit landing while a teleport was still in flight was measured against a
  position no client involved had yet. It applies to **both** sides — being teleported yourself
  desyncs your view of everyone, and a target arriving next to you desyncs theirs.
- **The same swing is no longer counted several times.** One hit does not always produce one
  event: item plugins that add their own damage fire EntityDamageByEntityEvent repeatedly with
  identical geometry. Measured here: of 327 hit groups, 83 arrived twice, 76 three times and 4
  four times — same attacker, same victim, same aim angle to the degree. Every streak
  requirement in the combat checks was defeated by that, so a `reach_violations` of 3 was
  satisfied by a **single** over-reach hit. Deduplicated within one tick, before any streak is
  touched; weapon cooldowns put genuine hits hundreds of milliseconds apart.
- **Ranged item abilities are no longer judged as melee hits.** This was the largest source of
  REACH alerts and it is not a threshold problem. MythicLib delivers ability damage as
  `LivingEntity.damage(amount, player)`, which Bukkit reports as ENTITY_ATTACK with the player
  as damager — the exact shape of a melee hit, from wherever the ability reaches. Live case: a
  sword whose right-click fires a VOID_ZAPPER beam with `length: 10.0`; all 17 of that player's
  REACH alerts (4.14 to 9.37 blocks) fell inside that range, and the server had 21 such
  right-click abilities configured.

  A real melee hit is announced by the client with an INTERACT_ENTITY/ATTACK packet; ability
  damage has none, because the player never attacked anything. Damage without that packet is
  now skipped by **all** combat checks, not just reach: aim angle and multi-target are equally
  meaningless for a beam, which hits what it covers rather than what is aimed at, and which
  reaching three targets at once is exactly the "3 targets in 250 ms" that was being reported.
- **Reach latency is modelled as a distance, not a percentage.** `pingSlack` scales a limit,
  which is right for a speed (blocks per tick × slack) and wrong for a distance: what latency
  costs here is however far the target travelled while the hit was in flight, and that is speed
  × time. Measured in a four-way PvP session at 373–1519 ms round trips — the old model allowed
  4.7–5.5 blocks while a sprinting pair separates by 4.2 to 17.0 in one round trip, and the
  alerts ran to 14.61, a figure the multiplicative model cannot produce and ordinary play can.
  The allowance is now additive and capped, and above `reach_max_ping_ms` the check **stands
  down** rather than compensating further: past that the server cannot tell where either player
  was, and a hole that grows with latency is one a cheat can open by answering pings late.
- **Muted alert categories survive a disconnect.** Persistent preferences are read from the
  config once, at startup, so anything dropped from memory on quit was gone for the rest of the
  server's uptime. `cleanup()` matched the stored entry with an exact-string test, which only
  ever recognised the "everything muted" form — a player who had muted individual categories
  counted as non-persistent and got those alerts back on rejoin.

- **The reach check's ping ceiling no longer switches off the rest of combat.** Above
  `reach_max_ping_ms` reach stands down, which is right — but the exit was a `return` out of
  the event handler, so it took KillAura's aim angle and multi-target count with it. Neither
  becomes less answerable as latency rises: an aim 90 degrees off the target is off however
  long the packet took, and the multi-target count is a question about time, not distance. On
  a link like the one behind the timer case above (1275-1444 ms) that is KillAura switched off
  permanently, and a cheat can put itself there deliberately by answering transaction pings
  late — the same trick the timer check is explicitly hardened against.
- **A punishment tier no longer fires twice.** The crossing test compared the level before
  and after the violation, and decay runs on every flag — so a VL sitting at exactly 1.0 is
  0.9999 a moment later, floors back to 0, and the next violation "crosses" the same tier a
  second time. With a kick in the tier that is a duplicate; with a ban or tempban it is a
  second punishment for a violation already punished. Tiers are now tracked as served and
  re-arm when the level has decayed to zero.
- **A configured tier no longer fires one violation late.** Levels were truncated, and decay
  runs on every flag and every read — so three violations a few seconds apart total 2.93 and
  `floor` made that a 2. Every threshold therefore acted one violation later than configured;
  for a kick tier of 25 that is the 26th alert. The same truncation made a fresh violation
  read as *"no violations"* in `/bsac info` a millisecond after it was counted, which is
  exactly when an admin looks after an alert. Levels are rounded now: the Nth violation
  reaches level N, while one that has genuinely decayed away still reads as gone. Verified
  live on mc-test — before the fix the notify tier needed three flags and the kick four, after
  it two and three.
- **The API event can no longer swallow a punishment.** `ViolationEvent` was fired
  unguarded on the punishment's critical path, so anything that made the scheduling throw
  took the tier below it with it — violation counted, nobody punished, nothing in the log.
- **Punishment commands run on the target's own region thread.** A kick, ban or tempban
  reaches into that player's state, and on Folia an entity may only be touched from the
  region that owns it; the global region is not that region. On Paper both are the main
  thread, so nothing changes there.
- **A rejected punishment command is reported.** `dispatchCommand`'s return value was
  discarded, so a typo in a tier, or a punishment plugin that had not loaded, looked exactly
  like a tier that never fired. Tiers that run are now logged, and an empty `tiers` section
  is called out the first time a level would have used it.
- **A hover run that once decayed can flag again.** The drop is measured against the run's
  first sample and that reference never moved, so a run which exceeded `fly_hover_max_drop`
  once stayed silent for its whole length. Entering the still band from a rise (+0.06, then
  held at -0.01) keeps a player inside the ±0.08 band indefinitely with a permanent drop of
  0.07: a hover descending 0.2 blocks a second that could never be flagged. Exceeding the drop
  now RESTARTS the run instead of only withholding the verdict, which costs the ballistic case
  nothing — it keeps falling out of the band anyway.

- **An edited punishment tier is no longer restored beside the admin's own.** The reported
  case: "kick messages can't be edited, the plugin sets them back". The VL threshold is part
  of the KEY, so changing `"40"` to `"10"` looks to the config merge like key 40 was lost —
  and it put the shipped tier back, default kick message and all, next to the edited one,
  where it kept firing. Everyone changes that threshold, because 40 is out of reach for a
  per-check VL that decays every five minutes. Sections whose keys are the admin's data
  (`punishments.tiers`, `xray_thresholds`) are now left alone once they exist; a config that
  predates a section entirely still gets it in full.

### Added

- **The plugin can punish without borrowing another plugin's commands.** A tier entry of
  `@kick [reason]` is carried out directly, and `@notify [text]` tells the holders of
  `bsanticheat.admin` and nobody else. `@kick` keeps every colour format in its reason — a
  dispatched command hands its text to whichever plugin owns `/kick`, and vanilla's passes it
  through as plain text — needs no such plugin installed, and behaves the same on Paper and
  Folia because it runs on the player's own region. With no reason given it uses
  `punishment.kick_default` from the language file, so the message is translated rather than
  copied into every server's config. Anything without an `@` is still run as a console
  command, so `/tempban` and friends are unaffected.
- **The shipped tiers are staggered**: VL 12 notifies the admins, VL 25 kicks. Previously a
  single step at 40 — reachable only under sustained cheating, so switching punishments on
  appeared to do nothing at all. The low step costs nothing when an alert turns out to be a
  false positive, and nothing bans automatically; that decision stays with a human.
- **`/bsac test <player> <CHECK> [count]`** raises a violation level by hand. There was no
  way to reach the punishment ladder without actually cheating — Speed and Fly need a modified
  client, Nuker and FastPlace need rates no hand achieves — so an admin who configured tiers
  could not find out whether they worked until somebody tripped them for real. It runs the
  real `flag()` path, so levels, decay, tier matching and the commands behave exactly as they
  will in earnest; which also means it really punishes, and logs who asked for it. Capped at
  50 per call.
- **Every common colour format, mixable in one line.** Only `&0`-`&f` was ever translated, so
  `&#9863E7` and `<#9863E7>` reached the player as literal text. Messages now accept the
  classic codes, hex as `&#RRGGBB` and as `&x&R&R&G&G&B&B`, and MiniMessage
  (`<#9863E7>`, `<red>`, `<bold>`, `<gradient:…>`) — in any combination. The legacy rule that
  a COLOUR clears bold/italic is preserved, so existing language files render as before, and
  tags that are not colours are left alone so `/bsac <reload|info|version>` still reads
  correctly. One parser, in `Messages`, reached through `LanguageManager` — which is why no
  call site had to change.
- **PlaceholderAPI placeholders are resolved in the plugin's own messages.** The expansion
  that PUBLISHES violation levels has existed since 1.0.0; nothing ever RESOLVED anybody
  else's placeholders. Alerts and punishment commands now do, against the player the message
  is about, and are unchanged on servers without PlaceholderAPI.

### Changed

- **ChestStealer is off by default.** The check judges the interval between container clicks,
  and vanilla shift-drag — hold shift, hold the button, sweep across slots — fires one
  InventoryClickEvent per slot crossed at 10–40 ms spacing. That is the exact band it watches,
  so one hand movement reads as a burst of inhuman clicking. Measured: a player sorting storage
  for two hours produced 96 alerts, and 88 % of their fast clicks landed on a slot *adjacent*
  to the previous one — a mouse path, not a click rate. Running total over the check's life:
  97 alerts, 0 real findings.

  No threshold fixes that: widen the floor past 40 ms and the check is dead, keep it below and
  it keeps catching sweeps. A ChestStealer's actual signature is a container standing **empty**
  a fraction of a second after it was opened, which is a question about container state and
  open duration rather than click spacing. The code is kept for that rewrite. An alert type
  that is wrong every time teaches everyone to ignore it, and then a real one goes unnoticed.
- `/bsac reload` now re-applies `transaction_interval_ticks`. The ping task's period is fixed
  when the task is created, so reloading the config alone left the old interval running.
- Teleports now reset **all** movement streaks, not only speed and fly. Every one of them is
  built from deltas against a position the teleport just invalidated.

### Fixed — correctness

- `DatabaseManager` uses a bounded queue instead of a `ConcurrentLinkedQueue` guarded by a size
  check. That check ran on every logged violation and `size()` walks the whole queue — harmless
  while it is short, but it is only ever long during a database outage, i.e. exactly when every
  violation would pay for an O(n) scan.
- `AsyncConfigSaver` no longer loses saves. The pending flag was checked while the saving flag
  was still set, so a request arriving in that gap found a save in progress, returned without
  scheduling anything, and was never picked up by anyone.
- `FallbackLogger` drains into a batch before writing. Polling straight into the writer meant a
  failure part-way through — a full disk, a revoked permission, precisely the situations this
  file exists for — had already removed those entries from the queue.
- `CombatChecker` uses a concurrent deque for its recent-target history. On Folia an
  EntityDamageByEntityEvent runs on the **victim's** region thread, so one attacker hitting
  entities in two regions reaches it from two threads at once.
- `WorldChecker` no longer dereferences a rate window that a concurrent disconnect may have
  removed.
- `UpdateChecker`'s fallback applies the same guard as the loop above it, instead of
  dereferencing a missing field on exactly the malformed entry that loop skipped.
- `Exemptions.via` is volatile — the packet checks read it from Netty threads, which share no
  happens-before edge with the enable that wrote it.
- Merged config keys arrive with the comments that ship above them. `mergeDefaults` only ever
  copied the value, so an admin updating the plugin got every new switch as a bare line while a
  fresh install got the reasoning — worst for exactly the keys that were calibrated against
  live data.
- A `cheststealer_detection` left ON is reported at startup. The merge only ADDS keys, so a
  changed default never reaches a server that already has the line; the value is the admin's
  decision, so it is named rather than overruled.
- All 33 `String.format` calls use `Locale.ROOT`. Previously mixed, so number formatting
  depended on the host JVM's locale.

### Diagnostics

- `[REACH-DEBUG]` and `[TIMER-DEBUG]`: these two checks recorded nothing at all, which is why a
  7.26-block reach alert and a 779 ms timer alert could not be taken apart afterwards. Reach
  logs from 80 % of its limit, not only on the flag.
- `[COMBAT-DEBUG]` reports damage skipped as a plugin ability.
- `[HOVER-DEBUG]` carries the absolute height and the measured drop.
- The AutoClicker line carries the connection's current packet rate. A burst of arrivals and a
  fast hand both raise the click count, and on 2026-08-25 twelve AutoClicker alerts landed on a
  player who was mining continuously for 25 minutes — ten of them in the exact seconds Paper
  disconnected him with "You are sending too many packets". No threshold is drawn from this
  number yet; it exists so the next such case is decidable rather than arguable.
- X-Ray logs the capped count and which veto fired.

### Investigated, not shipped

Two proposals were built out, measured against 4.5 months of the server's own block log, and
dropped. Both are recorded because the measurements are the useful part.

- **A longer spoil window for X-Ray.** The false positive came from a player who dug for
  minutes and then spent one minute extracting, so the spoil that explained the ore had expired
  by the time the ore was counted. Widening the window fixes that case and does not separate
  anything: an X-Ray user tunnels straight from vein to vein and digs just as much, so a wider
  window hands *them* the searching exemption too. Reverted; the config keys remain with the
  reasoning attached, so the idea is not re-invented.
- **A long-horizon ore-to-stone ratio.** The intuition was that honest mining has long dry
  stretches and X-Ray does not. Measured over 32 half-hour windows from four players, the
  honest maximum is 0.089 while a tunnelling X-Ray user computes to 0.04–0.13 — the two
  overlap, and a threshold high enough to avoid false positives (0.10) sits above where a
  typical cheat lands. A directedness proxy (what share of digging ends near ore) was tried
  next and separated no better: every honest player fell in a narrow 0.18–0.29 band, and an
  X-Ray tunnel produces the same, because only the tail of a corridor is near its ore.

  The exonerating side *is* calibratable and shipped (the strip-mining veto above). The
  incriminating side is not, on this data: there is no confirmed X-Ray case on either server to
  calibrate against, and any threshold picked without one is a guess wearing numbers.

### Tests

96 → 182. New suites for the mining-shape veto, the drop key, the timer balance, the melee
tracker, the combat grace, alert-preference persistence, the reach check's ping ceiling, the
colour and placeholder handling, and the punishment path — which had none at all, because every scenario elsewhere replaces the
ViolationManager with a counter to keep MockBukkit's missing region scheduler out of the way.
That is how a tier could fire twice unnoticed.

Every behavioural fix was counter-checked by reverting it and confirming the new test fails.
That caught four tests that passed for the wrong reason and proved nothing: two where a
different guard was doing the work, one where the profile was one sample short of its
threshold, and one whose "disabled" setting still satisfied the condition it was meant to
break.

---

## [1.0.5] - 2026-08-20

A code review pass, then two days of live alert data with `debug_mode` on. 1.0.4 fixed the
elytra speed check's assumption that one move event equals one tick; this release finds the
same assumption in the two places it was left standing, and closes several holes of the same
shape — a check whose evidence never expired, a guard that covered less ground than it
claimed, state that was only maintained on the paths that reached the end of the method, and
a rate that measured the network rather than the player.

### Fixed — false positives

- **A move event is no longer treated as one tick.** The ground speed and vertical checks
  compared a per-event delta against a per-tick threshold (0.4 blocks walking, 3.5 vertical).
  A move event is not reliably one tick: a client on a poor connection delivers one event
  carrying several ticks of travel, and judging that as a single tick multiplies the apparent
  speed by however many ticks were bundled. This is exactly the mistake 1.0.4 removed from the
  elytra check, where live data caught it as a 110→100 b/s "over-speed" curve from a routine
  rocket flight; the ground checks carried it unchanged. The delta is now divided by the ticks
  it actually spans, clamped at one so a sub-tick event is never scaled *up*.
- **The catch-up move after a packet gap is no longer judged.** Per-tick scaling handles the
  ordinary case but cannot rescue the pathological one: a stalled connection flushing seconds
  of backlog produces a single delta large enough for the TELEPORT threshold, which no
  division makes innocent. A gap over 400 ms now leaves that one move unjudged — the movement
  counterpart of the rule the Timer check has had since 1.0.4.
- **Vehicle speed is measured over real elapsed time.** `VehicleChecker` derived blocks per
  second from one move event × 20, the same assumption again, and a boat crossing loading
  chunks on an ice highway is precisely the case that breaks it. Speed is now averaged over a
  250 ms window, so distance and elapsed time grow together.
- **AutoClicker measures the click rate, not the packet arrival rate.** There are two ways to
  read a rate off arm-swing packets, and both fail upwards — in different situations. Counting
  arrivals in a sliding second puts the network in charge of the answer: a connection that
  delivers a tick's swings in bundles makes the count read whatever the bundling lines up
  with. Taking the typical interval instead is immune to that, but an interval is not a rate:
  it spans the whole consistency window, so a short fast burst fills the window with burst
  intervals and reports the speed *inside* the burst as though it were sustained.

  Both were observed in the field. A player placing blocks was flagged at 26 CPS while the
  interval median sat at exactly 50.0 ms — one server tick, the held-button cadence — through
  the entire run-up; the MAD of 49 ms against that median describes a bimodal arrival pattern,
  half the intervals near 0 ms and half near 100 ms, which is bundling and which also broke
  the held-button exclusion, since that wants the spread tight. Separately, three alerts of
  29–30 CPS had arrival counts for the same second of 5, 8 and 10 — a handful of quick clicks
  in a row, which is an ordinary thing to do.

  The rate is therefore the **smaller of the two** estimates. Neither can fall below the true
  rate, so the minimum is safe in both directions: bundling is capped by the median, a burst is
  capped by how many clicks actually arrived, and genuinely sustained fast clicking raises both
  and is still caught. Note that raising `autoclicker_max_cps` does not help against either
  artefact — the run-up went through 22, 23, 24 and 25 without pausing.
- **Rising is no longer counted as hovering.** The sustained-hover check counted every sample
  that was not *falling*, which made a climb indistinguishable from hanging in the air. Live
  data: four alerts fired while the player was moving UP at 0.12–0.20 b/t in a Trial Chamber —
  a Breeze, a wind charge or a Wind Burst mace throws a player upwards for far longer than the
  2 s knockback grace lasts, and the tail of that arc is a slow climb with nothing underneath.
  Hovering now means what the name says: vertical movement inside one tick of gravity
  (±0.08 b/t). Falling resets the counter as before; rising does too.
- **Nuker and FastPlace evidence now expires.** Both ask for several one-second windows over
  the rate limit before flagging, so that one bundled window — a vein miner, a burst of place
  packets — is not evidence. But the counter could only be reset by flagging: a window that
  stays *under* the limit produces no event on that path at all. The count therefore never
  came down, and unrelated bursts spread across a session added up until the third one flagged.
  The windows must now fall within 10 s of each other to count as consecutive, which is what
  "the rate was held up" was supposed to mean. `CombatChecker` already solved this the same
  way; these two were the ones that had not.
- **The unloaded-chunk guard now covers the neighbourhood it reads.** It checked the chunk the
  player stands in, but the checks behind it read the player's *neighbours*: the ground scan
  samples all four footprint corners, the fall-slowing and liquid exemptions look sideways,
  and Spider/Jesus look at the walls. On a chunk border those reach into the next chunk, where
  an unloaded result reads as "nothing below the player" — and forces a synchronous chunk load
  from inside a movement handler, which is what Folia forbids. The guard now covers one block
  of margin in every direction, and the vertical checks stand down (dropping their streaks
  rather than resuming them across the blind spot) when it is not satisfied.

### Added

- **Sustained ascent (opt-in, off by default).** Narrowing the hover band leaves a slow steady
  climb unwatched, so `anticheat.sustained_ascent_detection` covers it — by the one thing that
  separates thrown from flown. A ballistic rise sheds about one tick of gravity of vertical
  speed every tick and ends within a second or two; a climb that does not decay is not one
  anything threw. It ships off because it is a heuristic with no live data behind it yet: turn
  on `debug_mode`, watch the `[ASCENT-DEBUG]` decay values on your own server, then enable it.

### Fixed — other

- **The plugin no longer overwrites a `config.yml` it did not change.** The config was saved on
  every shutdown regardless, which made the plugin the last writer of a file it had not edited.
  An admin who edits a threshold while the server runs and does not run `/bsac reload` had that
  edit silently replaced by the stale in-memory value at the next stop — on a server with a
  scheduled restart twice a day, within hours, with nothing in the log to explain it. Saving is
  now tied to the plugin actually having something of its own to write: the whitelist and
  ore-exclusion commands, and the validator repairing an invalid value. Everything else on disk
  stays the admin's. `/bsac reload` is still what makes an edit take effect on a running
  server; it just no longer costs you the edit if you forget it.
- **Log rows carry the time of the violation.** The `ts` column was left to `CURRENT_TIMESTAMP`,
  which stamps the moment the row is INSERTed. Entries are batched and flushed every 30 s, so
  every alert in a batch shared one timestamp, up to half a minute after the fact — visible in
  the data as clusters of rows on the same second. That is exactly the column needed to line an
  alert up against the server log. The detection time is now recorded with the entry and
  written explicitly, in the same UTC format the default produced, so existing rows and queries
  are unaffected. The fallback logger, which receives whole batches at once during a database
  outage, had the same problem and takes the same timestamp.
- **Setback no longer teleports to a stale position.** The movement handler returned early for
  whitelisted, bypassing, OP-exempt, creative and spectator players without recording where
  they were. The last known position is what a setback teleports to, so a spell in creative
  froze it at the point of entry: the first violation after returning to survival sent the
  player back there, possibly thousands of blocks and many minutes ago. Every exempt path now
  keeps the baseline current. (Only reachable with `punishments.setback` enabled, which is off
  by default.)
- **The PacketFlood alert reports the rate it measured.** It reported the configured limit plus
  one — the same number every time, from which neither the severity of a logged flood nor a
  sensible limit could be read afterwards.
- **Suspending checks under lag is announced.** Below `lag_exempt_tps` nearly every check backs
  off, so a server sitting at 17 TPS runs with the anticheat effectively switched off. It now
  logs when it stands down and when it resumes, after five confirming samples so a server
  hovering on the threshold does not log every second.

### Diagnostics

`debug_mode` said nothing about the checks whose alerts most needed explaining, so they were
instrumented:

- **ChestStealer** had no debug output at all. It now logs the interval, click type, slot and
  streak for every counted container click, including the pairs below the physical floor that
  are deliberately ignored.
- **The hover check** only logged on `getLogger().fine()`, which the default log level drops.
  The counting path now logs on INFO: vertical speed, what the ground scan found below the
  feet, the on-ground flag, the pillar grace and the tick span of the sample.
- **AutoClicker** logs both rate estimates side by side (`cps=` and `arrivals=`), so a future
  alert shows at a glance which one is driving it.
- **The X-Ray "OP is still being checked" notice** fired on every block broken — 2843 identical
  lines in one debug session, burying what the mode was turned on for. Once per player now.

### Build

- **`maven-compiler-plugin` 3.11.0 → 3.14.0.** 3.11.0 cannot drive a JDK 25 toolchain: its
  incremental-compile scan throws `CompilerException: ConcurrentModificationException` before
  javac runs, so `mvn clean package` failed outright on any machine whose default JDK had
  moved past 21. The bytecode target is unchanged — `release` is still 21.

### Tests

74 → 96, each behavioural fix verified to go red against the behaviour it replaces.

The AutoClicker fixtures reproduce the live statistics exactly — median 50.0 ms with a MAD of
49.0 ms for the bundled case, the 33 ms cadence for the burst — and assert the rates that
follow (20, not 26; 8, not 30) while a genuinely sustained 30 CPS still flags. Ascent is
covered as a pair: a ballistic arc raises nothing, genuine hanging still raises FLY, and the
opt-in ascent check fires on a climb that never slows while ignoring one that does. Config
ownership is covered byte for byte — Bukkit's `saveConfig()` rewrites YAML in its own style,
so "was the file written" is directly observable against a fixture that is the shipped default
verbatim, including an edit made without a reload having to survive the next shutdown. The
rate-streak lapse is tested as a pure function with the clock passed in, so no test has to
sleep for the length of the window it tests.

Not covered end to end: the vehicle speed window, which needs a ridden vehicle MockBukkit
cannot supply — the same gap `PacketChecker` has.

### Investigated, not changed — elytra over-speed

Seven alerts of 204–311 b/s against the 140 ceiling, one player, one flight of roughly 1300
blocks. The hypothesis was the same class of bug as the rest of this release: a stalled client
flushing its backlog delivers real distance with almost no real time attached, and the window
that closes afterwards reports the lot. It did not survive a test — a stall poisons a single
window, and the low-speed window the stall itself produces resets the streak before it can
reach the three consecutive windows the check wants. Repeating the stall does not get there
either.

The data cannot settle it. Under 1.0.4 the `ts` column was the flush time, so all seven rows
carry the same batch write and the only bound on the flight is "somewhere inside a 30 s
window": 1300 blocks in 30 s is ordinary, in 4.5 s it is not. The geometry cannot break the
tie either — the distance between alert positions and the reported speed both derive from the
same 250 ms windows, so they agree whether or not the elapsed time was measured correctly.

No change was made. A stall guard on the elytra path would have been a fix for an unproven
cause, and a 3 s unjudged window after every 400 ms packet gap is something a cheat can ask
for on purpose. The detection-time fix above is what makes this answerable next time; until
then the elytra path at least has an end-to-end test that it fires on sustained over-speed,
which nothing verified before.

### Known limitation

AutoClicker cannot distinguish a clicker running at ~20 CPS from a held mouse button. The
held-button exclusion identifies a held button by its cadence — one swing per server tick,
50 ms — and an autoclicker set to that rate produces the same packet stream a held button does.
There is no signal left to separate them at that rate.

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

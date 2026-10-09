package dev.boondock.bsanticheat.anticheat;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPong;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUpdateSign;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import dev.boondock.bsanticheat.config.PluginConfig;
import dev.boondock.bsanticheat.db.DatabaseManager;
import dev.boondock.bsanticheat.integration.GeyserHook;
import dev.boondock.bsanticheat.integration.LuckPermsHook;
import dev.boondock.bsanticheat.lang.LanguageManager;
import dev.boondock.bsanticheat.util.Constants;
import dev.boondock.bsanticheat.util.ItemCompat;
import dev.boondock.bsanticheat.util.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Packet-level checks (via PacketEvents).
 * <ul>
 *   <li>AutoClicker — from arm-swing packets (ANIMATION, or PUNCH on MC 26.3+), using two signals: raw clicks
 *       per second, and click-interval consistency (low standard deviation = metronomic =
 *       autoclicker, which also catches slow-but-perfectly-regular clickers).</li>
 *   <li>BadPackets — impossible rotation values, plus the extended protocol rules
 *       (hotbar slot range, duplicate slot changes, self-attack, flight claims).</li>
 * </ul>
 *
 * <p>Runs on Netty threads, so all Bukkit access (alerts) is hopped back to the main
 * thread before use.
 *
 * <p><b>Threading:</b> the per-player maps are concurrent, but their VALUES are plain
 * {@link ArrayDeque}s. That is safe because a connection's packets are handled on that
 * connection's own event-loop thread, so one player's state is never touched concurrently.
 * If that ever stops holding, these need to become synchronized or concurrent deques.
 */
public class PacketChecker implements PacketListener, org.bukkit.event.Listener {

    private static final long WINDOW_MS = 1000L;
    // How many recent swing timestamps to keep per player for interval analysis.
    private static final int SAMPLE_CAP = 40;
    // Recent clicking window analysed for consistency. Wide enough that even slow
    // (~2 CPS) clickers accumulate enough samples; pauses still raise the outlier signal.
    private static final long CONSISTENCY_WINDOW_MS = 8000L;

    private final Plugin plugin;
    private final PluginConfig config;
    private final DatabaseManager database;
    private final LanguageManager lang;
    private LuckPermsHook luckPerms;
    private GeyserHook geyser;
    private MovementAlertManager alertManager;
    private ViolationManager violationManager;
    private TransactionManager transactionManager;
    private MeleeTracker meleeTracker;
    private LungeTracker lungeTracker;

    // Hotbar slot the client last selected, as seen in the packet stream (client changes and
    // server-set slots). Used to read the item a STAB was made with, rather than whatever the
    // player holds by the time a region thread gets to look.
    private final Map<UUID, Integer> clientSlot = new ConcurrentHashMap<>();
    // BadPackets (extended): consecutive identical hotbar changes, see noteHeldSlotPacket.
    private final Map<UUID, DuplicateSlotState> duplicateSlots = new ConcurrentHashMap<>();
    // BadPackets (extended): flight claims while flight is not allowed, as timestamps.
    private final Map<UUID, Deque<Long>> abilityClaims = new ConcurrentHashMap<>();
    // BadPackets (extended): the flight permission the CLIENT was last told (outgoing
    // PLAYER_ABILITIES) and the last PlayerToggleFlightEvent, see judgeFlightClaim.
    private final Map<UUID, FlightAbilityState> flightAbilities = new ConcurrentHashMap<>();
    // Vanilla hotbar size: the client only ever selects slots 0-8.
    private static final int HOTBAR_SIZE = 9;

    private final Map<UUID, ConcurrentLinkedDeque<Long>> clicks = new ConcurrentHashMap<>();
    // Timer accounting per player, see TimerState.
    private final Map<UUID, TimerState> timerState = new ConcurrentHashMap<>();
    private static final long TICK_MS = 50L;
    // Most credit (ms) idling or a short hiccup can bank. Bounds how far a timer hack can
    // run ahead after pausing, instead of opening a judgement-free window.
    private static final long MAX_CREDIT_MS = 1000L;
    // A gap between claimed ticks longer than this is a stalled connection, whose backlog
    // may pay off the real time the gap took (up to STALL_MAX_CREDIT_MS) — but only while
    // it arrives within the catch-up window that follows.
    private static final long STALL_GAP_MS = 1000L;
    private static final long STALL_MAX_CREDIT_MS = 30_000L;
    private static final long STALL_CATCHUP_BASE_MS = 1000L;
    // A connection that slows down without stopping delivers its ticks late, one gap at a
    // time. A gap clearly longer than one tick counts as late: the real time it took beyond
    // the credit floor is owed back by the backlog, exactly like a single long stall. Only
    // the jitter margin is excluded, so the drift leak of a client on time banks nothing.
    private static final long LATE_GAP_MS = TICK_MS + 10L;
    // Backlog arrives at network speed. Claims this close together while the debt is still
    // being paid keep the catch-up window open, however long the backlog takes to drain.
    private static final long BACKLOG_GAP_MS = 10L;
    private static final int MAX_PENDING_TELEPORT_MOVES = 3;
    // AimSnap looks at three consecutive rotation packets, which should span ~2 ticks. This
    // bounds how far apart they may actually have arrived before the "flick" is just a player
    // turning around at human speed with a packet gap in the middle.
    private static final long AIMSNAP_MAX_SPAN_MS = 150L;
    // Held-button (mining, or simply swinging at air) signature to exclude from AutoClicker.
    // It is identified by the RATE the swings arrive at, not by how many land in a window:
    // a held button is bound to the server tick, so its typical interval is TICK_MS. How far
    // the median interval may sit from one tick — 6ms accepts 44-56ms, i.e. 17.9-22.7 CPS,
    // and still excludes a 23 CPS clicker (43.5ms). The upper bound matters: it is what keeps
    // the exclusion from becoming a hiding place for a genuine clicker.
    private static final double HELD_TICK_TOLERANCE_MS = 6.0;
    // Spread around that interval, as median absolute deviation. A single pause between
    // swings inflates a standard deviation but leaves the MAD where it is.
    private static final double HELD_MAD_MAX_MS = 12.0;
    // Below this many intervals the standard deviation is noise, not a signature.
    private static final int MIN_INTERVALS_FOR_SD = 7;
    // KillAura rotation GCD analysis
    // The three rotation deques below are plain ArrayDeques on purpose: a connection is
    // bound to one Netty event-loop thread for its lifetime, so every packet of a given
    // player reaches them serially. Only the enclosing maps are shared across threads
    // (cleanup removes entries from a region thread), and those are concurrent.
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> yawDeltas = new ConcurrentHashMap<>();
    private static final double ROT_EXPANDER = 131072.0;
    // AimSnap: last 3 look directions + recent snap timestamps
    private final Map<UUID, Deque<double[]>> recentDirs = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> snapTimes = new ConcurrentHashMap<>();
    // Packet flood: [0]=window start(ms), [1]=count in window, [2]=consecutive windows over the limit
    private final Map<UUID, long[]> packetCounts = new ConcurrentHashMap<>();
    // Mining state: while a player is breaking a block the client sends a swing every tick,
    // which would false-flag AutoClicker. A START_DIGGING packet alone only opens a short
    // provisional window (it may name any position). It is extended to the block's expected
    // break time by a server-side confirmation that the named block is non-air and within
    // reach: either BlockDamageEvent, or a region-thread lookup scheduled for every START.
    // The lookup is needed because Paper skips BlockDamageEvent when the dig is refused
    // earlier (spawn protection, region plugins), while the client keeps digging and
    // swinging. FINISH/CANCEL and entity attacks (vanilla aborts digging before attacking)
    // close the window.
    private final Map<UUID, MiningState> mining = new ConcurrentHashMap<>();
    // Covers the swings between the START packet and the server processing it (<= 1-2 ticks).
    private static final long MINING_PROVISIONAL_MS = 250L;
    // STARTs the server never confirmed; beyond this they no longer open a window.
    private static final int MAX_UNCONFIRMED_STARTS = 2;
    // Upper bound for a confirmed window. Longer digs are still covered by the held-button
    // exclusion, which recognises tick-cadence swings on its own.
    private static final long MINING_SAFETY_MS = 5000L;
    private static final long MINING_MARGIN_MS = 250L;
    // Eye-to-block-centre distance a START may name to count as a dig: survival block reach
    // plus the block's half diagonal, with some slack. Only used when the player has no
    // block interaction range attribute.
    private static final double MINING_REACH_FALLBACK = 6.0;
    private static final double MINING_REACH_SLACK = 1.5;
    // Drop state: holding Q sends a DROP_ITEM every tick, each accompanied by an arm swing,
    // which would count as clicks. Deliberately a SHORT window, refreshed by every drop
    // packet, so a single dropped item cannot open a long exemption for a real clicker.
    private final Map<UUID, Long> droppingUntil = new ConcurrentHashMap<>();
    private static final long DROP_SUPPRESSION_MS = 200L;
    // Grace after a teleport/join/respawn/world change. Loading the destination stalls
    // the CLIENT, which then flushes everything it queued in one burst; Timer and
    // PacketFlood would read that burst as a violation. This class listens for those Bukkit
    // events for that reason.
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();
    // Teleports shorter than this stay inside the chunks the client already has loaded
    // (two chunks is the minimum view distance), so they cannot stall it.
    private static final double TELEPORT_GRACE_BLOCKS = 32.0;
    // Player-triggered teleports: beyond any ordinary pearl throw, so only stasis-chamber
    // style long jumps qualify.
    private static final double SELF_TELEPORT_GRACE_BLOCKS = 96.0;

    public PacketChecker(Plugin plugin, PluginConfig config, DatabaseManager database, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.lang = lang;
    }

    public void setLuckPerms(LuckPermsHook luckPerms) {
        this.luckPerms = luckPerms;
    }

    public void setGeyser(GeyserHook geyser) {
        this.geyser = geyser;
    }

    public void setAlertManager(MovementAlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public void setViolationManager(ViolationManager violationManager) {
        this.violationManager = violationManager;
    }

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setMeleeTracker(MeleeTracker meleeTracker) {
        this.meleeTracker = meleeTracker;
    }

    public void setLungeTracker(LungeTracker lungeTracker) {
        this.lungeTracker = lungeTracker;
    }

    // ---- Bukkit events that make a client stall and then burst ----

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (teleportNeedsGrace(event.getCause(), event.getFrom(), event.getTo())) {
            grant(id);
        } else {
            // The client answers a teleport with one movement packet outside its tick loop.
            TimerState st = timerState.get(id);
            if (st != null) st.expectTeleportMove();
        }
    }

    /**
     * Whether a teleport can stall the client (chunk loading) or desync positions enough to
     * warrant a grace window. Shared with CombatChecker.
     *
     * <p>A world change always does. Otherwise only a long teleport does: a destination
     * within the chunks the client already holds loads nothing. Causes the player triggers
     * themselves (pearls, chorus fruit, consumables, dismounting, leaving a bed) get a much
     * higher distance bar, because they can be repeated at will — granting grace for every
     * pearl would let pearl spam keep the checks off indefinitely. Setbacks and other short
     * plugin teleports get none for the same reason.
     */
    static boolean teleportNeedsGrace(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
                                      org.bukkit.Location from, org.bukkit.Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) return true;
        if (!from.getWorld().equals(to.getWorld())) return true;
        double limit = isSelfInitiated(cause) ? SELF_TELEPORT_GRACE_BLOCKS : TELEPORT_GRACE_BLOCKS;
        return from.distanceSquared(to) > limit * limit;
    }

    private static boolean isSelfInitiated(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        if (cause == null) return false;
        // Chorus fruit reports CONSUMABLE_EFFECT (CHORUS_FRUIT is only a deprecated alias).
        return switch (cause) {
            case ENDER_PEARL, CONSUMABLE_EFFECT, DISMOUNT, EXIT_BED -> true;
            default -> false;
        };
    }

    /**
     * The server processed a flight toggle while it allowed flight. Recorded whether or not
     * a plugin cancelled it: double-jump plugins cancel it and revoke flight right away,
     * after the client legitimately claimed flight.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onToggleFlight(org.bukkit.event.player.PlayerToggleFlightEvent event) {
        noteToggleFlight(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        grant(event.getPlayer().getUniqueId());
    }

    private void grant(UUID id) {
        graceUntil.put(id, System.currentTimeMillis() + Constants.PACKET_GRACE_MS);
        // A new session or respawn starts a new hotbar history.
        duplicateSlots.remove(id);
        // The accumulated balance is meaningless across a teleport — start clean.
        timerState.remove(id);
        packetCounts.remove(id);
    }

    /** True while the client is still catching up after a teleport/join/world change. */
    private boolean inGrace(UUID id) {
        Long until = graceUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Outbound side: where our transaction pings and the server's keep-alives sit in the
     * stream (ping-spoof analysis), and hotbar slots the server sets.
     */
    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.PING && type != PacketType.Play.Server.KEEP_ALIVE
                && type != PacketType.Play.Server.HELD_ITEM_CHANGE
                && type != PacketType.Play.Server.PLAYER_ABILITIES) {
            return;
        }
        User u = event.getUser();
        if (u == null || u.getUUID() == null) return;
        UUID id = u.getUUID();
        if (type == PacketType.Play.Server.HELD_ITEM_CHANGE) {
            noteServerSlot(id, new WrapperPlayServerHeldItemChange(event).getSlot());
        } else if (type == PacketType.Play.Server.PLAYER_ABILITIES) {
            noteAbilitiesSent(id, new WrapperPlayServerPlayerAbilities(event).isFlightAllowed(),
                    System.currentTimeMillis());
        } else if (transactionManager != null) {
            if (type == PacketType.Play.Server.PING) {
                transactionManager.onPingWritten(id, new WrapperPlayServerPing(event).getId());
            } else {
                transactionManager.onKeepAliveWritten(id, new WrapperPlayServerKeepAlive(event).getId(), System.nanoTime());
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();

        // Transaction pongs feed the latency system and must run regardless of which
        // checks are enabled — they are latency infrastructure, not a detection.
        if (type == PacketType.Play.Client.PONG) {
            if (transactionManager != null) {
                User u = event.getUser();
                if (u != null && u.getUUID() != null) {
                    transactionManager.onPong(u.getUUID(), new WrapperPlayClientPong(event).getId(), u.getName());
                }
            }
            return;
        }
        if (type == PacketType.Play.Client.KEEP_ALIVE) {
            if (transactionManager != null) {
                User u = event.getUser();
                if (u != null && u.getUUID() != null) {
                    transactionManager.onKeepAliveReply(u.getUUID(), new WrapperPlayClientKeepAlive(event).getId(),
                            System.nanoTime(), u.getName());
                }
            }
            return;
        }

        User user = event.getUser();
        UUID uid = user != null ? user.getUUID() : null;

        // A real melee hit is announced by the client. Recorded before the packet_checks
        // toggle and regardless of lag or grace: the combat checks read its absence as
        // "plugin damage", so a gap here — or a tracker left active while nothing feeds it —
        // would silently switch Reach/KillAura off. Up to MC 26.0 it is INTERACT_ENTITY with
        // action ATTACK; from 26.1 a dedicated ATTACK packet (PacketEvents then reports
        // INTERACT_ENTITY as INTERACT_AT, so the two never double-count).
        if (uid != null) {
            if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
                if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    onAttackPacket(uid, user.getName(), interact.getEntityId(), user.getEntityId(),
                            System.currentTimeMillis());
                }
            } else if (PacketCompat.isAttack(type)) {
                int target = PacketCompat.attackTarget(event);
                if (target >= 0) {
                    onAttackPacket(uid, user.getName(), target, user.getEntityId(), System.currentTimeMillis());
                }
            }
        }

        // Impulse and hotbar bookkeeping for the movement checks (LungeTracker) and the item
        // lookups below. Like the attack record, it must not depend on packet_checks: the
        // movement side reads its absence as "no lunge happened".
        WrapperPlayClientPlayerDigging digging = null;
        if (uid != null && type == PacketType.Play.Client.PLAYER_DIGGING) {
            digging = new WrapperPlayClientPlayerDigging(event);
            if (PacketCompat.isStab(digging.getAction())) handleStab(uid, System.currentTimeMillis());
        }
        int heldSlotVerdict = SLOT_OK;
        int heldSlot = -1;
        if (uid != null && type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            heldSlot = new WrapperPlayClientHeldItemChange(event).getSlot();
            heldSlotVerdict = noteHeldSlotPacket(uid, heldSlot, System.currentTimeMillis());
        }
        boolean tickPacket = WrapperPlayClientPlayerFlying.isFlying(type) || type == PacketType.Play.Client.CLIENT_TICK_END;
        if (uid != null && tickPacket && transactionManager != null) {
            transactionManager.onClientTick(uid);
        }

        if (!config.packetChecksEnabled()) return;

        boolean grace = uid != null && inGrace(uid);

        // Crash protection runs even under lag and even in grace — it CANCELS malicious
        // packets, so suspending it would open exactly the hole it exists to close.
        // Flood counting does back off during grace: a client flushing its queue after a
        // teleport legitimately produces one oversized window.
        if (!grace) handleFlood(event);
        if (type == PacketType.Play.Client.EDIT_BOOK || type == PacketType.Play.Client.UPDATE_SIGN) {
            handleCrasher(event, type);
        }

        // Track mining so held-to-mine swings don't count as AutoClicker clicks.
        if (digging != null) {
            handleDigging(uid, digging);
        }

        // Deterministic protocol rules: a verdict does not depend on timing, so neither lag
        // nor grace suspends them.
        if (uid != null && extendedBadPackets()) {
            if (heldSlotVerdict == SLOT_OUT_OF_RANGE) {
                int slot = heldSlot;
                flagProtocol(uid, "BADPACKETS", lang.format("alert.badpackets_slot", slot), slot);
            } else if (heldSlotVerdict == SLOT_DUPLICATE_STREAK) {
                int n = Constants.BADPACKETS_DUPLICATE_SLOT_COUNT;
                flagProtocol(uid, "BADPACKETS", lang.format("alert.badpackets_duplicate_slot", n), n);
            }
            if (type == PacketType.Play.Client.PLAYER_ABILITIES
                    && new WrapperPlayClientPlayerAbilities(event).isFlying()) {
                onFlightClaim(uid, System.currentTimeMillis());
            }
        }

        if (grace || ServerLoad.isLagging(config, uid)) return;
        if (PacketCompat.isSwing(type)) {
            handleSwing(user);
        } else if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            handleFlying(event);
            handleTimer(user, false);
        } else if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            handleTimer(user, true);
        }
    }

    // ---- Attacks, spear jabs, hotbar ----

    /**
     * One client attack on {@code target}, from either attack packet. Feeds the melee record,
     * ends the mining window (vanilla aborts digging before attacking) and applies the
     * self-attack rule. Package-private so both packet paths can be tested without a packet.
     */
    void onAttackPacket(UUID id, String name, int target, int selfEntityId, long now) {
        if (meleeTracker != null) meleeTracker.noteAttack(id, target, now);
        noteEntityAttack(id);
        // Self-attack: the vanilla client's crosshair never picks its own entity, and the
        // server rejects such a packet. Deterministic, so a single one is enough.
        if (selfEntityId > 0 && target == selfEntityId && config.packetChecksEnabled() && extendedBadPackets()) {
            flagProtocol(id, "BADPACKETS", lang.get("alert.badpackets_self_attack"), target);
        }
    }

    private boolean extendedBadPackets() {
        return config.badPacketsDetectionEnabled() && config.badPacketsExtendedEnabled();
    }

    static final int SLOT_OK = 0;
    static final int SLOT_OUT_OF_RANGE = 1;
    static final int SLOT_DUPLICATE_STREAK = 2;

    /**
     * A hotbar change from the client. Records the slot, tells the lunge tracker, and returns
     * a BadPackets verdict:
     * <ul>
     *   <li>out of range: the vanilla client selects 0-8 only;</li>
     *   <li>duplicate streak: the vanilla client sends a change only when the slot differs
     *       from the one it last SENT, so two identical ones in a row cannot happen — not even
     *       around a server-set slot, because that updates the selection but not the client's
     *       record of what it sent (the baseline is reset below for that reason). Several
     *       within a short window are required anyway, for proxies that might re-send one.</li>
     * </ul>
     * Package-private and clock-free for tests.
     */
    int noteHeldSlotPacket(UUID id, int slot, long now) {
        if (lungeTracker != null) lungeTracker.noteHeldSlotChange(id, now);
        if (slot < 0 || slot >= HOTBAR_SIZE) return SLOT_OUT_OF_RANGE;
        clientSlot.put(id, slot);
        DuplicateSlotState st = duplicateSlots.computeIfAbsent(id, k -> new DuplicateSlotState());
        synchronized (st) {
            if (slot != st.lastSlot) {
                st.lastSlot = slot;
                st.duplicates = 0;
                return SLOT_OK;
            }
            if (st.duplicates == 0 || now - st.firstDuplicate > Constants.BADPACKETS_DUPLICATE_SLOT_WINDOW_MS) {
                st.duplicates = 1;
                st.firstDuplicate = now;
            } else {
                st.duplicates++;
            }
            if (st.duplicates >= Constants.BADPACKETS_DUPLICATE_SLOT_COUNT) {
                st.duplicates = 0;
                return SLOT_DUPLICATE_STREAK;
            }
            return SLOT_OK;
        }
    }

    /** The server set the hotbar slot. The client may now send that slot without it being a repeat. */
    void noteServerSlot(UUID id, int slot) {
        if (slot >= 0 && slot < HOTBAR_SIZE) clientSlot.put(id, slot);
        DuplicateSlotState st = duplicateSlots.get(id);
        if (st != null) {
            synchronized (st) {
                st.lastSlot = -1;
                st.duplicates = 0;
            }
        }
    }

    /** Hotbar slot last seen in the packet stream, or -1. */
    int clientSlot(UUID id) {
        Integer s = clientSlot.get(id);
        return s == null ? -1 : s;
    }

    private static final class DuplicateSlotState {
        int lastSlot = -1;
        int duplicates;
        long firstDuplicate;
    }

    /**
     * A spear jab (STAB). Recorded at once; whether it carried Lunge is read on the region
     * thread from the slot the client had selected when it sent the jab, so a swap sent right
     * after it cannot hide the spear.
     */
    private void handleStab(UUID id, long now) {
        if (lungeTracker == null) return;
        lungeTracker.noteStab(id, now);
        int slot = clientSlot(id);
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        try {
            Scheduler.runForEntity(plugin, player, () -> resolveLunge(player, slot, now));
        } catch (RuntimeException e) {
            // Scheduler refused (plugin disabling, entity removed): nothing to record.
        }
    }

    /**
     * Region thread: read the jab's item and, if it is a spear with Lunge, register a lunge
     * candidate. It only becomes a lunge with the server's evidence (see LungeTracker).
     */
    void resolveLunge(Player player, int slot, long stabTime) {
        if (lungeTracker == null || !player.isOnline()) return;
        org.bukkit.inventory.ItemStack item = slot >= 0 && slot < HOTBAR_SIZE
                ? player.getInventory().getItem(slot)
                : player.getInventory().getItemInMainHand();
        if (!ItemCompat.isSpear(item)) return;
        int level = ItemCompat.lungeLevel(item);
        if (level > 0) {
            lungeTracker.noteLungeCandidate(player.getUniqueId(), stabTime, level,
                    attackCooldownMs(player), System.currentTimeMillis());
        }
    }

    /**
     * The player's attack cooldown in ms (1 / attack speed, which includes the held item's
     * modifier), or 0 when not readable. Spears need a full charge to jab.
     */
    static long attackCooldownMs(Player player) {
        try {
            org.bukkit.attribute.AttributeInstance speed = player.getAttribute(org.bukkit.attribute.Attribute.ATTACK_SPEED);
            if (speed == null) return 0L;
            double perSecond = speed.getValue();
            if (!(perSecond > 0) || !Double.isFinite(perSecond)) return 0L;
            return (long) (1000.0 / perSecond);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * Exhaustion applied to a player. The Lunge effect's exhaustion is the server's evidence
     * that a jab really lunged; recorded even when another plugin cancelled the exhaustion,
     * since the impulse is applied regardless.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onExhaustion(org.bukkit.event.entity.EntityExhaustionEvent event) {
        if (lungeTracker == null || !(event.getEntity() instanceof Player player)) return;
        if (event.getExhaustionReason() != org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.UNKNOWN) return;
        lungeTracker.noteExhaustion(player.getUniqueId(), event.getExhaustion(), System.currentTimeMillis());
    }

    static final int CLAIM_ALLOWED = 0;
    static final int CLAIM_IN_FLIGHT = 1;
    static final int CLAIM_DISALLOWED = 2;
    static final int CLAIM_UNKNOWN = 3;

    /** What the client was last told about flight, and the last server-side toggle. */
    private static final class FlightAbilityState {
        int lastSentMayfly = -1;  // -1 unknown, 0 not allowed, 1 allowed
        long lastSentAt;
        long lastToggleAt = Long.MIN_VALUE;
    }

    /** The server wrote PLAYER_ABILITIES to this client (Netty thread). */
    void noteAbilitiesSent(UUID id, boolean mayfly, long now) {
        FlightAbilityState st = flightAbilities.computeIfAbsent(id, k -> new FlightAbilityState());
        synchronized (st) {
            st.lastSentMayfly = mayfly ? 1 : 0;
            st.lastSentAt = now;
        }
    }

    /** A PlayerToggleFlightEvent fired for this player (cancelled or not). */
    void noteToggleFlight(UUID id, long now) {
        FlightAbilityState st = flightAbilities.computeIfAbsent(id, k -> new FlightAbilityState());
        synchronized (st) {
            st.lastToggleAt = now;
        }
    }

    /**
     * Judge a flight claim against the permission the CLIENT had when it sent it, not against
     * the server state when a task gets to look — a plugin may revoke flight in between (a
     * double-jump plugin cancels the toggle and calls setAllowFlight(false) at once).
     * <ul>
     *   <li>allowed: the last abilities written to the client allowed flight;</li>
     *   <li>in flight: they revoked it, but so recently (round trip plus margin) that the
     *       client may not have received them before sending the claim;</li>
     *   <li>disallowed: they revoked it long enough ago;</li>
     *   <li>unknown: no abilities seen yet for this connection.</li>
     * </ul>
     * Package-private and clock-free for tests.
     */
    int judgeFlightClaim(UUID id, long now, double rttMs) {
        FlightAbilityState st = flightAbilities.get(id);
        if (st == null) return CLAIM_UNKNOWN;
        synchronized (st) {
            if (st.lastSentMayfly < 0) return CLAIM_UNKNOWN;
            if (st.lastSentMayfly == 1) return CLAIM_ALLOWED;
            long rtt = (long) Math.min(Constants.BADPACKETS_ABILITIES_MAX_RTT_MS, Math.max(0.0, rttMs));
            if (now - st.lastSentAt <= rtt + Constants.BADPACKETS_ABILITIES_MARGIN_MS) return CLAIM_IN_FLIGHT;
            return CLAIM_DISALLOWED;
        }
    }

    /** Whether a claim with this verdict has to be checked against the server state. */
    static boolean needsConfirmation(int verdict) {
        return verdict == CLAIM_DISALLOWED || verdict == CLAIM_UNKNOWN;
    }

    /** Whether a flight toggle event fired within the correlation window of this claim. */
    boolean claimMatchesToggle(UUID id, long claimTime) {
        FlightAbilityState st = flightAbilities.get(id);
        if (st == null) return false;
        synchronized (st) {
            if (st.lastToggleAt == Long.MIN_VALUE) return false;
            return Math.abs(st.lastToggleAt - claimTime) <= Constants.BADPACKETS_ABILITIES_TOGGLE_WINDOW_MS;
        }
    }

    /**
     * A PLAYER_ABILITIES packet claiming flight (Netty thread). The vanilla client only
     * toggles flight while its local abilities allow it, and those come from the server — so
     * a claim is judged against the abilities last written to the client. A claim that
     * crossed a revocation in flight is ignored; the remaining ones are confirmed on the
     * player's thread two ticks later: flight still disallowed, not creative or
     * spectator, and no toggle event around the claim (the event only fires while the server
     * allows flight). Several within the window are still required.
     */
    void onFlightClaim(UUID id, long now) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        double rtt = transactionManager != null ? transactionManager.roundTripMs(id) : -1;
        if (rtt < 0) rtt = player.getPing();
        if (!needsConfirmation(judgeFlightClaim(id, now, rtt))) return;
        try {
            // Delayed so the server has handled the claim packet, and fired its toggle event
            // if flight was allowed, before the check looks.
            Scheduler.runForEntityLater(plugin, player, () -> confirmFlightClaim(player, now),
                    Constants.BADPACKETS_ABILITIES_CHECK_DELAY_TICKS);
        } catch (RuntimeException e) {
            // Scheduler refused: skip this sample.
        }
    }

    /** Player thread: confirm a claim against the server state and count it. Package-private for tests. */
    void confirmFlightClaim(Player player, long claimTime) {
        if (!player.isOnline() || player.getAllowFlight()) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        UUID id = player.getUniqueId();
        if (claimMatchesToggle(id, claimTime)) return;
        int n = noteFlightClaim(id, claimTime);
        if (n > 0) {
            flagOnMainProtocol(id, "BADPACKETS", lang.format("alert.badpackets_abilities", n), n);
        }
    }

    /**
     * Count one disallowed flight claim; returns the count when it reaches the threshold
     * inside the window (and resets), else 0. Package-private for tests.
     */
    int noteFlightClaim(UUID id, long now) {
        Deque<Long> dq = abilityClaims.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());
        dq.addLast(now);
        Long head;
        while ((head = dq.peekFirst()) != null && head < now - Constants.BADPACKETS_ABILITIES_WINDOW_MS) dq.pollFirst();
        int n = dq.size();
        if (n >= Constants.BADPACKETS_ABILITIES_COUNT) {
            dq.clear();
            return n;
        }
        return 0;
    }

    /** Verdict from the transaction manager's ping-spoof analysis (Netty thread). */
    void onSpoof(UUID id, String name, String kind, double value) {
        String details = "divergence".equals(kind)
                ? lang.format("alert.pingspoof_divergence", value)
                : lang.format("alert.pingspoof_" + kind, (int) value);
        Scheduler.runForPlayer(plugin, id, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            // Clients older than 1.17 have no ping packet; a proxy translating it for them
            // cannot keep its ids intact, so their replies say nothing about the player.
            int protocol = Exemptions.clientProtocol(player);
            if (protocol > 0 && protocol < Constants.PROTOCOL_1_17) return;
            flagOnMainProtocol(id, "PINGSPOOF", details, value);
        });
    }

    /**
     * Flag path for the protocol rules: Bedrock players never, whatever the exemption setting,
     * because their packets are produced by the translating proxy, not by their client.
     */
    private void flagProtocol(UUID id, String type, String details, double value) {
        Scheduler.runForPlayer(plugin, id, () -> flagOnMainProtocol(id, type, details, value));
    }

    private void flagOnMainProtocol(UUID id, String type, String details, double value) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (geyser != null && geyser.isBedrock(player)) return;
        flagOnMain(id, type, details, value);
    }

    /**
     * Track block-mining state from PLAYER_DIGGING so held-to-mine arm swings are not
     * counted as AutoClicker clicks (they fire once per tick while breaking a block).
     */
    private void handleDigging(UUID id, WrapperPlayClientPlayerDigging wrapper) {
        DiggingAction action = wrapper.getAction();
        long seq = noteDigging(id, action, System.currentTimeMillis());
        if (seq > 0L && wrapper.getBlockPosition() != null) {
            var pos = wrapper.getBlockPosition();
            scheduleDigConfirmation(id, seq, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * Look the dug block up on the player's region thread (Netty must not touch the world)
     * and confirm the START if it names a non-air block within reach.
     */
    private void scheduleDigConfirmation(UUID id, long seq, int x, int y, int z) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        try {
            Scheduler.runForEntity(plugin, player, () -> confirmDig(player, seq, x, y, z));
        } catch (RuntimeException e) {
            // Scheduler refused (plugin disabling, entity removed): the START stays provisional.
        }
    }

    private void confirmDig(Player player, long seq, int x, int y, int z) {
        if (!player.isOnline()) return;
        org.bukkit.World world = player.getWorld();
        if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) return;
        org.bukkit.Location eye = player.getEyeLocation();
        if (!withinDigReach(eye.getX(), eye.getY(), eye.getZ(), x, y, z, digReach(player))) return;
        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
        if (block.getType().isAir()) return;
        long expectedMs = expectedBreakMs(block.getBreakSpeed(player));
        noteDigConfirmed(player.getUniqueId(), seq, expectedMs, System.currentTimeMillis());
    }

    private static double digReach(Player player) {
        try {
            var range = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE);
            if (range != null) return range.getValue() + MINING_REACH_SLACK;
        } catch (Throwable ignored) {
            // attribute not available on this server build
        }
        return MINING_REACH_FALLBACK;
    }

    /** Whether the centre of block (x, y, z) lies within {@code reach} of the eye position. */
    static boolean withinDigReach(double eyeX, double eyeY, double eyeZ, int x, int y, int z, double reach) {
        double dx = x + 0.5 - eyeX;
        double dy = y + 0.5 - eyeY;
        double dz = z + 0.5 - eyeZ;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * Expected break time in ms from the per-tick break progress; 0 for instant breaks
     * ({@code speed >= 1}) and unbreakable blocks ({@code speed <= 0}), which keep only the
     * provisional window.
     */
    static long expectedBreakMs(float speed) {
        if (!(speed > 0.0f) || speed >= 1.0f) return 0L;
        return (long) Math.ceil(1.0 / speed) * TICK_MS;
    }

    /**
     * The state transitions behind {@link #handleDigging}, split out so they can be driven
     * without a packet: PLAYER_DIGGING carries both block breaking and item drops.
     */
    long noteDigging(UUID id, DiggingAction action, long now) {
        if (action == DiggingAction.START_DIGGING) {
            MiningState st = mining.computeIfAbsent(id, k -> new MiningState());
            synchronized (st) {
                st.open = true;
                long seq = ++st.seq;
                // Only a bounded number of STARTs may open a window before the server has
                // confirmed one of them — otherwise resending START at every click would
                // hide the clicks behind an endless provisional window.
                if (++st.unconfirmed <= MAX_UNCONFIRMED_STARTS) {
                    st.until = now + MINING_PROVISIONAL_MS;
                } else {
                    st.until = now;
                }
                return seq;
            }
        } else if (action == DiggingAction.FINISHED_DIGGING || action == DiggingAction.CANCELLED_DIGGING) {
            MiningState st = mining.get(id);
            if (st != null) {
                synchronized (st) {
                    st.open = false;
                    st.until = now;
                }
            }
        } else if (action == DiggingAction.DROP_ITEM || action == DiggingAction.DROP_ITEM_STACK) {
            droppingUntil.put(id, now + DROP_SUPPRESSION_MS); // dropping, not clicking
        }
        return 0L;
    }

    /**
     * Server-side confirmation of a dig: BlockDamageEvent only fires for a non-air block
     * within reach. Extends the window to that block's expected break time (0 = instant or
     * unbreakable, which keeps only the provisional window).
     */
    void noteBlockDamage(UUID id, long expectedMs, long now) {
        MiningState st = mining.computeIfAbsent(id, k -> new MiningState());
        synchronized (st) {
            confirm(st, expectedMs, now);
        }
    }

    /**
     * Confirmation from the region-thread block lookup for START number {@code seq}. Ignored
     * when a later START has superseded it, so a stale lookup cannot extend the window of a
     * different block.
     */
    void noteDigConfirmed(UUID id, long seq, long expectedMs, long now) {
        MiningState st = mining.get(id);
        if (st == null) return;
        synchronized (st) {
            if (st.seq != seq) return;
            confirm(st, expectedMs, now);
        }
    }

    /** Caller holds the lock on {@code st}. */
    private static void confirm(MiningState st, long expectedMs, long now) {
        st.unconfirmed = 0;
        // The dig already ended (START and FINISH arrived before the server processed
        // them): nothing left to cover.
        if (!st.open) return;
        long window = expectedMs <= 0L ? MINING_PROVISIONAL_MS
                : Math.min(MINING_SAFETY_MS, expectedMs * 3L / 2L + MINING_MARGIN_MS);
        st.until = Math.max(st.until, now + window);
    }

    /** An entity attack: vanilla aborts digging first, so the swings are clicks again. */
    void noteEntityAttack(UUID id) {
        MiningState st = mining.get(id);
        if (st == null) return;
        synchronized (st) {
            st.open = false;
            st.until = 0L;
        }
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockDamage(org.bukkit.event.block.BlockDamageEvent event) {
        Player player = event.getPlayer();
        long expectedMs = event.getInstaBreak() ? 0L : expectedBreakMs(event.getBlock().getBreakSpeed(player));
        noteBlockDamage(player.getUniqueId(), expectedMs, System.currentTimeMillis());
    }

    /** True while the player is (very recently) breaking a block. */
    boolean isMining(UUID id) {
        MiningState st = mining.get(id);
        if (st == null) return false;
        synchronized (st) {
            return System.currentTimeMillis() < st.until;
        }
    }

    /** Written from the Netty thread (packets) and the region thread (BlockDamageEvent). */
    private static final class MiningState {
        long until;
        long seq;
        int unconfirmed;
        boolean open;
    }

    /** True while the player is (very recently) dropping items via the drop key. */
    boolean isDropping(UUID id) {
        Long until = droppingUntil.get(id);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Packets seen from this connection in the current one-second window, or -1 if none has
     * opened yet. Reported alongside every AutoClicker verdict because the two are easy to
     * confuse: a burst of arrivals and a fast hand both raise the click count, and a
     * connection that Paper itself is about to drop for flooding is not evidence of clicking.
     * Diagnostic only; no threshold is derived from it.
     */
    private long currentPacketRate(UUID id) {
        long[] st = packetCounts.get(id);
        if (st == null) return -1;
        synchronized (st) {
            return st[1];
        }
    }

    /**
     * Packet flood: raw packets per second per connection. A vanilla client peaks well
     * below 200/s even in hectic PvP; floods (crash/lag bots) send thousands.
     */
    private void handleFlood(PacketReceiveEvent event) {
        if (!config.packetFloodDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        long now = System.currentTimeMillis();
        long[] st = packetCounts.computeIfAbsent(id, k -> new long[]{now, 0L, 0L});
        synchronized (st) {
            if (now - st[0] >= WINDOW_MS) {
                // A window that stayed under the limit ends the streak: a connection
                // catching up after a stall floods exactly one window, an attack floods
                // every window it lasts.
                if (st[1] <= config.packetFloodMaxPerSecond()) st[2] = 0;
                st[0] = now;
                st[1] = 1;
                return;
            }
            st[1]++;
            if (st[1] > config.packetFloodMaxPerSecond()) {
                // Read the count BEFORE the window is restarted below, so the alert reports
                // the observed rate rather than the limit.
                long observed = st[1];
                long over = ++st[2];
                st[0] = now;
                st[1] = 0;
                if (over >= config.packetFloodWindows()) {
                    st[2] = 0;
                    String name = user.getName();
                    flagSimple(id, name, "PACKETFLOOD", lang.format("alert.packetflood", observed), observed);
                }
            }
        }
    }

    /**
     * Crash protection: oversized book/sign payloads (classic crasher exploits). The
     * malicious packet is cancelled so the server never processes it; the flag is
     * raised afterwards on the main thread.
     */
    private void handleCrasher(PacketReceiveEvent event, PacketTypeCommon type) {
        if (!config.crasherDetectionEnabled()) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        if (type == PacketType.Play.Client.EDIT_BOOK) {
            WrapperPlayClientEditBook wrapper = new WrapperPlayClientEditBook(event);
            var pages = wrapper.getPages();
            int pageCount = pages != null ? pages.size() : 0;
            long totalChars = 0;
            boolean oversizedPage = false;
            if (pages != null) {
                for (String page : pages) {
                    if (page == null) continue;
                    totalChars += page.length();
                    if (page.length() > Constants.CRASHER_MAX_BOOK_PAGE_CHARS) oversizedPage = true;
                }
            }
            if (pageCount > Constants.CRASHER_MAX_BOOK_PAGES || oversizedPage
                    || totalChars > Constants.CRASHER_MAX_BOOK_TOTAL_CHARS) {
                event.setCancelled(true);
                int pc = pageCount;
                flagSimple(id, user.getName(), "CRASHER", lang.format("alert.crasher_book", pc), pc);
            }
        } else {
            WrapperPlayClientUpdateSign wrapper = new WrapperPlayClientUpdateSign(event);
            String[] lines = wrapper.getTextLines();
            if (lines != null) {
                for (String line : lines) {
                    if (line != null && line.length() > Constants.CRASHER_MAX_SIGN_LINE_CHARS) {
                        event.setCancelled(true);
                        flagSimple(id, user.getName(), "CRASHER", lang.get("alert.crasher_sign"), line.length());
                        return;
                    }
                }
            }
        }
    }

    /**
     * Timer: every client tick claims 50ms of game time. A balance accumulates those claims
     * minus the real time elapsed; a client running faster than real time (game-speed/timer
     * hack) pushes it past the limit.
     *
     * <p>The tick signal is CLIENT_TICK_END (one per client tick, sent by 1.21.2+ clients
     * even while idle). Clients that never send it fall back to movement packets, which a
     * modern client only sends on change or as a once-per-second position reminder.
     *
     * <p>The balance is judged continuously; there is no judgement-free window. Credit from
     * idling or a hiccup is clamped to {@link #MAX_CREDIT_MS}. A genuine stall is absorbed by
     * letting its backlog pay off the stalled time, but only within a short catch-up window
     * after it — a backlog arrives at once, a timer hack cannot bank it for later.
     *
     * <p>The balance is kept in hundredths of a millisecond so the 1% drift leak survives
     * integer arithmetic: real time is counted at 101%, which absorbs benign client clock
     * drift (cheap hardware clocks run up to ~0.5% fast). A real timer hack gains far more.
     */
    private void handleTimer(User user, boolean tickEnd) {
        if (!config.timerDetectionEnabled()) return;
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();
        long now = System.currentTimeMillis();

        double rtt = transactionManager != null ? transactionManager.roundTripMs(id) : -1;
        long rttComp = rtt > 0 ? (long) Math.min(rtt, config.timerMaxRttCompensationMs()) : 0L;
        long catchUpWindow = STALL_CATCHUP_BASE_MS + rttComp;

        TimerState st = timerState.computeIfAbsent(id, k -> new TimerState());
        boolean claimed;
        if (tickEnd) {
            st.onTickEnd(now, catchUpWindow);
            claimed = true;
        } else {
            claimed = st.onMovement(now, catchUpWindow);
        }
        if (!claimed) return;

        long required = requiredExcursionMs(config.timerSustainedMs(), rtt, config.timerMaxRttCompensationMs());
        long balance = st.balance;
        long start = st.excursionStart;
        long growth = balance - st.excursionBase;
        int verdict = st.judge(now, config.timerMaxBalanceMs() * 100L, required,
                config.timerMinGrowthMs() * 100L);
        if (verdict == TimerState.FLAG) {
            long balMs = balance / 100L;
            String details = lang.format("alert.timer", balMs);
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "TIMER", details, balMs));
        } else if (verdict == TimerState.PLATEAU && config.debugMode()) {
            // Over the limit for the whole window but no longer gaining: a catch-up, not a hack.
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[TIMER-DEBUG] %s spike %dms long, balance %dms, gain %dms "
                            + "(<%dms) rtt=%.0fms -> no alert, connection is catching up",
                    user.getName(), now - start, balance / 100L, growth / 100L,
                    config.timerMinGrowthMs(), Math.max(rtt, 0)));
        }
    }

    /**
     * Per-player timer accounting. Free of server state and clock, so the accounting can be
     * driven directly by tests. Only ever touched from the connection's Netty thread.
     */
    static final class TimerState {
        static final int NONE = 0;
        static final int FLAG = 1;
        static final int PLATEAU = 2;

        long balance;              // centi-ms: claimed game time minus real time
        long lastClaim = -1L;      // ms, arrival of the previous claimed tick
        long excursionStart;       // ms the balance went over the limit, 0 = under it
        long excursionBase;        // balance at that moment
        long stallFloor = -MAX_CREDIT_MS * 100L; // lowest balance allowed while a backlog is due
        long stallUntil;           // ms, end of the catch-up window
        boolean tickEndMode;       // the client sends CLIENT_TICK_END
        boolean tickEndSinceMove;
        // Teleports whose confirming movement packet has not arrived yet. Written from the
        // region thread (teleport event), read from the Netty thread.
        private final java.util.concurrent.atomic.AtomicInteger teleportMoves =
                new java.util.concurrent.atomic.AtomicInteger();

        /** A server teleport: the next movement packet answers it and is not a tick. */
        void expectTeleportMove() {
            // Bounded, so a burst of teleports cannot pile up free packets for later.
            teleportMoves.updateAndGet(n -> Math.min(n + 1, MAX_PENDING_TELEPORT_MOVES));
        }

        /** One CLIENT_TICK_END: exactly one client tick. */
        void onTickEnd(long now, long catchUpWindowMs) {
            tickEndMode = true;
            tickEndSinceMove = true;
            claim(now, catchUpWindowMs);
        }

        /**
         * One movement packet. Returns whether it claimed a tick. Once the client is known to
         * send tick-end packets, a movement packet only claims a tick when no tick-end came
         * since the previous one — so a client that stops sending them, or packs extra
         * movement packets into a tick, is still charged for every tick it plays.
         */
        boolean onMovement(long now, long catchUpWindowMs) {
            if (teleportMoves.getAndUpdate(n -> Math.max(0, n - 1)) > 0) return false;
            if (tickEndMode && tickEndSinceMove) {
                tickEndSinceMove = false;
                return false;
            }
            claim(now, catchUpWindowMs);
            return true;
        }

        void claim(long now, long catchUpWindowMs) {
            if (lastClaim < 0L) {
                lastClaim = now;
                return;
            }
            long elapsed = Math.max(0L, now - lastClaim);
            lastClaim = now;
            long normalFloor = -MAX_CREDIT_MS * 100L;
            if (now > stallUntil) {
                // The catch-up window is over: whatever backlog did not arrive is not banked.
                stallFloor = normalFloor;
                balance = Math.max(balance, normalFloor);
            }
            long raw = rawBalance(balance, elapsed);
            if (raw < normalFloor && elapsed > lateGapMs()) {
                // A stall, or a connection delivering late. Its backlog is about to arrive and
                // will claim the time that passed, so the balance may briefly go as low as
                // that time actually was (capped).
                stallFloor = Math.min(stallFloor, Math.max(raw, -STALL_MAX_CREDIT_MS * 100L));
                stallUntil = now + catchUpWindowMs;
            } else if (now <= stallUntil && elapsed <= BACKLOG_GAP_MS && balance < normalFloor) {
                // The backlog is still draining: the debt is real time that already passed,
                // so paying it off never puts the client ahead of the clock.
                stallUntil = now + catchUpWindowMs;
            }
            balance = Math.max(raw, Math.min(stallFloor, normalFloor));
        }

        /**
         * In tick-end mode every tick is announced, so a gap clearly over one tick is already
         * late delivery. Without tick-end, an idle client sends one movement packet per second,
         * and only a gap longer than that is a stall.
         */
        private long lateGapMs() {
            return tickEndMode ? LATE_GAP_MS : STALL_GAP_MS;
        }

        /**
         * True while a backlog is being paid off or the balance is over the limit. Packets
         * then arrive bunched, so their spacing says nothing about the player's input.
         */
        boolean catchingUp(long now) {
            return now <= stallUntil || excursionStart != 0L;
        }

        /**
         * Judge the balance after a claim. It must stay over the limit for {@code requiredMs}
         * and still be gaining at least {@code minGrowthCenti} across that span: TCP bundles
         * spike it briefly, a drained backlog plateaus, a timer hack keeps gaining.
         */
        int judge(long now, long limitCenti, long requiredMs, long minGrowthCenti) {
            if (balance <= limitCenti) {
                excursionStart = 0L;
                excursionBase = 0L;
                return NONE;
            }
            if (excursionStart == 0L) {
                excursionStart = now;
                excursionBase = balance;
                return NONE;
            }
            if (now - excursionStart < requiredMs) return NONE;
            if (balance - excursionBase >= minGrowthCenti) {
                balance = 0L;
                excursionStart = 0L;
                excursionBase = 0L;
                return FLAG;
            }
            return PLATEAU;
        }
    }

    /** One tick's claim minus the real time elapsed, in hundredths of a millisecond. */
    static long rawBalance(long balanceCenti, long elapsedMs) {
        return balanceCenti + TICK_MS * 100L - elapsedMs * 101L;
    }

    /**
     * One claimed tick's effect on the timer balance outside a stall, in hundredths of a
     * millisecond: counted at 101% of real time so benign clock drift leaks away, floored so
     * an idle player cannot bank more than {@link #MAX_CREDIT_MS} to spend later.
     */
    static long updateBalance(long balanceCenti, long elapsedMs) {
        return Math.max(rawBalance(balanceCenti, elapsedMs), -MAX_CREDIT_MS * 100L);
    }

    /**
     * How long a balance excursion must persist before it counts as a timer hack.
     *
     * <p>Extended by the player's measured round trip. A connection recovering from a stall
     * delivers its backlog over roughly one round trip, and every packet in that burst credits
     * a full tick against almost no real time — so the balance climbs for about that long
     * through no fault of the player, even when no single gap is long enough to count as a
     * stall.
     *
     * <p>Deliberately extends the WINDOW and not the limit. A cheat can inflate its measured
     * latency by answering transaction pings late, and against a raised limit that would buy
     * immunity; against a longer window it buys nothing but a later flag, because a real hack
     * holds the balance up indefinitely while a catch-up cannot. The compensation is capped
     * anyway so the delay stays bounded.
     */
    static long requiredExcursionMs(long configuredMs, double rttMs, long capMs) {
        if (!(rttMs > 0)) return configuredMs;
        return configuredMs + (long) Math.min(rttMs, capMs);
    }

    /**
     * AutoClicker, from arm-swing packets (ANIMATION; PUNCH from MC 26.3, which has no hand
     * field and is always the main hand) — covers clicking on air, blocks or entities. Two independent signals:
     * <ol>
     *   <li>raw clicks per second above {@code autoclicker_max_cps};</li>
     *   <li>interval consistency ({@code autoclicker_consistency}, opt-in): three "robotic"
     *       markers, flagged when at least {@code autoclicker_min_signals} of them hold.
     *       This also catches slow-but-metronomic clickers that never exceed the CPS cap.</li>
     * </ol>
     * Holding the button to mine/swing sends one swing per tick (~20 CPS at near-zero
     * jitter); that "held button" signature is excluded from both signals so normal
     * mining/holding doesn't false-flag.
     */
    private void handleSwing(User user) {
        if (!config.autoClickerDetectionEnabled()) return;
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();

        // Swings sent while breaking a block are mining, not clicking — don't count them.
        if (isMining(id)) return;
        // Same for the swing that accompanies each item drop: holding the drop key emits one
        // per tick and is a hand on a key, not a click rate.
        if (isDropping(id)) return;

        long now = System.currentTimeMillis();
        // A backlog delivers a second of swings in a few milliseconds and makes a held button
        // read as 26 CPS. Its intervals are discarded instead of judged.
        TimerState timer = timerState.get(id);
        if (timer != null && timer.catchingUp(now)) {
            clicks.remove(id);
            return;
        }
        ConcurrentLinkedDeque<Long> buf = clicks.computeIfAbsent(id, k -> new ConcurrentLinkedDeque<>());

        buf.addLast(now);
        long minTime = now - CONSISTENCY_WINDOW_MS;
        Long head;
        while ((head = buf.peekFirst()) != null && head < minTime) buf.pollFirst();
        while (buf.size() > SAMPLE_CAP) buf.pollFirst();

        Long[] times = buf.toArray(new Long[0]);
        int n = times.length;

        // Swings that arrived inside the last second. Kept for the debug line, but NOT used
        // to judge: it counts ARRIVALS, and the network decides those. See below.
        int arrivals = 0;
        for (Long t : times) if (t >= now - WINDOW_MS) arrivals++;

        int maxCps = config.autoClickerMaxCps();

        // Click intervals over the consistency window, shared by the held-button exclusion
        // and the pattern analysis below.
        long[] intervals = new long[Math.max(0, n - 1)];
        for (int i = 1; i < n; i++) intervals[i - 1] = times[i] - times[i - 1];
        double mean = 0, sd = -1;
        if (intervals.length >= MIN_INTERVALS_FOR_SD) {
            double sum = 0;
            for (long iv : intervals) sum += iv;
            mean = sum / intervals.length;
            double v = 0;
            for (long iv : intervals) { double d = iv - mean; v += d * d; }
            sd = Math.sqrt(v / intervals.length);
        }
        // Held left-click sends exactly one swing per tick, so the swings' typical INTERVAL is
        // one tick however much their arrival times jitter. That distinction is the whole
        // point of measuring intervals here: a count of arrivals in a sliding window is
        // pushed past any fixed ceiling by bunched packets while the interval stays at 50ms.
        // A hand clicking at 23 CPS, by contrast, has a ~43.5ms interval and is not
        // excluded. Median and MAD rather than mean and standard deviation: one pause between
        // swings shifts a mean and explodes a deviation, but leaves the median where it is.
        double medianInterval = intervals.length >= MIN_INTERVALS_FOR_SD ? median(intervals) : -1;
        boolean heldButton = isHeldButton(intervals);

        // The rate is derived from the TYPICAL INTERVAL, not from how many packets landed in
        // the last second. A sliding count over arrival times is at the mercy of the network:
        // when a connection delivers a tick's swings in bundles, the count reads whatever the
        // bundling happens to line up, while nothing about the clicking changed. It is the
        // same statistic the held-button test relies on.
        int cps = cpsFromInterval(medianInterval, arrivals);

        if (config.debugMode()) {
            plugin.getLogger().info(String.format(java.util.Locale.ROOT, 
                    "[AC-DEBUG] %s cps=%d arrivals=%d median=%s mad=%s sd=%s cv=%s outliers=%s held=%b (max %d)",
                    user.getName(), cps, arrivals,
                    medianInterval < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.1fms", medianInterval),
                    medianInterval < 0 ? "n/a"
                            : String.format(java.util.Locale.ROOT, "%.1fms", medianAbsoluteDeviation(intervals, medianInterval)),
                    sd < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f", sd),
                    sd < 0 || mean <= 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", sd / mean),
                    intervals.length == 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", outlierRatio(intervals)),
                    heldButton, maxCps) + " packets/s=" + currentPacketRate(id));
        }

        // (a) Raw click rate
        if (cps > maxCps && !heldButton) {
            buf.clear(); // reset so it must re-accumulate
            String details = lang.format("alert.autoclicker", cps, maxCps);
            int value = cps;
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "AUTOCLICKER", details, value));
            return;
        }

        // (b) Interval consistency (opt-in). Humans jitter and pause, so they usually fail
        // ALL three markers; an autoclicker fails at most one.
        if (!config.autoClickerConsistencyEnabled()) return;
        if (heldButton || sd < 0 || mean <= 0) return;
        if (n < config.autoClickerMinSamples() || cps < config.autoClickerMinCps()) return;

        int signals = 0;
        if (sd <= config.autoClickerMaxDeviationMs()) signals++;                    // absolute jitter
        if (sd / mean <= config.autoClickerMaxCv()) signals++;                      // jitter relative to rate
        if (outlierRatio(intervals) <= config.autoClickerMaxOutlierRatio()) signals++; // missing human pauses

        if (signals >= config.autoClickerMinSignals()) {
            buf.clear(); // reset so it must re-accumulate
            String details = lang.format("alert.autoclicker_pattern", sd, cps);
            double value = sd;
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "AUTOCLICKER", details, value));
        }
    }

    /**
     * Whether these swing intervals carry the cadence of a held mouse button.
     *
     * <p>A held button is bound to the server tick — the client emits exactly one swing per
     * tick — so the TYPICAL interval sits at {@link #TICK_MS} however much individual arrival
     * times jitter. Deciding on the interval distribution rather than on the CPS count is the
     * whole point: the count is a sliding window over arrival times, so bunched packets push
     * it past any fixed ceiling while the cadence never moved.
     *
     * <p>Package-private and free of any server state so the behaviour can be tested
     * directly.
     */
    static boolean isHeldButton(long[] intervals) {
        if (intervals.length < MIN_INTERVALS_FOR_SD) return false;
        double m = median(intervals);
        return Math.abs(m - TICK_MS) <= HELD_TICK_TOLERANCE_MS
                && medianAbsoluteDeviation(intervals, m) <= HELD_MAD_MAX_MS;
    }

    /**
     * Clicks per second from the typical interval between swings, falling back to the raw
     * arrival count while there are too few samples for a median to mean anything.
     *
     * <p>Package-private and free of server state, so the property that matters can be tested
     * directly: bundled arrivals must not change the answer.
     */
    static int cpsFromInterval(double medianIntervalMs, int arrivalsInWindow) {
        if (medianIntervalMs <= 0) return arrivalsInWindow;
        int fromInterval = (int) Math.round(1000.0 / medianIntervalMs);
        // Both estimates fail upwards, in different situations, so the smaller one is the
        // only defensible answer.
        //
        // The arrival count is inflated by a network that delivers a tick's swings in
        // bundles. The interval median is immune to that, but it is not a rate: it is
        // measured over the whole consistency window, so a short fast burst fills it with
        // burst intervals and the median reports the speed INSIDE the burst as though it
        // were sustained.
        //
        // Neither can go below the true rate, so min() is safe in both directions: bundling
        // is capped by the median, a burst is capped by how many clicks actually arrived, and
        // genuinely sustained fast clicking raises both and is still caught.
        return Math.min(fromInterval, arrivalsInWindow);
    }

    /** Median of a sample, or 0 for an empty one. */
    static double median(long[] values) {
        if (values.length == 0) return 0.0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
    }

    /**
     * Median absolute deviation around {@code centre} — a spread measure that a single
     * outlier cannot inflate, unlike a standard deviation. One pause in a stream of held
     * swings is exactly such an outlier.
     */
    static double medianAbsoluteDeviation(long[] values, double centre) {
        if (values.length == 0) return 0.0;
        long[] deviations = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            deviations[i] = Math.abs(Math.round(values[i] - centre));
        }
        return median(deviations);
    }

    /**
     * Share of intervals longer than 1.5x the median. A human pauses regularly (high share),
     * an autoclicker runs without a break (near zero).
     */
    static double outlierRatio(long[] intervals) {
        if (intervals.length == 0) return 1.0;
        double median = median(intervals);
        if (median <= 0) return 0.0; // no measurable gaps at all — as robotic as it gets
        double limit = median * 1.5;
        int outliers = 0;
        for (long iv : intervals) if (iv > limit) outliers++;
        return (double) outliers / intervals.length;
    }

    /** Rotation packets: BadPackets (impossible pitch) and KillAura rotation GCD. */
    private void handleFlying(PacketReceiveEvent event) {
        boolean bp = config.badPacketsDetectionEnabled();
        boolean rot = config.killAuraRotationDetectionEnabled();
        boolean snap = config.aimSnapDetectionEnabled();
        if (!bp && !rot && !snap) return;

        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        if (!wrapper.hasRotationChanged()) return;
        Location loc = wrapper.getLocation();
        if (loc == null) return;
        User user = event.getUser();
        if (user == null || user.getUUID() == null) return;
        UUID id = user.getUUID();
        float pitch = loc.getPitch();
        float yaw = loc.getYaw();

        // BadPackets: impossible rotation values
        if (bp && (!Float.isFinite(pitch) || !Float.isFinite(yaw) || pitch < -90.0f || pitch > 90.0f)) {
            String details = lang.format("alert.badpackets", pitch);
            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "BADPACKETS", details, pitch));
        }

        // KillAura rotation GCD: human mouse input is quantised (yaw deltas share a common
        // divisor); programmatic aim collapses the GCD toward 1. Experimental — off by
        // default; calibrate killaura_rotation_min_gcd via debug_mode (logs measured gcd).
        if (rot && Float.isFinite(yaw)) {
            Float last = lastYaw.put(id, yaw);
            if (last != null) {
                double dyaw = Math.abs(wrapAngle(yaw - last));
                if (dyaw > 0.05 && dyaw < 30.0) { // ignore idle noise and big snaps/spins
                    long scaled = Math.round(dyaw * ROT_EXPANDER);
                    Deque<Long> dq = yawDeltas.computeIfAbsent(id, k -> new ArrayDeque<>());
                    dq.addLast(scaled);
                    int samples = config.killAuraRotationSamples();
                    while (dq.size() > samples) dq.pollFirst();
                    if (dq.size() >= samples) {
                        long g = 0;
                        for (long v : dq) g = gcd(g, v);
                        if (config.debugMode()) {
                            plugin.getLogger().info("[ROT-DEBUG] " + user.getName() + " gcd=" + g);
                        }
                        if (g > 0 && g < config.killAuraRotationMinGcd()) {
                            dq.clear();
                            long flagged = g;
                            String details = lang.format("alert.killaura_rotation", flagged);
                            Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "KILLAURA", details, flagged));
                        }
                    }
                }
            }
        }

        // (c) AimSnap: a robotic rotation snaps to the target and back within ~1 tick
        // (look A -> far B -> back near A), which a mouse can't do. Catches rotation-
        // spoofing Scaffold/KillAura even when the per-frame angle to the target is small.
        if (snap && Float.isFinite(yaw) && Float.isFinite(pitch)) {
            Deque<double[]> dirs = recentDirs.computeIfAbsent(id, k -> new ArrayDeque<>());
            dirs.addLast(dirOf(yaw, pitch, System.currentTimeMillis()));
            while (dirs.size() > 3) dirs.pollFirst();
            if (dirs.size() == 3) {
                double[][] d = dirs.toArray(new double[0][]);
                double spikeIn = angleBetween(d[0], d[1]);
                double spikeOut = angleBetween(d[1], d[2]);
                double net = angleBetween(d[0], d[2]);
                if (config.debugMode()) {
                    plugin.getLogger().info(String.format(java.util.Locale.ROOT, "[SNAP-DEBUG] %s in=%.0f out=%.0f net=%.0f",
                            user.getName(), spikeIn, spikeOut, net));
                }
                // The three samples must be consecutive in TIME, not merely in arrival order.
                // Turning to look at something and back is an entirely ordinary thing to do
                // over a second — it is only beyond a mouse inside two ticks. Without this
                // bound, a packet gap (a stalled connection catching up) hands the check three
                // rotations seconds apart and they read as one impossible flick.
                boolean withinTwoTicks = d[2][3] - d[0][3] <= AIMSNAP_MAX_SPAN_MS;
                if (withinTwoTicks && spikeIn > config.aimSnapMinAngle()
                        && spikeOut > config.aimSnapMinAngle()
                        && net < config.aimSnapReturnAngle()) {
                    long now = System.currentTimeMillis();
                    Deque<Long> st = snapTimes.computeIfAbsent(id, k -> new ArrayDeque<>());
                    st.addLast(now);
                    while (!st.isEmpty() && st.peekFirst() < now - config.aimSnapWindowMs()) st.pollFirst();
                    if (st.size() >= config.aimSnapThreshold()) {
                        st.clear();
                        double angle = spikeIn;
                        String details = lang.format("alert.aimsnap", angle);
                        Scheduler.runForPlayer(plugin, id, () -> flagOnMain(id, "AIMSNAP", details, angle));
                    }
                }
            }
        }
    }

    /**
     * Look direction as a unit vector, with the arrival time appended as a fourth element.
     * {@link #angleBetween} reads only the first three, so the timestamp rides along without
     * affecting the geometry.
     */
    private static double[] dirOf(float yaw, float pitch, long timeMs) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new double[]{ -cp * Math.sin(y), -Math.sin(p), cp * Math.cos(y), timeMs };
    }

    private static double angleBetween(double[] a, double[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private static double wrapAngle(double a) {
        a %= 360.0;
        if (a >= 180.0) a -= 360.0;
        if (a < -180.0) a += 360.0;
        return a;
    }

    private static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    /**
     * Generic flag path for the simple packet checks (crasher/flood). Callable from the
     * Netty thread: the DB log goes through the thread-safe queue immediately with the
     * connection's name, so the record survives even when a crash/flood attempt drops the
     * connection before the next tick. Alert + VL need Bukkit API, so they hop to the main
     * thread and are best-effort (skipped if the player already disconnected).
     */
    private void flagSimple(UUID id, String name, String type, String details, double value) {
        if (database != null) {
            database.logAsync(id, "anticheat_" + type.toLowerCase(), value, name + ": " + details);
        }
        Runnable main = () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || Exemptions.isExempt(player, config, luckPerms, geyser)) return;
            if (alertManager != null) {
                alertManager.addAlert(player, type, details, value, player.getLocation());
            } else if (config.debugMode()) {
                plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
            }
            if (violationManager != null) {
                violationManager.flag(player, type);
            }
        };
        Scheduler.runForPlayer(plugin, id, main);
    }

    /**
     * Flag path for the checks that need the Player object (alert location, exemption
     * state). Runs on the player's region thread; best-effort — skipped if the player
     * already disconnected.
     */
    private void flagOnMain(UUID id, String type, String details, double value) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        if (Exemptions.isExempt(player, config, luckPerms, geyser)) {
            if (config.debugMode()) {
                plugin.getLogger().info("[AC-DEBUG] " + type + " flag skipped: " + player.getName() + " is exempt");
            }
            return;
        }
        if (config.debugMode()) {
            plugin.getLogger().info("[AC-DEBUG] FLAG " + player.getName() + " " + type + " - " + details);
        }
        if (database != null) {
            database.logAsync(id, "anticheat_" + type.toLowerCase(), value,
                    player.getName() + ": " + details + " @ "
                            + dev.boondock.bsanticheat.util.CheckMath.formatLocation(player.getLocation()));
        }
        if (alertManager != null) {
            alertManager.addAlert(player, type, details, value, player.getLocation());
        } else if (config.debugMode()) {
            plugin.getLogger().warning("[AntiCheat] " + player.getName() + " " + type + " - " + details);
        }
        if (violationManager != null) {
            violationManager.flag(player, type);
        }
    }


    public void cleanup(UUID playerId) {
        if (meleeTracker != null) meleeTracker.cleanup(playerId);
        clicks.remove(playerId);
        graceUntil.remove(playerId);
        timerState.remove(playerId);
        packetCounts.remove(playerId);
        mining.remove(playerId);
        droppingUntil.remove(playerId);
        lastYaw.remove(playerId);
        yawDeltas.remove(playerId);
        recentDirs.remove(playerId);
        snapTimes.remove(playerId);
        clientSlot.remove(playerId);
        duplicateSlots.remove(playerId);
        abilityClaims.remove(playerId);
        flightAbilities.remove(playerId);
    }
}

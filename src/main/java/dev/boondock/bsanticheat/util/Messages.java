package dev.boondock.bsanticheat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One place where a configured message becomes coloured text.
 *
 * <p>Every colour format an admin might reasonably type is accepted, mixed freely in one
 * string: the classic codes ({@code &a}, {@code &l}), hex in the Spigot spellings
 * ({@code &#9863E7} and {@code &x&9&8&6&3&E&7}), and MiniMessage ({@code <#9863E7>},
 * {@code <red>}, {@code <bold>}, {@code <gradient:...>}). Previously only {@code &0}-{@code &f}
 * worked and everything else reached the player verbatim, which is what the report was about.
 *
 * <p>How: the legacy spellings are rewritten into their MiniMessage equivalents and the whole
 * string is then parsed by MiniMessage. That is what allows the formats to be mixed, and it
 * means one parser decides everything rather than two that disagree.
 *
 * <p><b>Legacy semantics are preserved.</b> In legacy text a COLOUR also clears bold/italic,
 * while in MiniMessage it does not — so a colour code becomes {@code <reset><colour>} and a
 * format code ({@code &l} and friends) becomes a plain tag. Without that, an existing
 * {@code "&cwrong &lbold &cwrong again"} would keep the bold running to the end of the line.
 *
 * <p>Unknown tags are left alone by MiniMessage, which is what keeps the usage strings in the
 * language files ({@code /bsac <reload|info|version>}) readable instead of eaten.
 */
public final class Messages {

    private Messages() {}

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** {@code &#RRGGBB} — the spelling most plugins use, and the one in the report. */
    private static final Pattern HEX_SHORT = Pattern.compile("[&§]#([0-9a-fA-F]{6})");
    /** {@code &x&R&R&G&G&B&B} — the BungeeCord/Spigot expansion of the same thing. */
    private static final Pattern HEX_LONG =
            Pattern.compile("[&§]x((?:[&§][0-9a-fA-F]){6})");
    /** A classic colour or format code. */
    private static final Pattern CODE = Pattern.compile("[&§]([0-9a-fk-orA-FK-OR])");

    private static final Map<Character, String> COLOURS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"));

    private static final Map<Character, String> FORMATS = Map.of(
            'k', "obfuscated", 'l', "bold", 'm', "strikethrough",
            'n', "underlined", 'o', "italic");

    /** Set once at enable, so the optional dependency is never touched when it is absent. */
    private static volatile boolean placeholderApi = false;

    public static void setPlaceholderApiPresent(boolean present) {
        placeholderApi = present;
    }

    /**
     * Rewrite every legacy colour spelling into MiniMessage. Visible for testing — this is
     * the part with the rules in it, and it is a pure function of its input.
     */
    static String toMiniMessage(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        StringBuilder out = new StringBuilder(raw.length() + 16);
        Matcher hexLong = HEX_LONG.matcher(raw);
        while (hexLong.find()) {
            // Strip the & / § separators out of &x&9&8&6&3&E&7 to get the six digits.
            String digits = hexLong.group(1).replaceAll("[&§]", "");
            hexLong.appendReplacement(out, Matcher.quoteReplacement(
                    "<reset><#" + digits.toLowerCase(Locale.ROOT) + ">"));
        }
        hexLong.appendTail(out);

        String step = out.toString();
        out = new StringBuilder(step.length() + 16);
        Matcher hexShort = HEX_SHORT.matcher(step);
        while (hexShort.find()) {
            hexShort.appendReplacement(out, Matcher.quoteReplacement(
                    "<reset><#" + hexShort.group(1).toLowerCase(Locale.ROOT) + ">"));
        }
        hexShort.appendTail(out);

        step = out.toString();
        out = new StringBuilder(step.length() + 16);
        Matcher code = CODE.matcher(step);
        while (code.find()) {
            char c = Character.toLowerCase(code.group(1).charAt(0));
            String replacement;
            if (c == 'r') {
                replacement = "<reset>";
            } else if (COLOURS.containsKey(c)) {
                // A colour clears formatting in legacy text; in MiniMessage it does not.
                replacement = "<reset><" + COLOURS.get(c) + ">";
            } else {
                replacement = "<" + FORMATS.get(c) + ">";
            }
            code.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        code.appendTail(out);
        return out.toString();
    }

    /** The message as a component, with every supported colour format applied. */
    public static Component component(String raw) {
        return MINI.deserialize(toMiniMessage(raw));
    }

    /**
     * The message as a legacy section-coded string, for the many call sites that still hand a
     * String to {@code sendMessage}. Hex survives this: the section serializer writes it in
     * the {@code §x§R§R…} form the client understands.
     */
    public static String legacy(String raw) {
        return LEGACY.serialize(component(raw));
    }

    /**
     * Resolve PlaceholderAPI placeholders, if PlaceholderAPI is installed. Returns the text
     * unchanged when it is not, so the same message works on servers without it.
     *
     * <p>The class reference lives in a nested holder so that the optional dependency is only
     * loaded once it is known to be there — touching it otherwise is a NoClassDefFoundError.
     */
    public static String placeholders(OfflinePlayer player, String text) {
        if (!placeholderApi || text == null || text.isEmpty() || text.indexOf('%') < 0) {
            return text;
        }
        try {
            return Papi.apply(player, text);
        } catch (Throwable t) {
            // Never let a placeholder expansion take down an alert or a punishment.
            Bukkit.getLogger().warning("[BSAntiCheat] PlaceholderAPI failed on a message: " + t);
            return text;
        }
    }

    /** Loaded only when PlaceholderAPI is actually present. */
    private static final class Papi {
        private Papi() {}

        static String apply(OfflinePlayer player, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        }
    }
}

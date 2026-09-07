package dev.boondock.bsanticheat.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every colour format an admin might type, and the two things that must NOT change while
 * supporting them: the plain text of a message, and legacy's colour-clears-formatting rule.
 */
class MessagesTest {

    private static String plain(String raw) {
        return PlainTextComponentSerializer.plainText().serialize(Messages.component(raw));
    }

    /** Colour of the first styled child, which is where a leading code lands. */
    private static TextColor firstColour(String raw) {
        Component c = Messages.component(raw);
        if (c.color() != null) return c.color();
        for (Component child : c.children()) {
            if (child.color() != null) return child.color();
        }
        return null;
    }

    @Test
    @DisplayName("Classic codes still work")
    void legacyCodes() {
        assertEquals(NamedTextColor.GREEN, firstColour("&aHallo"));
        assertEquals("Hallo", plain("&aHallo"));
    }

    @Test
    @DisplayName("Hex in the &#RRGGBB spelling — the one from the report")
    void ampHex() {
        assertEquals(TextColor.fromHexString("#9863e7"), firstColour("&#9863E7Hallo"));
        assertEquals("Hallo", plain("&#9863E7Hallo"), "the code must not survive as text");
    }

    @Test
    @DisplayName("Hex in the &x&R&R&G&G&B&B spelling")
    void spigotHex() {
        assertEquals(TextColor.fromHexString("#9863e7"), firstColour("&x&9&8&6&3&E&7Hallo"));
        assertEquals("Hallo", plain("&x&9&8&6&3&E&7Hallo"));
    }

    @Test
    @DisplayName("MiniMessage hex")
    void miniMessageHex() {
        assertEquals(TextColor.fromHexString("#9863e7"), firstColour("<#9863E7>Hallo"));
        assertEquals("Hallo", plain("<#9863E7>Hallo"));
    }

    @Test
    @DisplayName("MiniMessage named tags")
    void miniMessageNamed() {
        assertEquals(NamedTextColor.RED, firstColour("<red>Hallo"));
        assertEquals("Hallo", plain("<red><bold>Hallo"));
    }

    @Test
    @DisplayName("The formats can be mixed in one string")
    void mixed() {
        assertEquals("ab c", plain("&aa<#9863E7>b &#9863E7c"));
    }

    @Test
    @DisplayName("A colour clears formatting, the way legacy does")
    void colourResetsFormatting() {
        // "&cred &lbold &cred again" — in legacy the second &c ends the bold. MiniMessage
        // alone would carry it to the end of the line, which would silently restyle every
        // existing message in the language files.
        Component c = Messages.component("&ca&lb&cc");
        String serialized = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().serialize(c);
        int bold = serialized.indexOf("§l");
        int lastRed = serialized.lastIndexOf("§c");
        assertTrue(bold >= 0, "the bold code has to be there at all");
        assertTrue(lastRed > bold, "the colour after the bold must come later and clear it");
        assertTrue(serialized.endsWith("c"), "and the last section is plain red text");
    }

    @Test
    @DisplayName("Usage strings with angle brackets survive")
    void unknownTagsAreNotEaten() {
        // The language files are full of these: "/bsac <reload|info|version> [player]".
        // MiniMessage leaves tags it does not know alone, and this holds it to that.
        assertEquals("Usage: /bsac <reload|info|version> [player]",
                plain("&eUsage: &f/bsac <reload|info|version> [player]"));
        assertEquals("/acwhitelist add <player|group:name>",
                plain("&f/acwhitelist add <player|group:name>"));
    }

    @Test
    @DisplayName("Percent placeholders are left untouched")
    void percentPlaceholdersSurvive() {
        assertEquals("Player not found: %player%", plain("&cPlayer not found: &f%player%"));
    }

    @Test
    @DisplayName("Hex survives the trip back to a legacy string")
    void hexSurvivesLegacySerialisation() {
        // Most call sites still send a String, so the colour has to come back out of
        // Messages.legacy in a form the client understands (§x§9§8§6§3§e§7).
        String legacy = Messages.legacy("&#9863E7Hallo");
        assertTrue(legacy.contains("§x"), "hex has to be written in the §x form: " + legacy);
        assertTrue(legacy.endsWith("Hallo"));
    }

    @Test
    @DisplayName("Null and empty are not a crash")
    void emptyInput() {
        assertEquals("", plain(""));
        assertEquals("", PlainTextComponentSerializer.plainText().serialize(Messages.component(null)));
    }

    @Test
    @DisplayName("Without PlaceholderAPI the text is returned unchanged")
    void placeholdersWithoutPapi() {
        Messages.setPlaceholderApiPresent(false);
        assertEquals("%player_world%", Messages.placeholders(null, "%player_world%"));
    }
}

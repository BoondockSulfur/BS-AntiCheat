package dev.boondock.bsanticheat.alerts;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled language files: same keys in every language, and every name the code asks for. */
class LanguageFilesTest {

    private static YamlConfiguration load(String lang) {
        InputStream in = LanguageFilesTest.class.getClassLoader().getResourceAsStream("lang/" + lang + ".yml");
        assertNotNull(in, lang + ".yml missing");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static Set<String> stringKeys(YamlConfiguration yaml) {
        Set<String> keys = new TreeSet<>();
        for (String key : yaml.getKeys(true)) if (yaml.isString(key)) keys.add(key);
        return keys;
    }

    @Test
    @DisplayName("German and English define the same messages")
    void sameKeys() {
        assertEquals(stringKeys(load("en")), stringKeys(load("de")));
    }

    @Test
    @DisplayName("Every alert category has a display name in every language")
    void categoryNames() {
        for (String lang : new String[] {"en", "de"}) {
            YamlConfiguration yaml = load(lang);
            for (AlertPreferenceManager.AlertCategory c : AlertPreferenceManager.AlertCategory.values()) {
                assertTrue(yaml.isString(c.langKey()), lang + ": " + c.langKey());
            }
        }
    }
}

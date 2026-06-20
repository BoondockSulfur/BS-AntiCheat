package dev.boondock.bsanticheat.lang;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages plugin language/translations.
 * Supports multiple languages via YAML files in the lang/ folder.
 */
public class LanguageManager {

    private final JavaPlugin plugin;
    private final Map<String, String> messages = new HashMap<>();
    private String currentLanguage;

    // Supported languages
    public static final String[] SUPPORTED_LANGUAGES = {"en", "de"};

    public LanguageManager(JavaPlugin plugin, String language) {
        this.plugin = plugin;
        this.currentLanguage = language;
        loadLanguage(language);
    }

    /**
     * Load a language file.
     */
    public void loadLanguage(String language) {
        this.currentLanguage = language;
        messages.clear();

        // Create lang folder if it doesn't exist
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // Save default language files if they don't exist
        saveDefaultLanguageFiles();

        // Load the selected language file
        File langFile = new File(langFolder, language + ".yml");
        FileConfiguration langConfig;

        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
        } else {
            // Fallback to English if selected language doesn't exist
            plugin.getLogger().warning("[Lang] Language file not found: " + language + ".yml, falling back to English");
            langFile = new File(langFolder, "en.yml");
            if (langFile.exists()) {
                langConfig = YamlConfiguration.loadConfiguration(langFile);
            } else {
                // Load from resources as last resort
                InputStream is = plugin.getResource("lang/en.yml");
                if (is != null) {
                    langConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                } else {
                    plugin.getLogger().severe("[Lang] No language files found!");
                    return;
                }
            }
        }

        // Check for missing keys and merge from defaults
        mergeDefaultKeys(langFile, language);

        // Reload after merge
        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Load all messages into the map
        for (String key : langConfig.getKeys(true)) {
            if (langConfig.isString(key)) {
                messages.put(key, langConfig.getString(key));
            }
        }

        plugin.getLogger().info("[Lang] Loaded language: " + language + " (" + messages.size() + " messages)");
    }

    /**
     * Merge missing keys from default resource into existing file.
     */
    private void mergeDefaultKeys(File langFile, String language) {
        InputStream is = plugin.getResource("lang/" + language + ".yml");
        if (is == null) {
            is = plugin.getResource("lang/en.yml");
        }
        if (is == null) return;

        try {
            FileConfiguration existing = YamlConfiguration.loadConfiguration(langFile);
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));

            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (!existing.contains(key) && defaults.isString(key)) {
                    existing.set(key, defaults.getString(key));
                    changed = true;
                }
            }

            if (changed) {
                existing.save(langFile);
                plugin.getLogger().info("[Lang] Merged new keys into " + language + ".yml");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Lang] Could not merge keys: " + e.getMessage());
        }
    }

    /**
     * Save default language files from resources.
     */
    private void saveDefaultLanguageFiles() {
        for (String lang : SUPPORTED_LANGUAGES) {
            File langFile = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
            if (!langFile.exists()) {
                plugin.saveResource("lang/" + lang + ".yml", false);
            }
        }
    }

    /**
     * Get a translated message.
     * @param key The message key
     * @return The translated message with color codes applied
     */
    public String get(String key) {
        String message = messages.getOrDefault(key, key);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Get a translated message with placeholders.
     * @param key The message key
     * @param replacements Pairs of placeholder and replacement (e.g., "%player%", "Steve")
     * @return The translated message with placeholders replaced
     */
    public String get(String key, Object... replacements) {
        String message = get(key);
        if (replacements.length >= 2) {
            for (int i = 0; i < replacements.length - 1; i += 2) {
                String placeholder = String.valueOf(replacements[i]);
                String replacement = String.valueOf(replacements[i + 1]);
                message = message.replace(placeholder, replacement);
            }
        }
        return message;
    }

    /**
     * Get a translated message formatted with String.format().
     * @param key The message key
     * @param args Format arguments
     * @return The formatted translated message
     */
    public String format(String key, Object... args) {
        String message = get(key);
        try {
            return String.format(message, args);
        } catch (Exception e) {
            return message;
        }
    }

    /**
     * Get current language code.
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * Reload the current language.
     */
    public void reload() {
        loadLanguage(currentLanguage);
    }

    /**
     * Change language at runtime.
     */
    public void setLanguage(String language) {
        loadLanguage(language);
    }
}

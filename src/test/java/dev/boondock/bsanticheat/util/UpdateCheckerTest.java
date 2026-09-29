package dev.boondock.bsanticheat.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Version comparison behind the update notice. */
class UpdateCheckerTest {

    @Test
    @DisplayName("Plain numeric versions compare numerically, not as text")
    void numeric() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.7", "1.0.6"));
        assertTrue(UpdateChecker.isNewerVersion("1.0.10", "1.0.9"));
        assertTrue(UpdateChecker.isNewerVersion("1.1", "1.0.9"));
        assertFalse(UpdateChecker.isNewerVersion("1.0.6", "1.0.6"));
        assertFalse(UpdateChecker.isNewerVersion("1.0.5", "1.0.6"));
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.0"));
    }

    @Test
    @DisplayName("A v prefix on either side is ignored")
    void vPrefix() {
        assertTrue(UpdateChecker.isNewerVersion("v1.0.7", "1.0.6"));
        assertFalse(UpdateChecker.isNewerVersion("v1.0.6", "1.0.6"), "same version, only spelled as a tag");
        assertFalse(UpdateChecker.isNewerVersion("V1.0.5", "1.0.6"));
        assertTrue(UpdateChecker.isNewerVersion("1.0.7", "v1.0.6"));
    }

    @Test
    @DisplayName("Pre-release and build suffixes")
    void suffixes() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.7", "1.0.7-SNAPSHOT"), "the release follows its snapshot");
        assertFalse(UpdateChecker.isNewerVersion("1.0.7-SNAPSHOT", "1.0.7"));
        assertFalse(UpdateChecker.isNewerVersion("1.0.7-beta.2", "1.0.7-beta.1"), "no guessing between pre-releases");
        assertTrue(UpdateChecker.isNewerVersion("1.0.8-beta", "1.0.7"));
        assertFalse(UpdateChecker.isNewerVersion("1.0.6+build.5", "1.0.6"));
        assertTrue(UpdateChecker.isNewerVersion("v1.0.7+42", "1.0.6-SNAPSHOT"));
    }

    @Test
    @DisplayName("Something that is not a version never announces an update")
    void garbage() {
        assertFalse(UpdateChecker.isNewerVersion("latest", "1.0.6"));
        assertFalse(UpdateChecker.isNewerVersion("", "1.0.6"));
        assertFalse(UpdateChecker.isNewerVersion(null, "1.0.6"));
        assertFalse(UpdateChecker.isNewerVersion("1.0.7", "dev"));
    }
}

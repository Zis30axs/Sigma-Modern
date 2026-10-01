package com.mentalfrostbyte.jello.gui.modern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The parts of the music windows' shared browse model that need no game: the category list and the search text. */
class MusicBrowseTest {
    @AfterEach
    void reset() {
        MusicBrowse.reset();
    }

    @Test
    void theCategoriesHaveUniqueKeysAndTheFallbackIsTheHotChart() {
        Set<String> keys = new HashSet<>();
        for (MusicBrowse.Category c : MusicBrowse.CATEGORIES) {
            assertTrue(keys.add(c.key()), "duplicate key " + c.key());
        }

        assertEquals("search", MusicBrowse.CATEGORIES.get(0).key());
        // current() falls back to index 1 once a signed-in category is open without an account.
        assertEquals("hot", MusicBrowse.CATEGORIES.get(1).key());
        assertEquals(MusicBrowse.Kind.PLAYLIST, MusicBrowse.CATEGORIES.get(1).kind());
    }

    @Test
    void signedOutShowsOnlyTheCategoriesThatNeedNoAccount() {
        List<MusicBrowse.Category> out = MusicBrowse.categories(false);
        assertFalse(out.isEmpty());
        assertTrue(out.stream().noneMatch(MusicBrowse.Category::needsLogin));
        assertEquals(MusicBrowse.CATEGORIES.size(), MusicBrowse.categories(true).size());
        assertTrue(MusicBrowse.categories(true).stream().anyMatch(MusicBrowse.Category::needsLogin));
    }

    @Test
    void selectingTheOpenCategoryAgainChangesNothing() {
        assertFalse(MusicBrowse.select("hot"));
        assertTrue(MusicBrowse.select("new"));
        assertFalse(MusicBrowse.select("new"));
        // "playlists" again only goes back when a playlist is open; none is.
        assertTrue(MusicBrowse.select("mine"));
        assertFalse(MusicBrowse.select("mine"));
    }

    @Test
    void theQueryIsCutToItsLimitWithoutSplittingACharacter() {
        MusicBrowse.setQuery("a".repeat(MusicBrowse.MAX_QUERY + 25));
        assertEquals(MusicBrowse.MAX_QUERY, MusicBrowse.query().length());

        // Each of these is one character made of two UTF-16 units.
        MusicBrowse.setQuery("🎵".repeat(MusicBrowse.MAX_QUERY + 5));
        assertEquals(MusicBrowse.MAX_QUERY, MusicBrowse.query().codePointCount(0, MusicBrowse.query().length()));
        assertEquals(MusicBrowse.MAX_QUERY * 2, MusicBrowse.query().length());
    }

    @Test
    void aBlankSearchSearchesForNothingAndClearingEmptiesTheBox() {
        MusicBrowse.setQuery("   ");
        MusicBrowse.submit();
        assertNull(MusicBrowse.searched());

        MusicBrowse.setQuery("anything");
        MusicBrowse.clearSearch();
        assertEquals("", MusicBrowse.query());
        assertNull(MusicBrowse.searched());
    }
}

package dev.ultraxr

import dev.ultraxr.core.GameLibrary
import dev.ultraxr.core.WebGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameLibraryTest {
    private val shipped: List<WebGame> by lazy {
        val f = listOf("src/main/assets/webxr_pages.json", "app/src/main/assets/webxr_pages.json").map { File(it) }.first { it.exists() }
        GameLibrary.parse(f.readText())
    }

    @Test fun theShippedListParsesAndClaimsNothingItHasNot() {
        assertTrue(shipped.size >= 4)
        for (g in shipped) { assertTrue(g.url.startsWith("https://")); assertTrue(g.name.isNotBlank()); assertTrue(g.needs.isNotBlank()) }
        // nothing has been played on the glasses yet, so nothing may say so
        assertTrue(shipped.none { it.state == WebGame.State.VERIFIED })
        assertTrue(shipped.none { it.state == WebGame.State.UNKNOWN })
        assertTrue(shipped.any { it.state == WebGame.State.BLOCKED })
    }

    @Test fun labelsSayWhatWasDone() {
        assertTrue(GameLibrary.label(WebGame.State.TESTED).contains("not yet tried on the glasses"))
        assertFalse(GameLibrary.label(WebGame.State.TESTED).contains("Verified"))
        assertEquals("Verified on the glasses", GameLibrary.label(WebGame.State.VERIFIED))
    }

    @Test fun recentlyPlayedKeepsTheNewestFirstWithoutRepeats() {
        var r = emptyList<String>()
        for (u in listOf("a", "b", "a", "c", "d")) r = GameLibrary.played(r, u)
        assertEquals(listOf("d", "c", "a"), r)
    }

    @Test fun favouritesToggle() {
        var f = emptySet<String>()
        f = GameLibrary.toggleFavourite(f, "a"); f = GameLibrary.toggleFavourite(f, "b"); f = GameLibrary.toggleFavourite(f, "a")
        assertEquals(setOf("b"), f)
    }

    @Test fun gamesThatCannotStartAreListedLast() {
        val o = GameLibrary.ordered(shipped)
        val firstBlocked = o.indexOfFirst { it.state == WebGame.State.BLOCKED }
        assertTrue(o.drop(firstBlocked).all { it.state == WebGame.State.BLOCKED })
    }
}

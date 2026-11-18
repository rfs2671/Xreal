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
        // a page is on the web (https) or served by the app itself on this phone
        for (g in shipped) { assertTrue(g.url, g.url.startsWith("https://") || g.url.startsWith("http://localhost:8765/")); assertTrue(g.name.isNotBlank()); assertTrue(g.needs.isNotBlank()) }
        // "verified" is only for a game its owner has played on the glasses: one so far, and its note must say when
        val verified = shipped.filter { it.state == WebGame.State.VERIFIED }
        assertEquals(listOf("Ball shooter (three.js)"), verified.map { it.name })
        assertTrue(verified.all { it.detail.contains("Played on the Pixel") && it.detail.contains("2026") })
        assertTrue(shipped.none { it.state == WebGame.State.UNKNOWN })
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
        fun g(n: String, st: WebGame.State) = WebGame(n, "https://example.org/" + n, "hands", st, "")
        val o = GameLibrary.ordered(listOf(g("a", WebGame.State.BLOCKED), g("b", WebGame.State.TESTED), g("c", WebGame.State.BLOCKED), g("d", WebGame.State.TESTED)))
        assertEquals(listOf("b", "d", "a", "c"), o.map { it.name })
        assertEquals(shipped, GameLibrary.ordered(shipped).sortedBy { shipped.indexOf(it) })
    }
}

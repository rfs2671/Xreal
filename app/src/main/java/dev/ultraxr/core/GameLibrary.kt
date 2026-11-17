package dev.ultraxr.core

import org.json.JSONArray

/** One WebXR experience the launcher offers, with what is actually known about it. */
data class WebGame(val name: String, val url: String, val needs: String, val state: State, val detail: String) {
    enum class State {
        /** Played on the Pixel with the glasses by a person. Nothing has this yet. */
        VERIFIED,
        /** Ran in automated testing on an emulator with simulated tracking; never on the glasses. */
        TESTED,
        /** Known not to start, with the reason in [detail]. */
        BLOCKED,
        UNKNOWN,
    }
}

/** The list of experiences, favourites and recently played: plain data, so it can be tested. */
object GameLibrary {
    const val MAX_RECENT = 3

    fun parse(json: String): List<WebGame> {
        val a = JSONArray(json); val out = ArrayList<WebGame>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val state = when (o.optString("state")) { "verified" -> WebGame.State.VERIFIED; "tested" -> WebGame.State.TESTED; "blocked" -> WebGame.State.BLOCKED; else -> WebGame.State.UNKNOWN }
            if (o.optString("url").isNotEmpty()) out += WebGame(o.optString("name"), o.optString("url"), o.optString("needs"), state, o.optString("status"))
        }
        return out
    }

    /** What the card says about a game's state, in the user's words. Never claims more than was done. */
    fun label(s: WebGame.State): String = when (s) {
        WebGame.State.VERIFIED -> "Verified on the glasses"
        WebGame.State.TESTED -> "Passed automated tests, not yet tried on the glasses"
        WebGame.State.BLOCKED -> "Does not start yet"
        WebGame.State.UNKNOWN -> "Not tested"
    }

    /** [url] moved to the front of the recently played list, which keeps [MAX_RECENT] entries. */
    fun played(recent: List<String>, url: String): List<String> = (listOf(url) + recent.filter { it != url }).take(MAX_RECENT)

    fun toggleFavourite(favourites: Set<String>, url: String): Set<String> = if (url in favourites) favourites - url else favourites + url

    /** Games in the order to show them: playable ones first, in their listed order; blocked ones last. */
    fun ordered(all: List<WebGame>): List<WebGame> = all.sortedBy { if (it.state == WebGame.State.BLOCKED) 1 else 0 }
}

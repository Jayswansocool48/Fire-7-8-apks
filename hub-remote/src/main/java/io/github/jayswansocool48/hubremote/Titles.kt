package io.github.jayswansocool48.hubremote

import android.app.AlertDialog
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONObject

/** One show or movie: details, which of your channels have it, and buttons that open the
 *  title itself on the TV (not just the app). The Hub server does the lookups. */
object Titles {
    fun query(it: JSONObject): String {
        val q = StringBuilder("?title=").append(enc(it.optString("title"))).append("&kind=").append(it.optString("kind", "tv").ifEmpty { "tv" })
        for (k in listOf("year", "tvmaze", "anilist")) if (it.has(k) && !it.isNull(k) && it.optString(k).isNotEmpty()) q.append("&$k=").append(enc(it.optString(k)))
        if (it.optBoolean("anime")) q.append("&anime=1")
        return q.toString()
    }

    fun play(act: MainActivity, item: JSONObject, provider: String? = null, episode: JSONObject? = null) {
        act.toast("Opening ${item.optString("title")} on the TV")
        val body = json("item" to item, "provider" to provider, "episode" to episode)
        Hub.post("/api/title/play", body) { j, e ->
            when {
                e != null -> act.toast(e)
                j?.optString("how") == "search" -> act.toast("Searching your channels for ${item.optString("title")}")
            }
            Hub.refresh()
        }
    }

    fun show(act: MainActivity, item: JSONObject) {
        val col = act.vbox()
        val p = act.dpi(20f)
        col.setPadding(p, p, p, p)
        col.addView(act.label(item.optString("title"), 24f, Theme.SNOW, serif = true))
        val wait = act.skeleton(act.dpi(220f), act.dpi(14f), 6f)
        col.addView(wait, lp(act.dpi(220f), act.dpi(14f)).apply { topMargin = act.dpi(12f) })
        val sv = ScrollView(act).apply { addView(col) }
        val dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert).setView(sv)
            .setPositiveButton("Close", null).show()
        Hub.get("/api/title" + query(item)) { j, e ->
            col.removeAllViews()
            if (e != null) { col.addView(act.label(e, 15f, Theme.MIST)); return@get }
            fill(act, col, item, j!!.getJSONObject("title")) { dlg.dismiss() }
        }
    }

    private fun fill(act: MainActivity, col: LinearLayout, item: JSONObject, d: JSONObject, close: () -> Unit) {
        val head = act.hbox().apply { gravity = Gravity.TOP }
        val poster = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(Theme.RAISE, act.dp(10f)); clipToOutline = true }
        if (d.optString("poster").isNotEmpty()) Images.load(poster, "/api/thumb?u=" + enc(d.optString("poster")), 300)
        head.addView(poster, lp(act.dpi(96f), act.dpi(144f)))
        val info = act.vbox()
        info.addView(act.label(d.optString("title"), 22f, Theme.SNOW, serif = true))
        val seasons = d.optJSONArray("seasons")?.length() ?: 0
        val kind = if (d.optString("kind") == "movie") "Movie" else if (d.optBoolean("anime")) "Anime" else "Show"
        val meta = listOf(d.optString("year"), kind, if (seasons > 0) "$seasons season" + (if (seasons > 1) "s" else "") else "",
            if (!d.isNull("runtime") && d.has("runtime")) d.optString("runtime") + " min" else "",
            if (!d.isNull("rating") && d.has("rating")) "★ " + d.optString("rating") else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
        info.addView(act.label(meta, 13f, Theme.MIST), lp(MATCH, WRAP).apply { topMargin = act.dpi(6f) })
        val genres = d.optJSONArray("genres")
        if (genres != null && genres.length() > 0) info.addView(act.label((0 until genres.length()).joinToString(", ") { genres.getString(it) }, 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = act.dpi(4f) })
        head.addView(info, lp(0, WRAP, 1f).apply { leftMargin = act.dpi(14f) })
        col.addView(head)

        // where it streams: your channels get Watch buttons, the rest a line of text
        val provs = d.optJSONArray("providers")?.objects() ?: emptyList()
        val flow = Flow(act)
        val others = ArrayList<String>()
        for (pv in provs) {
            if (pv.optBoolean("installed")) {
                flow.addView(act.button((if (pv.optString("contentId").isNotEmpty()) "▶ " else "") + "Watch on " + pv.optString("appName"), primary = true) {
                    play(act, item, pv.optString("name")); close()
                })
            } else others.add(pv.optString("name"))
        }
        if (provs.none { it.optBoolean("installed") }) flow.addView(act.button("Find it on the TV", primary = true) { play(act, item); close() })
        col.addView(flow, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })
        if (others.isNotEmpty()) col.addView(act.label("Also on " + others.joinToString(", ") + " (not on your TV)", 13f, Theme.DIM), lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        if (d.optString("summary").isNotEmpty()) col.addView(act.label(d.optString("summary"), 15f, Theme.MIST), lp(MATCH, WRAP).apply { topMargin = act.dpi(12f) })

        val tools = Flow(act)
        var saved = d.optBoolean("saved")
        lateinit var save: android.widget.TextView
        save = act.button(if (saved) "In My Shows" else "Save to My Shows") {
            val path = if (saved) "/api/shows/remove-title" else "/api/shows/add"
            val body = if (saved) json("title" to d.optString("title"), "kind" to d.optString("kind"))
            else json("title" to d.optString("title"), "kind" to d.optString("kind"), "year" to d.optString("year"), "poster" to d.optString("poster"),
                "backdrop" to d.optString("backdrop"), "genres" to d.optJSONArray("genres"), "network" to d.optString("network"),
                "tvmaze" to d.optJSONObject("ids")?.opt("tvmaze"))
            Hub.post(path, body) { _, e -> if (e != null) act.toast(e) else { saved = !saved; save.text = if (saved) "In My Shows" else "Save to My Shows" } }
        }
        tools.addView(save)
        tools.addView(act.button("Show on the TV") {
            val ids = d.optJSONObject("ids") ?: JSONObject()
            Hub.post("/api/tv/hub", json("app" to "details", "title" to d.optString("title"), "kind" to d.optString("kind"), "year" to d.optString("year"),
                "tvmaze" to ids.opt("tvmaze"), "anilist" to ids.opt("anilist"))) { _, e -> act.toast(e ?: "Showing it on the TV") }
        })
        col.addView(tools, lp(MATCH, WRAP).apply { topMargin = act.dpi(14f) })

        // cast
        val cast = d.optJSONArray("cast")?.objects() ?: emptyList()
        if (cast.isNotEmpty()) {
            col.addView(act.label("Cast", 17f, Theme.SNOW, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(18f) })
            val row = act.hbox()
            for (c in cast.take(12)) {
                val cell = act.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
                val face = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = circle(Theme.RAISE); clipToOutline = true }
                if (c.optString("photo").isNotEmpty()) Images.load(face, "/api/thumb?u=" + enc(c.optString("photo")), 160)
                cell.addView(face, lp(act.dpi(56f), act.dpi(56f)))
                cell.addView(act.label(c.optString("name"), 12f, Theme.SNOW).apply { gravity = Gravity.CENTER; maxLines = 2 }, lp(act.dpi(84f), WRAP))
                cell.addView(act.label(c.optString("role"), 11f, Theme.DIM).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(act.dpi(84f), WRAP))
                row.addView(cell, lp(act.dpi(88f), WRAP))
            }
            col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        }

        // episodes: anime episode pages open the exact episode; TV seasons list what's in each
        val eps = d.optJSONArray("episodes")?.objects() ?: emptyList()
        val seasonList = d.optJSONArray("seasons")?.objects() ?: emptyList()
        if (d.optBoolean("anime") && eps.isNotEmpty()) {
            col.addView(act.label("Episodes", 17f, Theme.SNOW, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(18f) })
            val box = ListBox(act)
            for (e in eps.take(40)) box.row(e.optString("name"), e.optString("provider").ifEmpty { null }) {
                play(act, item, e.optString("provider").ifEmpty { null }, if (e.optString("contentId").isNotEmpty()) json("contentId" to e.optString("contentId"), "provider" to e.optString("provider")) else null)
                close()
            }
            col.addView(box, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        } else if (seasonList.isNotEmpty()) {
            col.addView(act.label("Seasons", 17f, Theme.SNOW, bold = true), lp(MATCH, WRAP).apply { topMargin = act.dpi(18f) })
            val chips = Chips(act)
            val box = ListBox(act)
            fun loadSeason(id: String) {
                box.clear(); box.row("Loading episodes")
                Hub.get("/api/title/episodes?season=$id") { j, e ->
                    box.clear()
                    if (e != null) { box.row(e); return@get }
                    for (ep in j!!.getJSONArray("episodes").objects())
                        box.row("E${ep.optInt("number")}  ${ep.optString("name")}", listOf(ep.optString("airdate"), if (ep.isNull("runtime")) "" else ep.optString("runtime") + " min").filter { it.isNotEmpty() }.joinToString("  ·  "))
                }
            }
            for (s in seasonList) chips.add(s.optString("id"), "Season " + s.optString("number"))
            chips.onPick = { k -> loadSeason(k) }
            val last = seasonList.last().optString("id")
            chips.select(last)
            loadSeason(last)
            col.addView(chips, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
            col.addView(box, lp(MATCH, WRAP).apply { topMargin = act.dpi(8f) })
        }
    }
}

package au.com.shiftyjelly.pocketcasts.repositories.mimi

/**
 * Removes podcast measurement/ad "prefix" redirects from enclosure URLs, so the file is fetched straight from
 * its host. These services wrap the real URL by prepending themselves, e.g.
 * `https://www.podtrac.com/pts/redirect.mp3/pdst.fm/e/pscrb.fm/rss/p/traffic.megaphone.fm/X.mp3`
 * -> `https://traffic.megaphone.fm/X.mp3`. Only known prefixes are stripped, and only when what remains still
 * looks like `host/path`; anything else is returned unchanged.
 */
object TrackingRedirects {
    private val prefixes = listOf(
        """(?:www\.|dts\.)?podtrac\.com/pts/redirect\.[a-z0-9]+/""",
        """dts\.podtrac\.com/redirect\.[a-z0-9]+/""",
        """play\.podtrac\.com/[^/]+/""",
        """pdst\.fm/e/""",
        """(?:verifi\.)?pscrb\.fm/rss/p/""",
        """verifi\.podscribe\.com/rss/p/""",
        """mgln\.ai/e/[^/]+/""",
        """(?:chtbl\.com|chrt\.fm)/track/[^/]+/""",
        """op3\.dev/e(?:,[^/]*)?/""",
        """pfx\.vpixl\.com/[^/]+/""",
        """arttrk\.com/p/[^/]+/""",
        """(?:tracking\.)?swap\.fm/track/[^/]+/""",
        """prfx\.byspotify\.com/e/""",
        """claritaspod\.com/measure/""",
        """pdcn\.co/e/""",
    ).map { Regex("^https?://$it", RegexOption.IGNORE_CASE) }

    private val hostAndPath = Regex("""^(?:https?://)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?::\d+)?/.+""", RegexOption.IGNORE_CASE)

    fun strip(url: String): String {
        var current = url
        repeat(10) {
            val match = prefixes.firstNotNullOfOrNull { it.find(current) } ?: return current
            val rest = current.substring(match.range.last + 1)
            if (!hostAndPath.matches(rest)) return current
            current = if (rest.startsWith("http://", true) || rest.startsWith("https://", true)) rest else "https://$rest"
        }
        return current
    }
}

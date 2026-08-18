package dev.tonexotg.app.ui.screens.about

/**
 * One upstream project's attribution facts, as recorded in `/CREDITS.md` — the project name, its
 * repository URL, a human-readable licence summary (e.g. `"MIT License (SPDX: `MIT`)"`), and the
 * exact upstream copyright line.
 */
data class UpstreamProjectCredit(
    val name: String,
    val repositoryUrl: String,
    val licenceSummary: String,
    val copyright: String,
)

/**
 * Parses the "Upstream projects" section of `/CREDITS.md` (S3, issue #24 / S19) into structured
 * [UpstreamProjectCredit] values, so the About/credits screen renders content sourced from the
 * repo's actual attribution source of truth rather than a hand-typed second copy that could
 * silently drift from it.
 *
 * This deliberately only extracts the fields it needs (name, repository, licence, copyright) via
 * targeted regexes against CREDITS.md's known `### N. <name>` / `- **Field:** value` structure —
 * it is not a general Markdown parser, and it is expected to fail loud (return an empty list, or
 * a credit with a blank field) rather than guess if that structure ever changes without this
 * parser being updated to match. Callers that need certainty the parse actually found real data
 * should assert on the parsed values directly, as [dev.tonexotg.app.ui.screens.about.CreditsParserTest] does.
 */
object CreditsParser {

    // Matches "### 1. TonexOneController" / "### 2. tonex_controller" — a numbered ### header,
    // deliberately narrower than "### Rules of thumb" or "### Template — ..." (no leading digit
    // + period there), so only the two real upstream-project sections match.
    private val sectionHeader = Regex("""(?m)^###\s+\d+\.\s+(.+?)\s*$""")

    private fun fieldRegex(label: String): Regex =
        Regex("""(?m)^-\s+\*\*$label:\*\*\s+(.+?)\s*$""")

    private val repositoryField = fieldRegex("Repository")
    private val licenceField = fieldRegex("Licence")
    private val copyrightField = fieldRegex("Copyright")

    /**
     * Extracts every numbered upstream-project section from [creditsMarkdown] (the verbatim
     * contents of `/CREDITS.md`). Returns one [UpstreamProjectCredit] per `### N. <name>` header,
     * in document order.
     */
    fun parseUpstreamProjects(creditsMarkdown: String): List<UpstreamProjectCredit> {
        val headers = sectionHeader.findAll(creditsMarkdown).toList()
        return headers.mapIndexed { index, header ->
            val sectionStart = header.range.last + 1
            val sectionEnd = if (index + 1 < headers.size) {
                headers[index + 1].range.first
            } else {
                creditsMarkdown.length
            }
            val section = creditsMarkdown.substring(sectionStart, sectionEnd)

            UpstreamProjectCredit(
                name = header.groupValues[1].trim(),
                repositoryUrl = repositoryField.find(section)?.groupValues?.get(1)?.trim().orEmpty(),
                licenceSummary = licenceField.find(section)?.groupValues?.get(1)?.trim().orEmpty(),
                copyright = copyrightField.find(section)?.groupValues?.get(1)?.trim().orEmpty(),
            )
        }
    }
}

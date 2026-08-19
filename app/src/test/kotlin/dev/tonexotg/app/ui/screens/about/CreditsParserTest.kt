package dev.tonexotg.app.ui.screens.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parses the *real* repo-root `CREDITS.md` (not a hand-typed copy in this test file) and asserts
 * against literal values copied verbatim from it. Per the house "byte-exact / literal-exact
 * acceptance criteria are gold" guidance (`CLAUDE.md`), this is what actually catches
 * [CreditsParser] drifting out of sync with CREDITS.md's structure or copy — a self-consistent
 * "parse then re-serialize and compare" test would not.
 *
 * If this test starts failing because CREDITS.md's wording changed, that is the signal working as
 * intended: either update the parser to match the new structure, or confirm the new wording still
 * says what issue #24's acceptance criteria require (correct licence + copyright holder per
 * upstream project) and update the expected literals here to match.
 */
class CreditsParserTest {

    private val creditsMarkdown = File("../CREDITS.md").readText()

    @Test
    fun parsesExactlyTwoUpstreamProjects() {
        val projects = CreditsParser.parseUpstreamProjects(creditsMarkdown)
        assertEquals(2, projects.size)
    }

    @Test
    fun tonexOneController_matchesCreditsMdLiterally() {
        val project = CreditsParser.parseUpstreamProjects(creditsMarkdown)
            .single { it.name == "TonexOneController" }

        assertEquals("TonexOneController", project.name)
        assertEquals("https://github.com/Builty/TonexOneController", project.repositoryUrl)
        assertEquals("Apache License 2.0 (SPDX: `Apache-2.0`)", project.licenceSummary)
        assertEquals("Copyright 2025 Greg Smith", project.copyright)
    }

    @Test
    fun tonexController_matchesCreditsMdLiterally() {
        val project = CreditsParser.parseUpstreamProjects(creditsMarkdown)
            .single { it.name == "tonex_controller" }

        assertEquals("tonex_controller", project.name)
        assertEquals("https://github.com/vit3k/tonex_controller", project.repositoryUrl)
        assertEquals("MIT License (SPDX: `MIT`)", project.licenceSummary)
        assertEquals("Copyright (c) 2024 vit3k", project.copyright)
    }

    @Test
    fun doesNotMatchNonProjectHeadings() {
        // "### Rules of thumb" and "### Template — ..." headings elsewhere in CREDITS.md must
        // not be mistaken for a numbered upstream-project section.
        val names = CreditsParser.parseUpstreamProjects(creditsMarkdown).map { it.name }
        assertEquals(listOf("TonexOneController", "tonex_controller"), names)
    }
}

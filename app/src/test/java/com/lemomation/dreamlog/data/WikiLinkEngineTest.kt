package com.lemomation.dreamlog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WikiLinkEngineTest {

    @Test
    fun automaticMatchingWrapsKnownNamesAndKeepsWholeWords() {
        val result = WikiLinkEngine.autoLink(
            text = "my brother went to China with brotherhood",
            nodeNames = listOf("Brother", "China"),
        )
        assertEquals("my [[Brother]] went to [[China]] with brotherhood", result)
    }

    @Test
    fun existingWikiLinksAreNeverDoubleWrapped() {
        val result = WikiLinkEngine.autoLink(
            text = "my [[Brother]] went to [[China]] and saw China",
            nodeNames = listOf("Brother", "China"),
        )
        assertEquals("my [[Brother]] went to [[China]] and saw [[China]]", result)
    }

    @Test
    fun longerNodeNamesWinOverlappingSpans() {
        val result = WikiLinkEngine.autoLink(
            text = "I flew to New York alone",
            nodeNames = listOf("York", "New York"),
        )
        assertEquals("I flew to [[New York]] alone", result)
    }

    @Test
    fun extractionIsTrimmedAndCaseInsensitivelyDistinct() {
        val targets = WikiLinkEngine.extractTargets("[[ Mom ]] [[mom]] [[Brother]]")
        assertEquals(listOf("Mom", "Brother"), targets)
    }

    @Test
    fun markdownFileNamesBecomeCleanTitles() {
        assertEquals("03:45 dream", MarkdownCodec.titleFromFileName("03:45 dream.md"))
        assertEquals("A/B", MarkdownCodec.titleFromFileName("A/B.MD"))
        assertEquals("Untitled", MarkdownCodec.sanitizeFileName(" ... "))
        assertFalse(MarkdownCodec.sanitizeFileName("a:b?c").contains(":"))
    }
}

package com.example.qidk_hack2026.form

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTidyTest {

    private fun input(id: Int, label: String) = ScreenField(id, FieldKind.INPUT, label)
    private fun option(id: Int, label: String, group: String = "") =
        ScreenField(id, FieldKind.OPTION, label, group = group)

    @Test
    fun clickableLabelNextToItsInputIsNotAChoice() {
        // What Firefox reported for <label>Location</label><input placeholder="Location">
        val fields = listOf(option(0, "Location"), input(1, "Location"), option(2, "Email"), input(3, "Email"))
        assertEquals(listOf(1, 3), ScreenTidy.tidy(fields, emptySet()).map { it.id })
    }

    @Test
    fun radioAndItsCaptionBecomeOneOptionKeepingTheControl() {
        // Caption (clickable text) was listed first, the radio itself second.
        val fields = listOf(option(4, "Low", "Severity"), option(5, "Low", "Severity"), option(6, "High", "Severity"), option(7, "High", "Severity"))
        val tidy = ScreenTidy.tidy(fields, choiceIds = setOf(5, 7))
        assertEquals(listOf(5, 7), tidy.map { it.id })
    }

    @Test
    fun captionIsKeptWhenItIsTheOnlyEntry() {
        val fields = listOf(option(0, "Low", "Severity"), option(1, "High", "Severity"))
        assertEquals(listOf(0, 1), ScreenTidy.tidy(fields, emptySet()).map { it.id })
    }

    @Test
    fun sameWordInDifferentQuestionsIsNotMerged() {
        val fields = listOf(option(0, "Yes", "Injured"), option(1, "Yes", "Evacuated"))
        assertEquals(listOf(0, 1), ScreenTidy.tidy(fields, setOf(0, 1)).map { it.id })
    }

    @Test
    fun sameAnswerInALaterQuestionUnderTheSameHeadingIsNotMerged() {
        // Google Forms: question 3 and question 6 both sit under "20 points" and both offer True/False.
        val fields = listOf(
            option(0, "True", "20 points"), option(1, "False", "20 points"),
            option(2, "Image", "20 points"),
            option(3, "True", "20 points"), option(4, "False", "20 points"),
        )
        assertEquals(listOf(0, 1, 2, 3, 4), ScreenTidy.tidy(fields, setOf(0, 1, 2, 3, 4)).map { it.id })
    }

    @Test
    fun realButtonsAndRadiosSurvive() {
        val fields = listOf(input(0, "Location"), option(1, "Submit"), option(2, "High", "Severity"))
        assertEquals(listOf(0, 1, 2), ScreenTidy.tidy(fields, setOf(2)).map { it.id })
    }

    @Test
    fun clickableLabelNextToItsDropdownIsNotAChoice() {
        val dropdown = ScreenField(1, FieldKind.DROPDOWN, "Country")
        val fields = listOf(option(0, "Country"), dropdown)
        assertEquals(listOf(1), ScreenTidy.tidy(fields, emptySet()).map { it.id })
    }
}

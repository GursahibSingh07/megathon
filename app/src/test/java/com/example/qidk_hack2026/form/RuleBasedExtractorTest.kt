package com.example.qidk_hack2026.form

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBasedExtractorTest {

    private fun input(id: Int, label: String, current: String = "") =
        ScreenField(id, FieldKind.INPUT, label, current = current)

    private fun option(id: Int, label: String, group: String, selected: Boolean = false) =
        ScreenField(id, FieldKind.OPTION, label, group = group, selected = selected)

    private fun screen(vararg f: ScreenField) = ScreenSnapshot(f.toList(), "")

    private val incidentForm = screen(
        input(0, "Location"),
        input(1, "Description"),
        option(2, "Low", "Severity"),
        option(3, "Medium", "Severity"),
        option(4, "High", "Severity"),
        input(5, "Email"),
    )

    @Test
    fun fillsInputsAndOptionsFromOneNarration() {
        val actions = RuleBasedExtractor.extract(
            "location is Conference Room 2, severity high, description water leak near the window",
            incidentForm,
        )
        assertEquals(
            listOf(
                FormAction.Type(0, "Conference Room 2"),
                FormAction.Type(1, "water leak near the window"),
                FormAction.Click(4),
            ),
            actions,
        )
    }

    @Test
    fun spokenEmailBecomesAnAddress() {
        val actions = RuleBasedExtractor.extract("email priya at example dot com", incidentForm)
        assertEquals(listOf<FormAction>(FormAction.Type(5, "priya@example.com")), actions)
    }

    @Test
    fun valuesStopAtTheNextFieldName() {
        val actions = RuleBasedExtractor.extract("location lobby and description broken glass", incidentForm)
        assertEquals(
            listOf<FormAction>(FormAction.Type(0, "lobby"), FormAction.Type(1, "broken glass")),
            actions,
        )
    }

    @Test
    fun longerLabelWinsOverItsLastWord() {
        val form = screen(input(0, "Full name"), input(1, "Name of witness"))
        val actions = RuleBasedExtractor.extract("name of witness is Arun, full name is Priya Shah", form)
        assertEquals(
            listOf<FormAction>(FormAction.Type(0, "Priya Shah"), FormAction.Type(1, "Arun")),
            actions,
        )
    }

    @Test
    fun alreadySelectedOptionIsNotClickedAgain() {
        val form = screen(option(0, "Low", "Severity", selected = true), option(1, "High", "Severity"))
        assertTrue(RuleBasedExtractor.extract("severity low", form).isEmpty())
    }

    @Test
    fun optionWithoutItsQuestionNamedIsClickedWhenItsOwnLabelIsSpoken() {
        val actions = RuleBasedExtractor.extract("it was a high priority leak", incidentForm)
        assertEquals(listOf<FormAction>(FormAction.Click(4)), actions)
    }

    @Test
    fun nothingMatchedGivesNoActions() {
        assertTrue(RuleBasedExtractor.extract("the weather is nice today", incidentForm).isEmpty())
        assertTrue(RuleBasedExtractor.extract("   ", incidentForm).isEmpty())
    }
}

package com.example.qidk_hack2026.form

import org.junit.Assert.assertEquals
import org.junit.Test

class MissingFieldPromptTest {
    @Test fun oneField() =
        assertEquals("I still need Email. Please tell me.", MissingFieldPrompt.ask(listOf("Email *")))

    @Test fun twoFields() =
        assertEquals("I still need Name and Phone number. Please tell me.",
            MissingFieldPrompt.ask(listOf("Name", "Phone number:")))

    @Test fun manyFieldsAreCapped() =
        assertEquals("I still need A, B, C and 2 more. Please tell me.",
            MissingFieldPrompt.ask(listOf("A", "B", "C", "D", "E")))

    @Test fun unlabelledFieldsAreDropped() =
        assertEquals("I still need Name. Please tell me.", MissingFieldPrompt.ask(listOf("field 3", "Name")))

    @Test fun didntCatch() =
        assertEquals("Sorry, I didn't catch that. I still need Name. Please tell me.",
            MissingFieldPrompt.ask(listOf("Name"), didntCatch = true))
}

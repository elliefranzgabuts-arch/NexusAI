package com.nexusai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalCommandEngineTest {
    @Test
    fun normalizesPunctuationAndWhitespaceForEnglishAndTaglishInput() {
        assertEquals("anong oras na", LocalCommandEngine.normalize("  Anong   oras na?! "))
        assertEquals(LocalCommandEngine.Command.TIME, LocalCommandEngine.classify("Anong oras na?!"))
    }

    @Test
    fun classifiesSupportedLocalCommands() {
        assertEquals(LocalCommandEngine.Command.TIME, LocalCommandEngine.classify("Tell me the time"))
        assertEquals(LocalCommandEngine.Command.DATE, LocalCommandEngine.classify("What is the date?"))
        assertEquals(LocalCommandEngine.Command.SETTINGS, LocalCommandEngine.classify("Open device settings"))
        assertEquals(LocalCommandEngine.Command.CALCULATOR, LocalCommandEngine.classify("Launch calculator"))
    }

    @Test
    fun unknownNaturalQuestionsDoNotMasqueradeAsLocalCommands() {
        assertEquals(LocalCommandEngine.Command.UNKNOWN, LocalCommandEngine.classify("Explain databases"))
    }
}

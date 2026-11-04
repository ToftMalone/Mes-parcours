package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.component.logTouches
import com.example.util.DebugLog
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Un appui est noté avec ce qui a été touché : libellé, étiquette, état. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchLogTest {

    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DebugLog.init(context)
        DebugLog.clear(context)
    }

    private fun journal(): String {
        DebugLog.flushNow()
        return File(context.filesDir, "logs").listFiles().orEmpty().joinToString("") { it.readText() }
    }

    @Test
    fun `un appui note le bouton touché, et un interrupteur son état`() {
        compose.setContent {
            Column(Modifier.fillMaxSize().logTouches("essai")) {
                Button(onClick = {}, modifier = Modifier.testTag("bouton_essai")) { Text("Vider la corbeille") }
                Switch(checked = true, onCheckedChange = {}, modifier = Modifier.testTag("interrupteur_essai"))
            }
        }
        compose.onNodeWithTag("bouton_essai").performClick()
        compose.onNodeWithTag("interrupteur_essai").performClick()
        compose.waitForIdle()

        val text = journal()
        assertTrue(text, text.contains("Toucher : Appui (essai)") && text.contains("« Vider la corbeille »") && text.contains("[bouton_essai]"))
        assertTrue(text, text.contains("[interrupteur_essai]") && text.contains("état On"))
    }
}

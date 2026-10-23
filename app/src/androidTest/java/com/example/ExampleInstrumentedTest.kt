package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test instrumenté, exécuté sur un appareil Android.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
  @Test
  fun useAppContext() {
    // Contexte de l'application testée.
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("com.example", appContext.packageName)
  }
}

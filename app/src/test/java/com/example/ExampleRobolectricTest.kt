package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    // Ce test garde le nom affiché de l'application : il échoue si `app_name` change
    // sans que le reste suive. C'est ce qui l'a signalé lors des renommages
    // successifs (« Sillage », puis « Mes parcours »).
    assertEquals("Mes parcours", appName)
  }
}

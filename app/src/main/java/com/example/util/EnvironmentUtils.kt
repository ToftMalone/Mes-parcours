package com.example.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.BuildConfig

object EnvironmentUtils {
    private const val TAG = "EnvironmentUtils"

    fun isEmulatorOrCloud(context: Context): Boolean {
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val fingerprint = Build.FINGERPRINT.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val board = Build.BOARD.lowercase()

        val isEmulator = fingerprint.startsWith("generic") ||
                fingerprint.startsWith("unknown") ||
                fingerprint.contains("emulator") ||
                fingerprint.contains("cuttlefish") ||
                fingerprint.contains("cf_") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk") ||
                model.contains("sdk_gphone") ||
                model.contains("cuttlefish") ||
                model.contains("gphone") ||
                manufacturer.contains("genymotion") ||
                manufacturer.contains("google") && (model.contains("sdk") || model.contains("gphone")) ||
                brand.startsWith("generic") ||
                brand.contains("emulator") ||
                device.startsWith("generic") ||
                device.contains("emulator") ||
                device.contains("vsoc") ||
                product.contains("sdk_gphone") ||
                product.contains("google_sdk") ||
                product.contains("emulator") ||
                product.contains("sdk") ||
                product.contains("cf_") ||
                product.contains("cuttlefish") ||
                product.contains("vbox") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                hardware.contains("vsoc") ||
                hardware.contains("cuttlefish") ||
                board.contains("vsoc") ||
                board.contains("cuttlefish") ||
                board.contains("cf_")

        // Journal de diagnostic réservé au debug. Ces champs identifient l'appareil
        // assez précisément pour le distinguer d'un autre ; ils n'ont rien à faire
        // dans les journaux d'une version distribuée, où ils ne servent personne.
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Environment check - Brand: $brand, Device: $device, Model: $model, " +
                    "Product: $product, Hardware: $hardware, Fingerprint: $fingerprint, " +
                    "Manufacturer: $manufacturer, Board: $board -> isEmulatorOrCloud: $isEmulator"
            )
        }
        return isEmulator
    }
}

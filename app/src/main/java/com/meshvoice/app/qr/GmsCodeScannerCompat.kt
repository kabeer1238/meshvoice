@file:Suppress("unused")

package com.google.android.gms.mlkit.barcode

import android.content.Context
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions as RealOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning as RealScanning

/**
 * Temporary source-compatible bridge for the V0.2.2 prototype.
 * MainActivity originally imported the scanner classes from the Google Play
 * services group path. The SDK exposes them from com.google.mlkit.vision.codescanner.
 * This bridge keeps the feature buildable without touching unrelated ride logic.
 */
class GmsBarcodeScannerOptions internal constructor(internal val delegate: RealOptions) {
    class Builder {
        private val delegate = RealOptions.Builder()

        fun setBarcodeFormats(vararg formats: Int): Builder = apply {
            if (formats.isNotEmpty()) {
                val rest = if (formats.size > 1) formats.copyOfRange(1, formats.size) else intArrayOf()
                delegate.setBarcodeFormats(formats[0], *rest)
            }
        }

        fun enableAutoZoom(): Builder = apply {
            delegate.enableAutoZoom()
        }

        fun build(): GmsBarcodeScannerOptions = GmsBarcodeScannerOptions(delegate.build())
    }
}

object GmsBarcodeScanning {
    fun getClient(context: Context, options: GmsBarcodeScannerOptions) =
        RealScanning.getClient(context, options.delegate)
}

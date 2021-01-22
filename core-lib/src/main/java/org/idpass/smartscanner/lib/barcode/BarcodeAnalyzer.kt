/*
 * Copyright (C) 2020 Newlogic Pte. Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 *
 */
package org.idpass.smartscanner.lib.barcode

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.gson.Gson
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.jayway.jsonpath.JsonPath
import org.idpass.smartscanner.api.ScannerConstants
import org.idpass.smartscanner.lib.SmartScannerActivity
import org.idpass.smartscanner.lib.platform.extension.cacheImagePath
import org.idpass.smartscanner.lib.platform.extension.cacheImageToLocal
import org.idpass.smartscanner.lib.platform.extension.toBitmap
import org.idpass.smartscanner.lib.platform.utils.GzipUtils
import org.idpass.smartscanner.lib.scanner.config.Modes
import org.json.JSONObject

class BarcodeAnalyzer(
    private val activity: Activity,
    private val intent : Intent,
    private val barcodeFormats: List<Int>
) : ImageAnalysis.Analyzer {

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            Log.d(SmartScannerActivity.TAG, "Bitmap: (${mediaImage.width}, ${mediaImage.height})")
            val rot = imageProxy.imageInfo.rotationDegrees
            val bf = mediaImage.toBitmap(rot, Modes.BARCODE.value)
            val start = System.currentTimeMillis()
            var barcodeFormat = Barcode.FORMAT_QR_CODE
            barcodeFormats.forEach {
                barcodeFormat = it or barcodeFormat // bitwise different barcode format options
            }
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(barcodeFormat).build()
            val image = InputImage.fromBitmap(bf, imageProxy.imageInfo.rotationDegrees)
            val scanner = BarcodeScanning.getClient(options)
            Log.d("${SmartScannerActivity.TAG}/SmartScanner", "barcode: process")
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val timeRequired = System.currentTimeMillis() - start
                    val cornersString: String
                    val rawValue: String
                    Log.d(
                        "${SmartScannerActivity.TAG}/SmartScanner",
                        "barcode: success: $timeRequired ms"
                    )
                    val filePath = activity.cacheImagePath()
                    if (barcodes.isNotEmpty()) {
                        val corners = barcodes[0].cornerPoints
                        val builder = StringBuilder()
                        if (corners != null) {
                            for (corner in corners) {
                                builder.append("${corner.x},${corner.y} ")
                            }
                        }
                        bf.cacheImageToLocal(
                            filePath,
                            imageProxy.imageInfo.rotationDegrees
                        )
                        cornersString = builder.toString()
                        rawValue = barcodes[0].rawValue!!
                        val result = BarcodeResult(filePath, cornersString, rawValue)
                        when (intent.action) {
                            ScannerConstants.IDPASS_SMARTSCANNER_QRCODE_INTENT -> {
                                sendGzippedResult(rawValue = rawValue, rawBytes = barcodes[0].rawBytes!!)
                            }
                            ScannerConstants.IDPASS_SMARTSCANNER_BARCODE_INTENT,
                            ScannerConstants.IDPASS_SMARTSCANNER_ODK_BARCODE_INTENT -> {
                                sendBundleResult(barcodeResult = result)
                            }
                            else -> {
                                val jsonString = Gson().toJson(result)
                                sendAnalyzerResult(result = jsonString)
                            }
                        }
                    } else {
                        Log.d(
                            "${SmartScannerActivity.TAG}/SmartScanner",
                            "barcode: nothing detected"
                        )
                    }
                    imageProxy.close()
                }
                .addOnFailureListener { e ->
                    imageProxy.close()
                    Log.d(
                        "${SmartScannerActivity.TAG}/SmartScanner",
                        "barcode: failure: ${e.message}"
                    )
                }
        }
    }

    private fun sendGzippedResult(rawValue : String, rawBytes: ByteArray) {
        // parse and read gzip json and add to bundle intent
        val bundle = Bundle()
        val isGzipped = intent.getBooleanExtra(ScannerConstants.GZIPPED_ENABLED, true)
        val isJson = intent.getBooleanExtra(ScannerConstants.JSON_ENABLED, true)
        val jsonPath = intent.getStringExtra(ScannerConstants.JSON_PATH)
        // check gzipped parameters for bundle return result
        if (isGzipped) {
            val gzippedData = GzipUtils.decompress(rawBytes)
            jsonPath?.let { path ->
                val ctx = JsonPath.parse(gzippedData)
                bundle.putString(ScannerConstants.QRCODE_JSON_VALUE, ctx.read<Any>(path).toString())
            } ?: run {
                if (isJson) {
                    val json = JSONObject(gzippedData)
                    bundle.putString(ScannerConstants.QRCODE_TEXT, json.toString())
                } else {
                    bundle.putString(ScannerConstants.QRCODE_TEXT, gzippedData)
                }
            }
        } else {
            bundle.putString(ScannerConstants.QRCODE_TEXT, rawValue)
        }
        // send gzip json data
        val data = Intent()
        data.putExtra(ScannerConstants.RESULT, bundle)
        activity.setResult(Activity.RESULT_OK, data)
        activity.finish()
    }

    private fun sendBundleResult(barcodeResult : BarcodeResult? = null) {
        val bundle = Bundle()
        Log.d(SmartScannerActivity.TAG, "Success from BARCODE")
        if (intent.action == ScannerConstants.IDPASS_SMARTSCANNER_ODK_BARCODE_INTENT) {
            bundle.putString(ScannerConstants.IDPASS_ODK_INTENT_DATA, barcodeResult?.value)
        }
        bundle.putString(ScannerConstants.BARCODE_IMAGE, barcodeResult?.imagePath)
        bundle.putString(ScannerConstants.BARCODE_CORNERS, barcodeResult?.corners)
        bundle.putString(ScannerConstants.BARCODE_VALUE, barcodeResult?.value)

        val result = Intent()
        val prefix = if (intent.hasExtra(ScannerConstants.IDPASS_ODK_PREFIX_EXTRA)) {
            intent.getStringExtra(ScannerConstants.IDPASS_ODK_PREFIX_EXTRA)
        } else { "" }
        result.putExtra(ScannerConstants.RESULT, bundle)
        // Copy all the values in the intent result to be compatible with other implementations than commcare
        for (key in bundle.keySet()) {
            result.putExtra(prefix + key, bundle.getString(key))
        }
        activity.setResult(Activity.RESULT_OK, result)
        activity.finish()
    }

    private fun sendAnalyzerResult(result: String? = null) {
        val data = Intent()
        Log.d(SmartScannerActivity.TAG, "Success from BARCODE")
        Log.d(SmartScannerActivity.TAG, "value: $result")
        data.putExtra(SmartScannerActivity.SCANNER_RESULT, result)
        activity.setResult(Activity.RESULT_OK, data)
        activity.finish()
    }
}
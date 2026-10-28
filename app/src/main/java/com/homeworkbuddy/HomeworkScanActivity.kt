package com.homeworkbuddy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dev.offlinescan.core.DetectionMode
import dev.offlinescan.core.ExportFormat
import dev.offlinescan.core.ScanConfig
import dev.offlinescan.core.ScanMode
import dev.offlinescan.core.ScanResult
import dev.offlinescan.ui.ScannerFlow
import java.io.File

/** Runs the offline scanner as one JPEG-producing homework photo session. */
class HomeworkScanActivity : ComponentActivity() {
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ScannerFlow(
                    config = ScanConfig(
                        mode = ScanMode.DOCUMENT,
                        maxPages = 1,
                        detectionMode = DetectionMode.AI
                    ),
                    outputDirectory = File(cacheDir, "homework-scans"),
                    defaultExportFormat = ExportFormat.JPEG,
                    allowPdfExport = false
                ) { result -> deliverResult(result) }
            }
        }
    }

    private fun deliverResult(result: ScanResult) {
        if (resultDelivered) return
        resultDelivered = true
        when (result) {
            is ScanResult.Completed -> {
                val file = result.output.files.singleOrNull()
                if (result.output.mimeType == "image/jpeg" && file?.isFile == true) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, file.absolutePath))
                } else {
                    Toast.makeText(this, "扫描没有生成照片，请重试", Toast.LENGTH_LONG).show()
                    setResult(Activity.RESULT_CANCELED)
                }
            }
            is ScanResult.Failed -> {
                Toast.makeText(this, "扫描失败：${result.error.message}", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
            }
            ScanResult.Cancelled -> setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    companion object { const val EXTRA_RESULT_PATH = "result_path" }
}

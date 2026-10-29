package com.homeworkbuddy

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import com.zynksoftware.documentscanner.ScanActivity
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel
import com.zynksoftware.documentscanner.model.ScannerResults
import com.zynksoftware.documentscanner.ui.DocumentScanner

/** Offline document capture; returns the already cropped image to the homework flow. */
class HomeworkScanActivity : ScanActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DocumentScanner.init(this)
        super.onCreate(savedInstanceState)
        addFragmentContentLayout()
        val content = findViewById<View>(android.R.id.content)
        content.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        content.requestApplyInsets()
    }

    override fun onSuccess(scannerResults: ScannerResults) {
        val file = scannerResults.transformedImageFile ?: scannerResults.croppedImageFile
        if (file == null) {
            Toast.makeText(this, "没有获取到裁剪照片", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, file.absolutePath))
        }
        finish()
    }

    override fun onError(error: DocumentScannerErrorModel) {
        Toast.makeText(this, "扫描失败：${error.errorMessage}", Toast.LENGTH_LONG).show()
    }

    override fun onClose() { finish() }

    companion object { const val EXTRA_RESULT_PATH = "result_path" }
}

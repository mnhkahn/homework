/**
    Copyright 2020 ZynkSoftware SRL

    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
    associated documentation files (the "Software"), to deal in the Software without restriction,
    including without limitation the rights to use, copy, modify, merge, publish, distribute,
    sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all copies or
    substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
    INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
    NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
    DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.zynksoftware.documentscanner.ui.camerascreen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.fondesa.kpermissions.allGranted
import com.fondesa.kpermissions.allShouldShowRationale
import com.fondesa.kpermissions.extension.permissionsBuilder
import com.fondesa.kpermissions.extension.send
import com.zynksoftware.documentscanner.R
import com.zynksoftware.documentscanner.common.extensions.hide
import com.zynksoftware.documentscanner.common.extensions.show
import com.zynksoftware.documentscanner.common.utils.FileUriUtils
import com.zynksoftware.documentscanner.model.DocumentScannerErrorModel
import com.zynksoftware.documentscanner.ui.base.BaseFragment
import com.zynksoftware.documentscanner.ui.components.scansurface.ScanSurfaceListener
import com.zynksoftware.documentscanner.ui.scan.InternalScanActivity
import kotlinx.android.synthetic.main.fragment_camera_screen.*
import id.zelory.compressor.determineImageRotation
import java.io.File
import java.io.FileNotFoundException


internal class CameraScreenFragment: BaseFragment(), ScanSurfaceListener  {

    companion object {
        private val TAG = CameraScreenFragment::class.simpleName

        fun newInstance(): CameraScreenFragment {
            return CameraScreenFragment()
        }
    }

    private var resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val imageUri = result?.data?.data
                if (imageUri != null) {
                    val realPath = FileUriUtils.getRealPath(getScanActivity(), imageUri)
                    if (realPath != null) {
                        getScanActivity().reInitOriginalImageFile()
                        getScanActivity().originalImageFile = File(realPath)
                        startCroppingProcess()
                    } else {
                        Log.e(TAG, DocumentScannerErrorModel.ErrorMessage.TAKE_IMAGE_FROM_GALLERY_ERROR.error)
                        onError(DocumentScannerErrorModel(
                            DocumentScannerErrorModel.ErrorMessage.TAKE_IMAGE_FROM_GALLERY_ERROR, null))
                    }
                } else {
                    Log.e(TAG, DocumentScannerErrorModel.ErrorMessage.TAKE_IMAGE_FROM_GALLERY_ERROR.error)
                    onError(DocumentScannerErrorModel(
                        DocumentScannerErrorModel.ErrorMessage.TAKE_IMAGE_FROM_GALLERY_ERROR, null))
                }
            } catch (e: FileNotFoundException) {
                Log.e(TAG, "FileNotFoundException", e)
                onError(DocumentScannerErrorModel(
                    DocumentScannerErrorModel.ErrorMessage.TAKE_IMAGE_FROM_GALLERY_ERROR, e))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_camera_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scanSurfaceView.lifecycleOwner = this
        scanSurfaceView.listener = this
        scanSurfaceView.originalImageFile = getScanActivity().originalImageFile

        checkForCameraPermissions()
        initListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        if(getScanActivity().shouldCallOnClose) {
            getScanActivity().onClose()
        }
    }

    override fun onResume() {
        super.onResume()
        getScanActivity().reInitOriginalImageFile()
        scanSurfaceView.originalImageFile = getScanActivity().originalImageFile
    }

    private fun initListeners() {
        cameraCaptureButton.setOnClickListener {
            takePhoto()
        }
        cancelButton.setOnClickListener {
            finishActivity()
        }
        flashButton.setOnClickListener {
            switchFlashState()
        }
        galleryButton.setOnClickListener {
            // ACTION_OPEN_DOCUMENT grants access to the selected image itself.
            selectImageFromGallery()
        }
        autoButton.setOnClickListener {
            toggleAutoManualButton()
        }
    }

    private fun toggleAutoManualButton() {
        scanSurfaceView.isAutoCaptureOn = !scanSurfaceView.isAutoCaptureOn
        if (scanSurfaceView.isAutoCaptureOn) {
            autoButton.text = getString(R.string.zdc_auto)
        } else {
            autoButton.text = getString(R.string.zdc_manual)
        }
    }

    private fun checkForCameraPermissions() {
        permissionsBuilder(Manifest.permission.CAMERA)
            .build()
            .send { result ->
                if (result.allGranted()) {
                    startCamera()
                } else if(result.allShouldShowRationale()) {
                    onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.CAMERA_PERMISSION_REFUSED_WITHOUT_NEVER_ASK_AGAIN))
                } else {
                    onError(DocumentScannerErrorModel(DocumentScannerErrorModel.ErrorMessage.CAMERA_PERMISSION_REFUSED_GO_TO_SETTINGS))
                }
            }
    }

    private fun startCamera() {
        scanSurfaceView.start()
    }

    private fun takePhoto() {
        scanSurfaceView.takePicture()
    }

    private fun getScanActivity(): InternalScanActivity {
        return (requireActivity() as InternalScanActivity)
    }

    private fun finishActivity() {
        getScanActivity().finish()
    }

    private fun switchFlashState() {
        scanSurfaceView.switchFlashState()
    }

    override fun showFlash() {
        flashButton?.show()
    }

    override fun hideFlash() {
        flashButton?.hide()
    }

    private fun selectImageFromGallery() {
        val photoPickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        photoPickerIntent.addCategory(Intent.CATEGORY_OPENABLE)
        photoPickerIntent.type = "image/*"
        resultLauncher.launch(photoPickerIntent)
    }

    override fun scanSurfacePictureTaken(previewFrame: org.opencv.core.Mat, previewCorners: Array<org.opencv.core.Point>) {
        val activity = getScanActivity()
        Thread {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(activity.originalImageFile.absolutePath)
                    ?: error("图片读取失败")
                val upright = determineImageRotation(activity.originalImageFile, bitmap)
                val points = com.zynksoftware.documentscanner.common.utils.CapturedFrameAlignment.align(previewFrame, previewCorners, upright)
                    ?: error("无法对齐取景框与照片，请重拍")
                val cropped = com.zynksoftware.documentscanner.common.utils.OpenCvNativeBridge().getScannedBitmap(
                    upright, points[0], points[1], points[2], points[3], points[4], points[5], points[6], points[7])
                activity.runOnUiThread {
                    if (isAdded) {
                        hideProgressBar()
                        activity.croppedImage = cropped
                        activity.finalScannerResult()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OfflineScan", "Automatic crop failed", e)
                activity.runOnUiThread {
                    if (isAdded) {
                        hideProgressBar()
                        android.widget.Toast.makeText(activity, "自动裁剪失败，请重新拍摄", android.widget.Toast.LENGTH_LONG).show()
                        activity.recreate()
                    }
                }
            } finally {
                previewFrame.release()
            }
        }.start()
    }

    private fun startCroppingProcess() {
        if (isAdded) {
            getScanActivity().showImageCropFragment()
        }
    }

    override fun scanSurfaceShowProgress() {
        showProgressBar()
    }

    override fun scanSurfaceHideProgress() {
        hideProgressBar()
    }

    override fun onError(error: DocumentScannerErrorModel) {
        if(isAdded) {
            getScanActivity().onError(error)
        }
    }

    override fun showFlashModeOn() {
        flashButton.setImageResource(R.drawable.zdc_flash_on)
    }

    override fun showFlashModeOff() {
        flashButton.setImageResource(R.drawable.zdc_flash_off)
    }
}

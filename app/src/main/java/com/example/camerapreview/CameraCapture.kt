package com.example.camerapreview

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.media.ImageReader
import android.os.Environment
import android.os.Handler
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Created by lyr on 2020/11/9 & content is 摄像头捕获
 */
class CameraCapture {

  private var mContext: Context? = null
  private var mHandler: Handler? = null
  private var mSurfaceTexture: SurfaceTexture? = null
  private var mImageReader: ImageReader? = null //接收预览的帧数据
  private var mSensorOrientation: Int? = null

  private var mCameraDevice: CameraDevice? = null
  private var mPreviewSurface: Surface? = null //设置预览输出的Surface
  private var mCameraCaptureSession: CameraCaptureSession? = null

  private val mSavePhotoExecutor: Executor = Executors.newSingleThreadExecutor()

  companion object {
    const val TAG = "CameraCapture"
  }

  constructor(context: Context) {
    this.mContext = context
  }

  fun openCamera(cameraManager: CameraManager, width: Int, height: Int, cameraId: String, cc: CameraCharacteristics, handler: Handler,
    surfaceTexture: SurfaceTexture?,
    imageReader: ImageReader?,
    sensorOrientation: Int?) {
    try {
      this.mHandler = handler
      this.mSurfaceTexture = surfaceTexture
      this.mImageReader = imageReader
      this.mSensorOrientation = sensorOrientation

      mSensorOrientation = cc.get(SENSOR_ORIENTATION)
      cc.get(SCALER_STREAM_CONFIGURATION_MAP)?.let {map ->

        getMostSuitableSize(map.getOutputSizes(SurfaceTexture::class.java), width, height)?.let {previewSize ->
          surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        }

        getMostSuitableSize(map.getOutputSizes(ImageReader::class.java), width, height)?.let {photoSize ->
          mImageReader = getImageReader(photoSize)
        }
      }

      cameraManager.openCamera(cameraId, mOpenCameraCallback, mHandler)
    } catch (e: CameraAccessException) {
      Log.d(TAG, "openCamera: cameraAccessException = $e")
      e.printStackTrace()
    } catch (e: SecurityException) {
      Log.d(TAG, "openCamera: please turn on camera permissions")
      e.printStackTrace()
    }
  }

  fun closeCamera() {
    mCameraDevice?.close()
    mCameraDevice = null

    mCameraCaptureSession?.close()
    mCameraCaptureSession = null

    mImageReader?.close()
    mImageReader = null
  }

  private val mOpenCameraCallback: CameraDevice.StateCallback = object: CameraDevice.StateCallback() {
    override fun onOpened(camera: CameraDevice) {
      Log.d(TAG, "onOpened: ")
      mCameraDevice = camera
      openCameraSession()
    }

    override fun onDisconnected(camera: CameraDevice) {
      Log.d(TAG, "onDisconnected: ")
      camera.close()
      mCameraDevice = null
    }

    override fun onError(camera: CameraDevice, error: Int) {
      Log.d(TAG, "onError: ")
      val msg = when(error) {
        ERROR_CAMERA_DEVICE -> "Fatal (device)"
        ERROR_CAMERA_DISABLED -> "Device policy"
        ERROR_CAMERA_IN_USE -> "Camera in use"
        ERROR_CAMERA_SERVICE -> "Fatal (service)"
        ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
        else -> "Unknown"
      }
      Log.d(TAG, "onError: $error cameraDevice = $camera msg = $msg")

      camera.close()
      mCameraDevice = null
    }
  }

  private fun openCameraSession() {
    mSurfaceTexture?.let {
      mPreviewSurface = Surface(it)
    }
    try {
      if (mPreviewSurface != null && mImageReader != null) {
        val outputs = listOf<Surface>(mPreviewSurface !!, mImageReader !!.surface)
        mCameraDevice?.createCaptureSession(outputs, mCreateSessionCallback, mHandler)
      }
    } catch (e: Exception) {
      Log.d(TAG, "createPreviewPipeline: e = $e ")
      e.printStackTrace()
    }
  }

  private val mCreateSessionCallback: CameraCaptureSession.StateCallback = object: CameraCaptureSession.StateCallback() {
    override fun onConfigured(session: CameraCaptureSession) {
      Log.d(TAG, "onConfigured: ")
      mCameraCaptureSession = session
      requestPreview(session)
    }

    override fun onConfigureFailed(session: CameraCaptureSession) {
      Log.d(TAG, "onConfigureFailed: ")
    }
  }

  private fun requestPreview(session: CameraCaptureSession) {
    if (mCameraDevice == null) return
    try {
      val builder = mCameraDevice !!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      builder.addTarget(mPreviewSurface !!)
      mImageReader?.let {builder.addTarget(it.surface)}
      builder.set(CaptureRequest.JPEG_ORIENTATION, mSensorOrientation ?: 0 + 180)
      session.setRepeatingRequest(builder.build(), null, null)
    } catch (e: CameraAccessException) {
      Log.e(TAG, "requestPreview failed", e)
    }
  }

  fun takePhoto() {
    if (mCameraDevice == null) return
    try {
      val builder = mCameraDevice !!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
      builder.addTarget(mPreviewSurface !!)
      builder.addTarget(mImageReader !!.surface)
      builder.set(CaptureRequest.JPEG_ORIENTATION, mSensorOrientation)
      mCameraCaptureSession !!.capture(builder.build(), null, null)
    } catch (e: CameraAccessException) {
      Log.d(TAG, "takePhoto failed", e)
    }
  }

  private fun getImageReader(size: Size): ImageReader? {
    val imageReader = ImageReader.newInstance(
      size.width,
      size.height,
      ImageFormat.YUV_420_888,
      5)
    imageReader.setOnImageAvailableListener(mOnImageAvailableListener, mHandler)
    return imageReader
  }

  private val mOnImageAvailableListener = ImageReader.OnImageAvailableListener {reader -> savePhoto(reader)}


  private fun getMostSuitableSize(
    sizes: Array<Size>,
    width: Int,
    height: Int): Size? {
    val targetRatio = height / width.toFloat()
    var result: Size? = null
    for (size in sizes) {
      if (result == null || isMoreSuitable(result, size, targetRatio)) {
        result = size
      }
    }
    return result
  }

  private fun isMoreSuitable(current: Size?, target: Size, targetRatio: Float): Boolean {
    if (current == null) {
      return true
    }
    val dRatioTarget: Float = Math.abs(targetRatio - getRatio(target))
    val dRatioCurrent: Float = Math.abs(targetRatio - getRatio(current))
    return (dRatioTarget < dRatioCurrent
        || dRatioTarget == dRatioCurrent && getArea(target) > getArea(current))
  }

  private fun getArea(size: Size): Int {
    return size.width * size.height
  }

  private fun getRatio(size: Size): Float {
    return size.width.toFloat() / size.height
  }

  private fun savePhoto(reader: ImageReader) {
    val image = reader.acquireNextImage() ?: return
    val filename = "IMG_${System.currentTimeMillis()}.jpeg"
    Toast.makeText(mContext, filename, Toast.LENGTH_SHORT).show()
    mSavePhotoExecutor.execute {
      val file = File(Environment.getExternalStorageDirectory(), filename)
      val buffer = image.planes[0].buffer
      val bytes = ByteArray(buffer.remaining())
      Log.d(TAG, "savePhoto: buffer data = $bytes")
      try {
        FileOutputStream(file).write(bytes)
      } catch (e: IOException) {
        Log.d(TAG, "save photo failed", e)
      }
      image.close()
    }
  }
}
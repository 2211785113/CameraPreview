package com.example.camerapreview

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.afollestad.assent.Permission
import com.afollestad.assent.askForPermissions
import com.afollestad.assent.isAllGranted
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity: AppCompatActivity() {

  private var mCameraCapture: CameraCapture = CameraCapture(this)

  private lateinit var mCameraManager: CameraManager
  private var mCameraId: String = ""
  private lateinit var cc: CameraCharacteristics
  private var systemFacing: Int? = null
  private var currentFacing: Int? = null

  private var mSurfaceTexture: SurfaceTexture? = null
  private var mSensorOrientation: Int? = 0
  private var mImageReader: ImageReader? = null //接收预览的帧数据

  private val mCameraThread = HandlerThread("mCameraThread").apply {start()}
  private val mCameraHandler = Handler(mCameraThread.looper)

  private var mIsRunning: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    initWidgets()

    currentFacing = LENS_FACING_BACK
    initCamera(currentFacing ?: 1)
  }

  private fun initWidgets() {
    var drawable = getDrawable(R.drawable.vector_drawable_switch)
    drawable !!.setBounds(0, 0, 40, 40)
    camera_direction.setCompoundDrawables(null, null, drawable, null)

    camera_switch.setOnCheckedChangeListener {buttonView, isChecked ->
      if (isChecked) {
        onOpenOperate()
      } else {
        onCloseOperate()
      }
    }

    camera_direction.setOnClickListener {
      currentFacing = when(currentFacing) {
        LENS_FACING_BACK -> {
          LENS_FACING_FRONT
        }
        LENS_FACING_FRONT -> {
          LENS_FACING_BACK
        }
        else -> LENS_FACING_BACK
      }
      switchCamera(currentFacing !!)
    }

    btn_take.setOnClickListener {
      if (isAllGranted(Permission.CAMERA)) {
        mCameraCapture.takePhoto()
      }
    }
  }

  private fun switchCamera(currentFacing: Int) {
    mCameraId = mCameraManager.cameraIdList.filter {cameraId ->
      mCameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING) == currentFacing
    }[0]

    onCloseOperate()
    reOpenCamera()
  }

  private fun reOpenCamera() {
    if (textureView.isAvailable) {
      onOpenOperate()
    } else {
      textureView.surfaceTextureListener = PreviewSurfaceTextureListener()
    }
  }

  private fun onOpenOperate() {
    mIsRunning = true
    textureView.visibility = View.VISIBLE

    mCameraCapture.openCamera(mCameraManager, textureView.width, textureView.height, mCameraId, cc, mCameraHandler, mSurfaceTexture, mImageReader,
      mSensorOrientation)
  }

  private fun onCloseOperate() {
    mIsRunning = false
    mCameraCapture.closeCamera()

    textureView.visibility = View.GONE
    rl_textureView.setBackgroundColor(Color.BLACK)
    rl_textureView.background = getDrawable(R.drawable.camera_forbidden)
  }

  private fun initCamera(facing: Int) {
    try {
      mCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
      mCameraManager.cameraIdList.map nn@{cameraId ->

        cc = mCameraManager.getCameraCharacteristics(cameraId)
        systemFacing = cc.get(CameraCharacteristics.LENS_FACING)
        if (systemFacing == facing) {
          mCameraId = cameraId
          return@nn
        }
      }

      Log.d(localClassName, "initCamera: cameraId = $mCameraId")
      initConfiguration()

    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun initConfiguration() {
    var level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
    if (level == null || level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      Toast.makeText(this, "您的手机不支持Camera2的高级特性", Toast.LENGTH_SHORT).show()
      return
    }

    checkPermissions()
  }

  private fun checkPermissions() {
    if (! isAllGranted(Permission.CAMERA)) {
      askForPermissions(Permission.CAMERA) {
        if (isAllGranted(Permission.CAMERA))
          initView()
      }
    } else {
      initView()
    }
  }

  private fun initView() {

    if (! StreamConfigurationMap.isOutputSupportedFor(SurfaceTexture::class.java)) {
      Toast.makeText(this, "相机不支持SurfaceTexture类型", Toast.LENGTH_SHORT).show()
      return
    }

    textureView.surfaceTextureListener = PreviewSurfaceTextureListener()

  }

  private inner class PreviewSurfaceTextureListener: TextureView.SurfaceTextureListener {

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
      mSurfaceTexture = surface
      onOpenOperate()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
      return false
    }
  }

  override fun onPause() {
    super.onPause()
    if (mIsRunning) {
      onCloseOperate()
    }
  }

  override fun onResume() {
    super.onResume()
    if (mSurfaceTexture != null && ! mIsRunning) {
      onOpenOperate()
    }
  }
}
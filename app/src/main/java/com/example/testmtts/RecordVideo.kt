package com.example.testmtts

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.activity_record_video.*
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class RecordVideo : AppCompatActivity() {

    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var videoCapture: VideoCapture<Recorder>
    private lateinit var recording: Recording
    private var camera: Camera? = null
    private var isRecording: Boolean = false
    // 总共录制多长时间(以秒为单位)
    private var recordTimeLimits: Long = 15
    private val serverIp: String = "192.168.100.8"
    private val serverPort: String = "5000"
    private val bassContext1: Context = this
    private lateinit var notice: NoticeSender
    private var tempVideoFileName: String = ""
    private lateinit var tempVideoFile: File
    // 存储所有的临时文件，方便关闭
    private var tempFileList: ArrayList<File> = ArrayList<File>()
    // 存储一下当前的activity
    private val activity: AppCompatActivity = this

    // 计时器
    private var timer = Timer()
    private var myTimerTask = myTimerTask(this)

    // 获取cameraProvider的东西
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record_video)

        notice = NoticeSender(this)

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // 获取cameraProvider用例
            cameraProvider = cameraProviderFuture.get()
            // 将previewView绑定给cameraProvider以显示相机的预览
            bindPreview(cameraProvider)

        }, ContextCompat.getMainExecutor(this))


        // 点击按钮后开始录制
        startRecord.setOnClickListener {
            try {
                if (isRecording) {
                    // 如果正在录制且按按钮就停止录制
                    recording.stop()
                } else {

                    val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

                    // Create MediaStoreOutputOptions for our recorder
                    // 设置视频的名字
                    val name = "CameraX-record"
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                    }
                    val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                        contentResolver,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    )
                        .setContentValues(contentValues)
                        .build()

                    tempVideoFile = getTempVideoFile("CameraX-record")
                    Log.d("fileName", tempVideoFileName)
                    if (!tempVideoFile.exists()) {
                        Toast.makeText(bassContext1, "临时文件创建失败，请重新录制", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val fileOutPut = FileOutputOptions.Builder(tempVideoFile).build()

                    // 开始录制，然后将文件输出到mediaStore里
                    this.recording = videoCapture.output
                        .prepareRecording(this, fileOutPut)
                        .start(ContextCompat.getMainExecutor(this)) { event: VideoRecordEvent ->
                            // 录制中的回调函数
                            when (event.getNameString()) {
                                "Started" -> {
                                    isRecording = true
                                    // 打开计时器
                                    myTimer.start()

                                    // 设置定时任务，定时关闭录制
                                    timer.schedule(myTimerTask, recordTimeLimits * 1000 + 1000)

                                    Toast.makeText(bassContext1, "开始录制", Toast.LENGTH_SHORT).show()
                                    // 发送录制通知
                                    notice.setNotification(
                                        bassContext1,
                                        "HeartDetact",
                                        "视频录制",
                                        NotificationManager.IMPORTANCE_DEFAULT,
                                        "视频录制中"
                                    )
                                }
                                "Finalized" -> {
                                    // 视频录制完毕
                                    recording.stop()
                                    myTimer.stop()
                                    // 重置计时器时间
                                    myTimer.base = SystemClock.elapsedRealtime();
                                    isRecording = false

                                    val finalizeEvent = event as VideoRecordEvent.Finalize
                                    val fileUri = finalizeEvent.outputResults.outputUri
//                                Log.d(
//                                    "VideoRecording",
//                                    "video Record complete, File saved in $fileUri"
//                                )
                                    Toast.makeText(bassContext1, "录制结束", Toast.LENGTH_SHORT).show()
                                    notice.setNotification(
                                        bassContext1,
                                        "HeartDetact",
                                        "视频录制",
                                        NotificationManager.IMPORTANCE_DEFAULT,
                                        "视频录制完成"
                                    )

                                    uploadVideo(fileUri)
                                    setResult(RESULT_OK, Intent().putExtra("fileUri", fileUri))
                                }
                                // 用于暂停当前的活跃录制。
                                "Paused" -> {
                                    isRecording = false
                                    myTimer.stop()
                                    recording.stop()
                                }
                                // 用于恢复已暂停的活跃录制。
                                "Resumed" -> {}
                            }
                        }
                }
            } finally {

            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 清除临时缓存的视频文件
        deleteAllTempFile()
    }

    // 点击录制按钮的回调
    private fun startVideoRecordCallback() {

    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder()
            .build()

        // 选择前置摄像头
        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        // 视频质量选择器，选择最高可以支持的分辨率
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )

        // 视频录制器，这里要传进一个excutor用作record运行的线程
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setExecutor(Executors.newSingleThreadExecutor())
            .build()
        // 录制接口
        this.videoCapture = VideoCapture.withOutput(recorder)

        // 相机绑定用例
        try {
            camera = cameraProvider.bindToLifecycle(
                this as LifecycleOwner,
                cameraSelector,
                preview,
                videoCapture
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }


    /**
     * A helper extended function to get the name(string) for the VideoRecordEvent.
     * 帮助获取当前录制器的状态
     */
    private fun VideoRecordEvent.getNameString(): String {
        return when (this) {
            is VideoRecordEvent.Status -> "Status"
            is VideoRecordEvent.Start -> "Started"
            is VideoRecordEvent.Finalize -> "Finalized"
            is VideoRecordEvent.Pause -> "Paused"
            is VideoRecordEvent.Resume -> "Resumed"
            else -> throw IllegalArgumentException("Unknown VideoRecordEvent: $this")
        }
    }

    /*
    * 视频上传
     */
    @SuppressLint("SuspiciousIndentation", "Recycle")
    private fun uploadVideo(videoUri: Uri?) {
        try {
            val ip: String = "$serverIp:$serverPort"
            Log.d("testInput", ip)

            // 获取视频文件的字节流通过Android Uri
            if (videoUri == null) {
                Toast.makeText(this, "视频文件选择出错", Toast.LENGTH_SHORT).show()
                return
            }
            println(videoUri.path)
//            val pfd: ParcelFileDescriptor? = this.contentResolver.openFileDescriptor(videoUri, "r")
//            val inputStream: InputStream?
//            if (pfd != null) {
//                inputStream = FileInputStream(pfd.fileDescriptor)
//            } else {
//                return
//            }

            val myVideoFIle: File = File(bassContext1.cacheDir, tempVideoFileName)
//        println(myVideoFIle.exists().toString() + "文件存在")

            val input: InputStream = FileInputStream(myVideoFIle)
//        val input: InputStream? = bassContext1.contentResolver.openInputStream(myVideoFIle.toUri())
//        println("输入流是否为空")

            val videoBody: RequestBody =
                RequestBody.create(MediaType.parse("video/mp4"), input.readBytes())

            input.close()

            // 设置超时时间
            val client: OkHttpClient = OkHttpClient().newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val retrofit: Retrofit = Retrofit.Builder()
                .baseUrl("http://${ip}")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val data: AppService = retrofit.create(AppService::class.java)

            val filePart: MultipartBody.Part =
                MultipartBody.Part.createFormData("file", tempVideoFileName, videoBody)

            val name = RequestBody.create(MediaType.parse("text/plain"), "file")

            val dataCall: Call<ReplyData<videoUploadReplyData>> = data.uploadVideo(name, filePart)

            // 发送请求
            dataCall.enqueue(object : Callback<ReplyData<videoUploadReplyData>> {
                @SuppressLint("SetTextI18n")
                override fun onResponse(
                    call: Call<ReplyData<videoUploadReplyData>>,
                    response: Response<ReplyData<videoUploadReplyData>>
                ) {
                    // 获取返回值
                    when (val body: ReplyData<videoUploadReplyData>? = response.body()) {
                        null -> return
                        else -> {
                            Log.d("sendRequest", "success to send Video")

                            // 测试提醒
                            Toast.makeText(bassContext1, "视频文件选择出错", Toast.LENGTH_SHORT).show()

                            // 获取心率和呼吸率
                            val heartRate = body.data?.HeartRate
                            val rR = body.data?.respirationRate
                            val path = body.data?.path

                            val rRRateView = activity.findViewById<TextView>(R.id.respirationRate)
                            if (rRRateView != null && path != null) {
                                rRRateView.text = "心率为: ${heartRate.toString()}, 呼吸率: ${rR.toString()}"
                            }

                            Log.d("心率和呼吸率", "心率: $heartRate   呼吸率: $rR,  路径: $path")
                            // 关闭文件流
//                            pfd.close()
                        }
                    }
                }

                override fun onFailure(call: Call<ReplyData<videoUploadReplyData>>, t: Throwable) {
                    Log.e("sendRequest", "failed to send Video")
                    Log.e("sendRequestFail", t.toString())
                }
            })
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } finally {

        }
    }

    // 删除所有临时缓存的文件
    private fun deleteAllTempFile() {
        for (file in tempFileList) {
            file.delete()
        }
    }

    // 方便管理录制的状态
    @SuppressLint("RestrictedApi")
    fun videoRecordUtil(action: String) {
        try {
            when (action) {
                "stop" -> {
                    if (isRecording) {
                        recording.stop()
                    } else {
                        throw IllegalStateException("视频还未开始录制")
                    }
                }
                else -> {
                    throw IllegalStateException("参数有误")
                }
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    // 获取文件的名字
    private fun getFileNameFromUri(context: Context?, uri: Uri?): String? {
        if (context == null || uri == null) return null
        val doc: DocumentFile = DocumentFile.fromSingleUri(context, uri) ?: return null
        return doc.name
    }

    /*
    * 创建视频临时文件
    * @param fileName 文件名字
     */
    private fun getTempVideoFile(fileName: String): File {
        val file = File.createTempFile(fileName, ".mp4", bassContext1.cacheDir)
        tempFileList.add(file)
        file.deleteOnExit()
        tempVideoFileName = file.name
        return File(bassContext1.cacheDir, tempVideoFileName)
    }

}
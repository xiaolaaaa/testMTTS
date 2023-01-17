package com.example.testmtts

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface AppService {
    /*
     * 视频上传api
     */
    @POST("uploadVideo")
    @Multipart
    fun uploadVideo(@Part("name") name: RequestBody, @Part video: MultipartBody.Part): Call<ReplyData<videoUploadReplyData>>
}
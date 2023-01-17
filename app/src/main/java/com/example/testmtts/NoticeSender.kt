package com.example.testmtts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class NoticeSender(
    private val context: Context,
    ) {
    @SuppressLint("ServiceCast")
    // 通知管理器
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // 发送通知
    public fun setNotification(context: Context, channelid: String, channelName: String, importance: Int, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             val channel = NotificationChannel(channelid, channelName, importance)
             manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelid)
             .setContentTitle(channelName)
             .setContentText(text)
             .setSmallIcon(R.drawable.ic_launcher_background)
            .build()

        manager.notify(1, notification)
    }
}
package com.example.testmtts

import android.content.Context
import java.util.TimerTask

class myTimerTask(context: RecordVideo): TimerTask() {
    private var context: RecordVideo = context
    override fun run() {
        context.videoRecordUtil("stop")
    }

    override fun cancel(): Boolean {
        return super.cancel()
    }
}
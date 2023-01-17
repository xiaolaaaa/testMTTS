package com.example.testmtts

data class ReplyData<T>(
    var code: Int? = null,
    var message: String,
    var data: T? = null
) {
}
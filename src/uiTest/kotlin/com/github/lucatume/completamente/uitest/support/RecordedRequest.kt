package com.github.lucatume.completamente.uitest.support

data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

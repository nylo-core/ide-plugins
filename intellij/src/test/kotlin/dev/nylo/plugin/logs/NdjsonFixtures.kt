package dev.nylo.plugin.logs

/** Shared NDJSON fixtures (the format `NyFileLogger` now writes, one JSON object per line). */
object NdjsonFixtures {

    /**
     * Two sessions:
     *  - `4lfu9t` (11:52:55): debug + error(+stack) + console — no network.
     *  - `9qbhab` (12:40:39): debug + net request/response/error — all on `/user`.
     */
    val TWO_SESSIONS: String = listOf(
        """{"t":"session","id":"2026-06-17T11-52-55-4lfu9t","started":"2026-06-17T11:52:55","platform":"ios Version 26.5 (Build 23F77)","app":"Pretalk 1.0.0","version":"nylo_framework v7.1.24","env":"developing"}""",
        """{"t":"log","ts":"2026-06-17T11:52:55","session":"4lfu9t","level":"debug","msg":"[AppProvider] setup in 217ms"}""",
        """{"t":"log","ts":"2026-06-17T11:52:55.100","session":"4lfu9t","level":"error","msg":"boom","stack":"#0      foo\n#1      bar"}""",
        """{"t":"console","ts":"2026-06-17T11:52:55.200","session":"4lfu9t","msg":"flutter: console output"}""",
        "",
        """{"t":"session","id":"2026-06-17T12-40-39-9qbhab","started":"2026-06-17T12:40:39","platform":"ios Version 26.5 (Build 23F77)","app":"Pretalk 1.0.0","version":"nylo_framework v7.1.24","env":"developing"}""",
        """{"t":"log","ts":"2026-06-17T12:40:39","session":"9qbhab","level":"debug","msg":"hello"}""",
        """{"t":"net","kind":"request","ts":"2026-06-17T12:40:39.010","session":"9qbhab","requestId":"096232f7","method":"GET","uri":"http://pretalk.test/api/v1/user"}""",
        """{"t":"net","kind":"response","ts":"2026-06-17T12:40:39.260","session":"9qbhab","requestId":"096232f7","method":"GET","uri":"http://pretalk.test/api/v1/user","statusCode":200,"statusMessage":"OK","responseTimeMs":250,"payloadSize":"1.2 KB","data":{"id":1,"name":"Ann"}}""",
        """{"t":"net","kind":"error","ts":"2026-06-17T12:40:40","session":"9qbhab","requestId":"5696bb06","method":"PUT","uri":"http://pretalk.test/api/v1/user","statusCode":500,"statusMessage":"Internal Server Error","responseTimeMs":12,"errorType":"DioExceptionType.badResponse","message":"server boom"}""",
    ).joinToString("\n")
}

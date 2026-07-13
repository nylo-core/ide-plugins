/** Shared NDJSON fixtures (the format `NyFileLogger` writes, one JSON object per line). */

/**
 * Two sessions:
 *  - `4lfu9t` (11:52:55): debug + error(+stack) + console — no network.
 *  - `9qbhab` (12:40:39): debug + net request/response/error — all on `/user`.
 */
/**
 * The *exact* NDJSON lines emitted by `nylo_support`'s `NyFileLogger` (captured from its Phase A
 * test run). Mirror of the Kotlin `LogParserFrameworkSampleTest` fixture — keep the two in sync;
 * they guard the schema contract shared with the framework repo.
 */
export const FRAMEWORK_SAMPLE: string = [
  '{"t":"session","id":"2026-06-26T14-17-24-idrxkz","started":"2026-06-26T14:17:24.223296","platform":"macos Version 26.5.1 (Build 25F80)","app":"Pretalk 1.0.0","version":"nylo_framework v7.1.24","env":"developing"}',
  '{"t":"log","ts":"2026-06-26T10:32:59.123","session":"idrxkz","level":"debug","msg":"[AppProvider] Booting"}',
  '{"t":"log","ts":"2026-06-26T10:33:00.001","session":"idrxkz","level":"error","msg":"Failed to load user","context":{"id":123,"retry":true},"stack":"#0 main (file.dart:1:1)\\n#1 x (y:2:2)"}',
  '{"t":"console","ts":"2026-06-26T14:17:24.229440","session":"idrxkz","msg":"flutter: raw console line"}',
  '{"t":"net","session":"idrxkz","ts":"2026-06-26T14:17:24.238011","kind":"request","requestId":"236f0833","method":"PUT","uri":"https://api.example.com/users/5","headers":{},"contentType":null,"responseType":"ResponseType.json","body":{"name":"Ada"}}',
  '{"t":"net","session":"idrxkz","ts":"2026-06-26T14:17:24.300000","kind":"response","requestId":"236f0833","method":"PUT","uri":"https://api.example.com/users/5","statusCode":200,"statusMessage":"OK","responseTimeMs":5,"payloadSizeBytes":18,"payloadSize":"18 B","data":{"id":5,"name":"Ada"}}',
  '{"t":"net","session":"idrxkz","ts":"2026-06-26T14:17:24.400000","kind":"error","requestId":"236f0833","errorType":"DioExceptionType.badResponse","method":"PUT","uri":"https://api.example.com/users/5","responseTimeMs":7,"payloadSizeBytes":15,"payloadSize":"15 B","statusCode":500,"statusMessage":"Server Error","data":{"error":"kaboom"},"message":"Http status error [500]"}',
].join('\n');

export const TWO_SESSIONS: string = [
  '{"t":"session","id":"2026-06-17T11-52-55-4lfu9t","started":"2026-06-17T11:52:55","platform":"ios Version 26.5 (Build 23F77)","app":"Pretalk 1.0.0","version":"nylo_framework v7.1.24","env":"developing"}',
  '{"t":"log","ts":"2026-06-17T11:52:55","session":"4lfu9t","level":"debug","msg":"[AppProvider] setup in 217ms"}',
  '{"t":"log","ts":"2026-06-17T11:52:55.100","session":"4lfu9t","level":"error","msg":"boom","stack":"#0      foo\\n#1      bar"}',
  '{"t":"console","ts":"2026-06-17T11:52:55.200","session":"4lfu9t","msg":"flutter: console output"}',
  '',
  '{"t":"session","id":"2026-06-17T12-40-39-9qbhab","started":"2026-06-17T12:40:39","platform":"ios Version 26.5 (Build 23F77)","app":"Pretalk 1.0.0","version":"nylo_framework v7.1.24","env":"developing"}',
  '{"t":"log","ts":"2026-06-17T12:40:39","session":"9qbhab","level":"debug","msg":"hello"}',
  '{"t":"net","kind":"request","ts":"2026-06-17T12:40:39.010","session":"9qbhab","requestId":"096232f7","method":"GET","uri":"http://pretalk.test/api/v1/user"}',
  '{"t":"net","kind":"response","ts":"2026-06-17T12:40:39.260","session":"9qbhab","requestId":"096232f7","method":"GET","uri":"http://pretalk.test/api/v1/user","statusCode":200,"statusMessage":"OK","responseTimeMs":250,"payloadSize":"1.2 KB","data":{"id":1,"name":"Ann"}}',
  '{"t":"net","kind":"error","ts":"2026-06-17T12:40:40","session":"9qbhab","requestId":"5696bb06","method":"PUT","uri":"http://pretalk.test/api/v1/user","statusCode":500,"statusMessage":"Internal Server Error","responseTimeMs":12,"errorType":"DioExceptionType.badResponse","message":"server boom"}',
].join('\n');

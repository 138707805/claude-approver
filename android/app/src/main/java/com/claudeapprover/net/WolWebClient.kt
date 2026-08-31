package com.claudeapprover.net

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SK브로드밴드 GW-HF611R 공유기에 내장된 WOL 웹 기능(포트 8079)을 그대로 흉내 낸다.
 * 이 공유기의 로그인 화면은 자바스크립트로 아이디/비번을 "암호화"해서 보내는데,
 * 실제로 열어보면 키(SK-BROADBAND-AAAA-1234-CCCC-5678)와 IV(SKBB-abcd-543210)가
 * 코드에 고정돼 있어서(진짜 무작위가 아님) 여기서 그대로 재현할 수 있다.
 *
 * 로그인에 성공하면 별도 세션 쿠키를 서버가 내려주지 않고, 클라이언트가 만든
 * PRVN0UWNJCW 쿠키 값(UUID) 자체를 서버가 세션 키로 인정한다 — 그래서 로그인과
 * "PC 켜기" 요청에 반드시 같은 UUID를 실어 보내야 한다.
 *
 * 직접 매직 패킷을 만들어 브로드캐스트 주소로 쏘는 방식(예전 구현)은 공유기가
 * 브로드캐스트 대상 포트포워딩을 막아둔 경우 아예 동작하지 않을 위험이 있어서,
 * 공유기가 자체적으로 매직 패킷을 만들어 보내주는 이 내장 기능으로 대체했다.
 */
object WolWebClient {

    private const val KEY_STR = "SK-BROADBAND-AAAA-1234-CCCC-5678"
    private const val IV_STR = "SKBB-abcd-543210"
    private const val TIMEOUT_MS = 8000

    fun wake(host: String, port: Int, username: String, password: String, macIndex: Int = 0): Result<Unit> {
        return try {
            val base = "http://$host:$port"
            val cookie = login(base, username, password)

            val bootResp = postForm("$base/boaform/special_wol", "common_action=boot&common_query=$macIndex,", cookie)
            if (!bootResp.contains("code_succ")) {
                return Result.failure(Exception("켜기 요청 실패: $bootResp"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * PC가 지금 와이파이에 붙어있는지(=켜져 있거나 최소한 절전 중에도 응답 가능한
     * 상태인지) 확인한다. 공유기의 WOL 페이지가 "현재 연결된 기기 목록"을 이미
     * `localhosts` 라는 이름으로 내려주고 있어서, 그 안에 이 MAC이 있는지만 보면
     * 된다 — WOL 등록 목록(`entry_list`)에는 항상 있으므로 그것과 헷갈리면 안 된다.
     */
    fun checkOnline(host: String, port: Int, username: String, password: String, mac: String): Result<Boolean> {
        return try {
            val base = "http://$host:$port"
            val cookie = login(base, username, password)

            val homeHtml = getPage("$base/basic_home.html", cookie)
            val localhostsBlock = Regex("var localhosts\\s*=\\s*\\[(.*?)];", RegexOption.DOT_MATCHES_ALL)
                .find(homeHtml)?.groupValues?.get(1) ?: ""
            Result.success(localhostsBlock.contains(mac, ignoreCase = true))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 로그인하고 세션으로 쓸 쿠키 문자열을 반환한다. 실패하면 예외를 던진다. */
    private fun login(base: String, username: String, password: String): String {
        val uuid = UUID.randomUUID().toString()
        val cookie = "PRVN0UWNJCW=$uuid"

        val tmp = "id=${urlEncode(encryptHex(username))}" +
            "&passwd=${urlEncode(encryptHex(password))}" +
            "&uuid=$uuid"
        val data = Base64.encodeToString(tmp.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)

        val loginResp = postForm("$base/boaform/login", "data=${urlEncode(data)}", cookie)
        if (!loginResp.contains("code_succ_login")) {
            throw Exception("로그인 실패 (아이디/비밀번호를 확인하세요): $loginResp")
        }
        return cookie
    }

    private fun encryptHex(plain: String): String {
        val key = SecretKeySpec(KEY_STR.toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec(IV_STR.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun postForm(urlStr: String, body: String, cookie: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun getPage(urlStr: String, cookie: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Cookie", cookie)
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

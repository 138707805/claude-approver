package com.claudeapprover.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN 매직 패킷 전송. 완전히 꺼진 PC는 ntfy 등 어떤 앱 서버도 듣고 있지
 * 않으므로, 공유기가 포트포워딩해둔 주소로 이 패킷을 직접 쏘는 것 말고는 방법이
 * 없다. 반드시 백그라운드 스레드에서 호출할 것 — 메인 스레드에서 부르면
 * NetworkOnMainThreadException.
 */
object WolSender {

    fun send(host: String, port: Int, mac: String): Result<Unit> = try {
        val macBytes = parseMac(mac)
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(packet, packet.size, address, port))
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    // "AA:BB:CC:DD:EE:FF", "AA-BB-CC-DD-EE-FF", 구분자 없는 형태 전부 허용.
    private fun parseMac(mac: String): ByteArray {
        val hex = mac.trim().replace(Regex("[^0-9A-Fa-f]"), "")
        require(hex.length == 12) { "MAC 주소 형식이 올바르지 않습니다 (예: AA:BB:CC:DD:EE:FF)" }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

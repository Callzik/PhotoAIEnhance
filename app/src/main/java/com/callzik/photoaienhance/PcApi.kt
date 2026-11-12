package com.callzik.photoaienhance

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** All traffic stays inside the Tailscale encrypted private network. */
object PcApi {
    private const val MAX_UPLOAD = 40 * 1024 * 1024
    private const val MAX_DOWNLOAD = 65 * 1024 * 1024

    fun validateAddress(value: String): String {
        val str = value.trim().removeSuffix("/")
        val address = URI(str)
        require(address.scheme == "http" && address.port == 8765 && address.rawUserInfo == null) {
            "Нужен адрес вида http://100.x.y.z:8765"
        }
        require(address.rawQuery == null && (address.rawPath.isNullOrEmpty() || address.rawPath == "/")) {
            "Адрес должен содержать только IP и порт 8765"
        }
        val host = address.host ?: throw IllegalArgumentException("Укажи IP Tailscale")
        val octets = host.split(".").map { it.toIntOrNull() }
        require(octets.size == 4 && octets.all { it != null && it in 0..255 } &&
                octets[0] == 100 && octets[1]!! in 64..127) {
            "Для безопасности нужен Tailscale IP (100.64.0.0–100.127.255.255)"
        }
        return str
    }

    fun test(address: String, token: String): String {
        val connection = createConnection(validateAddress(address) + "/health", token, "GET")
        try {
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException(readError(connection, code))
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun enhance(address: String, token: String, original: ByteArray): ByteArray {
        require(original.size in 1..MAX_UPLOAD) { "Фото больше 40 МБ, обработка не поддерживается" }
        val connection = createConnection(validateAddress(address) + "/enhance", token, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setFixedLengthStreamingMode(original.size)
        try {
            connection.outputStream.use { it.write(original) }
            val code = connection.responseCode
            if (code != 200) throw IllegalStateException(readError(connection, code))
            require(connection.contentType?.startsWith("image/jpeg") == true) {
                "Сервер вернул неожиданный формат"
            }
            val declared = connection.contentLengthLong
            require(declared <= MAX_DOWNLOAD) { "Результат слишком большой" }
            val received = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { stream ->
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    require(received.size() + count <= MAX_DOWNLOAD) { "Результат слишком большой" }
                    received.write(buffer, 0, count)
                }
            }
            val data = received.toByteArray()
            require(data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
                "Получен повреждённый JPEG"
            }
            return data
        } finally {
            connection.disconnect()
        }
    }

    private fun createConnection(url: String, token: String, method: String): HttpURLConnection {
        require(token.length >= 24) { "Введи ключ подключения, показанный на ПК" }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 480_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("X-PhotoAI-Token", token)
        connection.setRequestProperty("Accept", "image/jpeg, text/plain")
        return connection
    }

    private fun readError(c: HttpURLConnection, code: Int): String {
        val message = c.errorStream?.use { stream ->
            stream.readBytes().take(1024).toByteArray().toString(Charsets.UTF_8)
        } ?: "HTTP $code"
        return "Сервер: $message"
    }
}

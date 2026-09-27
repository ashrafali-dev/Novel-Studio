package com.ashraf.novelstudio

import java.security.MessageDigest

object WebtoonHash {
    fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun key(vararg parts: String): String =
        sha256(parts.joinToString("").toByteArray(Charsets.UTF_8))
}

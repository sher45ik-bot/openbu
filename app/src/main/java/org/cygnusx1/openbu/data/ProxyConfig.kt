package org.cygnusx1.openbu.data

data class ProxyConfig(
    val host: String,
    val port: Int = 1080,
    val username: String,
    val password: String,
)

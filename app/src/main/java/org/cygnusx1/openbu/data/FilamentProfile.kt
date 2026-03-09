package org.cygnusx1.openbu.data

data class FilamentProfile(
    val filamentId: String,
    val settingId: String,
    val name: String,
    val type: String,
    val nozzleTempMin: Int,
    val nozzleTempMax: Int,
)

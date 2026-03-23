package org.cygnusx1.openbu.network

data class PrintableObject(
    val identifyId: Int,
    val name: String,
    val centerX: Float?,  // mm coordinates from bbox
    val centerY: Float?,  // mm coordinates from bbox
    val imgX: Float?,     // normalized image X (0-1)
    val imgY: Float?,     // normalized image Y (0-1)
)

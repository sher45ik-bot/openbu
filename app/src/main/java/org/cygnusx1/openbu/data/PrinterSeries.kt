package org.cygnusx1.openbu.data

enum class PrinterSeries(
    val isEnclosed: Boolean,
    val usesMjpegCamera: Boolean,
) {
    P1(isEnclosed = true, usesMjpegCamera = true),
    P2(isEnclosed = true, usesMjpegCamera = false),
    A1(isEnclosed = false, usesMjpegCamera = true),
    H2(isEnclosed = true, usesMjpegCamera = false),
    X1(isEnclosed = true, usesMjpegCamera = false),
    UNKNOWN(isEnclosed = true, usesMjpegCamera = false),
}

private val SERIAL_PREFIX_MAP = mapOf(
    "01S" to PrinterSeries.P1,
    "01P" to PrinterSeries.P1,
    "22E" to PrinterSeries.P2,
    "030" to PrinterSeries.A1,
    "039" to PrinterSeries.A1,
    "31B" to PrinterSeries.H2,
    "094" to PrinterSeries.H2,
    "239" to PrinterSeries.H2,
    "093" to PrinterSeries.H2,
    "00M" to PrinterSeries.X1,
    "03W" to PrinterSeries.X1,
)

fun printerSeriesFromSerial(serial: String): PrinterSeries =
    SERIAL_PREFIX_MAP.entries.firstOrNull { serial.startsWith(it.key) }?.value
        ?: PrinterSeries.UNKNOWN

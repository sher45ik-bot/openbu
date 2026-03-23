package org.cygnusx1.openbu.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile

object ThreeMfParser {
    private const val TAG = "ThreeMfParser"

    data class SliceObject(val identifyId: Int, val name: String)

    data class ParseResult(
        val objects: List<PrintableObject>,
        val plateImage: Bitmap?,
    )

    /**
     * Parses a gcode.3mf file and returns printable objects with image coordinates,
     * plus the plate thumbnail image for display.
     *
     * The pick_N.png encodes each object's identify_id into RGB:
     *   R = id & 0xFF, G = (id >> 8) & 0xFF, B = (id >> 16) & 0xFF
     * By scanning this image we find each object's centroid in pixel space.
     */
    fun extractObjects(tempFile: File): ParseResult {
        Log.d(TAG, "extractObjects: 3MF file size=${tempFile.length()} bytes")

        val zipFile = ZipFile(tempFile)
        val entryNames = zipFile.entries().asSequence().map { it.name }.toList()
        Log.d(TAG, "extractObjects: zip contains ${entryNames.size} entries")

        fun readEntry(name: String): ByteArray? {
            val entry = zipFile.getEntry(name) ?: return null
            return zipFile.getInputStream(entry).use { it.readBytes() }
        }

        // Find plate index from gcode entries (e.g. "Metadata/plate_6.gcode" → 6)
        val gcodeEntries = entryNames.filter { it.startsWith("Metadata/plate_") && it.endsWith(".gcode") }
        Log.d(TAG, "extractObjects: gcode entries found: $gcodeEntries")

        val plateIndex = gcodeEntries.firstOrNull()?.let { name ->
            Regex("""plate_(\d+)\.gcode""").find(name)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (plateIndex == null) {
            Log.w(TAG, "No plate gcode found in 3MF archive")
            zipFile.close()
            return ParseResult(emptyList(), null)
        }
        Log.d(TAG, "extractObjects: active plate index=$plateIndex")

        val sliceInfoBytes = readEntry("Metadata/slice_info.config")
        if (sliceInfoBytes == null) {
            Log.w(TAG, "slice_info.config not found in 3MF")
            zipFile.close()
            return ParseResult(emptyList(), null)
        }

        // Parse slice_info.config for the active plate's objects
        val sliceObjects = parseSliceInfo(String(sliceInfoBytes), plateIndex)
        Log.d(TAG, "extractObjects: parsed ${sliceObjects.size} objects for plate $plateIndex: $sliceObjects")
        if (sliceObjects.isEmpty()) {
            Log.w(TAG, "No objects found for plate $plateIndex in slice_info.config")
            zipFile.close()
            return ParseResult(emptyList(), null)
        }

        // Parse plate_N.json for bounding box coordinates (mm)
        val plateJsonBytes = readEntry("Metadata/plate_$plateIndex.json")
        val bboxCoordinates = if (plateJsonBytes != null) {
            parseBboxCoordinates(String(plateJsonBytes), sliceObjects)
        } else {
            emptyMap()
        }

        // Load plate thumbnail image
        val topImageBytes = readEntry("Metadata/top_$plateIndex.png")
        val plateImage = if (topImageBytes != null) {
            BitmapFactory.decodeByteArray(topImageBytes, 0, topImageBytes.size)
        } else {
            Log.w(TAG, "top_$plateIndex.png not found")
            null
        }

        // Load pick image and compute centroids
        val pickImageBytes = readEntry("Metadata/pick_$plateIndex.png")
        val imgCentroids = if (pickImageBytes != null) {
            computeCentroidsFromPickImage(pickImageBytes, sliceObjects)
        } else {
            Log.w(TAG, "pick_$plateIndex.png not found, no image coordinates")
            emptyMap()
        }

        zipFile.close()

        val objects = sliceObjects.map { obj ->
            val bboxCoord = bboxCoordinates[obj.identifyId]
            val imgCoord = imgCentroids[obj.identifyId]
            PrintableObject(
                identifyId = obj.identifyId,
                name = obj.name,
                centerX = bboxCoord?.first,
                centerY = bboxCoord?.second,
                imgX = imgCoord?.first,
                imgY = imgCoord?.second,
            )
        }

        Log.d(TAG, "extractObjects: returning ${objects.size} objects")
        return ParseResult(objects, plateImage)
    }

    /**
     * Decodes pick_N.png to find centroid of each object.
     * Color encoding: R = id & 0xFF, G = (id >> 8) & 0xFF, B = (id >> 16) & 0xFF.
     * Returns identify_id → (normalizedX, normalizedY) where 0-1 maps to image dimensions.
     */
    private fun computeCentroidsFromPickImage(
        pngBytes: ByteArray,
        sliceObjects: List<SliceObject>,
    ): Map<Int, Pair<Float, Float>> {
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return emptyMap()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        val knownIds = sliceObjects.map { it.identifyId }.toSet()

        // Accumulate pixel positions per identify_id
        data class Accumulator(var sumX: Long = 0, var sumY: Long = 0, var count: Int = 0)
        val accumulators = mutableMapOf<Int, Accumulator>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pixel = pixels[y * w + x]
                val a = (pixel shr 24) and 0xFF
                if (a == 0) continue
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r == 0 && g == 0 && b == 0) continue
                if (r == 255 && g == 255 && b == 255) continue

                val objId = r or (g shl 8) or (b shl 16)
                if (objId in knownIds) {
                    accumulators.getOrPut(objId) { Accumulator() }.let {
                        it.sumX += x
                        it.sumY += y
                        it.count++
                    }
                }
            }
        }

        val result = mutableMapOf<Int, Pair<Float, Float>>()
        for ((objId, acc) in accumulators) {
            if (acc.count > 0) {
                val normX = (acc.sumX.toFloat() / acc.count) / w
                val normY = (acc.sumY.toFloat() / acc.count) / h
                result[objId] = Pair(normX, normY)
                Log.d(TAG, "pick centroid: id=$objId pixels=${acc.count} pos=(${normX * w}, ${normY * h}) norm=($normX, $normY)")
            }
        }
        return result
    }

    private fun parseSliceInfo(xml: String, targetPlateIndex: Int): List<SliceObject> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var inPlate = false
        var currentPlateIndex: Int? = null
        val objects = mutableListOf<SliceObject>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "plate" -> {
                            inPlate = true
                            currentPlateIndex = null
                            objects.clear()
                        }
                        "metadata" -> {
                            if (inPlate) {
                                val key = parser.getAttributeValue(null, "key")
                                val value = parser.getAttributeValue(null, "value")
                                if (key == "index" && value != null) {
                                    currentPlateIndex = value.toIntOrNull()
                                }
                            }
                        }
                        "object" -> {
                            if (inPlate) {
                                val skipped = parser.getAttributeValue(null, "skipped")
                                if (skipped != "true") {
                                    val identifyId = parser.getAttributeValue(null, "identify_id")?.toIntOrNull()
                                    val name = parser.getAttributeValue(null, "name") ?: ""
                                    if (identifyId != null) {
                                        objects.add(SliceObject(identifyId, name))
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "plate" && inPlate) {
                        if (currentPlateIndex == targetPlateIndex) {
                            return objects.toList()
                        }
                        inPlate = false
                    }
                }
            }
            eventType = parser.next()
        }
        return emptyList()
    }

    private fun parseBboxCoordinates(
        json: String,
        sliceObjects: List<SliceObject>,
    ): Map<Int, Pair<Float, Float>> {
        val root = JSONObject(json)
        val bboxObjects = root.optJSONArray("bbox_objects") ?: return emptyMap()

        val nameToBboxes = mutableMapOf<String, MutableList<Pair<Float, Float>>>()
        for (i in 0 until bboxObjects.length()) {
            val obj = bboxObjects.getJSONObject(i)
            val name = obj.optString("name", "")
            val bboxArray = obj.optJSONArray("bbox")
            if (bboxArray != null && bboxArray.length() >= 4) {
                val xMin = bboxArray.getDouble(0).toFloat()
                val yMin = bboxArray.getDouble(1).toFloat()
                val xMax = bboxArray.getDouble(2).toFloat()
                val yMax = bboxArray.getDouble(3).toFloat()
                val centerX = (xMin + xMax) / 2f
                val centerY = (yMin + yMax) / 2f
                nameToBboxes.getOrPut(name) { mutableListOf() }.add(Pair(centerX, centerY))
            }
        }

        val result = mutableMapOf<Int, Pair<Float, Float>>()
        val nameUsageIndex = mutableMapOf<String, Int>()
        for (obj in sliceObjects) {
            val bboxes = nameToBboxes[obj.name] ?: continue
            val idx = nameUsageIndex.getOrPut(obj.name) { 0 }
            if (idx < bboxes.size) {
                result[obj.identifyId] = bboxes[idx]
                nameUsageIndex[obj.name] = idx + 1
            }
        }

        return result
    }
}

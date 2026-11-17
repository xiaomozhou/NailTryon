package com.example.nailtryon

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

internal enum class HandPhotoProblem(val message: String) {
    NAILS_REQUIRED("请换一张指甲清晰可见的手部照片"),
}

internal data class AlbumHandCheck(val problems: List<HandPhotoProblem>, val nails: NailDetection? = null) {
    val accepted get() = problems.isEmpty() && nails?.readyForAlignment == true
}

internal suspend fun inspectAlbumHand(context: Context, photo: Bitmap): AlbumHandCheck = withContext(Dispatchers.Default) {
    val nails = detectPhotoNails(context, photo)
    if (!nails.readyForAlignment) return@withContext AlbumHandCheck(listOf(HandPhotoProblem.NAILS_REQUIRED))
    fun model(name: String) = context.assets.open("models/$name").use { it.readBytes() }
    val hands = try {
        HandLandmarkDetector(model("palm_detection_full_Nx3x192x192.onnx"),
            model("hand_landmark_full_Nx3x224x224.onnx")).use { it.detect(photo) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The nail masks remain usable if the landmark model cannot identify the fingers.
        emptyList()
    }
    AlbumHandPolicy.inspect(hands, photo.width, photo.height, nails)
}

internal object AlbumHandPolicy {
    private data class Match(val finger: NailFinger, val nailIndex: Int, val distance: Float)
    private data class Assignment(val matches: List<Match>, val distance: Float)

    fun inspect(hands: List<HandObservation>, width: Int, height: Int, nails: NailDetection): AlbumHandCheck {
        if (nails.width != width || nails.height != height || !nails.readyForAlignment) {
            return AlbumHandCheck(listOf(HandPhotoProblem.NAILS_REQUIRED))
        }

        val regions = nails.usableRegions.sortedWith(compareBy({ it.center.x }, { it.center.y }))
        val hand = hands.singleOrNull()?.takeIf { it.presence >= .8f && it.points.size == 21 }
        val matches = mutableListOf<Match>()
        if (hand != null) {
            for ((finger, tipIndex) in NailFinger.entries.zip(listOf(4, 8, 12, 16, 20))) {
                val tip = hand.points[tipIndex]
                val dip = hand.points[tipIndex - 1]
                val dx = tip.x - dip.x
                val dy = tip.y - dip.y
                val length = hypot(dx, dy)
                if (!length.isFinite() || length < 1f) continue
                for ((index, nail) in regions.withIndex()) {
                    val x = nail.center.x - dip.x
                    val y = nail.center.y - dip.y
                    val along = (x * dx + y * dy) / (length * length)
                    val across = abs(x * dy - y * dx) / length
                    if (along in -.35f..1.45f && across <= length * .8f) {
                        matches += Match(finger, index, hypot(along - .7f, across / length))
                    }
                }
            }
        }
        // Track the cheapest unique assignment for every subset of the five fingers.
        // A nearby mask that can match two fingers should not take the only match from another.
        var assignments = mapOf(0 to Assignment(emptyList(), 0f))
        for (candidates in matches.groupBy { it.nailIndex }.values) {
            val next = assignments.toMutableMap()
            for ((used, assignment) in assignments) {
                for (match in candidates) {
                    val bit = 1 shl match.finger.ordinal
                    if ((used and bit) != 0) continue
                    val mask = used or bit
                    val distance = assignment.distance + match.distance
                    if (distance < (next[mask]?.distance ?: Float.POSITIVE_INFINITY)) {
                        next[mask] = Assignment(assignment.matches + match, distance)
                    }
                }
            }
            assignments = next
        }
        val assigned = assignments.values
            .minWith(compareByDescending<Assignment> { it.matches.size }.thenBy { it.distance })
            .matches.associate { it.nailIndex to it.finger }
        val labeled = regions.mapIndexed { index, nail ->
            val finger = assigned[index]
            val direction = if (finger != null && hand != null) {
                val tipIndex = listOf(4, 8, 12, 16, 20)[finger.ordinal]
                val tip = hand.points[tipIndex]
                val base = hand.points[tipIndex - 1]
                NailPoint(tip.x - base.x, tip.y - base.y)
            } else null
            nail.copy(finger = finger, tipDirection = direction)
        }
            .sortedBy { it.finger?.ordinal ?: NailFinger.entries.size }
        return AlbumHandCheck(emptyList(), nails.copy(regions = labeled))
    }
}

package com.andrerinas.openheadunit.aap.cluster

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

/**
 * The cluster video profile that makes the phone lay its cluster UI out in exactly the map area.
 *
 * Android Auto only streams a few fixed sizes, but the phone draws its UI inside the stream minus
 * the announced margins (split evenly on both sides). So the smallest stream that contains the area
 * is announced, with margins equal to the difference, and the sink crops the centre back out. The
 * picture is then 1:1 with the area: no stretching and no black bars.
 */
internal data class ClusterVideoGeometry(
    val resolution: VideoCodecResolutionType,
    val codecWidth: Int,
    val codecHeight: Int,
    val marginWidth: Int,
    val marginHeight: Int,
) {
    val contentWidth get() = codecWidth - marginWidth
    val contentHeight get() = codecHeight - marginHeight

    companion object {
        private val SIZES = listOf(
            Triple(VideoCodecResolutionType._800x480, 800, 480),
            Triple(VideoCodecResolutionType._1280x720, 1280, 720),
            Triple(VideoCodecResolutionType._1920x1080, 1920, 1080),
        )

        /** Null when the area is larger than every stream size; the caller keeps its own profile. */
        fun forArea(width: Int, height: Int): ClusterVideoGeometry? {
            val (type, w, h) = SIZES.firstOrNull { (_, w, h) -> width <= w && height <= h } ?: return null
            // Even margins keep the content exactly centred.
            val mw = (w - width) and 1.inv()
            val mh = (h - height) and 1.inv()
            return ClusterVideoGeometry(type, w, h, mw, mh)
        }
    }
}

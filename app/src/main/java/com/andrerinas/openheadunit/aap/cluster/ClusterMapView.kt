package com.andrerinas.openheadunit.aap.cluster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

/**
 * Shows the centre of the cluster stream - the area inside the announced margins - filling the
 * view 1:1. A TextureView rather than a SurfaceView, because only a view-backed surface can be
 * cropped by a transform.
 *
 * @param geometry the stream the phone was asked for; null shows the whole frame stretched.
 * @param onSurface receives the decoder surface when it is ready and null when it goes away.
 */
@SuppressLint("ViewConstructor")
internal class ClusterMapView(
    context: Context,
    private val geometry: ClusterVideoGeometry?,
    private val onSurface: (Surface?) -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var surface: Surface? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        geometry?.let { texture.setDefaultBufferSize(it.codecWidth, it.codecHeight) }
        applyCrop(width, height)
        surface = Surface(texture).also(onSurface)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
        applyCrop(width, height)

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        onSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}

    /** The frame is drawn stretched to the view; scaling it up about the centre crops the margins. */
    private fun applyCrop(width: Int, height: Int) {
        val g = geometry ?: return
        setTransform(Matrix().apply {
            setScale(
                g.codecWidth.toFloat() / g.contentWidth,
                g.codecHeight.toFloat() / g.contentHeight,
                width / 2f,
                height / 2f,
            )
        })
    }
}

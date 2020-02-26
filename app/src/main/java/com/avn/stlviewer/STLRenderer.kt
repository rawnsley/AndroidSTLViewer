package com.avn.stlviewer

import android.app.Activity
import android.opengl.*
import android.opengl.GLES31.*
import android.opengl.GLES32.glFramebufferTexture
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView


class STLRenderer(val activity : Activity, val surfaceView: SurfaceView) : SurfaceHolder.Callback {

    companion object {
        private val TAG = STLRenderer::class.java.simpleName

        // Number of texture buffers in the swap chain
        val FrameBufferCount = 2
    }

    // Qualcomm VR SDK wrapper
    val sxrManager = SXRManager(activity, surfaceView.holder.surface)

    // EGL context manager
    private lateinit var eglManager : EGLManager

    // Render textures (double buffered)

    private val frameBufferHandles = IntArray(FrameBufferCount)
    private val renderColorTextures = IntArray(FrameBufferCount)
    private val renderDepthTextures = IntArray(FrameBufferCount)

    // Choreographer is used to schedule new frames
    private val choreographer: Choreographer = Choreographer.getInstance()
    // Performs the rendering and schedules new frames
    private val frameScheduler = FrameCallback()

    private val projectionMatrix = FloatArray(16)

    private lateinit var stlModel : STLModel

    init {
        surfaceView.holder.addCallback(this)
    }

    fun resume() {
        Log.i(TAG, "resume")
        sxrManager.resume()
        choreographer.postFrameCallback(frameScheduler)
    }

    fun pause() {
        Log.i(TAG, "pause")
        choreographer.removeFrameCallback(frameScheduler)
        sxrManager.pause()
    }

    fun focusChanged(hasFocus : Boolean) {
        //TODO: Pause rendering
    }

    fun destroy() {
        // Stop the animation and any pending frames
        choreographer.removeFrameCallback(frameScheduler)
        sxrManager.destroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceCreated")

        eglManager = EGLManager(surfaceView)

        val ratio: Float = sxrManager.deviceInfo.targetEyeWidthPixels.toFloat() / sxrManager.deviceInfo.targetEyeHeightPixels.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 180f * sxrManager.deviceInfo.targetFovYRad / Math.PI.toFloat(), ratio, 0.1f, 1_000f)

        createSwapChain()

        stlModel = STLModel(activity)
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: format: $format, width: $width, height: $height")
        sxrManager.captureSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceDestroyed")
        sxrManager.releaseSurface()
    }


    fun createSwapChain() {

        val intArray = IntArray(1)

        // Setup render targets

        frameBufferHandles.indices.forEach { index ->

            // Color

            glGenTextures(1, intArray, 0)
            renderColorTextures[index] = intArray[0]
            glBindTexture(GL_TEXTURE_2D, renderColorTextures[index])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,  sxrManager.deviceInfo.targetEyeWidthPixels,  sxrManager.deviceInfo.targetEyeHeightPixels, 0, GL_RGBA, GL_UNSIGNED_BYTE, null )
            glBindTexture(GL_TEXTURE_2D, 0)
            OpenGLUtil.assertNoGlError()

            // Depth

            glGenTextures(1, intArray, 0)
            renderDepthTextures[index] = intArray[0]
            glBindTexture(GL_TEXTURE_2D, renderDepthTextures[index])
            glTexImage2D(GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT,  sxrManager.deviceInfo.targetEyeWidthPixels,  sxrManager.deviceInfo.targetEyeHeightPixels, 0, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT, null )
            glBindTexture(GL_TEXTURE_2D, 0)
            OpenGLUtil.assertNoGlError()

            // Framebuffer

            glGenFramebuffers(1, intArray, 0)
            frameBufferHandles[index] = intArray[0]
            glBindFramebuffer(GL_FRAMEBUFFER, frameBufferHandles[index])
            glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, renderColorTextures[index], 0)
            glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, renderDepthTextures[index], 0)
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw Exception("Failed to create framebuffer")
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0)

            OpenGLUtil.assertNoGlError()
        }
    }

    inner class FrameCallback : Choreographer.FrameCallback {

        // FPS Counters
        private var renderedFrames = 0
        private var lastReportTimeNanos = 0L

        var bufferIndex = 0

        override fun doFrame(frameTimeNanos: Long) {

            try {
                if (sxrManager.isReady) {

                    // Select alternating texture buffers
                    bufferIndex = ++bufferIndex % FrameBufferCount

                    glBindFramebuffer(GL_FRAMEBUFFER, frameBufferHandles[bufferIndex])

                    // Clear screen
                    glViewport(0, 0, sxrManager.deviceInfo.targetEyeWidthPixels,  sxrManager.deviceInfo.targetEyeHeightPixels)
                    glScissor(0, 0, sxrManager.deviceInfo.targetEyeWidthPixels,  sxrManager.deviceInfo.targetEyeHeightPixels)
                    glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

                    val headPoseState = sxrManager.startFrame(renderColorTextures[bufferIndex],  renderColorTextures[bufferIndex])
                    if(headPoseState.isValid()) {
                        // Get camera position from head pose
                        val viewMatrix = headPoseState.pose.rotation.quatToMatrix().queueInArray()

                        stlModel.render(headPoseState.expectedDisplayTimeNs, viewMatrix, projectionMatrix)
                    }
                    sxrManager.endFrame()

                    ++renderedFrames

                }

                // Report frame rate
                if (frameTimeNanos - lastReportTimeNanos > 1_000_000_000) {
                    Log.i(TAG, "FPS: $renderedFrames")
                    lastReportTimeNanos = frameTimeNanos
                    renderedFrames = 0
                }
            } finally {
                // Schedule the next frame
                choreographer.postFrameCallback(this)
            }
        }
    }

}
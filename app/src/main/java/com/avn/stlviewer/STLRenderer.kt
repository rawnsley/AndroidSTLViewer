package com.avn.stlviewer

import android.app.Activity
import android.opengl.*
import android.opengl.GLES31.*
import android.opengl.GLES32.glFramebufferTexture
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.avn.stlviewer.geometry.Vector3
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import kotlin.math.max


class STLRenderer(val activity : Activity, val surfaceView: SurfaceView) : SurfaceHolder.Callback {

    companion object {
        private val TAG = STLRenderer::class.java.simpleName

        // Small cube model
//        private val TestModelUrl = URL("https://upload.wikimedia.org/wikipedia/commons/3/36/3D_model_of_a_Cube.stl")
//        private const val TestModelSizeBytes = 684L

        // Large model from https://www.cc.gatech.edu/projects/large_models/blade.html
        // and converted to STL with MeshLab
        private val TestModelUrl = URL("https://data.avncloud.com/dev/blade.stl")
        private const val TestModelSizeBytes = 88269484L

        //TODO: WHAT VALUES TO USE HERE?
        val RenderTextureWidth = 1280
        val RenderTextureHeight = 1440
        val FrameBufferCount = 2
        val VerticalFoV = 90f
    }

    // Qualcomm VR SDK wrapper
    val sxrManager = SXRManager(activity, surfaceView.holder.surface)

    // EGL context manager
    private lateinit var eglManager : EGLManager

    // Render textures (double buffered)

    private val frameBufferHandles = IntArray(FrameBufferCount)
    private val renderColorTextures = IntArray(FrameBufferCount)
    private val renderDepthTextures = IntArray(FrameBufferCount)

    private var modelRenderProgram : Int = 0
    private var mvpMatrixHandle: Int = 0

    private var vao : Int = 0
    private var modelVertexBuffer : Int = 0
    private var modelIndexBuffer : Int = 0
    private var modelNormalBuffer : Int = 0
    private var modelColorBuffer : Int = 0
    private var modelTexCoordBuffer : Int = 0

    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private var modelVertexCount = 0
    private var modelIndexCount = 0

    private var pendingAsset : STLAsset? = null

    private var modelScale = 1f
    private var modelTranslation : Vector3 = Vector3(0f, 0f, 0f)

    // Choreographer is used to schedule new frames
    private val choreographer: Choreographer = Choreographer.getInstance()
    // Performs the rendering and schedules new frames
    private val frameScheduler = FrameCallback()

    init {
        surfaceView.holder.addCallback(this)
    }

    var pendingResume = false
    var pendingPause = false

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

    }

    fun destroy() {
        // Stop the animation and any pending frames
        choreographer.removeFrameCallback(frameScheduler)
        sxrManager.destroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceCreated")

        eglManager = EGLManager(surfaceView)

        loadOpenGL()
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: format: $format, width: $width, height: $height")
        sxrManager.captureSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceDestroyed")
        sxrManager.releaseSurface()
    }


    //RESUSE DEPTH?
    fun loadOpenGL() {

        glEnable(GL_DEPTH_TEST)
        glClearColor(0.5f, 0.5f, 0.5f, 1.0f)

        val intArray = IntArray(1)

        // Setup render targets

        frameBufferHandles.indices.forEach { index ->
            // Framebuffer
            glGenFramebuffers(1, intArray, 0)
            frameBufferHandles[index] = intArray[0]
            glBindFramebuffer(GL_FRAMEBUFFER, frameBufferHandles[index])

            // Color
            glGenTextures(1, intArray, 0)
            renderColorTextures[index] = intArray[0]
            glBindTexture(GL_TEXTURE_2D, renderColorTextures[index])

            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, RenderTextureWidth, RenderTextureHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null )
            glBindTexture(GL_TEXTURE_2D, 0)

            // Depth
            glGenRenderbuffers(1, intArray, 0)

            renderDepthTextures[index] = intArray[0]
            glBindRenderbuffer(GL_RENDERBUFFER, renderDepthTextures[index])
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, RenderTextureWidth, RenderTextureHeight)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, renderDepthTextures[index])
            glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, renderColorTextures[index], 0)
            glDrawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0), 0)

            if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw Exception("Failed to create framebuffer")
            }

            OpenGLUtil.assertNoGlError()
        }

        // Load model render program

        val vertShaderHandle = OpenGLUtil.compileShader(GL_VERTEX_SHADER, activity.assets.open("shader.vert").bufferedReader().use { it.readText() })
        val fragShaderHandle = OpenGLUtil.compileShader(GL_FRAGMENT_SHADER, activity.assets.open("shader.frag").bufferedReader().use { it.readText() })

        modelRenderProgram = glCreateProgram()
        glAttachShader(modelRenderProgram, vertShaderHandle)
        glAttachShader(modelRenderProgram, fragShaderHandle)
        OpenGLUtil.assertNoGlError()

        // Link program
        glLinkProgram(modelRenderProgram)
        OpenGLUtil.assertNoGlError()
        // Check for compilation errors
        glGetProgramiv (modelRenderProgram, GL_LINK_STATUS, intArray, 0)
        if (intArray [0] == GL_FALSE) {
            throw Exception ("Error in linking program: ${glGetProgramInfoLog (modelRenderProgram)}")
        }
        OpenGLUtil.assertNoGlError()

        // Bind MVP matrix uniform
        mvpMatrixHandle = glGetUniformLocation(modelRenderProgram, "mvpMatrix")

        // Shaders no longer required once the program is linked
        glDeleteShader(vertShaderHandle)
        glDeleteShader(fragShaderHandle)

        val ratio: Float = RenderTextureWidth.toFloat() / RenderTextureHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, VerticalFoV, ratio, 0.1f, 1_000f)

        // Start loading the STL file in the background

        val thread = Thread {
            val downloadFile = File(activity.cacheDir, "model.stl")
            if(!downloadFile.exists() || downloadFile.length() != TestModelSizeBytes) {
                Log.i(TAG, "Downloading model to ${downloadFile.path}")
                downloadFile(TestModelUrl, downloadFile)
                Log.i(TAG, "Downloaded model file: ${downloadFile.length()} bytes")
            } else {
                Log.i(TAG, "Model already cached here ${downloadFile.path}")
            }

            val mBuffer = RandomAccessFile(downloadFile, "r").use { raf ->
                raf.channel.use { channel ->
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, downloadFile.length())
                }
            }
            mBuffer.order(ByteOrder.LITTLE_ENDIAN)

            Log.i("TAG", "STL load started")
            pendingAsset = STLAsset(mBuffer)
            Log.i("TAG", "STL load complete")
        }
        thread.start()
    }

    fun loadGeometryOnGpu(asset : STLAsset) {
        Log.i("TAG", "Loading asset geometry with ${asset.triangleCount} triangles")

        modelVertexCount = asset.vertexCount
        modelIndexCount = asset.triangleCount * 3

        val intBuffer = IntBuffer.allocate(1)

        glGenVertexArrays(1, intBuffer)
        vao = intBuffer[0]

        glGenBuffers(1, intBuffer)
        modelVertexBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        modelIndexBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        modelNormalBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        modelColorBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        modelTexCoordBuffer = intBuffer[0]

        glBindVertexArray(vao)

        // Verticies

        glBindBuffer(GL_ARRAY_BUFFER, modelVertexBuffer)
        glBufferData(GL_ARRAY_BUFFER, modelVertexCount * 3 * 4, asset.verts, GL_STATIC_DRAW)
        val vPosition = glGetAttribLocation(modelRenderProgram, "vPosition")
        glEnableVertexAttribArray(vPosition)
        glVertexAttribPointer(vPosition, 3, GL_FLOAT, false, 0, 0)

        // Normals

        glBindBuffer(GL_ARRAY_BUFFER, modelNormalBuffer)
        glBufferData(GL_ARRAY_BUFFER, modelVertexCount * 3 * 4, asset.norms, GL_STATIC_DRAW)
        val vNormal = glGetAttribLocation(modelRenderProgram, "vNormal")
        glEnableVertexAttribArray(vNormal)
        glVertexAttribPointer(vNormal, 3, GL_FLOAT, false, 0, 0)

        // Colors (from normals)

        glBindBuffer(GL_ARRAY_BUFFER, modelColorBuffer)
        glBufferData(GL_ARRAY_BUFFER, modelVertexCount * 3 * 4, asset.norms, GL_STATIC_DRAW)
        val vColor = glGetAttribLocation(modelRenderProgram, "vColor")
        glEnableVertexAttribArray(vColor)
        glVertexAttribPointer(vColor, 3, GL_FLOAT, false, 0, 0)

        // TexCoords (from normals)

        glBindBuffer(GL_ARRAY_BUFFER, modelTexCoordBuffer)
        glBufferData(GL_ARRAY_BUFFER, modelVertexCount * 2 * 4, asset.norms, GL_STATIC_DRAW)
        val vTexCoord = glGetAttribLocation(modelRenderProgram, "vTexCoord")
        glEnableVertexAttribArray(vTexCoord)
        glVertexAttribPointer(vTexCoord, 2, GL_FLOAT, false, 0, 0)

        // Indicies

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, modelIndexBuffer)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, modelIndexCount * 4, asset.indxs, GL_STATIC_DRAW)

        glBindVertexArray(0)

        // Set translation and scale factors to get the model in the middle of the view
        modelTranslation = -asset.bounds.center
        val maxDimension = max(max(asset.bounds.size.x, asset.bounds.size.y), asset.bounds.size.z)
        modelScale = 1f / maxDimension
    }

    inner class FrameCallback : Choreographer.FrameCallback {

        // FPS Counters
        private var renderedFrames = 0
        private var lastReportTimeNanos = 0L

        var bufferIndex = 0

        override fun doFrame(frameTimeNanos: Long) {

            try {
                pendingAsset?.let {
                    loadGeometryOnGpu(it)
                    pendingAsset = null
                }

                if (sxrManager.isReady) {

                    // Select alternating texture buffers
                    val bufferIndex = bufferIndex++ % FrameBufferCount

                    glBindFramebuffer(GL_FRAMEBUFFER, frameBufferHandles[bufferIndex])

                    // Clear screen
                    glViewport(0, 0, RenderTextureWidth, RenderTextureHeight)
                    glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

                    val headPoseState = sxrManager.startFrame(renderColorTextures[bufferIndex],  renderColorTextures[bufferIndex])
                    if(headPoseState.isValid()) {
                        // Set the camera position
                        val viewMatrix = headPoseState.pose.rotation.quatToMatrix().queueInArray()

                        // Render model

                        glUseProgram(modelRenderProgram)

                        // Combine the projection and camera view transformation
                        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                        // Position and spin the model
                        Matrix.setIdentityM(modelMatrix, 0)
                        Matrix.translateM(modelMatrix, 0, 0f, 0f, -1f)
                        Matrix.rotateM(
                            modelMatrix,
                            0,
                            (headPoseState.expectedDisplayTimeNs.toDouble() / 49_997_117.0).toFloat(),
                            1f,
                            0.5f,
                            0f
                        )
                        Matrix.scaleM(modelMatrix, 0, modelScale, modelScale, modelScale)
                        Matrix.translateM(
                            modelMatrix,
                            0,
                            modelTranslation.x,
                            modelTranslation.y,
                            modelTranslation.z
                        )

                        // Create final MVP and set in program
                        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
                        glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

                        // Draw model
                        glBindVertexArray(vao)
                        glDrawElements(GL_TRIANGLES, modelIndexCount, GL_UNSIGNED_INT, 0)
                        glBindVertexArray(0)

                        GLES20.glFinish()
                    }
                    sxrManager.endFrame()

//Thread.sleep(1000)
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
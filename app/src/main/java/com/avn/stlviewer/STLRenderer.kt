package com.avn.stlviewer

import android.app.Activity
import android.opengl.*
import android.opengl.GLES31.*
import android.opengl.GLES32.glFramebufferTexture
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.avn.stlviewer.geometry.Vector3
import com.qualcomm.sxrapi.SxrApi
import com.qualcomm.sxrapi.SxrApi.*
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

        // Large model from https://www.cc.gatech.edu/projects/large_models/blade.html
        // and converted to STL with MeshLab
        private val TestModelUrl = URL("https://data.avncloud.com/dev/blade.stl")
        private const val TestModelSizeBytes = 88269484L

//        private val TestModelUrl = URL("https://upload.wikimedia.org/wikipedia/commons/3/36/3D_model_of_a_Cube.stl")
//        private const val TestModelSizeBytes = 684L

        fun assertNoGlError() {
            val code = glGetError()
            if (code != GL_NONE){
                throw Exception("OpenGL Error: ${GLU.gluErrorString(code)} ($code)");
            }
        }

        private fun compileShader(shaderType : Int, shaderSource : String) : Int {
            val handle = glCreateShader(shaderType)
            assertNoGlError()

            glShaderSource(handle, shaderSource)
            assertNoGlError()

            glCompileShader(handle)
            assertNoGlError()

            // Write out any log messages that might be relevant
            val log = glGetShaderInfoLog (handle)
            if (log.isNotEmpty()) {
                Log.w(TAG, "Shader warnings: $log")
            }

            // Check for compilation errors
            val result = intArrayOf(1)
            glGetShaderiv (handle, GL_COMPILE_STATUS, result, 0)
            if (result [0] == GL_FALSE) {
                throw Exception ("Error in shader: $log")
            }

            return handle
        }

        val RenderTextureWidth = 1024
        val RenderTextureHeight = 1024
        val FrameBufferCount = 2
    }

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

    private var canRender = false

    private var sxrApiRenderer: SxrApi
    private var sxrFrameParams: sxrFrameParams
    private var sxrBeginParams: sxrBeginParams
    private var layoutCoords: sxrLayoutCoords

    private lateinit var eglContext : EGLContext
    private lateinit var eglConfig : EGLConfig
    private lateinit var eglDisplay : EGLDisplay
    private lateinit var eglAttribs : IntArray
    private var eglSurface : EGLSurface? = null

    // Choreographer is used to schedule new frames
    private val choreographer: Choreographer = Choreographer.getInstance()
    // Performs the rendering and schedules new frames
    private val frameScheduler = FrameCallback()

    init {
        val svrHolder: SurfaceHolder = surfaceView.holder
        svrHolder.addCallback(this)
        sxrApiRenderer = SxrApi()
        sxrFrameParams = sxrApiRenderer.sxrFrameParams()
        sxrBeginParams = sxrApiRenderer.sxrBeginParams(svrHolder.surface)
        layoutCoords = sxrApiRenderer.sxrLayoutCoords()
        sxrInitialize(activity)
    }

    var pendingResume = false
    var pendingPause = false

    fun resume() {
        Log.i(TAG, "resume")
        pendingResume = true
        choreographer.postFrameCallback(frameScheduler)
    }

    fun pause() {
        Log.i(TAG, "pause")
        pendingPause = true
    }

    fun destroy() {
        // Stop the animation and any pending frames
        choreographer.removeFrameCallback(frameScheduler)
        sxrShutdown()
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: format: $format, width: $width, height: $height")

        createEGLContext()

        loadOpenGL()

        // SXR setup

        sxrSetLayoutCoords()
        sxrSetFrameParams()

        canRender = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceDestroyed")
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceCreated")
    }
    private fun createEGLContext() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val minorMajor = IntArray(2)
        EGL14.eglInitialize(eglDisplay, minorMajor, 0, minorMajor, 1)
        Log.i(TAG, "eglDisplay: major = ${minorMajor[0]}, minor = ${minorMajor[1]}")
        eglAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, eglAttribs, 0, configs, 0, 1, numConfig, 0)
        Log.i(TAG, "numConfig = ${numConfig[0]}")

        eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        assertNoGlError()

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceView, null, 0)
        assertNoGlError()
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        assertNoGlError()



    }


    fun loadOpenGL() {

        glEnable(GL_DEPTH_TEST)

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
            assertNoGlError()
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, RenderTextureWidth, RenderTextureHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, null )
            glBindTexture(GL_TEXTURE_2D, 0)
            assertNoGlError()

            // Depth
            glGenRenderbuffers(1, intArray, 0)
            assertNoGlError()
            renderDepthTextures[index] = intArray[0]
            glBindRenderbuffer(GL_RENDERBUFFER, renderDepthTextures[index])
            assertNoGlError()
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, RenderTextureWidth, RenderTextureHeight)
            assertNoGlError()

            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, renderDepthTextures[index])
            assertNoGlError()

            glFramebufferTexture(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, renderColorTextures[index], 0)
            assertNoGlError()

            glDrawBuffers(1, intArrayOf(GL_COLOR_ATTACHMENT0), 0)
            assertNoGlError()

            if(glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw Exception("Failed to create framebuffer")
            }
            assertNoGlError()
        }

        // Load model render program

        val vertShaderHandle = compileShader(GL_VERTEX_SHADER, activity.assets.open("shader.vert").bufferedReader().use { it.readText() })
        val fragShaderHandle = compileShader(GL_FRAGMENT_SHADER, activity.assets.open("shader.frag").bufferedReader().use { it.readText() })

        modelRenderProgram = glCreateProgram()
        glAttachShader(modelRenderProgram, vertShaderHandle)
        glAttachShader(modelRenderProgram, fragShaderHandle)
        assertNoGlError()

        // Link program
        glLinkProgram(modelRenderProgram)
        assertNoGlError()
        // Check for compilation errors
        glGetProgramiv (modelRenderProgram, GL_LINK_STATUS, intArray, 0)
        if (intArray [0] == GL_FALSE) {
            throw Exception ("Error in linking program: ${glGetProgramInfoLog (modelRenderProgram)}")
        }
        assertNoGlError()

        // Bind any uniforms here
        mvpMatrixHandle = glGetUniformLocation(modelRenderProgram, "mvpMatrix")

        // Shaders no longer required once the program is linked
        glDeleteShader(vertShaderHandle)
        glDeleteShader(fragShaderHandle)

        val ratio: Float = RenderTextureWidth.toFloat() / RenderTextureHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 90f, ratio, 0.1f, 1_000f)

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

                if (canRender) {

                    if (pendingResume) {
                        sxrBeginXr(activity, sxrBeginParams)
                        pendingResume = false
                    }

                    // Select alternating texture buffers

                    bufferIndex = (bufferIndex + 1) % FrameBufferCount
                    sxrFrameParams.renderLayers[0].imageHandle = renderColorTextures[bufferIndex]
                    sxrFrameParams.renderLayers[1].imageHandle = renderColorTextures[bufferIndex]

                    glBindFramebuffer(GL_FRAMEBUFFER, frameBufferHandles[bufferIndex])

                    // Clear screen
                    glViewport(0, 0, RenderTextureWidth, RenderTextureHeight)
                    glClearColor(0.5f, 0.5f, 0.5f, 1.0f)
                    glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

                    sxrUpdatePerFrameParams()

                    // Set the camera position
                    val viewMatrix =
                        sxrFrameParams.headPoseState.pose.rotation.quatToMatrix().queueInArray()

                    // Render model

                    glUseProgram(modelRenderProgram)

                    // Combine the projection and camera view transformation
                    Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                    // Position and spin the model
                    Matrix.setIdentityM(modelMatrix, 0)
                    Matrix.translateM(modelMatrix, 0, 0f, 0f, -1f)
                //Matrix.rotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 49_997_117.0).toFloat(), 1f, 0.5f, 0f)
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

                    sxrSubmitFrame(activity, sxrFrameParams)

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
                if(pendingPause) {
                    sxrEndXr()
                    pendingPause = false
                } else {
                    // Schedule the next frame
                    choreographer.postFrameCallback(this)
                }
            }
        }
    }

    // SXR utility functions

    fun sxrUpdatePerFrameParams() {
        sxrFrameParams.frameIndex++
        sxrFrameParams.headPoseState = sxrGetPredictedHeadPose(sxrGetPredictedDisplayTime())
    }

    fun sxrSetFrameParams() {

        sxrFrameParams.minVsyncs = 1

        sxrFrameParams.renderLayers[0].imageType = sxrTextureType.kTypeTexture
        sxrFrameParams.renderLayers[0].eyeMask = sxrEyeMask.kEyeMaskLeft
        sxrFrameParams.renderLayers[0].layerFlags = 0

        sxrFrameParams.renderLayers[1].imageType = sxrTextureType.kTypeTexture
        sxrFrameParams.renderLayers[1].eyeMask = sxrEyeMask.kEyeMaskRight
        sxrFrameParams.renderLayers[1].layerFlags = 0

        sxrFrameParams.fieldOfView = 90.0f
        sxrFrameParams.renderLayers[0].imageCoords = layoutCoords
        sxrFrameParams.renderLayers[1].imageCoords = layoutCoords
        sxrSetPerformanceLevels(sxrPerfLevel.kPerfMaximum, sxrPerfLevel.kPerfMaximum)
        val trackingMode = sxrTrackingMode.kTrackingPosition.trackingMode
        sxrSetTrackingMode(trackingMode)
        val trackedMode: Int = sxrGetTrackingMode()
    }


    fun sxrSetLayoutCoords() {
        layoutCoords.LowerLeftPos =  floatArrayOf(-1f, -1f, 0f, +1f) // {-1,-1,0,1}
        layoutCoords.LowerRightPos = floatArrayOf(+1f, -1f, 0f, +1f) // {1,-1,0,1}
        layoutCoords.UpperLeftPos =  floatArrayOf(-1f, +1f, 0f, +1f) // {-1,1,0,1}
        layoutCoords.UpperRightPos = floatArrayOf(+1f, +1f, 0f, +1f) // {1,1,0,1}
        layoutCoords.LowerUVs = floatArrayOf(0f, 0f, 1f, 0f)
        layoutCoords.UpperUVs = floatArrayOf(0f, 1f, 1f, 1f)
        layoutCoords.TransformMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }


}
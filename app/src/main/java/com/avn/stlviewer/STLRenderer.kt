package com.avn.stlviewer

import android.app.Activity
import android.graphics.BitmapFactory
import android.opengl.*
import android.opengl.GLES31.*
import android.opengl.GLES32.glFramebufferTexture
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

    }

    // Render textures (double buffered)

    private val frameBufferHandles = IntArray(2)
    private val renderColorTextures = IntArray(2)
    private val renderDepthTextures = IntArray(2)

    private var program0 : Int = 0
    private var program1 : Int = 0
    private var mvpMatrixHandle: Int = 0

    private var vao : Int = 0
    private var vertexBuffer : Int = 0
    private var indexBuffer : Int = 0
    private var normalBuffer : Int = 0
    private var colorBuffer : Int = 0
    private var texCoordBuffer : Int = 0

    private var textureHandle : Int = 0

    private val mvpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    private var vertexCount = 0
    private var indexCount = 0

    private var pendingAsset : STLAsset? = null

    private var scale = 1f
    private var translation : Vector3 = Vector3(0f, 0f, 0f)

    private var canRender = false

    private var sxrApiRenderer: SxrApi
    private var mSvrFrameParams: sxrFrameParams
    private var mBeginParams: sxrBeginParams
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

        // get notified when the underlying surface is created and destroyed
        sxrApiRenderer = SxrApi()
        mSvrFrameParams = sxrApiRenderer.sxrFrameParams()
        mBeginParams = sxrApiRenderer.sxrBeginParams(svrHolder.surface)
        layoutCoords = sxrApiRenderer.sxrLayoutCoords()
        Log.i(TAG, "sxrGetVersion = ${sxrGetVersion()}")
        Log.i(TAG, "sxrGetXrServiceVersion = ${sxrGetXrServiceVersion()}")
        Log.i(TAG, "sxrGetXrClientVersion = ${sxrGetXrClientVersion()}")
        sxrInitialize(activity)
    }

    var sxrRunning = false

    fun resume() {
        Log.i(TAG, "resume")
        choreographer.postFrameCallback(frameScheduler)
    }

    fun pause() {
        Log.i(TAG, "pause")
        choreographer.removeFrameCallback(frameScheduler)
//        if(sxrRunning) {
//            sxrEndXr()
//            sxrRunning = false
//        }

    }

    fun destroy() {
        // Stop the animation and any pending frames
        choreographer.removeFrameCallback(frameScheduler)
        sxrShutdown()
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: format: $format, width: $width, height: $height")

        createEGLContext()

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceView, null, 0)
        assertNoGlError()
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        assertNoGlError()

        loadOpenGL()

        // SXR setup

        sxrSetLayoutCoords()
        sxrSetFrameParams()

        canRender = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        Log.i(TAG, "surfaceCreated")
    }
    private fun createEGLContext() {
        // Providing this constant here (rather than using EGL_OPENGL_ES3_BIT ) allows us to use a lower target API for this project.
        val kEGLOpenGLES3Bit = 64

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
            //EGL14.EGL_STENCIL_SIZE, 8,
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
            glGenTextures(1,intArray, 0)
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

        // Load programs

        val vertShaderHandle = compileShader(GL_VERTEX_SHADER, activity.assets.open("shader.vert").bufferedReader().use { it.readText() })
        val fragShader0Handle = compileShader(GL_FRAGMENT_SHADER, activity.assets.open("shader0.frag").bufferedReader().use { it.readText() })
        val fragShader1Handle = compileShader(GL_FRAGMENT_SHADER, activity.assets.open("shader1.frag").bufferedReader().use { it.readText() })

        program0 = glCreateProgram()
        glAttachShader(program0, vertShaderHandle)
        glAttachShader(program0, fragShader0Handle)
        assertNoGlError()

        // Link program 0
        glLinkProgram(program0)
        assertNoGlError()
        // Check for compilation errors
        glGetProgramiv (program0, GL_LINK_STATUS, intArray, 0)
        if (intArray [0] == GL_FALSE) {
            throw Exception ("Error in linking program: ${glGetProgramInfoLog (program0)}")
        }
        assertNoGlError()

        program1 = glCreateProgram()
        glAttachShader(program1, vertShaderHandle)
        glAttachShader(program1, fragShader1Handle)
        assertNoGlError()

        // Link program 1
        glLinkProgram(program1)
        assertNoGlError()
        // Check for compilation errors
        glGetProgramiv (program1, GL_LINK_STATUS, intArray, 0)
        if (intArray [0] == GL_FALSE) {
            throw Exception ("Error in linking program: ${glGetProgramInfoLog (program1)}")
        }
        assertNoGlError()


        // Bind any uniforms here
        mvpMatrixHandle = glGetUniformLocation(program0, "mvpMatrix")

        // Shaders no longer required once the program is linked
        glDeleteShader(vertShaderHandle)
        glDeleteShader(fragShader0Handle)
        glDeleteShader(fragShader1Handle)

        // Load sample texture

        val uvCheckerBitmap = BitmapFactory.decodeResource(activity.resources, R.drawable.uvchecker)
        glGenTextures(1, intArray, 0)
        textureHandle = intArray[0]
        if (textureHandle != 0) {
            // Bind to the texture in OpenGL
            glBindTexture(GL_TEXTURE_2D, textureHandle)
            // Set filtering
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, uvCheckerBitmap, 0)
            glGenerateMipmap(GL_TEXTURE_2D)
        }
        uvCheckerBitmap.recycle()
        glBindTexture(GL_TEXTURE_2D, 0)

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

        vertexCount = asset.vertexCount
        indexCount = asset.triangleCount * 3

        val intBuffer = IntBuffer.allocate(1)

        glGenVertexArrays(1, intBuffer)
        vao = intBuffer[0]

        glGenBuffers(1, intBuffer)
        vertexBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        indexBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        normalBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        colorBuffer = intBuffer[0]

        glGenBuffers(1, intBuffer)
        texCoordBuffer = intBuffer[0]

        glBindVertexArray(vao)

        // Verticies

        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 3 * 4, asset.verts, GL_STATIC_DRAW)
        val vPosition = glGetAttribLocation(program0, "vPosition")
        glEnableVertexAttribArray(vPosition)
        glVertexAttribPointer(vPosition, 3, GL_FLOAT, false, 0, 0)

        // Normals

        glBindBuffer(GL_ARRAY_BUFFER, normalBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 3 * 4, asset.norms, GL_STATIC_DRAW)
        val vNormal = glGetAttribLocation(program0, "vNormal")
        glEnableVertexAttribArray(vNormal)
        glVertexAttribPointer(vNormal, 3, GL_FLOAT, false, 0, 0)

        // Colors (from normals)

        glBindBuffer(GL_ARRAY_BUFFER, colorBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 3 * 4, asset.norms, GL_STATIC_DRAW)
        val vColor = glGetAttribLocation(program0, "vColor")
        glEnableVertexAttribArray(vColor)
        glVertexAttribPointer(vColor, 3, GL_FLOAT, false, 0, 0)

        // TexCoords (from normals)

        glBindBuffer(GL_ARRAY_BUFFER, texCoordBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 2 * 4, asset.norms, GL_STATIC_DRAW)
        val vTexCoord = glGetAttribLocation(program0, "vTexCoord")
        glEnableVertexAttribArray(vTexCoord)
        glVertexAttribPointer(vTexCoord, 2, GL_FLOAT, false, 0, 0)

        // Indicies

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer)
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexCount * 4, asset.indxs, GL_STATIC_DRAW)

        glBindVertexArray(0)

        // Set translation and scale factors to get the model in the middle of the view
        translation = -asset.bounds.center
        val maxDimension = max(max(asset.bounds.size.x, asset.bounds.size.y), asset.bounds.size.z)
        scale = 1f / maxDimension
    }

    inner class FrameCallback : Choreographer.FrameCallback {

        // FPS Counters
        private var requestedFrames = 0
        private var renderedFrames = 0
        private var lastReportTimeNanos = 0L

        var bufferIndex = 0

        override fun doFrame(frameTimeNanos: Long) {
            // Schedule the next frame
            choreographer.postFrameCallback(this)

            pendingAsset?.let {
                loadGeometryOnGpu(it)
                pendingAsset = null
            }


            if(canRender) {

                if(!sxrRunning) {
                    sxrBeginXr(activity, mBeginParams)
                    sxrRunning = true
                }

                // Select alternating texture buffers

                bufferIndex = (bufferIndex + 1) % frameBufferHandles.size
                mSvrFrameParams.renderLayers[0].imageHandle = renderColorTextures[bufferIndex]
                mSvrFrameParams.renderLayers[1].imageHandle = renderColorTextures[bufferIndex]

                glBindFramebuffer(GL_FRAMEBUFFER, frameBufferHandles[bufferIndex])

                // Clear screen
                glViewport(0, 0, RenderTextureWidth, RenderTextureHeight)
                glClearColor(0.5f, 0.5f, 0.5f, 1.0f)
                glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

                sxrUpdatePerFrameParams()

                // Set the camera position
                val viewMatrix = mSvrFrameParams.headPoseState.pose.rotation.quatToMatrix().queueInArray()

                // Bind texture
                glBindTexture(GL_TEXTURE_2D, textureHandle)

                // Render 0

                glUseProgram(program0)

                // Combine the projection and camera view transformation
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                // Rotate the model
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, 0f, 0f, -1f)
//                Matrix.rotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 49_997_117.0).toFloat(), 1f, 0.5f, 0f)
                Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
                Matrix.translateM(modelMatrix, 0, translation.x, translation.y, translation.z)

                // Create final MVP and set in program
                Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
                glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

                // Draw model
                glBindVertexArray(vao)
                glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0)
                glBindVertexArray(0)

                // Render 1

//                glUseProgram(program1)
//
//                // Combine the projection and camera view transformation
//                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
//
//                // Rotate the model
//                Matrix.setIdentityM(modelMatrix, 0)
//                Matrix.translateM(modelMatrix, 0, 0f, 0f, -2f)
////                Matrix.rotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 100_000_049.0).toFloat(), -0.7f, 0.25f, 0.1f)
//                Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
//                Matrix.translateM(modelMatrix, 0, translation.x, translation.y, translation.z)
//
//                // Create final MVP and set in program
//                Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
//                glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
//
//                // Draw model
//                glBindVertexArray(vao)
//                glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0)
//                glBindVertexArray(0)

                sxrSubmitFrame(activity, mSvrFrameParams)

//Thread.sleep(1000)
                ++renderedFrames

            }

            ++requestedFrames
            // Report frame rate
            if(frameTimeNanos - lastReportTimeNanos > 1_000_000_000) {
                Log.i(TAG, "FPS: $renderedFrames / $requestedFrames")
                lastReportTimeNanos = frameTimeNanos
                requestedFrames = 0
                renderedFrames = 0
            }
        }
    }

    // SXR utility functions

    fun sxrUpdatePerFrameParams() {
        mSvrFrameParams.frameIndex++
        mSvrFrameParams.headPoseState = sxrGetPredictedHeadPose(sxrGetPredictedDisplayTime())
    }

    fun sxrSetFrameParams() {

        mSvrFrameParams.minVsyncs = 1

        mSvrFrameParams.renderLayers[0].imageType = sxrTextureType.kTypeTexture
        mSvrFrameParams.renderLayers[0].eyeMask = sxrEyeMask.kEyeMaskLeft
        mSvrFrameParams.renderLayers[0].layerFlags = 0

        mSvrFrameParams.renderLayers[1].imageType = sxrTextureType.kTypeTexture
        mSvrFrameParams.renderLayers[1].eyeMask = sxrEyeMask.kEyeMaskRight
        mSvrFrameParams.renderLayers[1].layerFlags = 0

        mSvrFrameParams.fieldOfView = 90.0f
        mSvrFrameParams.renderLayers[0].imageCoords = layoutCoords
        mSvrFrameParams.renderLayers[1].imageCoords = layoutCoords
        sxrSetPerformanceLevels(sxrPerfLevel.kPerfMaximum, sxrPerfLevel.kPerfMaximum)
        val trackingMode = sxrTrackingMode.kTrackingPosition.trackingMode
        sxrSetTrackingMode(trackingMode)
        val trackedMode: Int = sxrGetTrackingMode()
    }


    fun sxrSetLayoutCoords() {

        val centerX = 0.0f
        val centerY = 0.0f
        val radiusX = 1.0f
        val radiusY = 1.0f

        layoutCoords.LowerLeftPos =  floatArrayOf(centerX - radiusX, centerY - radiusY, 0.0f, 1.0f) // {-1,-1,0,1}
        layoutCoords.LowerRightPos = floatArrayOf(centerX + radiusX, centerY - radiusY, 0.0f, 1.0f) // {1,-1,0,1}
        layoutCoords.UpperLeftPos = floatArrayOf(centerX - radiusX, centerY + radiusY, 0.0f, 1.0f) // {-1,1,0,1}
        layoutCoords.UpperRightPos = floatArrayOf(centerX + radiusX, centerY + radiusY, 0.0f, 1.0f) // {1,1,0,1}
        layoutCoords.LowerUVs = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f)
        layoutCoords.UpperUVs = floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f)
        layoutCoords.TransformMatrix = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        )
    }


}
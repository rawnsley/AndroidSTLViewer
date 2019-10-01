package com.avn.stlviewer

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.*
import android.opengl.GLES30.*
import android.util.Log
import com.avn.stlviewer.geometry.Vector3
import java.nio.ByteOrder
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import android.opengl.GLES20
import android.opengl.GLUtils
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.nio.channels.FileChannel

class STLRenderer(val context : Context) : GLSurfaceView.Renderer {

    companion object {
        private val TAG = STLRenderer::class.java.simpleName

        // Large model from https://www.cc.gatech.edu/projects/large_models/blade.html
        // and converted to STL with MeshLab
        private val TestModelUrl = URL("https://data.avncloud.com/dev/blade.stl")
        private const val TestModelSizeBytes = 88269484L

        fun assertNoGlError() {
            val code = GLES31.glGetError()
            if (code != GLES31.GL_NONE){
                throw Exception("OpenGL Error: ${GLU.gluErrorString(code)} ($code)");
            }
        }

        private fun compileShader(shaderType : Int, shaderSource : String) : Int {
            val handle = GLES31.glCreateShader(shaderType)
            assertNoGlError()

            GLES31.glShaderSource(handle, shaderSource)
            assertNoGlError()

            GLES31.glCompileShader(handle)
            assertNoGlError()

            // Write out any log messages that might be relevant
            val log = GLES31.glGetShaderInfoLog (handle)
            if (log.isNotEmpty()) {
                Log.w(TAG, "Shader warnings: $log")
            }

            // Check for compilation errors
            val result = intArrayOf(1)
            GLES31.glGetShaderiv (handle, GLES31.GL_COMPILE_STATUS, result, 0)
            if (result [0] == GLES31.GL_FALSE) {
                throw Exception ("Error in shader: $log")
            }

            return handle
        }

    }

    private var program : Int = 0
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
    private val viewMatrix = FloatArray(16)

    private var viewportWidth = 0
    private var viewportHeight = 0

    private var vertexCount = 0
    private var indexCount = 0

    private var pendingAsset : STLAsset? = null

    private var scale = 1f
    private var translation : Vector3 = Vector3(0f, 0f, 0f)

    private var canRender = false

    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {
        Log.i(TAG, "onSurfaceCreated")

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // Load program

        val vertShaderHandle = compileShader(GLES31.GL_VERTEX_SHADER, context.assets.open("shader.vert").bufferedReader().use { it.readText() })
        val fragShaderHandle = compileShader(GLES31.GL_FRAGMENT_SHADER, context.assets.open("shader.frag").bufferedReader().use { it.readText() })

        program = GLES31.glCreateProgram()
        GLES31.glAttachShader(program, vertShaderHandle)
        GLES31.glAttachShader(program, fragShaderHandle)
        assertNoGlError()

        // Link program
        GLES31.glLinkProgram(program)
        assertNoGlError()
        // Check for compilation errors
        val result = IntArray(1)
        GLES31.glGetProgramiv (program, GLES31.GL_LINK_STATUS, result, 0)
        if (result [0] == GLES31.GL_FALSE) {
            throw Exception ("Error in linking program: ${GLES31.glGetProgramInfoLog (program)}")
        }
        assertNoGlError()

        // Bind any uniforms here
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "mvpMatrix")

        // Shaders no longer required once the program is linked
        glDeleteShader(vertShaderHandle)
        glDeleteShader(fragShaderHandle)

        // Load sample texture

        val uvCheckerBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.uvchecker)
        val intArray = IntArray(1)
        GLES20.glGenTextures(1, intArray, 0)
        textureHandle = intArray[0]
        if (textureHandle != 0) {
            // Bind to the texture in OpenGL
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)
            // Set filtering
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uvCheckerBitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
        uvCheckerBitmap.recycle()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // Start loading the STL file in the background

        val thread = Thread {
            val downloadFile = File(context.cacheDir, "model.stl")
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

    override fun onSurfaceChanged(p0: GL10?, p1: Int, p2: Int) {
        Log.i(TAG, "onSurfaceChanged: $p1 x $p2")
        viewportWidth = p1
        viewportHeight = p2
        val ratio: Float = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 30f, ratio, 0.1f, 1_000f)
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
        val vPosition = GLES20.glGetAttribLocation(program, "vPosition")
        glEnableVertexAttribArray(vPosition)
        glVertexAttribPointer(vPosition, 3, GL_FLOAT, false, 0, 0)

        // Normals

        glBindBuffer(GL_ARRAY_BUFFER, normalBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 3 * 4, asset.norms, GL_STATIC_DRAW)
        val vNormal = GLES20.glGetAttribLocation(program, "vNormal")
        glEnableVertexAttribArray(vNormal)
        glVertexAttribPointer(vNormal, 3, GL_FLOAT, false, 0, 0)

        // Colors (from normals)

        glBindBuffer(GL_ARRAY_BUFFER, colorBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 3 * 4, asset.norms, GL_STATIC_DRAW)
        val vColor = GLES20.glGetAttribLocation(program, "vColor")
        glEnableVertexAttribArray(vColor)
        glVertexAttribPointer(vColor, 3, GL_FLOAT, false, 0, 0)

        // TexCoords (from normals)

        glBindBuffer(GL_ARRAY_BUFFER, texCoordBuffer)
        glBufferData(GL_ARRAY_BUFFER, vertexCount * 2 * 4, asset.verts, GL_STATIC_DRAW)
        val vTexCoord = GLES20.glGetAttribLocation(program, "vTexCoord")
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

        canRender = true
    }

    // FPS Counters
    private var requestedFrames = 0
    private var renderedFrames = 0
    private var lastReportTimeNanos = 0L

    override fun onDrawFrame(p0: GL10?) {

        val frameTimeNanos = System.nanoTime()

        pendingAsset?.let {
            loadGeometryOnGpu(it)
            pendingAsset = null
        }

        // Clear screen
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES20.glClearColor(0.5f, 0.5f, 0.5f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        // Is rendering ready?
        if(canRender) {

            glUseProgram(program)

            // Set the camera position
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)

            // Combine the projection and camera view transformation
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            // Rotate the model
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.setRotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 100_000_000.0).toFloat(), 0.5f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
            Matrix.translateM(modelMatrix, 0, translation.x, translation.y, translation.z)

            // Create final MVP and set in program
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

            // Bind texture
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle)

            // Draw model
            glBindVertexArray(vao)
            glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0)
            glBindVertexArray(0)

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
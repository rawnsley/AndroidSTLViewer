package com.avn.stlviewer

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.opengl.Matrix
import android.util.Log
import com.avn.stlviewer.geometry.Vector3
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

class STLModel(activity: Activity) {

    companion object {
        private val TAG = STLModel::class.java.simpleName

        // Small cube model
//        private val TestModelUrl = URL("https://upload.wikimedia.org/wikipedia/commons/3/36/3D_model_of_a_Cube.stl")
//        private const val TestModelSizeBytes = 684L

        // Large model from https://www.cc.gatech.edu/projects/large_models/blade.html
        // and converted to STL with MeshLab
        private val TestModelUrl = URL("https://data.avncloud.com/dev/blade.stl")
        private const val TestModelSizeBytes = 88269484L
    }

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

    private var modelVertexCount = 0
    private var modelIndexCount = 0

    private var pendingAsset : STLAsset? = null

    private var modelScale = 1f
    private var modelTranslation : Vector3 = Vector3(0f, 0f, 0f)

    init {

        // OpenGL defaults

        GLES31.glCullFace(GLES31.GL_BACK)
        GLES31.glEnable(GLES31.GL_CULL_FACE)
        GLES31.glEnable(GLES31.GL_DEPTH_TEST)
        GLES31.glClearColor(0.5f, 0.5f, 0.5f, 1.0f)

        val intArray = IntArray(1)

        // Load model render program

        val vertShaderHandle = OpenGLUtil.compileShader(GLES31.GL_VERTEX_SHADER, activity.assets.open("shader.vert").bufferedReader().use { it.readText() })
        val fragShaderHandle = OpenGLUtil.compileShader(GLES31.GL_FRAGMENT_SHADER, activity.assets.open("shader.frag").bufferedReader().use { it.readText() })

        modelRenderProgram = GLES31.glCreateProgram()
        GLES31.glAttachShader(modelRenderProgram, vertShaderHandle)
        GLES31.glAttachShader(modelRenderProgram, fragShaderHandle)
        OpenGLUtil.assertNoGlError()

        // Link program
        GLES31.glLinkProgram(modelRenderProgram)
        OpenGLUtil.assertNoGlError()
        // Check for compilation errors
        GLES31.glGetProgramiv(modelRenderProgram, GLES31.GL_LINK_STATUS, intArray, 0)
        if (intArray [0] == GLES31.GL_FALSE) {
            throw Exception ("Error in linking program: ${GLES31.glGetProgramInfoLog(
                modelRenderProgram
            )}")
        }
        OpenGLUtil.assertNoGlError()

        // Bind MVP matrix uniform
        mvpMatrixHandle = GLES31.glGetUniformLocation(modelRenderProgram, "mvpMatrix")

        // Shaders no longer required once the program is linked
        GLES31.glDeleteShader(vertShaderHandle)
        GLES31.glDeleteShader(fragShaderHandle)

        // Start loading the STL file in the background

        val thread = Thread {
            val downloadFile = File(activity.cacheDir, "model.stl")
            if(!downloadFile.exists() || downloadFile.length() != TestModelSizeBytes) {
                Log.i(TAG, "Downloading model to ${downloadFile.path}")
                com.avn.stlviewer.downloadFile(TestModelUrl, downloadFile)
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

        GLES31.glGenVertexArrays(1, intBuffer)
        vao = intBuffer[0]

        GLES31.glGenBuffers(1, intBuffer)
        modelVertexBuffer = intBuffer[0]

        GLES31.glGenBuffers(1, intBuffer)
        modelIndexBuffer = intBuffer[0]

        GLES31.glGenBuffers(1, intBuffer)
        modelNormalBuffer = intBuffer[0]

        GLES31.glGenBuffers(1, intBuffer)
        modelColorBuffer = intBuffer[0]

        GLES31.glGenBuffers(1, intBuffer)
        modelTexCoordBuffer = intBuffer[0]

        GLES31.glBindVertexArray(vao)

        // Verticies

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, modelVertexBuffer)
        GLES31.glBufferData(
            GLES31.GL_ARRAY_BUFFER,
            modelVertexCount * 3 * 4,
            asset.verts,
            GLES31.GL_STATIC_DRAW
        )
        val vPosition = GLES31.glGetAttribLocation(modelRenderProgram, "vPosition")
        GLES31.glEnableVertexAttribArray(vPosition)
        GLES31.glVertexAttribPointer(vPosition, 3, GLES31.GL_FLOAT, false, 0, 0)

        // Normals

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, modelNormalBuffer)
        GLES31.glBufferData(
            GLES31.GL_ARRAY_BUFFER,
            modelVertexCount * 3 * 4,
            asset.norms,
            GLES31.GL_STATIC_DRAW
        )
        val vNormal = GLES31.glGetAttribLocation(modelRenderProgram, "vNormal")
        GLES31.glEnableVertexAttribArray(vNormal)
        GLES31.glVertexAttribPointer(vNormal, 3, GLES31.GL_FLOAT, false, 0, 0)

        // Colors (from normals)

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, modelColorBuffer)
        GLES31.glBufferData(
            GLES31.GL_ARRAY_BUFFER,
            modelVertexCount * 3 * 4,
            asset.norms,
            GLES31.GL_STATIC_DRAW
        )
        val vColor = GLES31.glGetAttribLocation(modelRenderProgram, "vColor")
        GLES31.glEnableVertexAttribArray(vColor)
        GLES31.glVertexAttribPointer(vColor, 3, GLES31.GL_FLOAT, false, 0, 0)

        // TexCoords (from normals)

        GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, modelTexCoordBuffer)
        GLES31.glBufferData(
            GLES31.GL_ARRAY_BUFFER,
            modelVertexCount * 2 * 4,
            asset.norms,
            GLES31.GL_STATIC_DRAW
        )
        val vTexCoord = GLES31.glGetAttribLocation(modelRenderProgram, "vTexCoord")
        GLES31.glEnableVertexAttribArray(vTexCoord)
        GLES31.glVertexAttribPointer(vTexCoord, 2, GLES31.GL_FLOAT, false, 0, 0)

        // Indicies

        GLES31.glBindBuffer(GLES31.GL_ELEMENT_ARRAY_BUFFER, modelIndexBuffer)
        GLES31.glBufferData(
            GLES31.GL_ELEMENT_ARRAY_BUFFER,
            modelIndexCount * 4,
            asset.indxs,
            GLES31.GL_STATIC_DRAW
        )

        GLES31.glBindVertexArray(0)

        // Set translation and scale factors to get the model in the middle of the view
        modelTranslation = -asset.bounds.center
        val maxDimension = max(max(asset.bounds.size.x, asset.bounds.size.y), asset.bounds.size.z)
        modelScale = 1f / maxDimension
    }

    var startTimeNs = -1L

    fun render(displayTimeNs : Long, viewMatrix : FloatArray, projectionMatrix : FloatArray) {
        // Load the STL asset if it's ready
        pendingAsset?.let {
            loadGeometryOnGpu(it)
            pendingAsset = null
        }

        // Position, scale, and spin the model

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, -2f)
        // Use relative time to avoid underflow
        if(startTimeNs < 0L) {
            startTimeNs = displayTimeNs
        }
        val rotation = ((displayTimeNs - startTimeNs).toDouble() / 50_000_000.0).toFloat()
        Matrix.rotateM(
            modelMatrix,
            0,
            rotation,
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

        // Create combined MVP

        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)

        // Render model

        GLES31.glUseProgram(modelRenderProgram)
        GLES31.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES31.glBindVertexArray(vao)
        GLES31.glDrawElements(GLES31.GL_TRIANGLES, modelIndexCount, GLES31.GL_UNSIGNED_INT, 0)
        GLES31.glBindVertexArray(0)
    }

}
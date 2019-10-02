package com.avn.stlviewer

import android.content.Context
import android.opengl.*
import android.util.Log
import com.avn.stlviewer.geometry.Vector3
import java.nio.ByteOrder
import kotlin.math.max
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.filamat.MaterialBuilder
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.nio.channels.FileChannel

class STLRenderer(val context : Context, val surfaceView: SurfaceView) {

    companion object {
        private val TAG = STLRenderer::class.java.simpleName

        // Large model from https://www.cc.gatech.edu/projects/large_models/blade.html
        // and converted to STL with MeshLab
        private val TestModelUrl = URL("https://data.avncloud.com/dev/blade.stl")
        private const val TestModelSizeBytes = 88269484L

    }

    // UiHelper is provided by Filament to manage SurfaceView and SurfaceTexture
    private lateinit var uiHelper: UiHelper
    // Choreographer is used to schedule new frames
    private val choreographer: Choreographer = Choreographer.getInstance()

    // Engine creates and destroys Filament resources
    // Each engine must be accessed from a single thread of your choosing
    // Resources cannot be shared across engines
    private lateinit var engine: Engine
    // A renderer instance is tied to a single surface (SurfaceView, TextureView, etc.)
    private lateinit var renderer: Renderer
    // A scene holds all the renderable, lights, etc. to be drawn
    private lateinit var scene: Scene
    // A view defines a viewport, a scene and a camera for rendering
    private lateinit var view: View
    // Should be pretty obvious :)
    private lateinit var camera: Camera

    private lateinit var material: Material
    private lateinit var materialInstance: MaterialInstance

    private lateinit var baseColor: Texture

    private lateinit var mesh: Mesh

    // Filament entity representing a renderable object
    @Entity
    private var light = 0

    // A swap chain is Filament's representation of a surface
    private var swapChain: SwapChain? = null

    // Performs the rendering and schedules new frames
    private val frameScheduler = FrameCallback()

    private val modelMatrix = FloatArray(16)

    private var scale = 1f
    private var translation : Vector3 = Vector3(0f, 0f, 0f)

    init {
        choreographer
        setupSurfaceView()
        setupFilament()
        setupView()
        setupScene()

    }

    private fun setupSurfaceView() {
        Log.i(TAG, "setupSurfaceView")
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(surfaceView)
    }

    private fun setupFilament() {
        Log.i(TAG, "setupFilament")
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        camera = engine.createCamera()
    }

    private fun setupView() {
        Log.i(TAG, "setupView")
        view.setClearColor(0.5f, 0.5f, 0.5f, 1.0f)
        view.camera = camera
        view.scene = scene

        // NOTE: Try to disable post-processing (tone-mapping, etc.) to see the difference
        view.isPostProcessingEnabled = false

    }

    private fun setupScene() {
        Log.i(TAG, "setupScene")
        loadMaterial()
        setupMaterial()
        setupLighting()
        loadModel()
    }

    private fun loadModel() {

        // Download STL model if not already cached

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
        val stlAsset = STLAsset(mBuffer)
        Log.i("TAG", "STL load complete")

        mesh = loadMesh(engine, stlAsset, materialInstance)

        // Add the entity to the scene to render it
        scene.addEntity(mesh.renderable)

        // Set translation and scale factors to get the model in the middle of the view
        translation = -stlAsset.bounds.center
        val maxDimension = max(max(stlAsset.bounds.size.x, stlAsset.bounds.size.y), stlAsset.bounds.size.z)
        scale = 1f / maxDimension

    }

    private fun setupLighting() {
        light = EntityManager.get().create()
        val (r, g, b) = Colors.cct(6_500.0f)
        LightManager.Builder(LightManager.Type.SUN)
            .color(r, g, b)
            .intensity(100_000.0f)
            .direction(0f, -0.2f, -1f)
            .sunAngularRadius(10f)
            .sunHaloSize(10.0f)
            .sunHaloFalloff(10.0f)
            .castShadows(false)
            .build(engine, light)
        scene.addEntity(light)
        camera.setExposure(16.0f, 1.0f / 125.0f, 10.0f)
    }

    private fun loadMaterial() {
        Log.i(TAG, "loadMaterial")

        //val shadingModel = MaterialBuilder.Shading.LIT
        val shadingModel = MaterialBuilder.Shading.UNLIT

        val mb = MaterialBuilder()
            .platform(MaterialBuilder.Platform.MOBILE)
            .optimization(MaterialBuilder.Optimization.NONE)
            .targetApi(MaterialBuilder.TargetApi.OPENGL)
            .samplerParameter(
                MaterialBuilder.SamplerType.SAMPLER_2D,
                MaterialBuilder.SamplerFormat.FLOAT,
                MaterialBuilder.SamplerPrecision.DEFAULT,
                "baseColorMap"
            )
            .require(MaterialBuilder.VertexAttribute.COLOR)
            .require(MaterialBuilder.VertexAttribute.UV0)
            .shading(shadingModel)
            .flipUV(false)

        if (shadingModel == MaterialBuilder.Shading.LIT) {
            val sb = """
                void material(inout MaterialInputs material) { 
                    prepareMaterial(material); 
                    material.baseColor = texture(materialParams_baseColorMap, getUV0()) + getColor();
                    material.roughness = 0.1;
                }
            """
            mb.material(sb)
        } else {
            val sb = """
                void material(inout MaterialInputs material) { 
                    prepareMaterial(material); 
                    material.baseColor = texture(materialParams_baseColorMap, getUV0()) + getColor();
                }
            """
            mb.material(sb)
        }

        val materialPackage = mb.build()
        if (!materialPackage.isValid) {
            throw Exception("Invalid MaterialPackage")
        }
        material = Material.Builder().payload(materialPackage.buffer, materialPackage.buffer.limit()).build(engine)
    }

    private fun setupMaterial() {
        Log.i(TAG, "setupMaterial")
        // Create an instance of the material to set different parameters on it
        materialInstance = material.createInstance()

        // Note that the textures are stored in drawable-nodpi to prevent the system
        // from automatically resizing them based on the display's density
        baseColor = loadTexture(engine, context.resources, R.drawable.uvchecker)

        // A texture sampler does not need to be kept around or destroyed
        val sampler = TextureSampler()
        sampler.magFilter = TextureSampler.MagFilter.LINEAR
        sampler.minFilter = TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR
        sampler.wrapModeS = TextureSampler.WrapMode.REPEAT
        sampler.wrapModeT = TextureSampler.WrapMode.REPEAT

        materialInstance.setParameter("baseColorMap", baseColor, sampler)
    }

    fun resume() {
        Log.i(TAG, "resume")
        choreographer.postFrameCallback(frameScheduler)
    }

    fun pause() {
        Log.i(TAG, "pause")
        choreographer.removeFrameCallback(frameScheduler)
    }

    fun destroy() {
        // Stop the animation and any pending frame
        choreographer.removeFrameCallback(frameScheduler)

        // Always detach the surface before destroying the engine
        uiHelper.detach()

        // This ensures that all the commands we've sent to Filament have
        // been processed before we attempt to destroy anything
        engine.flushAndWait()

        // Cleanup all resources
        destroyMesh(engine, mesh)
        engine.destroyTexture(baseColor)
        engine.destroyEntity(light)
        engine.destroyRenderer(renderer)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyMaterial(material)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCamera(camera)

        // Engine.destroyEntity() destroys Filament related resources only
        // (components), not the entity itself
        val entityManager = EntityManager.get()
        entityManager.destroy(light)

        // Destroying the engine will free up any resource you may have forgotten
        // to destroy, but it's recommended to do the cleanup properly
        engine.destroy()
    }

    inner class FrameCallback : Choreographer.FrameCallback {

        // FPS Counters
        private var requestedFrames = 0
        private var renderedFrames = 0
        private var lastReportTimeNanos = 0L

        override fun doFrame(frameTimeNanos: Long) {
            // Schedule the next frame
            choreographer.postFrameCallback(this)

            // This check guarantees that we have a swap chain
            if (uiHelper.isReadyToRender) {

                // Rotate, scale, and center the model
                Matrix.setRotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 100_000_000.0).toFloat(), 0.5f, 1f, 0f)
                Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
                Matrix.translateM(modelMatrix, 0, translation.x, translation.y, translation.z)
                engine.transformManager.setTransform(engine.transformManager.getInstance(mesh.renderable), modelMatrix)

                if (renderer.beginFrame(swapChain!!)) {
                    renderer.render(view)
                    renderer.endFrame()
                    ++renderedFrames
                }
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

    inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
        }

        override fun onDetachedFromSurface() {
            swapChain?.let {
                engine.destroySwapChain(it)
                // Required to ensure we don't return before Filament is done executing the
                // destroySwapChain command, otherwise Android might destroy the Surface
                // too early
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            val aspect = width.toDouble() / height.toDouble()
            camera.setProjection(30.0, aspect, 0.1, 1_000.0, Camera.Fov.VERTICAL)
            camera.lookAt(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            view.viewport = Viewport(0, 0, width, height)
        }
    }

}
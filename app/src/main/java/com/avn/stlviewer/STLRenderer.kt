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

    private lateinit var material0: Material
    private lateinit var materialInstance0: MaterialInstance

    private lateinit var material1: Material
    private lateinit var materialInstance1: MaterialInstance

    private lateinit var baseColor: Texture

    private lateinit var mesh0: Mesh
    private lateinit var mesh1: Mesh

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

        // Post-processing increases render quality at the cost of performance
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

        mesh0 = loadMesh(engine, stlAsset, materialInstance0)
        mesh1 = loadMesh(engine, stlAsset, materialInstance1)

        // Add the entites to the scene to render it
        scene.addEntity(mesh0.renderable)
        scene.addEntity(mesh1.renderable)

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
        camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
    }

    private fun loadMaterial() {
        Log.i(TAG, "loadMaterial")

        // UNLIT model is the fastest, but LIT model is best quality
        //val shadingModel = MaterialBuilder.Shading.LIT
        val shadingModel = MaterialBuilder.Shading.UNLIT

        // Material 0

        val mb0 = MaterialBuilder()
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

        if (shadingModel == MaterialBuilder.Shading.LIT) {
            val sb = """
                void material(inout MaterialInputs material) { 
                    prepareMaterial(material); 
                    material.baseColor = getColor();
                    material.roughness = 0.25;
                }
            """
            mb0.material(sb)
        } else {
            val sb = """
                void material(inout MaterialInputs material) { 
                    prepareMaterial(material); 
                    material.baseColor = getColor();
                }
            """
            mb0.material(sb)
        }

        val materialPackage0 = mb0.build()
        if (!materialPackage0.isValid) {
            throw Exception("Invalid MaterialPackage")
        }
        material0 = Material.Builder().payload(materialPackage0.buffer, materialPackage0.buffer.limit()).build(engine)

        // Material 1

        val mb1 = MaterialBuilder()
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
                    material.baseColor = texture(materialParams_baseColorMap, getUV0());
                    material.roughness = 0.25;
                }
            """
            mb1.material(sb)
        } else {
            val sb = """
                void material(inout MaterialInputs material) { 
                    prepareMaterial(material); 
                    material.baseColor = texture(materialParams_baseColorMap, getUV0());
                }
            """
            mb1.material(sb)
        }

        val materialPackage1 = mb1.build()
        if (!materialPackage1.isValid) {
            throw Exception("Invalid MaterialPackage")
        }
        material1 = Material.Builder().payload(materialPackage1.buffer, materialPackage1.buffer.limit()).build(engine)

    }

    private fun setupMaterial() {
        Log.i(TAG, "setupMaterial")
        // Create an instance of the material to set different parameters on it
        materialInstance0 = material0.createInstance()
        materialInstance1 = material1.createInstance()

        // Note that the textures are stored in drawable-nodpi to prevent the system
        // from automatically resizing them based on the display's density
        baseColor = loadTexture(engine, context.resources, R.drawable.uvchecker)

        // A texture sampler does not need to be kept around or destroyed
        val sampler = TextureSampler()
        sampler.magFilter = TextureSampler.MagFilter.LINEAR
        sampler.minFilter = TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR
        sampler.wrapModeS = TextureSampler.WrapMode.REPEAT
        sampler.wrapModeT = TextureSampler.WrapMode.REPEAT

        materialInstance0.setParameter("baseColorMap", baseColor, sampler)
        materialInstance1.setParameter("baseColorMap", baseColor, sampler)
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
        destroyMesh(engine, mesh0)
        destroyMesh(engine, mesh1)
        engine.destroyTexture(baseColor)
        engine.destroyEntity(light)
        engine.destroyRenderer(renderer)
        engine.destroyMaterialInstance(materialInstance0)
        engine.destroyMaterialInstance(materialInstance1)
        engine.destroyMaterial(material0)
        engine.destroyMaterial(material1)
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

                // Rotate, scale, and center  model 0
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.rotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 49_997_117.0).toFloat(), 1f, 0.5f, 0f)
                Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
                Matrix.translateM(modelMatrix, 0, translation.x, translation.y, translation.z)
                engine.transformManager.setTransform(engine.transformManager.getInstance(mesh0.renderable), modelMatrix)

                // Rotate, scale, and center model 1
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.rotateM(modelMatrix, 0, (frameTimeNanos.toDouble() / 100_000_049.0).toFloat(), -0.7f, 0.25f, 0.1f)
                Matrix.scaleM(modelMatrix, 0, scale, scale, scale)
                Matrix.translateM(modelMatrix, 0, translation.x, translation.y, translation.z)
                engine.transformManager.setTransform(engine.transformManager.getInstance(mesh1.renderable), modelMatrix)

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
            camera.setProjection(20.0, aspect, 0.1, 1_000.0, Camera.Fov.VERTICAL)
            camera.lookAt(0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            view.viewport = Viewport(0, 0, width, height)
        }
    }

}
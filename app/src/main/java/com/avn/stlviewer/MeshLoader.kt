package com.avn.stlviewer

import com.google.android.filament.*
import com.google.android.filament.VertexBuffer.AttributeType.*
import com.google.android.filament.VertexBuffer.VertexAttribute.*
import java.nio.ByteBuffer

data class Mesh(
    @Entity val renderable: Int,
    val indexBuffer: IndexBuffer,
    val vertexBuffer: VertexBuffer)

fun destroyMesh(engine: Engine, mesh: Mesh) {
    engine.destroyEntity(mesh.renderable)
    engine.destroyIndexBuffer(mesh.indexBuffer)
    engine.destroyVertexBuffer(mesh.vertexBuffer)
    EntityManager.get().destroy(mesh.renderable)
}

fun loadMesh(engine: Engine, asset: STLAsset, material: MaterialInstance): Mesh {

        val indexBuffer = createIndexBuffer(engine, asset)
        val vertexBuffer = createVertexBuffer(engine, asset)

        val renderable = createRenderable(engine, asset, indexBuffer, vertexBuffer, material)

        return Mesh(renderable, indexBuffer, vertexBuffer)
}

private fun createIndexBuffer(engine: Engine, asset: STLAsset): IndexBuffer {
    return IndexBuffer.Builder()
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .indexCount(asset.vertexCount)
        .build(engine)
        .apply { setBuffer(engine, asset.indxs) }
}

private fun createVertexBuffer(engine: Engine, asset: STLAsset): VertexBuffer {

    // Allocate space for qtangents
    val tangs = ByteBuffer.allocateDirect(asset.vertexCount * 4 * 4)
    // Compose quat request
    val qtc = VertexBuffer.QuatTangentContext()
    qtc.quatCount = asset.vertexCount
    qtc.quatType = VertexBuffer.QuatType.FLOAT4
    qtc.outBuffer = tangs
    // Seed with normals and tangents (if available)
    qtc.normals = asset.norms
    // Populate qtangent array
    VertexBuffer.populateTangentQuaternions(qtc)


   val vertexBufferBuilder = VertexBuffer.Builder()
        .bufferCount(3)
        .vertexCount(asset.vertexCount)
        .attribute(POSITION, 0, FLOAT3)
        .attribute(COLOR, 1, FLOAT3)
        .attribute(UV0, 1, FLOAT2)
        .attribute(TANGENTS, 2, FLOAT4)
    return vertexBufferBuilder.build(engine).apply {
        setBufferAt(engine, 0, asset.verts)
        setBufferAt(engine, 1, asset.norms)
        setBufferAt(engine, 2, tangs)
    }
}

private fun createRenderable(
    engine: Engine,
    asset: STLAsset,
    indexBuffer: IndexBuffer,
    vertexBuffer: VertexBuffer,
    material: MaterialInstance): Int {

    val builder = RenderableManager.Builder(1).boundingBox(
        Box(
            asset.bounds.center.x, asset.bounds.center.y, asset.bounds.center.z,
            asset.bounds.size.x / 2, asset.bounds.size.y / 2, asset.bounds.size.z / 2))

        builder.geometry(0,
            RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer,
            indexBuffer)
        builder.material(0, material)

    return EntityManager.get().create().apply { builder.build(engine, this) }
}

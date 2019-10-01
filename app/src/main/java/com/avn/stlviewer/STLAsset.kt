package com.avn.stlviewer

import com.avn.stlviewer.geometry.BoundingBox
import com.avn.stlviewer.geometry.Vector3
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Note: STLs for 3D printing usually have UP as +Z so it is convinient to map STL +Z to OpenGL +Y and STL +Y to OpenGL -Z

class STLAsset(private val mBuffer: ByteBuffer) {

    var triangleCount : Int = 0
    var vertexCount : Int = 0

    lateinit var verts : ByteBuffer
    lateinit var norms : ByteBuffer
    lateinit var indxs : ByteBuffer

    var bounds = BoundingBox.empty; private set

    init {
        if(isAsciiSTL(mBuffer)) {
            throw Exception("ASCII file are not format")
        } else if(isBinarySTL(mBuffer)){
            loadBinaryFile()
        } else {
            throw Exception("File is not in STL format")
        }
    }

    companion object {

        infix fun ByteBuffer.startsWith(string: String) = string.all { get() == it.toByte() }

        // A valid binary stl buffer should consist of the following elements, in order:
        // 1) 80 byte header
        // 2) 4 byte face count
        // 3) 50 bytes per face
        fun isBinarySTL(buffer: ByteBuffer): Boolean {
            if (buffer.limit() < 84) {
                return false
            }
            val faceCount = buffer.getInt(80)
            val expectedBinaryFileSize = faceCount * 50 + 84

            return expectedBinaryFileSize == buffer.limit()
        }

        // An ascii stl buffer will begin with "solid NAME", where NAME is optional.
        // Note: The "solid NAME" check is necessary, but not sufficient, to determine if the buffer is ASCII;
        // a binary header could also begin with "solid NAME".
        fun isAsciiSTL(buffer: ByteBuffer): Boolean {
            var isASCII = buffer startsWith "solid"
            if (isASCII) {
                // A lot of importers start with "solid" even if the file is binary. So we have to check for ASCII-characters.
                // Check the first 500 chars (arbitrary) for non-ASCII bytes
                if (buffer.limit() >= 500) {
                    isASCII = true
                    for (i in 0 until 500) {
                        val char = buffer.get()
                        if (char < 0 || char > 127) {
                            return false
                        }
                    }
                }
            }
            return isASCII
        }
    }

    // Binary format from https://en.wikipedia.org/wiki/STL_(file_format)#Binary_STL
    //
    //    UINT8[80] – Header
    //    UINT32 – Number of triangles
    //
    //    foreach triangle
    //    REAL32[3] – Normal vector
    //    REAL32[3] – Vertex 1
    //    REAL32[3] – Vertex 2
    //    REAL32[3] – Vertex 3
    //    UINT16 – Attribute byte count
    //    end

    private fun loadBinaryFile() {
        mBuffer.rewind()
        val fileSize = mBuffer.limit()

        // Skip the first 80 bytes
        if (fileSize < 84) {
            throw Exception("stl: file is too small for the header")
        }
        mBuffer.position(80)
        triangleCount = mBuffer.int

        if (triangleCount <= 0) {
            throw Exception("stl: file is empty. There are no facets defined")
        }

        if (fileSize < 84 + triangleCount * 50) {
            throw Exception("stl: file is too small to hold all facets")
        }

        vertexCount = triangleCount * 3

        // Read verticies and normals and compose indicies

        verts = ByteBuffer.allocateDirect(vertexCount * 3 * 4).order(ByteOrder.nativeOrder())
        norms = ByteBuffer.allocateDirect(vertexCount * 3 * 4).order(ByteOrder.nativeOrder())
        indxs = ByteBuffer.allocateDirect(vertexCount * 4).order(ByteOrder.nativeOrder())
        for(i in 0 until triangleCount) {
            val nx = mBuffer.float
            val nz = -mBuffer.float
            val ny = mBuffer.float
            // STL triangles are flat, so the normal must be duplicated for each vertex
            norms.putFloat(nx); norms.putFloat(ny); norms.putFloat(nz)
            norms.putFloat(nx); norms.putFloat(ny); norms.putFloat(nz)
            norms.putFloat(nx); norms.putFloat(ny); norms.putFloat(nz)
            // Update bounds
            for(j in 0 until 3) {
                indxs.putInt(i * 3 + j)
                val x = mBuffer.float
                val z = -mBuffer.float
                val y = mBuffer.float
                verts.putFloat(x); verts.putFloat(y); verts.putFloat(z)
                bounds += Vector3(x, y, z)
            }
            // Vestigial postamble
            mBuffer.short
        }

        verts.rewind()
        norms.rewind()
        indxs.rewind()

        if(bounds.isEmpty) {
            throw Exception("Unexpected empty bounds")
        }

    }
}
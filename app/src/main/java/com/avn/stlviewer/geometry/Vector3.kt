package com.avn.stlviewer.geometry

data class Vector3(val x: Float = 0.0f, val y: Float = 0.0f, val z: Float = 0.0f) {

    companion object {
        val min = Vector3(-Float.MAX_VALUE)
        val max = Vector3(+Float.MAX_VALUE)
    }

    operator fun unaryMinus() = Vector3(-x, -y, -z)

    operator fun plus(v: Vector3) =
        Vector3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vector3) =
        Vector3(x - v.x, y - v.y, z - v.z)

}

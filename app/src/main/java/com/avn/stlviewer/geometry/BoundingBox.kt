package com.avn.stlviewer.geometry

data class BoundingBox(val min: Vector3, val max: Vector3) {
    companion object {
        val empty = BoundingBox(
            Vector3.max,
            Vector3.min
        )
    }

    inline val isEmpty : Boolean
        get() = min.x > max.x || min.y > max.y || min.z > max.z

    inline val center : Vector3
        get() = Vector3(
            (max.x + min.x) / 2,
            (max.y + min.y) / 2,
            (max.z + min.z) / 2
        )

    inline val size : Vector3
        get() = Vector3(max.x - min.x, max.y - min.y, max.z - min.z)

    operator fun plus(rhs: Vector3) = BoundingBox(
        Vector3(
            Math.min(min.x, rhs.x),
            Math.min(min.y, rhs.y),
            Math.min(min.z, rhs.z)
        ),
        Vector3(
            Math.max(max.x, rhs.x),
            Math.max(max.y, rhs.y),
            Math.max(max.z, rhs.z)
        )
    )

    operator fun plus(rhs: BoundingBox) =
        BoundingBox(
            Vector3(
                Math.min(min.x, rhs.min.x),
                Math.min(min.y, rhs.min.y),
                Math.min(min.z, rhs.min.z)
            ),
            Vector3(
                Math.max(max.x, rhs.max.x),
                Math.max(max.y, rhs.max.y),
                Math.max(max.z, rhs.max.z)
            )
        )
}
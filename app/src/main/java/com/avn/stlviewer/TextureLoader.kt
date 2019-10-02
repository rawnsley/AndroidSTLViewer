package com.avn.stlviewer

import android.content.res.Resources
import android.graphics.BitmapFactory
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.android.TextureHelper

fun loadTexture(engine: Engine, resources: Resources, resourceId: Int): Texture {
    val options = BitmapFactory.Options()

    val bitmap = BitmapFactory.decodeResource(resources, resourceId, options)

    val texture = Texture.Builder()
        .width(bitmap.width)
        .height(bitmap.height)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8)
        .levels(0xff)
        .build(engine)

    TextureHelper.setBitmap(engine, texture, 0, bitmap)
    texture.generateMipmaps(engine)

    return texture
}


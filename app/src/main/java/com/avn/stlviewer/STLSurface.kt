package com.avn.stlviewer

import android.content.Context
import android.opengl.GLSurfaceView

class STLSurface(context : Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8 , 8, 8, 8, 16, 4);
        setRenderer(STLRenderer(context))
    }
}
package com.avn.stlviewer

import android.opengl.*
import android.util.Log
import android.view.SurfaceView

// Create an EGLContext on the instantiation thread
// and connect it to the given SurfaceView
class EGLManager(surfaceView: SurfaceView) {

    companion object {
        private val TAG = EGLManager::class.java.simpleName
    }

    private val eglContext : EGLContext

    init {
        val intArray = IntArray(2)

        // Initialize the display connection
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, intArray, 0, intArray, 1)

        // Default attributes
        val eglAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
//            EGL14.EGL_SAMPLE_BUFFERS, 1,
//            EGL14.EGL_SAMPLES, 2,
            EGL14.EGL_NONE
        )

        // Select EGL first EGL config that matches the attributes
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, eglAttribs, 0, configs, 0, 1, intArray, 0)
        val configCount = intArray[0]
        Log.i(TAG, "Found $configCount configs matching the target")
        if(configCount == 0) {
            throw Exception("No valid configs could be found")
        }
        val eglConfig = configs[0]!!

        // Create the new EGLContext
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // Connect an EGLSurface to the given SurfaceView
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceView, null, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }


}
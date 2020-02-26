package com.avn.stlviewer

import android.app.Activity
import android.os.Bundle
import android.os.StrictMode
import android.view.SurfaceView

class MainActivity : Activity() {

    companion object {
        init {
            // Stop the OS closing the app for ANR (acceptable for a tech demo)
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)
        }
    }

    private lateinit var stlRenderer : STLRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surfaceView = SurfaceView(this)
        stlRenderer = STLRenderer(this, surfaceView)
        setContentView(surfaceView)
    }

    override fun onPause() {
        super.onPause()
        stlRenderer.pause()
    }

    override fun onResume() {
        super.onResume()
        stlRenderer.resume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        stlRenderer.focusChanged(hasFocus)
    }

    override fun onDestroy() {
        super.onDestroy()
        stlRenderer.destroy()
    }
}

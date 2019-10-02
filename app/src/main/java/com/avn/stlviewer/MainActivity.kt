package com.avn.stlviewer

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import com.google.android.filament.Filament
import com.google.android.filament.filamat.MaterialBuilder
import android.os.StrictMode

class MainActivity : Activity() {

    companion object {
        init {
            Filament.init()
            MaterialBuilder.init()

            // Ignore thread policy
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)

        }
    }

    lateinit var surfaceView : SurfaceView

    lateinit var stlRenderer: STLRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        stlRenderer = STLRenderer(this, surfaceView)
    }

    override fun onResume() {
        super.onResume()
        stlRenderer.resume()
    }

    override fun onPause() {
        super.onPause()
        stlRenderer.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stlRenderer.destroy()
    }
}

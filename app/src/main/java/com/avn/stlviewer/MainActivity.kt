package com.avn.stlviewer

import android.app.Activity
import android.os.Bundle
import android.os.StrictMode

class MainActivity : Activity() {

    companion object {
        init {
            // Stop the OS closing the app for ANR (acceptable for a tech demo)
            val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
            StrictMode.setThreadPolicy(policy)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(STLSurface(this))
    }
}

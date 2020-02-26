package com.avn.stlviewer

import android.opengl.GLES31
import android.opengl.GLU
import android.util.Log

class OpenGLUtil {
    companion object {

        private val TAG = OpenGLUtil::class.java.simpleName

        fun assertNoGlError() {
            val code = GLES31.glGetError()
            if (code != GLES31.GL_NONE){
                throw Exception("OpenGL Error: ${GLU.gluErrorString(code)} ($code)");
            }
        }

        fun compileShader(shaderType : Int, shaderSource : String) : Int {
            val handle = GLES31.glCreateShader(shaderType)
            assertNoGlError()

            GLES31.glShaderSource(handle, shaderSource)
            assertNoGlError()

            GLES31.glCompileShader(handle)
            assertNoGlError()

            // Write out any log messages that might be relevant
            val log = GLES31.glGetShaderInfoLog(handle)
            if (log.isNotEmpty()) {
                Log.w(TAG, "Shader warnings: $log")
            }

            // Check for compilation errors
            val result = intArrayOf(1)
            GLES31.glGetShaderiv(handle, GLES31.GL_COMPILE_STATUS, result, 0)
            if (result [0] == GLES31.GL_FALSE) {
                throw Exception ("Error in shader: $log")
            }

            return handle
        }

    }
}
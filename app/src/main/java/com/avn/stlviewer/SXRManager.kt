package com.avn.stlviewer

import android.app.Activity
import android.util.Log
import android.view.Surface
import com.qualcomm.sxrapi.SxrApi

class SXRManager(val activity : Activity, surface: Surface)  {

    companion object {
        private val TAG = SXRManager::class.java.simpleName
    }

    private val sxrApiRenderer: SxrApi
    private var sxrFrameParams: SxrApi.sxrFrameParams
    private var sxrBeginParams: SxrApi.sxrBeginParams

    val deviceInfo : SxrApi.sxrDeviceInfo

    init {
        Log.i(TAG, "init")
        SxrApi.sxrInitialize(activity)

        deviceInfo = SxrApi.sxrGetDeviceInfo()

        sxrApiRenderer = SxrApi()

        // Begin parameters will be used whenever VR mode is begun

        sxrBeginParams = sxrApiRenderer.sxrBeginParams(surface)

        // Set layout coords to the whole lens view

        val layoutCoords = sxrApiRenderer.sxrLayoutCoords()
        layoutCoords.LowerLeftPos =  floatArrayOf(-1f, -1f, 0f, +1f) // {-1,-1,0,1}
        layoutCoords.LowerRightPos = floatArrayOf(+1f, -1f, 0f, +1f) // {1,-1,0,1}
        layoutCoords.UpperLeftPos =  floatArrayOf(-1f, +1f, 0f, +1f) // {-1,1,0,1}
        layoutCoords.UpperRightPos = floatArrayOf(+1f, +1f, 0f, +1f) // {1,1,0,1}
        layoutCoords.LowerUVs = floatArrayOf(0f, 0f, 1f, 0f)
        layoutCoords.UpperUVs = floatArrayOf(0f, 1f, 1f, 1f)
        layoutCoords.TransformMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        // Initialize frame parameters (will be updated and submitted every frame)

        sxrFrameParams = sxrApiRenderer.sxrFrameParams()

        sxrFrameParams.minVsyncs = 1

        sxrFrameParams.renderLayers[0].imageType = SxrApi.sxrTextureType.kTypeTexture
        sxrFrameParams.renderLayers[0].eyeMask = SxrApi.sxrEyeMask.kEyeMaskLeft
        // Should possible be opaque (2)
        sxrFrameParams.renderLayers[0].layerFlags = 0

        sxrFrameParams.renderLayers[1].imageType = SxrApi.sxrTextureType.kTypeTexture
        sxrFrameParams.renderLayers[1].eyeMask = SxrApi.sxrEyeMask.kEyeMaskRight
        // Should possible be opaque (2)
        sxrFrameParams.renderLayers[1].layerFlags = 0

        sxrFrameParams.fieldOfView = 180f * deviceInfo.targetFovYRad / Math.PI.toFloat()
        sxrFrameParams.renderLayers[0].imageCoords = layoutCoords
        sxrFrameParams.renderLayers[1].imageCoords = layoutCoords

        // Initialize performance and tracking modes

        SxrApi.sxrSetPerformanceLevels(SxrApi.sxrPerfLevel.kPerfMaximum, SxrApi.sxrPerfLevel.kPerfMaximum)
        SxrApi.sxrSetTrackingMode(SxrApi.sxrTrackingMode.kTrackingRotation.trackingMode)
    }

    // Is rendering allowed?
    val isReady : Boolean
        get() = isInVRMode

    // Has VR mode begun?
    private var isInVRMode = false

    // Is the Activity resumed?
    private var isResumed = false

    // Has the surface been created?
    private var isCaptured = false

    // Should be called in SufaceHolder.Callback.surfaceChanged
    fun captureSurface() {
        Log.i(TAG, "captureSurface")
        isCaptured = true
        if(!isInVRMode && isResumed) {
            //TODO: This can timeout and fail without throwing an exception so that isBegun isn't true
            SxrApi.sxrBeginXr(activity, sxrBeginParams)
            isInVRMode = true
        }
    }

    // Should be called in SufaceHolder.Callback.surfaceDestroyed
    fun releaseSurface() {
        Log.i(TAG, "releaseSurface")
        isCaptured = false
        if(isInVRMode) {
            SxrApi.sxrEndXr()
            isInVRMode = false
        }
    }

    // Should be called in Activity.onResume
    fun resume() {
        Log.i(TAG, "resume")
        isResumed = true
        if(!isInVRMode && isCaptured) {
            SxrApi.sxrBeginXr(activity, sxrBeginParams)
            isInVRMode = true
        }
    }

    // Should be called in Activity.onPause
    fun pause() {
        Log.i(TAG, "pause")
        isResumed = false
        if(isInVRMode) {
            isInVRMode = false
            SxrApi.sxrEndXr()
        }
    }

    // Should be called in Activity.onDestroy
    fun destroy() {
        Log.i(TAG, "destroy")
        SxrApi.sxrShutdown()
    }

    fun startFrame(leftTexture : Int, rightTexture : Int) : SxrApi.sxrHeadPoseState {
        if(isInVRMode) {
            sxrFrameParams.frameIndex++
            sxrFrameParams.renderLayers[0].imageHandle = leftTexture
            sxrFrameParams.renderLayers[1].imageHandle = rightTexture
            sxrFrameParams.headPoseState = SxrApi.sxrGetPredictedHeadPose(SxrApi.sxrGetPredictedDisplayTime())
        }
        return sxrFrameParams.headPoseState
    }

    fun endFrame() {
        if(isInVRMode) {
            SxrApi.sxrSubmitFrame(activity, sxrFrameParams)
        }
    }
}

// Extensions

// poseStatus is a bitfield that corresponds to sxrTrackingMode
fun SxrApi.sxrHeadPoseState.isValid() : Boolean = this.poseStatus > 0
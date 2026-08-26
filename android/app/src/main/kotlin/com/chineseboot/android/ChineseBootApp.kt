package com.chineseboot.android

import android.app.Application

/**
 * Application entry point. Holds the single shared [com.chineseboot.android.capture.CaptureRepository]
 * instance so the Activity, the capture service and the overlay service all observe the same state.
 */
class ChineseBootApp : Application() {
    lateinit var captureRepository: com.chineseboot.android.capture.CaptureRepository
        private set

    override fun onCreate() {
        super.onCreate()
        captureRepository = com.chineseboot.android.capture.CaptureRepository()
    }
}

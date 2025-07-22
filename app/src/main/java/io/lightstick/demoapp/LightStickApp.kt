package io.lightstick.demoapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * LightStick BLE SDK 데모 앱의 Application 클래스.
 * Hilt DI 컨테이너의 루트로 사용됩니다.
 */
@HiltAndroidApp
class LightStickApp : Application()

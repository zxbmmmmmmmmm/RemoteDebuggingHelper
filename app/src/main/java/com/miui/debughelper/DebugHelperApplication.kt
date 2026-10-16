package com.miui.debughelper

import android.app.Application
import com.miui.debughelper.data.DebugRepository

class DebugHelperApplication : Application() {
    val repository: DebugRepository by lazy { DebugRepository.get(this) }
}

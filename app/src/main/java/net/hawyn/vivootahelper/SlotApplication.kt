package net.hawyn.vivootahelper

import android.app.Application

class SlotApplication : Application() {
    val repository: OtaRepository by lazy { OtaRepository(this) }
}

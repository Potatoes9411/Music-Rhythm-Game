package com.rhythmphysics.app

import android.app.Application
import com.rhythmphysics.app.platform.Haptics
import com.rhythmphysics.app.session.SessionLoader
import com.rhythmphysics.app.session.SoundFontProvider
import com.rhythmphysics.app.store.CustomPresetStore
import com.rhythmphysics.app.store.RecentStore
import com.rhythmphysics.app.store.SettingsStore

/** Process-wide object graph (no DI framework needed at this size). */
class RhythmApp : Application() {
    lateinit var settings: SettingsStore; private set
    lateinit var recents: RecentStore; private set
    lateinit var presets: CustomPresetStore; private set
    lateinit var soundFonts: SoundFontProvider; private set
    lateinit var loader: SessionLoader; private set
    lateinit var haptics: Haptics; private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        recents = RecentStore(this)
        presets = CustomPresetStore(this)
        soundFonts = SoundFontProvider(this).also { it.useUser = settings.useUserSoundFont }
        loader = SessionLoader(this, soundFonts)
        haptics = Haptics(this).also { it.level = settings.haptics }
    }
}

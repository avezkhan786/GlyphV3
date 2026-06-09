import os
from pathlib import Path
base = Path(r'app\src\main\java\com\glyph\glyph_v3\ui\status')
f = base / 'StatusViewerScreen.kt'
t = f.read_text('utf-8')

old_sync = '''    // Sync isPaused with voice MediaPlayer
    LaunchedEffect(isPaused) {
        val mp = voiceMediaPlayer ?: return@LaunchedEffect
        if (currentStatus.type == StatusType.VOICE) {
            if (isPaused) {
                if (mp.isPlaying) mp.pause()
                voiceIsPlaying = false
            } else {
                mp.start()
                voiceIsPlaying = true
            }
        }
    }'''
t = t.replace(old_sync, '')

old_press = '''                        onPress = {
                            isPaused = true
                            tryAwaitRelease()
                            isPaused = false
                        }'''
new_press = '''                        onPress = {
                            isPaused = true
                            if (currentStatus.type == com.glyph.glyph_v3.data.status.StatusType.VOICE) {
                                voiceMediaPlayer?.pause()
                                voiceIsPlaying = false
                            }
                            tryAwaitRelease()
                            isPaused = false
                            if (currentStatus.type == com.glyph.glyph_v3.data.status.StatusType.VOICE) {
                                voiceMediaPlayer?.start()
                                voiceIsPlaying = true
                            }
                        }'''
t = t.replace(old_press, new_press)
f.write_text(t, 'utf-8')
print('Fixed pause delay')

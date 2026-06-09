import os
from pathlib import Path
f = Path(r"app\src\main\java\com\glyph\glyph_v3\ui\status\StatusViewerScreen.kt")
t = f.read_text('utf-8')
old = """                      onLongPress = { isPaused = true },
                      onPress = {
                          tryAwaitRelease()
                          isPaused = false
                      }"""
new = """                      onLongPress = { },
                      onPress = {
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
                      }"""
t = t.replace(old, new)
f.write_text(t, 'utf-8')

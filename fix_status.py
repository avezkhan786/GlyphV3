import os
from pathlib import Path

base = Path(r"app\src\main\java\com\glyph\glyph_v3\ui\status")

print("1. Fixing StatusUploadWorker.kt")
f1 = base / "StatusUploadWorker.kt"
t1 = f1.read_text('utf-8')
t1 = t1.replace(".setProgress(100, progress, progress == 0)", ".setProgress(100, progress, false)")
# Also let's fix the throttling so it reports every 1% instead of 5% to make it smoother
t1 = t1.replace("if (pct >= lastNotifiedProgress + 5)", "if (pct >= lastNotifiedProgress + 1)")
f1.write_text(t1, 'utf-8')

print("2. Fixing StatusViewerScreen.kt")
f2 = base / "StatusViewerScreen.kt"
t2 = f2.read_text('utf-8')
t2 = t2.replace("delay(100)", "delay(16)")
t2 = t2.replace(
    "trackColor = Color.White.copy(alpha = 0.3f),",
    "trackColor = Color.White.copy(alpha = 0.3f),\n                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Square,"
)
t2 = t2.replace(
    "trackColor = Color.White.copy(alpha = 0.3f)\n",
    "trackColor = Color.White.copy(alpha = 0.3f),\n                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Square\n"
)
f2.write_text(t2, 'utf-8')

print("3. Fixing MediaPreviewScreen.kt")
f3 = base / "MediaPreviewScreen.kt"
t3 = f3.read_text('utf-8')
if "import androidx.compose.animation.core.animateFloatAsState" not in t3:
    t3 = t3.replace("import androidx.compose.runtime.*", "import androidx.compose.runtime.*\nimport androidx.compose.animation.core.animateFloatAsState\n")
t3 = t3.replace("progress = { progress },", "progress = { animateFloatAsState(targetValue = progress).value },")
t3 = t3.replace("progress = { progress }", "progress = { animateFloatAsState(targetValue = progress).value }")
f3.write_text(t3, 'utf-8')

print("4. Fixing StatusListScreen.kt")
f4 = base / "StatusListScreen.kt"
t4 = f4.read_text('utf-8')
if "import androidx.compose.animation.core.animateFloatAsState" not in t4:
    t4 = t4.replace("import androidx.compose.runtime.*", "import androidx.compose.runtime.*\nimport androidx.compose.animation.core.animateFloatAsState\n")
t4 = t4.replace("progress = { uploadProgress },", "progress = { animateFloatAsState(targetValue = uploadProgress).value },")
t4 = t4.replace("progress = { uploadProgress }", "progress = { animateFloatAsState(targetValue = uploadProgress).value }")
f4.write_text(t4, 'utf-8')

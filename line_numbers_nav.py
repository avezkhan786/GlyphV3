from pathlib import Path
path = Path('app/src/main/java/com/glyph/glyph_v3/ui/widgets/GlyphBottomNavigationView.kt')
lines = path.read_text().splitlines()
for i, line in enumerate(lines, start=1):
    if 1 <= i <= 200:
        print(f"{i:04d}: {line}")

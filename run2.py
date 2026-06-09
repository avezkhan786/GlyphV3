@
import sys

text = open('app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt', encoding='utf-8').read()
level = 0
for i, char in enumerate(text):
    if char == '{': level += 1
    elif char == '}': 
        level -= 1
        if level < 0:
            lines = text[:i].count('\n') + 1
            print(f'Negative balance at line {lines}')
            sys.exit(0)
print(f'Final balance: {level}')
@

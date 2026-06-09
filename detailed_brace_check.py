
import re

def check_braces(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # Remove comments
    content = re.sub(r'//.*', '', content)
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)
    
    # Remove strings (handle escaped quotes)
    content = re.sub(r'"(?:\\.|[^"\\])*"', '""', content)
    # Remove char literals
    content = re.sub(r"'(?:\\.|[^'\\])'", "''", content)

    stack = []
    lines = content.splitlines()
    
    for i, line in enumerate(lines):
        for char in line:
            if char == '{':
                stack.append(i + 1)
            elif char == '}':
                if not stack:
                    print(f"Extra closing brace at line {i + 1}")
                    # return # Continue to find more
                else:
                    stack.pop()

    if stack:
        print(f"Unclosed braces starting at lines: {stack} (total {len(stack)} unclosed)")
    else:
        print("Braces are balanced")

check_braces(r"c:\Users\avezk\AndroidStudioProjects\GlyphV3\app\src\main\java\com\glyph\glyph_v3\ui\chat\ChatActivity.kt")

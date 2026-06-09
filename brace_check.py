
def check_braces(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    stack = []
    
    for i, line in enumerate(lines):
        for char in line:
            if char == '{':
                stack.append(i + 1)
            elif char == '}':
                if not stack:
                    print(f"Extra closing brace at line {i + 1}")
                    return
                stack.pop()

    if stack:
        print(f"Unclosed braces starting at lines: {stack[-5:]} (total {len(stack)} unclosed)")
    else:
        print("Braces are balanced")

check_braces(r"c:\Users\avezk\AndroidStudioProjects\GlyphV3\app\src\main\java\com\glyph\glyph_v3\ui\chat\ChatActivity.kt")

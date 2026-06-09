import sys
text = open('app/src/main/java/com/glyph/glyph_v3/ui/chat/ChatActivity.kt', encoding='utf-8').read()
in_string = False
in_line_comment = False
in_block_comment = False
while i < len(text):
    char = text[i]
    if in_line_comment:
        if char == '\n': in_line_comment = False
        i += 1; continue
    if in_block_comment:
        if char == '*' and i+1 < len(text) and text[i+1] == '/':
            in_block_comment = False; i += 
        i += 1; continue
    if in_string:
        if char == '\': i += 2; continue
        if char == '\" ": in_string = False
    elif char == '/' and i+1 < len(text) and text[i+1] == '/': in_line_comment = True
    elif char == '/' and i+1 < len(text) and text[i+1] == '*': in_block_comment = True
    elif char == '{': level += 
    elif char == '}':
        level -= 
        if level < 0:
            lines = text[:i].count('\n') + 
            print('Negative balance at line', lines)
            sys.exit(0)
    i += 
print('Final balance:', level)

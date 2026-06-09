@
import sys
import re

text = open('build_errors3.txt', encoding='utf-16').read()
for line in text.splitlines():
    if 'Unresolved reference' in line:
        print(line.strip())
        sys.stdout.flush()
@

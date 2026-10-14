import re

with open('app/src/main/java/com/anonrode/downloader/resolvers/Resolvers.kt', 'r', encoding='utf-8') as f:
    code = f.read()

# remove multiline comments
clean = re.sub(r'/\*.*?\*/', '', code, flags=re.DOTALL)
# remove single line comments
clean = re.sub(r'//.*', '', clean)
# remove triple quoted strings
clean = re.sub(r'"""[\s\S]*?"""', '', clean)
# remove single quoted strings
clean = re.sub(r'"(\\.|[^"\\])*"', '', clean)

opens = clean.count('{')
closes = clean.count('}')
print(f"Resolvers.kt: opens={opens}, closes={closes}, balanced={opens == closes}")

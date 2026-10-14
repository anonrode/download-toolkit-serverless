import re

def check_file(path):
    with open(path, 'r', encoding='utf-8') as f:
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
    print(f"{path}: opens={opens}, closes={closes}, balanced={opens == closes}")

check_file('app/src/main/java/com/anonrode/downloader/engine/InstagramPhotoMuxer.kt')
check_file('app/src/main/java/com/anonrode/downloader/engine/YoutubeDlDownloader.kt')
check_file('app/src/main/java/com/anonrode/downloader/data/net/HttpClient.kt')

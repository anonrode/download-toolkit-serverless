with open('loadedfiles_step5.html', 'r', encoding='utf-8') as f:
    text = f.read()

import re
m = re.search(r'link:\s*[\'"]([^\'"]+)[\'"]', text)
if m:
    raw = m.group(1)
    print("Raw link string repr:", repr(raw[:60]))
    print("Contains '\\u':", "\\u" in raw)
    # Test Kotlin unescapeJsUrl logic
    # In Kotlin: val m = Pattern.compile("""\\u([0-9a-fA-F]{4})""").matcher(str)
    # which in python regex is r'\\u([0-9a-fA-F]{4})'
    m_re = re.search(r'\\u([0-9a-fA-F]{4})', raw)
    print("m_re match:", m_re)

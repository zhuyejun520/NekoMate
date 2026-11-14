import json

src = "cards.json"
dst = "cards-fixed.json"
s = open(src, encoding="utf-8-sig").read()
out = []
in_string = False
escaped = False
i = 0
while i < len(s):
    ch = s[i]
    if in_string:
        out.append(ch)
        if escaped:
            escaped = False
        elif ch == "\\":
            escaped = True
        elif ch == '"':
            in_string = False
        i += 1
        continue
    if ch == '"':
        in_string = True
        out.append(ch)
        i += 1
        continue
    if ch == ",":
        j = i + 1
        while j < len(s) and s[j].isspace():
            j += 1
        if j < len(s) and s[j] in "}]":
            i += 1
            continue
    out.append(ch)
    i += 1
fixed = "".join(out)
data = json.loads(fixed)
open(dst, "w", encoding="utf-8").write(fixed)
print("fixed", len(data), dst)

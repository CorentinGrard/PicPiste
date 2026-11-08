#!/usr/bin/env python3
"""
Preview of the radar's aircraft icons (app/.../render/AircraftIcons.kt), read from the Kotlin
source itself: python3 tools/aircraft-icons-preview.py > icons.html, then open it.
Each icon at the radar's size (26 px) and enlarged, on the light and dark backgrounds.
"""

import os
import re
import sys

SRC = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "kotlin", "fr", "cvvm",
                   "picpiste", "render", "AircraftIcons.kt")

# Background and aircraft colours of the radar (render/Palette.kt), for the look only.
THEMES = [("Light", "#f4f5f7", "#1f6fb2"), ("Dark", "#0d1117", "#7cc4ff")]


def icons(text):
    """[(name, turns, [(path data, stroke width or 0)])] in file order."""
    out = []
    for m in re.finditer(r"val (\w+) = Icon\(listOf\((.*?)\n    \)(, turns = false)?\)", text, re.S):
        name, body, fixed = m.group(1), m.group(2), m.group(3)
        parts = []
        for p in re.finditer(r'(fill|line)\(\s*((?:"[^"]*"\s*\+?\s*)+)(?:,\s*([\d.]+)f)?\)', body):
            d = "".join(re.findall(r'"([^"]*)"', p.group(2)))
            parts.append((d, float(p.group(3)) if p.group(1) == "line" else 0.0))
        out.append((name, not fixed, parts))
    return out


def svg(parts, size, colour):
    body = []
    for d, width in parts:
        if width:
            body.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            body.append(f'<path d="{d}" fill="{colour}"/>')
    return f'<svg width="{size}" height="{size}" viewBox="0 0 40 40">{"".join(body)}</svg>'


def main():
    items = icons(open(SRC, encoding="utf-8").read())
    if not items:
        sys.exit("No icons found in " + SRC)
    rows = []
    for theme, bg, fg in THEMES:
        cells = "".join(
            f'<figure>{svg(parts, 120, fg)}<div class="small">{svg(parts, 26, fg)}</div>'
            f'<figcaption>{name.replace("_", " ").title()}{"" if turns else " (upright)"}</figcaption></figure>'
            for name, turns, parts in items)
        rows.append(f'<section style="background:{bg};color:{fg}"><h2>{theme}</h2><div class="grid">{cells}</div></section>')
    print(f"""<!doctype html><html><head><meta charset="utf-8"><title>Aircraft icons</title>
<style>
body {{ margin: 0; font-family: system-ui, sans-serif; }}
section {{ padding: 16px 24px; }}
h2 {{ margin: 0 0 12px; font-size: 16px; }}
.grid {{ display: grid; grid-template-columns: repeat(auto-fill, 150px); gap: 12px; }}
figure {{ margin: 0; text-align: center; }}
.small {{ height: 30px; }}
figcaption {{ font-size: 13px; }}
</style></head><body>{"".join(rows)}</body></html>""")


if __name__ == "__main__":
    main()

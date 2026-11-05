#!/usr/bin/env python3
"""Generates Compy's launcher icons, monochrome icon and tile icon from one set of shapes.

Pass a folder to also write an SVG preview of each icon there.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/res"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'

# A compsognathus bust in a 108 unit canvas, facing right: scalloped spine, slim snout, rounded body.
BODY = (
    "M58,27.6 C65,26.9 71,29.7 76,33.5 C79.6,36.2 83,38.9 85,41.3 C86.7,43.4 85.8,45.5 83.2,46.5 "
    "C77,48.9 70,50.3 64,50.7 C58.6,51.1 55.2,53.5 54.4,57.5 C53.4,62.7 56,67.5 58.4,72.3 C60.8,77.1 61.2,81.2 58.6,84.2 "
    "C57,86.1 54,87 50,87 L33.5,87 C28,87 25,84.2 24.6,79.4 C24.2,73.9 26,69.6 29.6,65.8 "
    "C25.2,62.2 27,55.2 33,56.4 C30.2,51.4 32.6,45.4 38.2,46.8 C36.2,42.2 39,36.8 43.6,38.2 C45,32.2 51,28.2 58,27.6 Z"
)
ARM = "M50,71 C55,70.6 61.5,72.8 65.6,76.6 C67.6,78.5 66,81.4 63.4,80.4 C59.4,78.9 55,78.8 50,79.2 Z"
SHADE = "M34,49 C40,54 48,55 56.5,52 C55.2,53.2 54.6,55.2 54.4,57.5 C54.2,60.5 55,63.4 56.2,66.2 C50,67 42,64 34,57 Z"
BELLY = (
    "M58.4,72.3 C60.8,77.1 61.2,81.2 58.6,84.2 C57,86.1 54,87 50,87 L41,87 "
    "C47.8,84.6 52.6,77.4 53.6,64.6 C54.6,67.4 56.9,69.3 58.4,72.3 Z"
)
MOUTH = "M84.3,45.3 C78,47.2 71,47.6 65.2,46.2 C63.8,45.8 62.8,45 62.2,43.9"
NOSE = "M78.2,39.9 a.95,.95 0 1,0 1.9,0 a.95,.95 0 1,0 -1.9,0 Z"
EYE = "M59.8,36.9 a3.9,4.5 0 1,0 7.8,0 a3.9,4.5 0 1,0 -7.8,0 Z"
GLINT = "M63.7,34.9 a1.3,1.3 0 1,0 2.6,0 a1.3,1.3 0 1,0 -2.6,0 Z"
GLINT_SMALL = "M61.4,39.5 a.62,.62 0 1,0 1.24,0 a.62,.62 0 1,0 -1.24,0 Z"
SQUARE = "M0,0 h108 v108 h-108 Z"

RAINBOW = [(0, "#39C8FF"), (0.27, "#8F5BFF"), (0.52, "#F0509C"), (0.77, "#FF8A2A"), (1, "#FFD640")]
BODY_LINE = (27, 88, 84, 31)


# Colors are #RRGGBB, with an opacity from 0 to 1 where one is needed.
def color(c, a=1.0):
    return (c, a)


def lin(x1, y1, x2, y2, stops):
    return {"type": "linear", "at": (x1, y1, x2, y2), "stops": stops}


def rad(cx, cy, r, stops):
    return {"type": "radial", "at": (cx, cy, r), "stops": stops}


def stop(offset, c, a=1.0):
    return (offset, c, a)


def path(d, fill=None, alpha=1.0, stroke=None, width=0.0):
    return {"d": d, "fill": fill, "alpha": alpha, "stroke": stroke, "width": width}


def group(children, translate=None, scale=None, clip=None):
    return {"children": children, "translate": translate, "scale": scale, "clip": clip}


def fit(children):
    """Sizes the mascot to sit inside the part of the icon every launcher's mask keeps."""
    return group([group(children, translate=(-55, -57))], translate=(54.5, 55), scale=0.86)


def face(ink="#1B0F3D", mouth="#5A1466"):
    return [
        path(MOUTH, stroke=mouth, width=1.2),
        path(NOSE, fill=mouth, alpha=0.65),
        path(EYE, fill=ink),
        path(GLINT, fill="#FFFFFF"),
        path(GLINT_SMALL, fill="#FFFFFF", alpha=0.85),
    ]


def mascot(stops, shade="#2A0A5E", shade_alpha=0.3, arm="#7A3FD6", highlight=0.5, belly=0.13, mouth="#5A1466"):
    shaded = [
        path(SHADE, fill=lin(56, 54, 37, 60, [stop(0, shade, shade_alpha), stop(1, shade, 0)])),
        path(BELLY, fill="#FFFFFF", alpha=belly),
    ]
    if highlight:
        shaded.append(path(BODY, fill=rad(67, 31, 24, [stop(0, "#FFFFFF", highlight), stop(1, "#FFFFFF", 0)])))
    return [
        path(ARM, fill=arm),
        path(BODY, fill=lin(*BODY_LINE, stops)),
        group(shaded, clip=BODY),
        *face(mouth=mouth),
    ]


def rainbow_stops():
    return [stop(o, c) for o, c in RAINBOW]


def cast_shadow(dx, dy, c, alpha):
    return group([path(BODY, fill=c, alpha=alpha)], translate=(dx, dy))


def paper():
    layers = [
        group([path(BODY, fill=c)], translate=(dx, dy))
        for dx, dy, c in [(-6.6, 4.2, "#FFD640"), (-4.4, 2.8, "#FF8A2A"), (-2.2, 1.4, "#F0509C")]
    ]
    front = [
        path(ARM, fill="#3E25B8"),
        path(BODY, fill=lin(*BODY_LINE, [stop(0, "#5B3BE0"), stop(1, "#39C8FF")])),
        *face(mouth="#14106B"),
    ]
    return [group([*layers, *front], translate=(2.6, -1.4))]


def stripes():
    colors = [c for _, c in RAINBOW]
    band = 13.5
    top = 92
    bands = [path(f"M0,120 L108,120 L108,{top - 30} L0,{top + 18} Z", fill=colors[0])]
    for i, c in enumerate(colors):
        y = top - i * band
        bands.append(path(f"M0,{y + 18} L108,{y - 30} L108,{y - 30 - band} L0,{y + 18 - band} Z", fill=c))
    y = top - 4 * band
    bands.append(path(f"M0,0 L108,0 L108,{y - 30 - band} L0,{y + 18 - band} Z", fill=colors[-1]))
    return [path(ARM, fill="#1FA6E0"), group(bands, clip=BODY), *face(ink="#2B1140", mouth="#2B1140")]


ICONS = {
    "refined": dict(
        bg=[path(SQUARE, fill=lin(0, 0, 108, 108, [stop(0, "#FFFFFF"), stop(1, "#EFE6FF")]))],
        fg=mascot(rainbow_stops()),
    ),
    "pastel": dict(
        bg=[path(SQUARE, fill=lin(10, 100, 98, 8, [stop(0, "#D9F3FF"), stop(0.5, "#EBDFFF"), stop(1, "#FFE9D6")]))],
        fg=[cast_shadow(1.4, 2.4, "#5B3BB0", 0.13), *mascot(rainbow_stops())],
    ),
    "midnight": dict(
        bg=[
            path(SQUARE, fill=lin(0, 0, 108, 108, [stop(0, "#1D1A33"), stop(1, "#0C0B16")])),
            path(SQUARE, fill=rad(58, 52, 40, [stop(0, "#8F5BFF", 0.42), stop(1, "#8F5BFF", 0)])),
        ],
        fg=mascot(rainbow_stops(), shade="#12062F", shade_alpha=0.42),
    ),
    "rainbow": dict(
        bg=[path(SQUARE, fill=lin(14, 98, 96, 10, rainbow_stops()))],
        fg=[
            cast_shadow(1, 1.8, "#2A0A5E", 0.14),
            *mascot([stop(0, "#E6DCFF"), stop(1, "#FFFFFF")], shade="#4A1E96", shade_alpha=0.26, arm="#C9B6F5", highlight=0, belly=0, mouth="#6C4BB0"),
        ],
    ),
    "paper": dict(
        bg=[path(SQUARE, fill=lin(0, 0, 108, 108, [stop(0, "#FFFDF8"), stop(1, "#FFF0E2")]))],
        fg=paper(),
    ),
    "stripes": dict(
        bg=[path(SQUARE, fill="#FFF8EC")],
        fg=stripes(),
    ),
}


def hex_color(c, a=1.0):
    if a >= 1:
        return c
    return f"#{round(a * 255):02X}{c[1:]}"


def gradient_xml(g, alpha):
    items = "".join(f'<item android:offset="{o}" android:color="{hex_color(c, a * alpha)}"/>' for o, c, a in g["stops"])
    if g["type"] == "linear":
        x1, y1, x2, y2 = g["at"]
        where = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
    else:
        cx, cy, r = g["at"]
        where = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
    return f'<aapt:attr name="android:fillColor"><gradient {where}>{items}</gradient></aapt:attr>'


def node_xml(n):
    if "children" in n:
        attrs = ""
        if n["translate"]:
            attrs += f' android:translateX="{n["translate"][0]}" android:translateY="{n["translate"][1]}"'
        if n["scale"]:
            attrs += f' android:scaleX="{n["scale"]}" android:scaleY="{n["scale"]}"'
        clip = f'<clip-path android:pathData="{n["clip"]}"/>' if n["clip"] else ""
        return f"<group{attrs}>{clip}{''.join(node_xml(c) for c in n['children'])}</group>\n"
    attrs = f'android:pathData="{n["d"]}"'
    if n["stroke"]:
        attrs += f' android:strokeColor="{n["stroke"]}" android:strokeWidth="{n["width"]}" android:strokeLineCap="round" android:fillColor="#00000000"'
    if isinstance(n["fill"], dict):
        return f'<path {attrs}>{gradient_xml(n["fill"], n["alpha"])}</path>\n'
    if n["fill"]:
        attrs += f' android:fillColor="{hex_color(n["fill"], n["alpha"])}"'
    return f"<path {attrs}/>\n"


def vector(nodes, size=108):
    return (
        f'<?xml version="1.0" encoding="utf-8"?>\n<vector {NS}\n'
        f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="108" android:viewportHeight="108">\n'
        f"{''.join(node_xml(n) for n in nodes)}</vector>\n"
    )


def adaptive(name):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="@drawable/ic_launcher_bg_{name}" />\n'
        f'    <foreground android:drawable="@drawable/ic_launcher_fg_{name}" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_mono" />\n'
        "</adaptive-icon>\n"
    )


def svg_defs_and_body(nodes, prefix):
    defs, count = [], [0]

    def paint(g, alpha):
        count[0] += 1
        i = f"{prefix}{count[0]}"
        stops = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a * alpha}"/>' for o, c, a in g["stops"])
        if g["type"] == "linear":
            x1, y1, x2, y2 = g["at"]
            defs.append(f'<linearGradient id="{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
        else:
            cx, cy, r = g["at"]
            defs.append(f'<radialGradient id="{i}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{stops}</radialGradient>')
        return f"url(#{i})"

    def walk(n):
        if "children" in n:
            parts = []
            if n["translate"]:
                parts.append(f'translate({n["translate"][0]} {n["translate"][1]})')
            if n["scale"]:
                parts.append(f'scale({n["scale"]})')
            attrs = f' transform="{" ".join(parts)}"' if parts else ""
            if n["clip"]:
                count[0] += 1
                c = f"{prefix}{count[0]}"
                defs.append(f'<clipPath id="{c}"><path d="{n["clip"]}"/></clipPath>')
                attrs += f' clip-path="url(#{c})"'
            return f"<g{attrs}>{''.join(walk(k) for k in n['children'])}</g>"
        if n["stroke"]:
            return f'<path d="{n["d"]}" fill="none" stroke="{n["stroke"]}" stroke-width="{n["width"]}" stroke-linecap="round"/>'
        if isinstance(n["fill"], dict):
            return f'<path d="{n["d"]}" fill="{paint(n["fill"], n["alpha"])}"/>'
        return f'<path d="{n["d"]}" fill="{n["fill"]}" fill-opacity="{n["alpha"]}"/>'

    body = "".join(walk(n) for n in nodes)
    return "".join(defs), body


def main():
    drawable = ROOT / "drawable"
    mipmap = next(ROOT.glob("mipmap-anydpi*"))
    for name, icon in ICONS.items():
        (drawable / f"ic_launcher_bg_{name}.xml").write_text(vector(icon["bg"]))
        (drawable / f"ic_launcher_fg_{name}.xml").write_text(vector([fit(icon["fg"])]))
        (mipmap / f"ic_launcher_{name}.xml").write_text(adaptive(name))
    (mipmap / "ic_launcher.xml").write_text(adaptive("refined"))
    (drawable / "ic_launcher_mono.xml").write_text(
        vector([fit([path(ARM, fill="#FFFFFF"), path(BODY, fill="#FFFFFF")])])
    )
    tile = group([{"d": BODY + " " + EYE, "fill": "#FFFFFF", "alpha": 1.0, "stroke": None, "width": 0.0}], translate=(1, -3))
    tile_xml = vector([tile], size=24).replace('<path android:pathData', '<path android:fillType="evenOdd" android:pathData')
    (drawable / "ic_tile.xml").write_text(tile_xml)

    if len(sys.argv) > 1:
        for name, icon in ICONS.items():
            defs, body = svg_defs_and_body([*icon["bg"], fit(icon["fg"])], name)
            Path(sys.argv[1], f"icon-{name}.svg").write_text(
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="512" height="512"><defs>{defs}</defs>{body}</svg>'
            )


main()

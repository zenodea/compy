#!/usr/bin/env python3
"""Generates Compy's launcher icons, monochrome icon and tile icon from one set of shapes.

Pass a folder to also write an SVG preview of each icon there, and icon-store.svg: the refined
icon in a rounded mask. The picture in the store listing and the README is made from that one:

    rsvg-convert -w 512 -h 512 icon-store.svg -o fastlane/metadata/android/en-US/images/icon.png
"""
import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "app/src/main/res"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'


def f(v, places=2):
    """A number as short as it can be written."""
    s = f"{v:.{places}f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def spline(points):
    """A closed, smooth outline through the points, as cubic curves."""
    n = len(points)
    out = [f"M{f(points[0][0])},{f(points[0][1])}"]
    for i in range(n):
        p0, p1, p2, p3 = (points[(i + k) % n] for k in (-1, 0, 1, 2))
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        out.append(f"C{f(c1[0])},{f(c1[1])} {f(c2[0])},{f(c2[1])} {f(p2[0])},{f(p2[1])}")
    return " ".join(out) + " Z"


def ellipse(cx, cy, rx, ry, turn=0.0):
    """An ellipse as four cubic curves, turned by a number of degrees."""
    k = 0.5522847498
    a = math.radians(turn)

    def at(x, y):
        return f"{f(cx + x * math.cos(a) - y * math.sin(a))},{f(cy + x * math.sin(a) + y * math.cos(a))}"

    quarters = [
        ((rx, k * ry), (k * rx, ry), (0, ry)),
        ((-k * rx, ry), (-rx, k * ry), (-rx, 0)),
        ((-rx, -k * ry), (-k * rx, -ry), (0, -ry)),
        ((k * rx, -ry), (rx, -k * ry), (rx, 0)),
    ]
    return f"M{at(rx, 0)} " + " ".join(f"C{at(*c1)} {at(*c2)} {at(*end)}" for c1, c2, end in quarters) + " Z"


# A compsognathus from the chest up in a 108 unit canvas, facing right: a round head with a blunt
# snout, a neck that curves back from it, spines down the back and one small arm. A launcher shows
# the middle 72 units, so the chest runs off the bottom. Every outline runs clockwise, which lets
# several share one path without leaving holes where they overlap.
HEAD_POINTS = [
    (40.0, 43.0), (42.6, 34.6), (50.4, 28.8), (59.4, 28.2), (66.0, 31.0), (71.6, 35.4), (77.6, 36.6),
    (83.0, 39.6), (85.2, 44.6), (82.8, 49.4), (76.0, 52.0), (66.5, 54.0), (57.5, 56.0), (48.0, 54.6), (42.0, 50.2),
]
# The back from under the head down to the bottom edge, then the chest from the bottom edge up.
BACK_POINTS = [(44.5, 46.0), (41.0, 54.5), (37.4, 62.5), (33.4, 70.0), (28.4, 77.5), (22.6, 85.0), (17.0, 93.0), (12.0, 102.0), (9.0, 110.0)]
CHEST_POINTS = [(68.0, 110.0), (68.2, 100.0), (67.0, 90.0), (62.6, 81.0), (55.4, 74.4), (50.0, 68.0), (50.6, 60.6), (55.6, 54.0)]
ARM_POINTS = [
    (55.5, 77.4), (60.0, 77.2), (64.2, 78.4), (67.6, 80.4), (69.8, 82.2), (70.6, 83.8), (69.2, 85.2),
    (66.2, 84.6), (62.8, 83.6), (59.2, 83.4), (55.6, 84.0),
]


def spine(t, width, height):
    """A rounded spine at fraction t of the way down the back, leaning towards the head."""
    lengths = [math.dist(a, b) for a, b in zip(BACK_POINTS, BACK_POINTS[1:])]
    want = t * sum(lengths)
    for (a, b), length in zip(zip(BACK_POINTS, BACK_POINTS[1:]), lengths):
        if want <= length:
            break
        want -= length
    dx, dy = (b[0] - a[0]) / length, (b[1] - a[1]) / length
    nx, ny = -dy, dx  # Out of the back, to the upper left.
    # The base sits inside the neck, so the neck covers it.
    cx, cy = a[0] + dx * want - nx * 1.8, a[1] + dy * want - ny * 1.8
    height += 1.8
    top = (cx - dx * width / 2, cy - dy * width / 2)
    bottom = (cx + dx * width / 2, cy + dy * width / 2)
    tip = (cx + nx * height - dx, cy + ny * height - dy)

    def out(p, by):
        return f"{f(p[0] + nx * by)},{f(p[1] + ny * by)}"

    def slide(p, by):
        return f"{f(p[0] + dx * by)},{f(p[1] + dy * by)}"

    return (
        f"M{slide(bottom, 0)} C{out(bottom, height * 0.92)} {slide(tip, width * 0.27)} {slide(tip, 0)} "
        f"C{slide(tip, -width * 0.27)} {out(top, height * 0.92)} {slide(top, 0)} Z"
    )


HEAD = spline(HEAD_POINTS)
BODY = spline((BACK_POINTS + CHEST_POINTS)[::-1])
ARM = spline(ARM_POINTS)
SPINES = " ".join(spine(t, w, h) for t, w, h in [
    (0.105, 7.8, 3.0), (0.235, 8.8, 3.9), (0.375, 9.6, 4.5), (0.52, 10.2, 4.9), (0.665, 10.6, 5.1), (0.81, 10.8, 5.1),
])
SILHOUETTE = " ".join([SPINES, BODY, ARM, HEAD])

EYE = ellipse(61.6, 40.4, 4.2, 5.1)
GLINT = ellipse(63.2, 38.1, 1.5, 1.75)
GLINT_SMALL = ellipse(59.9, 43.2, 0.72, 0.72)
NOSE = ellipse(80.2, 40.4, 1.05, 0.72, 24)
# A smile that thins towards the snout, with a round corner under the eye.
MOUTH = (
    "M84.4,46.9 C79.6,49.3 73,50.3 67.4,49.7 C65.4,49.5 64,48.6 63.3,47 "
    "C63,46.3 62,46.6 62.2,47.4 C62.8,49.4 64.6,50.7 66.9,51 C73.2,51.8 80,50.4 84.4,46.9 Z"
)
# The light caught on the top of the head.
GLOSS = (
    "M45,36.4 C46.6,32.2 51.4,30 57.6,30.6 C60.4,30.9 62.8,31.7 64.8,32.9 "
    "C59.4,32 54.2,32.6 50.4,34.8 C48.4,36 46.4,37 45,36.4 Z"
)
SQUARE = "M0,0 h108 v108 h-108 Z"
STORE_MASK = "M45,18 H63 C81.9,18 90,26.1 90,45 V63 C90,81.9 81.9,90 63,90 H45 C26.1,90 18,81.9 18,63 V45 C18,26.1 26.1,18 45,18 Z"

RAINBOW = [(0, "#39C8FF"), (0.27, "#8F5BFF"), (0.52, "#F0509C"), (0.77, "#FF8A2A"), (1, "#FFD640")]
# Where the mascot's colors run from and to: its lower back to the tip of its snout.
BODY_LINE = (27, 92, 86, 30)


# Colors are #RRGGBB, with an opacity from 0 to 1 where one is needed.
def lin(x1, y1, x2, y2, stops):
    return {"type": "linear", "at": (x1, y1, x2, y2), "stops": stops}


def rad(cx, cy, r, stops):
    return {"type": "radial", "at": (cx, cy, r), "stops": stops}


def stop(offset, c, a=1.0):
    return (offset, c, a)


def glow(cx, cy, r, c, alpha):
    """A color that is strongest at a point and gone at a distance r from it."""
    return rad(cx, cy, r, [stop(0, c, alpha), stop(1, c, 0)])


def path(d, fill, alpha=1.0, even_odd=False):
    return {"d": d, "fill": fill, "alpha": alpha, "even_odd": even_odd}


def group(children, translate=None, scale=None):
    return {"children": children, "translate": translate, "scale": scale}


def fit(children):
    """Places the mascot so every launcher's mask keeps its head."""
    return group(children, translate=(-2.4, 2.4))


def glyph(children):
    """Sizes the mascot to fill a small one-color icon."""
    return group([group(children, translate=(-54, -54))], translate=(56.6, 48), scale=1.4)


def one_color():
    """The mascot as a white shape, with a hole for its eye."""
    white = "#FFFFFF"
    return [path(SPINES, white), path(BODY, white), path(ARM, white), path(HEAD + " " + EYE, white, even_odd=True)]


def face(ink="#1A0D3A", iris="#4A2C8A", plum="#6A1B6E", mouth=0.9):
    return [
        path(MOUTH, plum, mouth),
        path(NOSE, plum, 0.6),
        path(EYE, lin(62, 35.5, 63.5, 46, [stop(0, ink), stop(1, iris)])),
        path(GLINT, "#FFFFFF"),
        path(GLINT_SMALL, "#FFFFFF", 0.75),
    ]


def mascot(stops, plum="#6A1B6E", shade="#2A0A5E", lift=0.42, deep=0.2, spines=0.2, cast=0.5, gloss=0.55, jaw=0.24):
    """The mascot lit from the top left. Shading is drawn as each shape again in a see-through color."""
    white = "#FFFFFF"
    base = lin(*BODY_LINE, stops)
    # Darker towards the back and the bottom, lighter on the chest.
    torso = [
        lin(30, 74, 50, 96, [stop(0, shade, 0), stop(1, shade, deep)]),
        glow(60, 87, 15, white, 0.24),
    ]
    return [
        # Spines, behind the neck and a little deeper in tone.
        path(SPINES, base),
        path(SPINES, shade, spines),
        path(BODY, base),
        *[path(BODY, fill) for fill in torso],
        path(BODY, glow(36, 70, 14, white, 0.16)),
        # The head's shadow on the throat, and the arm's on the chest.
        path(BODY, rad(55, 54, 15, [stop(0, shade, cast), stop(0.55, shade, cast * 0.44), stop(1, shade, 0)])),
        path(BODY, glow(63.5, 86, 7, shade, cast * 0.62)),
        # The arm takes the chest's shading, so it grows out of the shoulder, and lightens towards the hand.
        path(ARM, base),
        *[path(ARM, fill) for fill in torso],
        path(ARM, lin(58, 79, 70, 84, [stop(0, white, 0), stop(1, white, 0.2)])),
        # The head: darker under the jaw, with light on the crown and the nose.
        path(HEAD, base),
        path(HEAD, glow(54, 30, 24, white, lift)),
        path(HEAD, lin(62, 40, 66, 57, [stop(0, plum, 0), stop(1, plum, jaw)])),
        path(HEAD, glow(78.6, 38.6, 6.5, white, 0.26)),
        path(GLOSS, lin(55, 30, 56, 36, [stop(0, white, gloss), stop(1, white, 0.05)])),
        *face(plum=plum),
    ]


def rainbow_stops():
    return [stop(o, c) for o, c in RAINBOW]


def rainbow_mascot(shade="#2A0A5E"):
    return mascot(rainbow_stops(), shade=shade)


def pearl_mascot():
    return mascot(
        [stop(0, "#E3D8FF"), stop(0.55, "#F6F1FF"), stop(1, "#FFFFFF")],
        plum="#7C4FC0", shade="#5B2FB0", lift=0.5, deep=0.16, spines=0.14, cast=0.34, gloss=0.9, jaw=0.2,
    )


def cast_shadow(c, alpha):
    """A soft shadow down and to the right: vector drawables can't blur, so it's built from faint copies."""
    return [group([path(SILHOUETTE, c, alpha)], translate=(f(0.18 * i), f(0.34 * i))) for i in range(1, 11)]


def paper():
    layers = [
        group([path(SILHOUETTE, c)], translate=(dx, dy))
        for dx, dy, c in [(-6, 3.9, "#FFD640"), (-4, 2.6, "#FF8A2A"), (-2, 1.3, "#F0509C")]
    ]
    front = [
        path(SILHOUETTE, lin(*BODY_LINE, [stop(0, "#5B3BE0"), stop(1, "#39C8FF")])),
        *face(plum="#14106B", mouth=1),
    ]
    return [group([*layers, *front], translate=(2, -1))]


def stripes():
    """Rainbow bands across the mascot: one gradient that changes color quickly between bands."""
    colors = [c for _, c in RAINBOW]
    n, soft = len(colors), 0.004
    stops = []
    for i, c in enumerate(colors):
        stops.append(stop(i / n + (soft if i else 0), c))
        stops.append(stop((i + 1) / n - (soft if i < n - 1 else 0), c))
    ink = "#2B1140"
    return [path(SILHOUETTE, lin(62, 100, 33, 24, stops)), *face(ink=ink, iris=ink, plum=ink, mouth=1)]


ICONS = {
    "refined": dict(
        bg=[
            path(SQUARE, lin(18, 18, 90, 90, [stop(0, "#FFFFFF"), stop(1, "#EDE4FF")])),
            path(SQUARE, glow(66, 40, 44, "#FFD9C2", 0.55)),
        ],
        fg=[*cast_shadow("#3A1A7A", 0.022), *rainbow_mascot()],
    ),
    "pastel": dict(
        bg=[path(SQUARE, lin(10, 100, 98, 8, [stop(0, "#CDEEFF"), stop(0.5, "#E6D9FF"), stop(1, "#FFE3CC")]))],
        fg=[*cast_shadow("#4B2BA0", 0.027), *rainbow_mascot()],
    ),
    "midnight": dict(
        bg=[
            path(SQUARE, lin(18, 18, 90, 90, [stop(0, "#221C3E"), stop(1, "#0B0916")])),
            path(SQUARE, glow(60, 46, 42, "#8F5BFF", 0.45)),
        ],
        fg=rainbow_mascot(shade="#12062F"),
    ),
    "rainbow": dict(
        bg=[
            path(SQUARE, lin(14, 98, 96, 10, rainbow_stops())),
            path(SQUARE, glow(30, 26, 46, "#FFFFFF", 0.22)),
        ],
        fg=[*cast_shadow("#3B0F7A", 0.032), *pearl_mascot()],
    ),
    "paper": dict(
        bg=[path(SQUARE, lin(0, 0, 108, 108, [stop(0, "#FFFDF8"), stop(1, "#FFF0E2")]))],
        fg=paper(),
    ),
    "stripes": dict(
        bg=[path(SQUARE, "#FFF8EC")],
        fg=stripes(),
    ),
}


def hex_color(c, a=1.0):
    if a >= 1:
        return c
    return f"#{round(a * 255):02X}{c[1:]}"


def gradient_xml(g, alpha):
    items = "".join(f'<item android:offset="{f(o, 4)}" android:color="{hex_color(c, a * alpha)}"/>' for o, c, a in g["stops"])
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
        return f"<group{attrs}>{''.join(node_xml(c) for c in n['children'])}</group>\n"
    attrs = f'android:pathData="{n["d"]}"'
    if n["even_odd"]:
        attrs = 'android:fillType="evenOdd" ' + attrs
    if isinstance(n["fill"], dict):
        return f'<path {attrs}>{gradient_xml(n["fill"], n["alpha"])}</path>\n'
    return f'<path {attrs} android:fillColor="{hex_color(n["fill"], n["alpha"])}"/>\n'


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


def svg(nodes, mask=None):
    """The same shapes as an SVG of the part a launcher shows, for looking at outside Android."""
    defs, count = [], [0]

    def paint(g, alpha):
        count[0] += 1
        i = f"g{count[0]}"
        stops = "".join(f'<stop offset="{f(o, 4)}" stop-color="{c}" stop-opacity="{a * alpha}"/>' for o, c, a in g["stops"])
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
            return f"<g{attrs}>{''.join(walk(k) for k in n['children'])}</g>"
        rule = ' fill-rule="evenodd"' if n["even_odd"] else ""
        if isinstance(n["fill"], dict):
            return f'<path d="{n["d"]}" fill="{paint(n["fill"], n["alpha"])}"{rule}/>'
        return f'<path d="{n["d"]}" fill="{n["fill"]}" fill-opacity="{n["alpha"]}"{rule}/>'

    body = "".join(walk(n) for n in nodes)
    if mask:
        defs.append(f'<clipPath id="mask"><path d="{mask}"/></clipPath>')
        body = f'<g clip-path="url(#mask)">{body}</g>'
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="512" height="512"><defs>{"".join(defs)}</defs>{body}</svg>'


def main():
    drawable = ROOT / "drawable"
    mipmap = next(ROOT.glob("mipmap-anydpi*"))
    for name, icon in ICONS.items():
        (drawable / f"ic_launcher_bg_{name}.xml").write_text(vector(icon["bg"]))
        (drawable / f"ic_launcher_fg_{name}.xml").write_text(vector([fit(icon["fg"])]))
        (mipmap / f"ic_launcher_{name}.xml").write_text(adaptive(name))
    (mipmap / "ic_launcher.xml").write_text(adaptive("refined"))
    (drawable / "ic_launcher_mono.xml").write_text(vector([fit(one_color())]))
    (drawable / "ic_tile.xml").write_text(vector([glyph(one_color())], size=24))

    if len(sys.argv) > 1:
        out = Path(sys.argv[1])
        for name, icon in ICONS.items():
            (out / f"icon-{name}.svg").write_text(svg([*icon["bg"], fit(icon["fg"])]))
        refined = ICONS["refined"]
        (out / "icon-store.svg").write_text(svg([*refined["bg"], fit(refined["fg"])], mask=STORE_MASK))


main()

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


class View:
    """Where a drawing made in a 512 unit square lands on an icon's 108 unit canvas."""

    def __init__(self, scale, left, top):
        self.scale, self.left, self.top = scale, left, top

    def at(self, p):
        return (self.left + p[0] * self.scale, self.top + p[1] * self.scale)

    def xy(self, p):
        x, y = self.at(p)
        return f"{f(x)},{f(y)}"


# A launcher shows the middle 72 units of the canvas. The drawing fills that part and runs past
# it on every side the head leaves by, from -128 to 640, so no launcher's mask finds an edge.
LAUNCHER = View(72 / 512, 18, 18)
# A small one-color icon uses the whole canvas.
GLYPH = View(108 / 512, 0, 0)


def outline(view, start, *steps, closed=True):
    """A path from a point through its steps: one point is a straight line, three are a curve."""
    out = [f"M{view.xy(start)}"]
    for step in steps:
        if isinstance(step[0], tuple):
            out.append("C" + " ".join(view.xy(p) for p in step))
        else:
            out.append(f"L{view.xy(step)}")
    return " ".join(out) + (" Z" if closed else "")


def turned(p, degrees, about):
    """A point given as an offset from another, turned around it."""
    a = math.radians(degrees)
    return (about[0] + p[0] * math.cos(a) - p[1] * math.sin(a), about[1] + p[0] * math.sin(a) + p[1] * math.cos(a))


def ellipse(view, center, rx, ry, turn=0.0):
    """An ellipse as four cubic curves, turned by a number of degrees."""
    k = 0.5522847498

    def at(x, y):
        return turned((x, y), turn, center)

    quarters = [
        ((rx, k * ry), (k * rx, ry), (0, ry)),
        ((-k * rx, ry), (-rx, k * ry), (-rx, 0)),
        ((-rx, -k * ry), (-k * rx, -ry), (0, -ry)),
        ((k * rx, -ry), (rx, -k * ry), (rx, 0)),
    ]
    return outline(view, at(rx, 0), *[(at(*c1), at(*c2), at(*end)) for c1, c2, end in quarters])


def along(curve, t):
    """A point at fraction t of a cubic curve, and the direction of travel there in degrees."""
    p0, c1, c2, p3 = curve
    m = 1 - t
    at = [m ** 3 * p0[k] + 3 * m * m * t * c1[k] + 3 * m * t * t * c2[k] + t ** 3 * p3[k] for k in (0, 1)]
    go = [3 * m * m * (c1[k] - p0[k]) + 6 * m * t * (c2[k] - c1[k]) + 3 * t * t * (p3[k] - c2[k]) for k in (0, 1)]
    return (at[0], at[1]), math.degrees(math.atan2(go[1], go[0]))


def plate(view, curve, t, w, h, lean=-0.14, sink=10):
    """A rounded plate standing on a curve, its base sunk into the head and its tip leaning back."""
    foot, heading = along(curve, t)
    tip = lean * w

    def at(x, y):
        return turned((x, y), heading, foot)

    return outline(
        view, at(-w / 2, sink),
        (at(-0.47 * w, -0.5 * h), at(tip - 0.27 * w, -h), at(tip, -h)),
        (at(tip + 0.27 * w, -h), at(0.47 * w, -0.5 * h), at(w / 2, sink)),
    )


def eye(view, center, rx, ry, turn=-10):
    """An eye, and the two spots of light in it: a big one up and to the right, a small one opposite."""
    return (
        ellipse(view, center, rx, ry, turn),
        ellipse(view, turned((0.34 * rx, -0.38 * ry), turn, center), 0.34 * rx, 0.34 * rx),
        ellipse(view, turned((-0.30 * rx, 0.36 * ry), turn, center), 0.14 * rx, 0.14 * rx),
    )


# Compy from very close, facing right: the back of a round head rising to its crown, a brow, and
# a snout that leaves by the right edge. Three plates stand on the back of the head. Most of the
# face is one eye; under it a smile runs off with the snout, a single tooth over the lip.
BACK = ((-54, 560), (-54, 330), (48, 84), (262, 78))
BROW = ((330, 76), (372, 112), (404, 158))
SNOUT = ((428, 192), (470, 206), (560, 206))
LIP = ((300, 398), (348, 438), (432, 440), (530, 388))
# Where the head's line begins and ends, past the canvas.
BELOW, BEYOND = (-54, 640), (640, 206)

CROWN = outline(LAUNCHER, BELOW, BACK[0], BACK[1:], BROW, SNOUT, BEYOND, closed=False)
HEAD = outline(LAUNCHER, BELOW, BACK[0], BACK[1:], BROW, SNOUT, BEYOND, (640, 640))
PLATES = " ".join(plate(LAUNCHER, BACK, t, w, h) for t, w, h in [(0.46, 96, 60), (0.66, 108, 68), (0.86, 90, 54)])
EYE, GLINT, GLINT_SMALL = eye(LAUNCHER, (222, 270), 92, 112)
NOSTRIL = ellipse(LAUNCHER, (462, 256), 13, 9, -24)
# The smile keeps its last direction until it is off the canvas.
SMILE = outline(LAUNCHER, LIP[0], LIP[1:], (640, 329.6), closed=False)
BITE, SLOPE = along(LIP, 0.42)
TOOTH = outline(LAUNCHER, *[turned(p, SLOPE, BITE) for p in [(-20, -6), (20, -6), (0, 36)]])
# How thick the lines are: the smile, the rounding of the tooth's corners, the head in one color.
SMILE_WIDTH, TOOTH_WIDTH, CROWN_WIDTH = (w * LAUNCHER.scale for w in (14, 8, 17))

# The same animal as a shape that fits inside a small icon: the head on a short neck.
BUST_BACK = ((60, 476), (40, 340), (70, 140), (236, 112))
BUST = outline(
    GLYPH, BUST_BACK[0], BUST_BACK[1:],
    ((306, 100), (356, 134), (384, 180)), ((402, 210), (432, 222), (458, 230)),
    ((494, 242), (508, 284), (496, 322)), ((484, 360), (448, 378), (406, 382)),
    ((366, 386), (330, 392), (314, 416)), ((304, 432), (306, 456), (310, 476)),
)
BUST_PLATES = " ".join(plate(GLYPH, BUST_BACK, t, w, h) for t, w, h in [(0.4, 84, 54), (0.63, 92, 58), (0.86, 78, 46)])
BUST_EYE, BUST_GLINT, _ = eye(GLYPH, (226, 262), 76, 92)

SQUARE = "M0,0 h108 v108 h-108 Z"
STORE_MASK = "M45,18 H63 C81.9,18 90,26.1 90,45 V63 C90,81.9 81.9,90 63,90 H45 C26.1,90 18,81.9 18,63 V45 C18,26.1 26.1,18 45,18 Z"

INK = "#2A1A5E"
WHITE = "#FFFFFF"
# From the tip of the snout to the bottom of the back.
RAINBOW = [(0, "#FFC83A"), (0.28, "#FF8A3C"), (0.52, "#F2569B"), (0.78, "#A56BFF"), (1, "#4FA3FF")]
SOFT = [(0, "#FFE29A"), (0.28, "#FFB9A0"), (0.52, "#F7A8CC"), (0.78, "#C7B5FF"), (1, "#A3D2FF")]


# Colors are #RRGGBB, with an opacity from 0 to 1 where one is needed.
def lin(x1, y1, x2, y2, stops):
    return {"type": "linear", "at": (x1, y1, x2, y2), "stops": [stop(*s) for s in stops]}


def across(start, end, stops):
    """Colors running between two points of the drawing."""
    return lin(*LAUNCHER.at(start), *LAUNCHER.at(end), stops)


def stop(offset, c, a=1.0):
    return (offset, c, a)


def path(d, fill, alpha=1.0, even_odd=False, line=None, width=0.0):
    return {"d": d, "fill": fill, "alpha": alpha, "even_odd": even_odd, "line": line, "width": width}


def line(d, color, width, alpha=1.0):
    """A path drawn as a line with round ends."""
    return path(d, None, alpha, line=color, width=width)


def group(children, translate=None):
    return {"children": children, "translate": translate}


def face(ink=INK, soft=0.5):
    return [
        path(EYE, ink),
        path(GLINT, WHITE),
        path(GLINT_SMALL, WHITE, 0.75),
        path(NOSTRIL, ink, soft),
        line(SMILE, ink, SMILE_WIDTH, soft),
        path(TOOTH, WHITE, line=WHITE, width=TOOTH_WIDTH),
    ]


def body(colors, plates):
    """The head in one run of colors from the snout to the back, and its plates in another."""
    return [
        path(PLATES, across((40, 40), (190, 280), plates)),
        path(HEAD, across((500, 70), (90, 560), colors)),
    ]


def compy(colors=RAINBOW, plates=((0, "#9A7BFF"), (1, "#5B45D6")), **look):
    return [*body(colors, plates), *face(**look)]


def paper():
    """Cut paper: the head in blue, on sheets of pink, orange and yellow that each show a little more."""
    sheets = [
        group([path(PLATES, c), path(HEAD, c)], translate=(f(-2.2 * i), f(-1.9 * i)))
        for i, c in [(3, "#FFD640"), (2, "#FF8A2A"), (1, "#F0509C")]
    ]
    blue = [(0, "#39C8FF"), (1, "#5B3BE0")]
    return [*sheets, *body(blue, blue), *face(ink="#14106B", soft=0.6)]


def stripes():
    """Rainbow bands across the head: one gradient that changes color quickly between bands."""
    colors = [c for _, c in reversed(RAINBOW)]
    n, soft = len(colors), 0.004
    stops = []
    for i, c in enumerate(colors):
        stops.append((i / n + (soft if i else 0), c))
        stops.append(((i + 1) / n - (soft if i < n - 1 else 0), c))
    bands = across((70, 520), (470, 130), stops)
    return [path(PLATES, bands), path(HEAD, bands), *face(ink="#2B1140", soft=0.6)]


def one_color():
    """The picture as white lines: the top of the head, with the plates, eye and tooth filled in."""
    return [
        path(PLATES, WHITE),
        line(CROWN, WHITE, CROWN_WIDTH),
        path(" ".join([EYE, GLINT, GLINT_SMALL]), WHITE, even_odd=True),
        path(NOSTRIL, WHITE),
        line(SMILE, WHITE, SMILE_WIDTH),
        path(TOOTH, WHITE, line=WHITE, width=TOOTH_WIDTH),
    ]


def glyph():
    """The bust as a white shape, with a hole for its eye and the light left in it."""
    return [path(BUST_PLATES, WHITE), path(" ".join([BUST, BUST_EYE, BUST_GLINT]), WHITE, even_odd=True)]


ICONS = {
    "refined": dict(
        bg=[path(SQUARE, lin(18, 18, 90, 90, [(0, "#FBF8FF"), (1, "#EAE2FA")]))],
        fg=compy(),
    ),
    "pastel": dict(
        bg=[path(SQUARE, lin(10, 100, 98, 8, [(0, "#CDEEFF"), (0.5, "#E6D9FF"), (1, "#FFE3CC")]))],
        fg=compy(SOFT, plates=[(0, "#B7A2FF"), (1, "#8E7BEA")], ink="#3A2A72"),
    ),
    "midnight": dict(
        bg=[path(SQUARE, lin(18, 18, 90, 90, [(0, "#241B47"), (1, "#0F0A22")]))],
        fg=compy(),
    ),
    "rainbow": dict(
        bg=[path(SQUARE, lin(96, 10, 14, 98, RAINBOW))],
        fg=compy([(0, WHITE), (1, "#B9A4FF")], plates=[(0, WHITE), (1, "#E9E1FF")]),
    ),
    "paper": dict(
        bg=[path(SQUARE, lin(0, 0, 108, 108, [(0, "#FFFDF8"), (1, "#FFF0E2")]))],
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
    x1, y1, x2, y2 = (f(v) for v in g["at"])
    where = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
    return f'<aapt:attr name="android:fillColor"><gradient {where}>{items}</gradient></aapt:attr>'


def node_xml(n):
    if "children" in n:
        attrs = ""
        if n["translate"]:
            attrs += f' android:translateX="{n["translate"][0]}" android:translateY="{n["translate"][1]}"'
        return f"<group{attrs}>{''.join(node_xml(c) for c in n['children'])}</group>\n"
    attrs = f'android:pathData="{n["d"]}"'
    if n["even_odd"]:
        attrs = 'android:fillType="evenOdd" ' + attrs
    if n["line"]:
        attrs += (
            f' android:strokeColor="{hex_color(n["line"], n["alpha"])}" android:strokeWidth="{f(n["width"])}"'
            ' android:strokeLineCap="round" android:strokeLineJoin="round"'
        )
    if n["fill"] is None:
        return f"<path {attrs}/>\n"
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


def svg(nodes, mask=None, whole=False):
    """The same shapes as an SVG, for looking at outside Android: the part a launcher shows, or the whole canvas."""
    defs, count = [], [0]

    def paint(g, alpha):
        count[0] += 1
        i = f"g{count[0]}"
        stops = "".join(f'<stop offset="{f(o, 4)}" stop-color="{c}" stop-opacity="{a * alpha}"/>' for o, c, a in g["stops"])
        x1, y1, x2, y2 = (f(v) for v in g["at"])
        defs.append(f'<linearGradient id="{i}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
        return f"url(#{i})"

    def walk(n):
        if "children" in n:
            attrs = f' transform="translate({n["translate"][0]} {n["translate"][1]})"' if n["translate"] else ""
            return f"<g{attrs}>{''.join(walk(k) for k in n['children'])}</g>"
        attrs = f'd="{n["d"]}"'
        if n["even_odd"]:
            attrs += ' fill-rule="evenodd"'
        if n["line"]:
            attrs += (
                f' stroke="{n["line"]}" stroke-opacity="{n["alpha"]}" stroke-width="{f(n["width"])}"'
                ' stroke-linecap="round" stroke-linejoin="round"'
            )
        if n["fill"] is None:
            return f'<path {attrs} fill="none"/>'
        if isinstance(n["fill"], dict):
            return f'<path {attrs} fill="{paint(n["fill"], n["alpha"])}"/>'
        return f'<path {attrs} fill="{n["fill"]}" fill-opacity="{n["alpha"]}"/>'

    content = "".join(walk(n) for n in nodes)
    if mask:
        defs.append(f'<clipPath id="mask"><path d="{mask}"/></clipPath>')
        content = f'<g clip-path="url(#mask)">{content}</g>'
    box = "0 0 108 108" if whole else "18 18 72 72"
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{box}" width="512" height="512"><defs>{"".join(defs)}</defs>{content}</svg>'


def main():
    drawable = ROOT / "drawable"
    mipmap = next(ROOT.glob("mipmap-anydpi*"))
    for name, icon in ICONS.items():
        (drawable / f"ic_launcher_bg_{name}.xml").write_text(vector(icon["bg"]))
        (drawable / f"ic_launcher_fg_{name}.xml").write_text(vector(icon["fg"]))
        (mipmap / f"ic_launcher_{name}.xml").write_text(adaptive(name))
    (mipmap / "ic_launcher.xml").write_text(adaptive("refined"))
    (drawable / "ic_launcher_mono.xml").write_text(vector(one_color()))
    (drawable / "ic_tile.xml").write_text(vector(glyph(), size=24))

    if len(sys.argv) > 1:
        out = Path(sys.argv[1])
        for name, icon in ICONS.items():
            (out / f"icon-{name}.svg").write_text(svg([*icon["bg"], *icon["fg"]]))
        refined = ICONS["refined"]
        (out / "icon-store.svg").write_text(svg([*refined["bg"], *refined["fg"]], mask=STORE_MASK))
        # The one-color icons are white, so they are shown on a dark square.
        dark = path(SQUARE, "#2A2440")
        (out / "icon-mono.svg").write_text(svg([dark, *one_color()]))
        (out / "icon-tile.svg").write_text(svg([dark, *glyph()], whole=True))


main()

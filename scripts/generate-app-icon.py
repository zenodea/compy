#!/usr/bin/env python3
"""Generates Compy's adaptive launcher icon and tile icon from one set of shapes.

Pass a folder to also write an SVG preview there.
"""
import sys
from pathlib import Path

res = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'

# A compsognathus bust in a 108 unit canvas, facing right: long neck, slim head, pointed snout.
BODY = (
    "M56,24 C64,22.5 71,26 76,30.5 C80,33.5 85,36.5 88,39.5 C89,41.5 87,43.5 84,44.5 "
    "C76,47 67,48.5 60,48.5 C54,49 50.5,52 49.5,57 C48.5,63 52,69 55,75 C58,81 58,87 55,91 "
    "C46,95 30,94 22,89 C20,80 23,71 30,64 "
    "C25,60.5 27,52.5 33.5,54 C30.5,49 32.5,42.5 37.5,44.5 C35.5,39.5 38.5,33.5 43,35.5 "
    "C44.5,29.5 50,25 56,24 Z"
)
MOUTH = "M85.5,42 C78,44.6 70,44.8 63.5,42.8"
EYE = "M62.4,33 a3.6,4.2 0 1,0 7.2,0 a3.6,4.2 0 1,0 -7.2,0 Z"
GLINT = "M66.8,30.9 a1,1 0 1,0 2,0 a1,1 0 1,0 -2,0 Z"
ARM = "M50,77 C55,76.5 60,79 63,83 C64.4,85 62.6,87 60.6,86 C56,83.5 52,83 48,83 Z"

STOPS = [(0.0, "#4FD2FF"), (0.28, "#A65CFF"), (0.55, "#F2559B"), (0.78, "#FF8A1F"), (1.0, "#FFD93B")]
GRADIENT = (24, 92, 80, 26)


def gradient_xml():
    x1, y1, x2, y2 = GRADIENT
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in STOPS)
    return (
        '<aapt:attr name="android:fillColor">'
        f'<gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}">'
        f"{items}</gradient></aapt:attr>"
    )


def vector(body, size=108):
    return (
        f'<?xml version="1.0" encoding="utf-8"?>\n<vector {NS}\n'
        f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">\n'
        f"{body}</vector>\n"
    )


foreground = f"""    <group android:scaleX="0.92" android:scaleY="0.92" android:pivotX="54" android:pivotY="54">
        <path android:pathData="{ARM}" android:fillColor="#B24DE0" />
        <path android:pathData="{BODY}">{gradient_xml()}</path>
        <path android:pathData="{BODY}">
            <aapt:attr name="android:fillColor">
                <gradient android:type="radial" android:centerX="66" android:centerY="30" android:gradientRadius="20">
                    <item android:offset="0" android:color="#66FFFFFF"/>
                    <item android:offset="1" android:color="#00FFFFFF"/>
                </gradient>
            </aapt:attr>
        </path>
        <path android:pathData="{MOUTH}" android:strokeColor="#7A1F7A" android:strokeWidth="1.3" android:strokeLineCap="round" android:fillColor="#00000000" />
        <path android:pathData="{EYE}" android:fillColor="#1D0B4F" />
        <path android:pathData="{GLINT}" android:fillColor="#FFFFFF" />
    </group>
"""
background = """    <path android:pathData="M0,0 h108 v108 h-108 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FFFFFF"/>
                <item android:offset="1" android:color="#F1E6FF"/>
            </gradient>
        </aapt:attr>
    </path>
"""
mono = f"""    <group android:scaleX="0.92" android:scaleY="0.92" android:pivotX="54" android:pivotY="54">
        <path android:pathData="{BODY}" android:fillColor="#FFFFFFFF" />
        <path android:pathData="{ARM}" android:fillColor="#FFFFFFFF" />
    </group>
"""
tile = f"""    <path android:fillType="evenOdd" android:fillColor="#FFFFFFFF" android:pathData="{BODY} {EYE}" />
"""

(res / "ic_launcher_fg.xml").write_text(vector(foreground))
(res / "ic_launcher_bg.xml").write_text(vector(background))
(res / "ic_launcher_mono.xml").write_text(vector(mono))
(res / "ic_tile.xml").write_text(
    vector(f'    <group android:scaleX="1.1" android:scaleY="1.1" android:pivotX="54" android:pivotY="54">\n{tile}    </group>\n').replace(
        'android:width="108dp" android:height="108dp"', 'android:width="24dp" android:height="24dp"'
    )
)

if len(sys.argv) > 1:
    x1, y1, x2, y2 = GRADIENT
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in STOPS)
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="864" height="864">
<defs><linearGradient id="g" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>
<radialGradient id="h" gradientUnits="userSpaceOnUse" cx="66" cy="30" r="20"><stop offset="0" stop-color="#fff" stop-opacity="0.4"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
<linearGradient id="b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#F1E6FF"/></linearGradient></defs>
<rect width="108" height="108" fill="url(#b)"/>
<g transform="translate(54 54) scale(0.92) translate(-54 -54)">
<path d="{ARM}" fill="#B24DE0"/><path d="{BODY}" fill="url(#g)"/><path d="{BODY}" fill="url(#h)"/>
<path d="{MOUTH}" stroke="#7A1F7A" stroke-width="1.3" stroke-linecap="round" fill="none"/>
<path d="{EYE}" fill="#1D0B4F"/><path d="{GLINT}" fill="#fff"/></g></svg>"""
    Path(sys.argv[1], "icon.svg").write_text(svg)

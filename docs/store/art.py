"""Draws PureEQ's Play Store icon and feature graphic as SVG, from the launcher icon's vectors.

Run: python3 docs/store/art.py docs/store, then render with rsvg-convert:
  rsvg-convert -w 512 -h 512 docs/store/icon.svg -o docs/store/icon-512.png
  rsvg-convert -w 1024 -h 500 docs/store/feature-graphic.svg -o docs/store/feature-graphic-1024x500.png
"""
import math, pathlib, sys

out = pathlib.Path(sys.argv[1])
BG0, BG1 = "#1B1F3B", "#2C1E4A"
LOGO = """
  <path d="M33,58 L33,52 A21,21 0 0 1 75,52 L75,58" fill="none" stroke="#FFFFFF" stroke-width="4.5" stroke-linecap="round"/>
  <path d="M30.5,56.5 a3,3 0 0 1 6,0 v14 a3,3 0 0 1 -6,0 z" fill="#7FA7FF"/>
  <path d="M41.5,62.5 a3,3 0 0 1 6,0 v8 a3,3 0 0 1 -6,0 z" fill="#B7A4FF"/>
  <path d="M51,52.5 a3,3 0 0 1 6,0 v18 a3,3 0 0 1 -6,0 z" fill="#FFFFFF"/>
  <path d="M60.5,59.5 a3,3 0 0 1 6,0 v11 a3,3 0 0 1 -6,0 z" fill="#B7A4FF"/>
  <path d="M71.5,55.5 a3,3 0 0 1 6,0 v15 a3,3 0 0 1 -6,0 z" fill="#FF8A8F"/>"""

# Play icon: the launcher's visible 72 dp of the 108 dp canvas, full bleed (Play rounds the corners).
(out / "icon.svg").write_text(f"""<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">
  <defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{BG0}"/><stop offset="1" stop-color="{BG1}"/></linearGradient></defs>
  <rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>{LOGO}
</svg>
""")

# Feature graphic: two ears' curves across the back, the icon and the name in front.
W, H = 1024, 500
def curve(shift, lift):
    pts = []
    for i in range(121):
        x = i / 120
        f = 20 * 1000 ** x  # 20 Hz .. 20 kHz on a log axis
        db = lift * (1 / (1 + (f / 120) ** 1.4)) + 2.2 * math.exp(-((math.log10(f) - 3.5) / 0.28) ** 2) \
            - 1.6 * math.exp(-((math.log10(f) - 2.45) / 0.3) ** 2) + shift * (math.log10(f) - 1.3) / 3
        pts.append((x * W, 432 - db * 13))
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)
left, right = curve(0.6, 6.5), curve(-0.4, 5.6)
(out / "feature-graphic.svg").write_text(f"""<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{BG0}"/><stop offset="1" stop-color="{BG1}"/></linearGradient>
    <radialGradient id="glow" cx="0.3" cy="0.45" r="0.6"><stop offset="0" stop-color="#AEA2FF" stop-opacity="0.22"/><stop offset="1" stop-color="#AEA2FF" stop-opacity="0"/></radialGradient>
    <linearGradient id="fadeL" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#5AA2FF" stop-opacity="0.28"/><stop offset="1" stop-color="#5AA2FF" stop-opacity="0"/></linearGradient>
    <linearGradient id="fadeR" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FF6B7A" stop-opacity="0.22"/><stop offset="1" stop-color="#FF6B7A" stop-opacity="0"/></linearGradient>
    <clipPath id="tile"><rect x="96" y="140" width="220" height="220" rx="52"/></clipPath>
  </defs>
  <rect width="{W}" height="{H}" fill="url(#bg)"/>
  <rect width="{W}" height="{H}" fill="url(#glow)"/>
  <path d="{left} L{W},{H} L0,{H} Z" fill="url(#fadeL)"/>
  <path d="{right} L{W},{H} L0,{H} Z" fill="url(#fadeR)"/>
  <path d="{left}" fill="none" stroke="#5AA2FF" stroke-width="4" stroke-opacity="0.85"/>
  <path d="{right}" fill="none" stroke="#FF6B7A" stroke-width="4" stroke-opacity="0.75"/>
  <g clip-path="url(#tile)">
    <rect x="96" y="140" width="220" height="220" fill="url(#bg)"/>
    <svg x="96" y="140" width="220" height="220" viewBox="18 18 72 72">{LOGO}</svg>
  </g>
  <rect x="96" y="140" width="220" height="220" rx="52" fill="none" stroke="#FFFFFF" stroke-opacity="0.12" stroke-width="2"/>
  <text x="360" y="245" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-weight="700" font-size="96" fill="#FFFFFF" letter-spacing="-1">PureEQ</text>
  <text x="364" y="300" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-size="34" fill="#E6E0FF">Your headphones, tuned to</text>
  <text x="364" y="342" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-size="34" fill="#E6E0FF">your ears and your taste.</text>
</svg>
""")

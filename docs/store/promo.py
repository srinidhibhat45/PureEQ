"""Draws PureEQ's Play Store promo art: the feature graphic and eight captioned phone screenshots.

Built from the real app screenshots in docs/store/screenshots/ and the logo's colours (navy
background, the EQ bars' blue, lavender and coral), so the store shows the app as it is.

Run from the repository root:
  python3 docs/store/promo.py
It writes SVGs and renders PNGs with rsvg-convert into docs/store/promo/:
  feature-graphic-1024x500.png (also copied to docs/store/), and 1-home.png .. 8-where-its-on.png
  at 1080 x 1920 (9:16, inside Play's phone screenshot limits).

Captions follow docs/play-store.md: no "hearing test", "hearing loss", "hearing aid", "audiogram",
"dB HL", "screening", "clinically validated", no Apple or Samsung product names, and nothing Play's
metadata policy bars from screenshots (rankings, "best", prices, calls to download).
"""
import base64, math, pathlib, shutil, struct, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
STORE = ROOT / "docs" / "store"
SHOTS = STORE / "screenshots"
OUT = STORE / "promo"
FONT = "Helvetica Neue, Helvetica, Arial, sans-serif"

NAVY0, NAVY1, INK = "#1B1F3B", "#2C1E4A", "#0D0F14"
BLUE, LAVENDER, CORAL = "#7FA7FF", "#B7A4FF", "#FF8A8F"
SUB = "#C9D2EC"

LOGO = """
  <path d="M33,58 L33,52 A21,21 0 0 1 75,52 L75,58" fill="none" stroke="#FFFFFF" stroke-width="4.5" stroke-linecap="round"/>
  <path d="M30.5,56.5 a3,3 0 0 1 6,0 v14 a3,3 0 0 1 -6,0 z" fill="#7FA7FF"/>
  <path d="M41.5,62.5 a3,3 0 0 1 6,0 v8 a3,3 0 0 1 -6,0 z" fill="#B7A4FF"/>
  <path d="M51,52.5 a3,3 0 0 1 6,0 v18 a3,3 0 0 1 -6,0 z" fill="#FFFFFF"/>
  <path d="M60.5,59.5 a3,3 0 0 1 6,0 v11 a3,3 0 0 1 -6,0 z" fill="#B7A4FF"/>
  <path d="M71.5,55.5 a3,3 0 0 1 6,0 v15 a3,3 0 0 1 -6,0 z" fill="#FF8A8F"/>"""

# (file, headline lines, subline lines)
SLIDES = [
    ("1-home", ["Your headphones,", "tuned to you"], ["One switch puts your sound", "on everything you play."]),
    ("2-modes", ["Made for what", "you listen to"], ["Music, Voice, Gaming, General,", "or Auto to follow what's playing."]),
    ("3-listening-check", ["A two-minute", "listening check"], ["Short beeps in each ear find", "what needs a lift."]),
    ("4-listening-profile", ["See your", "sound profile"], ["Each ear compared with a", "reference listener."]),
    ("5-taste-test", ["Pick what", "sounds better"], ["Blind A/B rounds find the", "bass and treble you like."]),
    ("6-your-eq", ["One smooth EQ", "for each ear"], ["Your ears and your taste,", "in a single curve."]),
    ("7-fine-tune", ["Fine-tune", "any detail"], ["Bass, treble and more, compared", "at the same loudness."]),
    ("8-where-its-on", ["Follows your", "headphones"], ["On for each pair you choose,", "off for the phone speaker."]),
]


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    return struct.unpack(">II", head[16:24])


def data_uri(path):
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode()


def eq_path(width, base_y, scale, x0=0.0):
    """A smooth personal-EQ shape across 20 Hz-20 kHz: bass lift, presence bump, airy top."""
    pts = []
    for i in range(161):
        x = i / 160
        f = 20 * 1000 ** x
        db = 4.2 / (1 + (f / 110) ** 1.6) + 3.0 * math.exp(-((math.log10(f) - 3.75) / 0.22) ** 2) \
            - 0.9 * math.exp(-((math.log10(f) - 2.6) / 0.35) ** 2) + 1.2 / (1 + (12000 / f) ** 3)
        pts.append((x0 + x * width, base_y - db * scale))
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)


def defs(width, height):
    return f"""<defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0.35" y2="1"><stop offset="0" stop-color="{NAVY1}"/><stop offset="0.45" stop-color="{NAVY0}"/><stop offset="1" stop-color="{INK}"/></linearGradient>
    <radialGradient id="glowA" cx="0.18" cy="0.08" r="0.65"><stop offset="0" stop-color="{LAVENDER}" stop-opacity="0.30"/><stop offset="1" stop-color="{LAVENDER}" stop-opacity="0"/></radialGradient>
    <radialGradient id="glowB" cx="0.9" cy="0.55" r="0.55"><stop offset="0" stop-color="{BLUE}" stop-opacity="0.20"/><stop offset="1" stop-color="{BLUE}" stop-opacity="0"/></radialGradient>
    <linearGradient id="eq" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="{BLUE}"/><stop offset="0.55" stop-color="{LAVENDER}"/><stop offset="1" stop-color="{CORAL}"/></linearGradient>
    <linearGradient id="eqFill" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{LAVENDER}" stop-opacity="0.22"/><stop offset="1" stop-color="{LAVENDER}" stop-opacity="0"/></linearGradient>
    <filter id="soft" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="40"/></filter>
  </defs>
  <rect width="{width}" height="{height}" fill="url(#bg)"/>
  <rect width="{width}" height="{height}" fill="url(#glowA)"/>
  <rect width="{width}" height="{height}" fill="url(#glowB)"/>"""


def phone(shot, x, y, w, frame_h, rx, bezel, clip_id):
    """A plain dark phone with the real screenshot in it; the screen's empty part is the app's background."""
    iw, ih = png_size(shot)
    sw = w - 2 * bezel
    sh = sw * ih / iw
    screen_h = frame_h - 2 * bezel
    return f"""
  <ellipse cx="{x + w / 2}" cy="{y + min(frame_h, 900) * 0.55}" rx="{w * 0.62}" ry="{min(frame_h, 900) * 0.5}" fill="{BLUE}" opacity="0.28" filter="url(#soft)"/>
  <rect x="{x}" y="{y}" width="{w}" height="{frame_h}" rx="{rx}" fill="#05060A"/>
  <rect x="{x + 1}" y="{y + 1}" width="{w - 2}" height="{frame_h - 2}" rx="{rx - 1}" fill="none" stroke="#FFFFFF" stroke-opacity="0.16" stroke-width="2"/>
  <clipPath id="{clip_id}"><rect x="{x + bezel}" y="{y + bezel}" width="{sw}" height="{screen_h}" rx="{rx - bezel}"/></clipPath>
  <g clip-path="url(#{clip_id})">
    <rect x="{x + bezel}" y="{y + bezel}" width="{sw}" height="{screen_h}" fill="{INK}"/>
    <image x="{x + bezel}" y="{y + bezel}" width="{sw:.1f}" height="{sh:.1f}" href="{data_uri(shot)}"/>
  </g>"""


def text_lines(lines, cx, y, size, weight, fill, gap, spacing="0"):
    return "".join(
        f'\n  <text x="{cx}" y="{y + i * gap}" text-anchor="middle" font-family="{FONT}" font-size="{size}" '
        f'font-weight="{weight}" fill="{fill}" letter-spacing="{spacing}">{t}</text>'
        for i, t in enumerate(lines))


def slide(name, head, sub):
    W, H = 1080, 1920
    shot = SHOTS / f"{name}.png"
    px, pw, top = 130, 820, 640
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  {defs(W, H)}
  <path d="{eq_path(W + 80, 760, 34, -40)} L{W + 40},{H} L-40,{H} Z" fill="url(#eqFill)"/>
  <path d="{eq_path(W + 80, 760, 34, -40)}" fill="none" stroke="url(#eq)" stroke-width="5" stroke-linecap="round" opacity="0.75"/>
  {text_lines(head, W / 2, 238, 92, 700, "#FFFFFF", 104, "-1.5")}
  {text_lines(sub, W / 2, 470, 42, 400, SUB, 56)}
  {phone(shot, px, top, pw, 1700, 86, 18, "scr")}
</svg>
"""
    return svg


def feature():
    W, H = 1024, 500
    shot = SHOTS / "1-home.png"
    tile = f"""
  <clipPath id="tile"><rect x="72" y="92" width="132" height="132" rx="32"/></clipPath>
  <g clip-path="url(#tile)">
    <rect x="72" y="92" width="132" height="132" fill="{NAVY0}"/>
    <svg x="72" y="92" width="132" height="132" viewBox="18 18 72 72">{LOGO}</svg>
  </g>
  <rect x="72" y="92" width="132" height="132" rx="32" fill="none" stroke="#FFFFFF" stroke-opacity="0.18" stroke-width="2"/>"""
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  {defs(W, H)}
  <path d="{eq_path(W + 40, 484, 14, -20)} L{W + 20},{H} L-20,{H} Z" fill="url(#eqFill)"/>
  <path d="{eq_path(W + 40, 484, 14, -20)}" fill="none" stroke="url(#eq)" stroke-width="4" stroke-linecap="round" opacity="0.9"/>
  {tile}
  <text x="72" y="306" font-family="{FONT}" font-size="76" font-weight="700" fill="#FFFFFF" letter-spacing="-1">PureEQ</text>
  <text x="74" y="354" font-family="{FONT}" font-size="30" fill="{SUB}">Your headphones, tuned to</text>
  <text x="74" y="392" font-family="{FONT}" font-size="30" fill="{SUB}">your ears and your taste.</text>
  <g transform="rotate(-7 790 330)">{phone(shot, 655, 78, 270, 560, 38, 8, "fscr")}
  </g>
</svg>
"""


def render(svg_text, path, w, h):
    svg = path.with_suffix(".svg")
    svg.write_text(svg_text)
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), str(svg), "-o", str(path)], check=True)
    # Play takes JPEG or 24-bit PNG without an alpha channel; rsvg-convert writes RGBA.
    from PIL import Image
    with Image.open(path) as im:
        flat = Image.new("RGB", im.size, INK)
        flat.paste(im, mask=im.convert("RGBA").split()[3])
    flat.save(path, optimize=True)


def main():
    OUT.mkdir(exist_ok=True)
    render(feature(), OUT / "feature-graphic-1024x500.png", 1024, 500)
    shutil.copy2(OUT / "feature-graphic-1024x500.png", STORE / "feature-graphic-1024x500.png")
    for name, head, sub in SLIDES:
        render(slide(name, head, sub), OUT / f"{name}.png", 1080, 1920)
    print(f"Wrote {len(SLIDES)} screenshots and the feature graphic to {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()

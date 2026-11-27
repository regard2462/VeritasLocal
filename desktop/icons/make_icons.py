"""Erzeugt das App-Bild fuer Windows (.ico) und Linux (.png) aus derselben Zeichnung wie das Android-Symbol.

Aufruf: python make_icons.py  (schreibt veritas.png und veritas.ico in diesen Ordner)
"""
from pathlib import Path
from PIL import Image, ImageDraw

S = 1024
K = S / 160  # Entwurf im Raster 160 x 160


def p(x, y):
    return (x * K, y * K)


def draw() -> Image.Image:
    big = S * 2  # doppelt zeichnen und verkleinern fuer glatte Kanten
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = 2 * K

    def q(x, y):
        return (x * k, y * k)

    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=36 * k, fill="#111827")
    blue = (96, 165, 250, 255)
    blue_soft = (96, 165, 250, 160)
    tip = q(80, 122)
    for (x, y) in [(58, 38), (102, 38)]:
        d.line([q(x, y), tip], fill=blue_soft, width=int(5 * k))
    for (x, y) in [(34, 44), (126, 44)]:
        d.line([q(x, y), tip], fill=blue, width=int(8 * k))
    for (x, y, r) in [(34, 44, 10), (58, 38, 8), (102, 38, 8), (126, 44, 10)]:
        d.ellipse([q(x - r, y - r), q(x + r, y + r)], fill="#FFFFFF")
    d.ellipse([q(80 - 18, 122 - 18), q(80 + 18, 122 + 18)], fill="#4ADE80")
    d.line([q(71, 122), q(78, 129), q(90, 115)], fill="#111827", width=int(5 * k), joint="curve")
    return img.resize((S, S), Image.LANCZOS)


def main():
    here = Path(__file__).parent
    img = draw()
    img.save(here / "veritas.png")
    img.save(here / "veritas.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])


if __name__ == "__main__":
    main()

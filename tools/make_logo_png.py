"""Renders src/main/resources/img/logo.png (256 px) from the same geometry as logo.svg. Needs Pillow."""
import os
from PIL import Image, ImageDraw

S = 1024
K = S / 64
out = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "img", "logo.png")

im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)


def poly(pts, fill, outline, width):
    d.polygon([(x * K, y * K) for x, y in pts], fill=fill, outline=outline, width=int(width * K))


poly([(32, 3), (61, 32), (32, 61), (3, 32)], (11, 22, 48, 255), (201, 162, 39, 255), 3)
poly([(32, 12), (52, 32), (32, 52), (12, 32)], (59, 130, 224, 255), (232, 200, 119, 255), 2)
shine = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(shine).polygon([(32 * K, 12 * K), (52 * K, 32 * K), (32 * K, 32 * K)], fill=(232, 200, 119, 90))
Image.alpha_composite(im, shine).resize((256, 256), Image.LANCZOS).save(out)

"""Import the reviewed Higgsfield sheets; requires Pillow, never runs generation.

python tools/import_equipment_pack.py --output out-test/texture-review/candidate-assets
python tools/import_equipment_pack.py

The four authored silhouettes are shared by all six materials. Palette mapping
is done in the asset build so every runtime renderer sees the same RGBA sprite.
"""
from argparse import ArgumentParser
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/textures/source"
KINDS = ("pickaxe", "axe", "shovel", "sword")


def colors(hexes):
    return [tuple(bytes.fromhex(c)) for c in hexes.split()]


WOOD = colors("252323 513823 79532e aa793e d4a461")
MATERIALS = {
    "wood": colors("252323 513823 76502e a07742 c69a59 e5bd79"),
    "stone": colors("252323 454747 646963 878e81 aab19f cdd2bd"),
    "iron": colors("252323 424c52 67757b 939fa2 c3cecd eaf1e9"),
    "copper": colors("252323 63392c 965136 c77747 e8a46b ffce92"),
    "gold": colors("252323 715021 a77a27 d2a438 f0ca5c ffeb9b"),
    "diamond": colors("252323 205458 287f83 39afb2 72d9d4 c0f4e5"),
}
STONE = colors("464f5a 535e6a 616d79 707b85 818b94 959ea5")


def nearest(rgb, palette):
    return min(palette, key=lambda c: sum((c[i] - rgb[i]) ** 2 for i in range(3)))


def cells(sheet):
    """Keep the 4x2 source layout; sample pixel centres, never smooth edges."""
    w, h = sheet.size
    return [sheet.crop((i % 4 * w // 4, i // 4 * h // 2,
                        (i % 4 + 1) * w // 4, (i // 4 + 1) * h // 2))
            for i in range(8)]


def keyed(cell):
    # A saturated magenta key preserves the dark outline, unlike the old
    # brightness threshold that punched holes through shadows and handles.
    cell = cell.convert("RGBA")
    data = []
    for r, g, b, a in cell.getdata():
        data.append((0, 0, 0, 0) if r > 100 and b > 100 and g < min(r, b) * .65
                    else (r, g, b, 255))
    cell.putdata(data)
    bounds = cell.getbbox()
    if bounds is None:
        raise ValueError("Empty generated sprite cell")
    art = cell.crop(bounds)
    # Preserve aspect and leave a transparent margin for inventory slots.
    ratio = 28 / max(art.size)
    art = art.resize((round(art.width * ratio), round(art.height * ratio)), Image.Resampling.NEAREST)
    tile = Image.new("RGBA", (32, 32))
    tile.paste(art, ((32 - art.width) // 2, (32 - art.height) // 2))
    return tile


def equipment(sheet):
    sprites = [keyed(c) for c in cells(sheet)]
    result = {}
    for kind, source in zip(KINDS, sprites):
        for material, palette in MATERIALS.items():
            pixels = []
            for r, g, b, a in source.getdata():
                if not a:
                    pixels.append((0, 0, 0, 0))
                elif r > g * 1.16 and g > b * 1.12:
                    pixels.append((*nearest((r, g, b), WOOD), 255))
                else:
                    luminance = (r + g + b) / 3
                    level = sum(luminance > threshold for threshold in (44, 80, 120, 165, 211))
                    pixels.append((*palette[level], 255))
            tile = Image.new("RGBA", (32, 32))
            tile.putdata(pixels)
            result[f"{material}_{kind}"] = tile
    for name, source in zip(("copper_ingot", "iron_ingot", "gold_ingot", "stick"), sprites[4:]):
        palette = WOOD if name == "stick" else MATERIALS[name.split("_")[0]]
        source.putdata([(*nearest(p[:3], palette), 255) if p[3] else (0, 0, 0, 0)
                        for p in source.getdata()])
        result[name] = source
    return result


def rocks(sheet):
    tiles = [c.resize((32, 32), Image.Resampling.NEAREST).convert("RGB") for c in cells(sheet)]
    names = ("stone", "cobblestone", "mossy_cobblestone", "bedrock",
             "coal_ore", "iron_ore", "gold_ore", "diamond_ore")
    stone = Image.new("RGB", (32, 32))
    stone.putdata([nearest(p, STONE) for p in tiles[0].getdata()])
    result = {"stone": stone.convert("RGBA")}
    for name, tile in zip(names[1:4], tiles[1:4]):
        # Reduce generation noise but retain the authored stone/moss shapes.
        result[name] = tile.quantize(colors=16, method=Image.Quantize.MEDIANCUT,
                                    dither=Image.Dither.NONE).convert("RGBA")
    for name, tile in zip(names[4:], tiles[4:]):
        pixels = []
        for base, p in zip(stone.getdata(), tile.getdata()):
            r, g, b = p
            if name == "coal_ore":
                mineral = max(p) < 60
                palette = colors("1d242a 29323b 38424d")
            elif name == "diamond_ore":
                mineral = g > r * 1.2 and b > r * 1.2
                palette = MATERIALS["diamond"]
            else:
                mineral = r > g * 1.15 and g > b * 1.15
                palette = MATERIALS["iron"] if name == "iron_ore" else MATERIALS["gold"]
                if name == "iron_ore":
                    palette = colors("72533d 99724e bb9369 d2b18a")
            pixels.append(nearest(p, palette) if mineral else base)
        ore = Image.new("RGB", (32, 32))
        ore.putdata(pixels)
        result[name] = ore.convert("RGBA")
    return result


def review(sprites, output):
    names = [f"{material}_{kind}" for material in MATERIALS for kind in KINDS]
    names += ["copper_ingot", "iron_ingot", "gold_ingot", "stick"]
    names += [name for name in sprites if name not in names]
    image = Image.new("RGB", (4 * 180, ((len(names) + 3) // 4) * 160), "#18222e")
    draw = ImageDraw.Draw(image)
    for i, name in enumerate(names):
        x, y = i % 4 * 180, i // 4 * 160
        for yy in range(128):
            for xx in range(128):
                image.putpixel((x + 24 + xx, y + yy), (44, 54, 65) if (xx // 8 + yy // 8) % 2 else (37, 47, 58))
        tile = sprites[name].resize((128, 128), Image.Resampling.NEAREST)
        image.paste(tile, (x + 24, y), tile)
        draw.text((x + 18, y + 135), name, fill="#e0e5ea")
    image.save(output)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "assets/textures/blocks")
    parser.add_argument("--review", type=Path, default=ROOT / "out-test/texture-review/candidate-textures.png")
    args = parser.parse_args()
    sprites = equipment(Image.open(SOURCE / "higgsfield-equipment-2026-09-24.png"))
    sprites.update(rocks(Image.open(SOURCE / "higgsfield-stone-2026-09-24.png")))
    args.output.mkdir(parents=True, exist_ok=True)
    for name, sprite in sprites.items():
        sprite.save(args.output / f"{name}.png")
    args.review.parent.mkdir(parents=True, exist_ok=True)
    review(sprites, args.review)
    print(f"Imported {len(sprites)} sprites to {args.output}; review: {args.review}")


if __name__ == "__main__":
    main()

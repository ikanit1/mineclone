# -*- coding: utf-8 -*-
"""
Собирает трейлер из кадров, отрендеренных tools/RenderTrailer.java.

Делает две дорожки:
  trailer-clean.mp4 — без единой надписи, чтобы монтировать поверх своего;
  trailer-ru.mp4    — с подписями шрифтом самой игры, готово к заливке.

Чистая версия не роскошь: подпись, вшитую в кадр, уже не убрать, а трейлер
почти всегда переозвучивают и перекраивают.

Запуск из корня репозитория:
    python tools/make_trailer.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import imageio.v2 as imageio

FRAMES = "out-test/trailer"
FONT = "assets/minecraft.ttf"
FPS = 30

# Подписи: (секунда начала, секунда конца, строка, подстрока).
# Текст называет вещь, а не расхваливает её: зритель сам видит картинку,
# ему нужно слово, по которому он поймёт, на что смотрит.
CAPTIONS = [
    (0.6,  6.0,  "Свет в линейном HDR",        "каскадные тени · god rays · ACES"),
    (7.0, 11.6,  "Реки и озёра",               "врезаны прямо в рельеф, а не положены сверху"),
    (12.6, 17.6, "Пещеры и руды",              "свет факела в руке и эхо по замкнутости"),
    (18.6, 24.0, "Погода фронтами",            "ливень, гроза, ветер — без единого байта в сейве"),
    (25.0, 30.0, "Метель",                     "снег знает, где крыша"),
    (31.0, 36.4, "Фазы луны и сияние",         "чистая функция времени и сида"),
    (37.4, 41.6, "Фоторежим",                  "свободная камера и глубина резкости"),
]

FADE_IN = 0.5      # секунд из чёрного в начале
FADE_OUT = 0.9     # и в чёрное в конце
CAPTION_FADE = 0.45


def frames():
    names = sorted(f for f in os.listdir(FRAMES) if f.endswith(".png"))
    if not names:
        sys.exit("no frames in %s - run tools/RenderTrailer.java first" % FRAMES)
    return [os.path.join(FRAMES, n) for n in names]


def caption_alpha(t):
    """Прозрачность подписи в момент t: какая активна и насколько проявилась."""
    for start, end, title, sub in CAPTIONS:
        if start <= t <= end:
            a = min((t - start) / CAPTION_FADE, (end - t) / CAPTION_FADE, 1.0)
            return max(0.0, a), title, sub
    return 0.0, None, None


def draw_caption(img, t, big, small):
    a, title, sub = caption_alpha(t)
    if a <= 0.01:
        return img
    w, h = img.size
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x = int(w * 0.055)
    y = int(h * 0.76)
    # Подложка-градиент снизу: по светлому небу белый текст иначе пропадает.
    band = Image.new("RGBA", (w, int(h * 0.34)), (0, 0, 0, 0))
    bd = ImageDraw.Draw(band)
    for i in range(band.size[1]):
        k = i / band.size[1]
        bd.line([(0, i), (w, i)], fill=(0, 0, 0, int(150 * k * k * a)))
    layer.alpha_composite(band, (0, h - band.size[1]))
    # Тонкая линия слева — якорь для глаза.
    d.rectangle([x - 14, y - 4, x - 10, y + int(h * 0.075)], fill=(255, 210, 140, int(230 * a)))
    d.text((x, y), title, font=big, fill=(255, 255, 255, int(255 * a)))
    d.text((x, y + int(h * 0.048)), sub, font=small, fill=(215, 222, 230, int(225 * a)))
    return Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")


def fade(img, t, total):
    k = 1.0
    if t < FADE_IN:
        k = t / FADE_IN
    if t > total - FADE_OUT:
        k = min(k, max(0.0, (total - t) / FADE_OUT))
    if k >= 0.999:
        return img
    return Image.blend(Image.new("RGB", img.size, (0, 0, 0)), img, k)


def encode(paths, out, big=None, small=None):
    total = len(paths) / FPS
    # CRF, а не битрейт: в трейлере половина кадров — гладкое небо, и
    # фиксированный битрейт тратит на него столько же, сколько на метель.
    writer = imageio.get_writer(out, fps=FPS, codec="libx264",
                                macro_block_size=1, pixelformat="yuv420p",
                                ffmpeg_params=["-crf", "21", "-preset", "slow"])
    for i, p in enumerate(paths):
        t = i / FPS
        img = Image.open(p).convert("RGB")
        if big is not None:
            img = draw_caption(img, t, big, small)
        img = fade(img, t, total)
        writer.append_data(np.asarray(img))
    writer.close()
    size = os.path.getsize(out) / 1e6
    # Сообщения латиницей: у консоли Windows кодировка не UTF-8.
    print("%s - %.1f s, %.1f MB" % (out, total, size))


def main():
    paths = frames()
    h = Image.open(paths[0]).size[1]
    big = ImageFont.truetype(FONT, int(h * 0.042))
    small = ImageFont.truetype(FONT, int(h * 0.023))
    encode(paths, "out-test/trailer-clean.mp4")
    encode(paths, "out-test/trailer-ru.mp4", big, small)


if __name__ == "__main__":
    main()

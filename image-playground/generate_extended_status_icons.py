#!/usr/bin/env python3
"""Generate status icon assets for values 101..140.

This script derives icon backgrounds from existing 000..100 assets and
renders the numeric text with ImageMagick, selecting point-size/offset
that best matches the existing 100 icon for each variant.
"""

import glob
import os
import subprocess
import tempfile

ROOT = "/Users/tomaszswistak/projekty/Battery-Indicator-Pro"
TARGETS = [
    ("app/res/drawable", "n"),
    ("app/res/drawable", "plain"),
    ("app/res/drawable", "small_plain"),
    ("app/res/drawable", "charging"),
    ("app/res/drawable", "small_charging"),
    ("app/res/drawable-hdpi", "n"),
    ("app/res/drawable-hdpi", "plain"),
    ("app/res/drawable-hdpi", "small_plain"),
    ("app/res/drawable-hdpi", "charging"),
    ("app/res/drawable-hdpi", "small_charging"),
]
FONT = "Helvetica-Bold"
GENERATE_RANGE = range(101, 141)


def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def image_size(path):
    out = subprocess.check_output([
        "magick",
        "identify",
        "-format",
        "%w %h",
        path,
    ], text=True)
    w_s, h_s = out.strip().split()
    return int(w_s), int(h_s)


def render_text(out_path, width, height, text, point_size, dy, fill):
    run([
        "magick",
        "-size",
        f"{width}x{height}",
        "xc:none",
        "-gravity",
        "center",
        "-fill",
        fill,
        "-font",
        FONT,
        "-pointsize",
        str(point_size),
        "-annotate",
        f"+0+{dy}",
        text,
        out_path,
    ])


def diff_metric(a, b):
    p = subprocess.run([
        "compare",
        "-metric",
        "AE",
        a,
        b,
        "null:",
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    s = (p.stderr or p.stdout).strip().split("(")[0].strip()
    return int(float(s))


def find_best_params(base_dir, variant):
    sample_path = os.path.join(base_dir, f"{variant}100.png")
    width, height = image_size(sample_path)
    fill = "black" if variant == "n" else "white"

    with tempfile.TemporaryDirectory() as td:
        bg_path = None
        if variant != "n":
            inputs = [os.path.join(base_dir, f"{variant}{i:03d}.png") for i in range(0, 101)]
            bg_path = os.path.join(td, f"{variant}_bg.png")
            run(["magick", *inputs, "-evaluate-sequence", "min", bg_path])

        min_size = max(6, int(height * 0.25))
        max_size = max(min_size + 1, int(height * 0.65))

        best = None
        for size in range(min_size, max_size + 1):
            for dy in range(-max(2, height // 10), max(2, height // 10) + 1):
                text_path = os.path.join(td, "text.png")
                cand_path = os.path.join(td, "cand.png")
                render_text(text_path, width, height, "100", size, dy, fill)

                if variant == "n":
                    run(["cp", text_path, cand_path])
                else:
                    run(["magick", bg_path, text_path, "-gravity", "center", "-composite", cand_path])

                metric = diff_metric(sample_path, cand_path)
                if best is None or metric < best[0]:
                    best = (metric, size, dy, bg_path)

        if best is None:
            raise RuntimeError(f"Unable to fit text params for {base_dir}/{variant}")

        return best[1], best[2], best[3], width, height


def generate_variant(base_dir, variant):
    point_size, dy, bg_path, width, height = find_best_params(base_dir, variant)
    fill = "black" if variant == "n" else "white"

    with tempfile.TemporaryDirectory() as td:
        if variant != "n":
            # Keep a stable background file after temp dir from fitting has been removed.
            bg = os.path.join(td, "bg.png")
            inputs = [os.path.join(base_dir, f"{variant}{i:03d}.png") for i in range(0, 101)]
            run(["magick", *inputs, "-evaluate-sequence", "min", bg])
        else:
            bg = None

        for value in GENERATE_RANGE:
            text_path = os.path.join(td, "text.png")
            out_path = os.path.join(base_dir, f"{variant}{value:03d}.png")
            render_text(text_path, width, height, str(value), point_size, dy, fill)

            if variant == "n":
                run(["cp", text_path, out_path])
            else:
                run(["magick", bg, text_path, "-gravity", "center", "-composite", out_path])

    return point_size, dy


def main():
    for rel_dir, variant in TARGETS:
        base_dir = os.path.join(ROOT, rel_dir)
        if not os.path.isdir(base_dir):
            continue

        sample = glob.glob(os.path.join(base_dir, f"{variant}100.png"))
        if not sample:
            continue

        size, dy = generate_variant(base_dir, variant)
        print(f"Generated {variant}101..140 in {rel_dir} (point={size}, dy={dy})")


if __name__ == "__main__":
    main()


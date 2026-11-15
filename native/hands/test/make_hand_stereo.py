#!/usr/bin/env python3
"""Synthesize 480x640 stereo PGM pairs containing photographed hands, for the
libultra_hands smoke test. NOT a ground-truth dataset: a flat photo is pasted
into both views with a horizontal disparity that matches the XREAL Air 2 Ultra
baseline for a chosen distance, ignoring fisheye distortion and perspective.
Good enough to see whether the detector fires and the tracking path runs,
and to sanity check the output frame (depth ~ simulated distance).

Source photo (not redistributed here): MediaPipe test asset
  https://storage.googleapis.com/mediapipe-assets/left_hands.jpg
usage: make_hand_stereo.py left_hands.jpg out_dir
"""
import sys
import numpy as np
from PIL import Image

W, H = 480, 640
FX, BASELINE = 238.5, 0.1364  # px, metres (from mercury_calib_unified_480_ccw90.json)


def hand_cutout(photo):
    g = np.asarray(photo.convert("L"), dtype=np.float32)
    left = g[:, : g.shape[1] // 2]            # back of a left hand, fingers up
    mask = left < 232                          # photo background is white
    return left, mask


def paste(canvas, img, mask, cx, cy, height_px, mirror=False):
    scale = height_px / img.shape[0]
    size = (max(1, int(img.shape[1] * scale)), int(height_px))
    im = Image.fromarray(img.astype(np.uint8)).resize(size, Image.LANCZOS)
    mk = Image.fromarray((mask * 255).astype(np.uint8)).resize(size, Image.LANCZOS)
    if mirror:
        im, mk = im.transpose(Image.FLIP_LEFT_RIGHT), mk.transpose(Image.FLIP_LEFT_RIGHT)
    canvas.paste(im, (int(cx - size[0] / 2), int(cy - size[1] / 2)), mk)


def make(photo, out, name, hands, background=45):
    """hands: list of (cx, cy, height_px, distance_m, mirror)"""
    left_img, mask = hand_cutout(photo)
    views = []
    for view in (0, 1):
        c = Image.new("L", (W, H), background)
        for (cx, cy, hpx, dist, mirror) in hands:
            disparity = FX * BASELINE / dist
            # cam0 is the user's left camera: objects appear further right in it.
            x = cx + disparity / 2 if view == 0 else cx - disparity / 2
            paste(c, left_img, mask, x, cy, hpx, mirror)
        views.append(c)
    for view, c in enumerate(views):
        with open(f"{out}/{name}_cam{view}.pgm", "wb") as f:
            f.write(b"P5\n%d %d\n255\n" % (W, H))
            f.write(c.tobytes())
        c.save(f"{out}/{name}_cam{view}.png")


if __name__ == "__main__":
    photo = Image.open(sys.argv[1])
    out = sys.argv[2]
    # The photo crop is ~1.15x the hand length (0.19 m): height_px = FX*0.19*1.15/dist = 52.1/dist
    # (cx, cy, height_px, distance_m, mirror). Mirroring the left hand gives a right hand.
    make(photo, out, "left_hand_20cm", [(240, 360, 260, 0.20, False)], background=255)
    make(photo, out, "right_hand_20cm", [(240, 360, 260, 0.20, True)], background=255)
    make(photo, out, "left_hand_16cm", [(240, 360, 320, 0.163, False)], background=255)
    # Negative examples kept on purpose: at realistic size/contrast for this
    # pasted studio photo the detector does NOT fire (see NOTES.md).
    make(photo, out, "left_hand_40cm_dark", [(240, 360, 130, 0.40, False)], background=45)
    make(photo, out, "left_hand_30cm_white", [(240, 360, 174, 0.30, False)], background=255)

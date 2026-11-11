"""Host checks for pb3_export.py: python3 -I test_pb3_export.py (numpy needed)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pb3_export import distort, rotate_point, rotate_tangential  # noqa: E402

# Pixel 6 ultrawide factory distortion [k1, k2, k3, p1, p2], plus exaggerated tangential terms
# so a wrong sign could not hide in rounding.
K1, K2, K3, P1, P2 = 0.09558, -0.10258, 0.01634, -0.03, 0.02


def test_tangential_rotation_matches_rotating_the_image():
    pts = [(0.3, -0.2), (-0.5, 0.4), (0.1, 0.6), (-0.7, -0.3)]
    for o in (0, 90, 180, 270):
        p1r, p2r = rotate_tangential(P1, P2, o)
        for x, y in pts:
            # distort in sensor orientation, then rotate the result ...
            a = rotate_point(*distort(x, y, K1, K2, K3, P1, P2), o)
            # ... equals rotating first and distorting with the converted coefficients
            b = distort(*rotate_point(x, y, o), K1, K2, K3, p1r, p2r)
            assert abs(a[0] - b[0]) < 1e-12 and abs(a[1] - b[1]) < 1e-12, (o, x, y, a, b)


def test_rotation_matches_the_apps_intrinsics_transform():
    # App, orientation 90 (FocalLengthHelper): cx' = H - cy - 1, cy' = cx -> x' = -y, y' = x.
    assert rotate_point(1.0, 0.0, 90) == (-0.0, 1.0)
    assert rotate_point(0.0, 1.0, 90) == (-1.0, 0.0)
    # 270: cx' = cy, cy' = W - cx - 1 -> x' = y, y' = -x.
    assert rotate_point(1.0, 0.0, 270) == (0.0, -1.0)


def test_wrong_direction_would_be_caught():
    x, y = 0.3, -0.2
    a = rotate_point(*distort(x, y, K1, K2, K3, P1, P2), 90)
    b = distort(*rotate_point(x, y, 90), K1, K2, K3, *rotate_tangential(P1, P2, 270))
    assert abs(a[0] - b[0]) > 1e-4 or abs(a[1] - b[1]) > 1e-4


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok ", name)

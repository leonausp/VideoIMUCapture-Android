"""Host checks for pb3_export.py: python3 -I test_pb3_export.py (numpy needed)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pb3_export import distort, fit_radial_k1k2, rotate_point, rotate_tangential, sensor_to_video  # noqa: E402

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




def test_k3_refit_matches_the_factory_curve_on_the_pixel6_main_lens():
    k1, k2, k3 = 0.12811672687530518, -0.19766631722450256, 0.12141027301549911
    fx, r_max = 876.67, 0.913
    f1, f2, err = fit_radial_k1k2(k1, k2, k3, r_max)
    assert err * fx < 5.0, err * fx                       # refit: a few px in the far corner
    r = r_max
    dropped = abs(r * k3 * r ** 6) * fx
    assert dropped > 40.0, dropped                        # truncating k3 would be ~56 px
    # No k3: the fit returns the same k1, k2.
    g1, g2, e2 = fit_radial_k1k2(0.1, -0.05, 0.0, r_max)
    assert abs(g1 - 0.1) < 1e-9 and abs(g2 + 0.05) < 1e-9 and e2 < 1e-12


def test_sensor_to_video_reproduces_the_apps_pixel6_values():
    # Main lens, factory LENS_INTRINSIC_CALIBRATION, 4080x3072 array, 960x1280 portrait video.
    cx, cy, s = sensor_to_video(2031.5876, 1547.8085, 4080, 3072, 960, 1280, 90)
    assert abs(2794.3948 * s - 876.6729) < 0.01, 2794.3948 * s     # app: fx 876.67
    assert abs(cy - 637.3608) < 0.01, cy                             # app: cy 637.36
    # cx: the app ignores the 6-row crop of the 4080x3072 array to 4:3 (473.41); this uses it.
    assert abs(cx - 473.4130) < 2.0, cx
    # Ultrawide, 4032x3016 array (Pixel 6 camera 3): fx ~600 px, as the app's estimate (601).
    cx, cy, s = sensor_to_video(2004.0356, 1508.5385, 4032, 3016, 960, 1280, 90)
    assert abs(1886.4777 * s - 600.5) < 1.0, 1886.4777 * s
    # The principal point of a centred sensor lands in the middle of the video, any rotation.
    for o, (w, h) in [(0, (1280, 960)), (90, (960, 1280)), (180, (1280, 960)), (270, (960, 1280))]:
        cx, cy, _ = sensor_to_video(2039.5, 1529.5, 4080, 3060, w, h, o)
        assert abs(cx - (w - 1) / 2) < 0.6 and abs(cy - (h - 1) / 2) < 0.6, (o, cx, cy)


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok ", name)

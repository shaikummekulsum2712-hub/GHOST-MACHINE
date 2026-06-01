"""
Device profile for Ghost Machine.
Contains screen dimensions and device info used by the AI brain
to generate accurate tap coordinates.
"""

# Auto-detected from: adb shell wm size / wm density / getprop ro.product.model
DEVICE_PROFILE = {
    "model": "iQOO I2221",
    "screen_width": 1260,
    "screen_height": 2800,
    "density_dpi": 480,
    "android_version": "16",
}


def get_device_context() -> str:
    """Return a text description of the device for the AI system prompt."""
    d = DEVICE_PROFILE
    return f"""Device: {d['model']}
Screen resolution: {d['screen_width']}x{d['screen_height']} pixels
Density: {d['density_dpi']} dpi
Android version: {d['android_version']}
Coordinate system: Origin (0,0) is top-left corner. X increases rightward, Y increases downward.
Status bar height: ~100px from top
Navigation bar height: ~150px from bottom
Usable area: approximately 0-{d['screen_width']} horizontally, 100-{d['screen_height'] - 150} vertically"""

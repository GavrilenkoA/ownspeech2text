from ml.ocr.real_probe import focused_field

XML = ('<?xml version="1.0"?><hierarchy rotation="0">'
       '<node class="android.widget.FrameLayout" package="p" focused="true" bounds="[0,0][1080,2520]">'
       '<node class="android.widget.EditText" package="com.google.android.apps.messaging" focused="true" text="Привет" '
       'bounds="[316,2310][796,2436]"/></node></hierarchy>')


def test_focused_field_takes_the_focused_edittext():
    assert focused_field(XML) == {"cls": "android.widget.EditText", "pkg": "com.google.android.apps.messaging",
                                  "box": [316, 2310, 796, 2436]}
    assert focused_field(XML.replace('focused="true" text', 'focused="false" text')) is None
    assert focused_field("garbage") is None

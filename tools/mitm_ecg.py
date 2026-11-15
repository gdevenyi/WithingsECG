"""mitmproxy addon: Withings ECG unlock support.

Run:  ~/.local/bin/mitmdump -s mitm_ecg.py --listen-port 8080

1. Logs every scalews.withings.net request/response (v2/feature, v2/firmware).
2. Rewrites `v2/feature?action=getbydevice` responses to include platform
   feature 4 (ECG) as an active feature for every device seen in the response,
   mirroring the shape Withings returns for ECG-cleared devices.
3. Captures `v2/firmware?action=getupdate` responses to disk so the Vitals and
   ScanWatch 2 firmware offers can be diffed.

Requires the patched app to trust user CAs (Override certificate pinning patch)
and the mitmproxy CA installed on the device.
"""

import gzip
import json
import os
import zlib
from datetime import datetime
from urllib.parse import parse_qs, urlparse

from mitmproxy import http

CAPTURE_DIR = os.path.dirname(os.path.abspath(__file__))
ECG_FEATURE_ID = 4


def _decode_body(content: bytes) -> str:
    if not content:
        return ""
    try:
        return gzip.decompress(content).decode("utf-8", "replace")
    except Exception:
        return content.decode("utf-8", "replace")


def response(flow: http.HTTPFlow) -> None:
    url = urlparse(flow.request.pretty_url)
    if "withings" not in url.netloc:
        return
    qs = parse_qs(url.query)
    action = qs.get("action", [""])[0]
    path = url.path

    if path.endswith("/v2/feature") and action == "getbydevice":
        body = _decode_body(flow.response.raw_content)
        with open(os.path.join(CAPTURE_DIR, "getbydevice.log"), "a") as log:
            log.write(f"--- {datetime.now()} ---\n{body}\n")
        try:
            payload = json.loads(body)
        except Exception:
            print("[mitm_ecg] getbydevice: non-JSON response, leaving untouched")
            return
        features = payload.get("features") or []
        device_ids = set()
        for feature in features:
            for owner in feature.get("owners") or []:
                if owner.get("deviceid") is not None:
                    device_ids.add(int(owner["deviceid"]))
        print(f"[mitm_ecg] getbydevice: {len(features)} features, devices={device_ids}")
        if not device_ids:
            # No device-keyed owners present: fall back to account-wide injection.
            device_ids = {0}
        for device_id in device_ids:
            features.append(
                {
                    "featureid": ECG_FEATURE_ID,
                    "name": "ECG",
                    "owners": [
                        {"deviceid": device_id, "active": True, "activable": True,
                         "write": True, "modified": 0, "startdate": None,
                         "enddate": None, "frequency": None,
                         "tutorialSeen": True, "minAge": None},
                    ],
                    "synced": True,
                }
            )
        payload["features"] = features
        flow.response.set_text(json.dumps(payload))
        print(f"[mitm_ecg] getbydevice: injected ECG feature 4 for {device_ids}")

    if path.endswith("/v2/feature") and action == "set":
        print(f"[mitm_ecg] feature SET request: {flow.request.text}")
        with open(os.path.join(CAPTURE_DIR, "feature_set.log"), "a") as log:
            log.write(f"--- {datetime.now()} ---\n{flow.request.text}\n"
                      f"response: {_decode_body(flow.response.raw_content)}\n")

    if path.endswith("/v2/firmware") and action == "getupdate":
        body = _decode_body(flow.response.raw_content)
        stamp = datetime.now().strftime("%H%M%S")
        with open(os.path.join(CAPTURE_DIR, f"firmware_getupdate_{stamp}.json"), "wb") as out:
            out.write(body.encode("utf-8", "replace"))
        print(f"[mitm_ecg] firmware offer captured ({len(body)} bytes)")
        try:
            offer = json.loads(body)
            node = offer.get("body", offer)
            if isinstance(node, dict):
                print(f"[mitm_ecg] firmware url={node.get('url')}")
                print(f"[mitm_ecg] firmware version={node.get('version')} md5={node.get('md5')} sha512={node.get('sha512')}")
        except Exception:
            pass

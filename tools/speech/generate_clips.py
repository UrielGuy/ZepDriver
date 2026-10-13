#!/usr/bin/env python3
"""Pre-records ZepDriver's warning phrases as audio clips.

Reads the phrase catalog written by `./gradlew :core:speechCatalog`
(core/build/speech/phrases.json), synthesizes each phrase with Piper
(https://github.com/rhasspy/piper) and encodes it as Ogg Vorbis. Writes
<out>/<id>.ogg plus <out>/index.json mapping phrase text -> file name, which
the app's alert player uses before falling back to the phone's text-to-speech.

  python3 tools/speech/generate_clips.py --phrases core/build/speech/phrases.json \
      --piper path/to/piper --model en_US-ljspeech-high.onnx --out app/src/main/assets/speech
"""
import argparse
import json
import os
import subprocess
import tempfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--phrases", required=True)
    ap.add_argument("--piper", required=True, help="piper executable")
    ap.add_argument("--model", required=True, help="Piper voice .onnx (its .onnx.json next to it)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--length-scale", default="0.95", help="<1 speaks a little faster")
    a = ap.parse_args()

    with open(a.phrases, encoding="utf-8") as f:
        phrases = json.load(f)
    os.makedirs(a.out, exist_ok=True)
    index = {}
    with tempfile.TemporaryDirectory() as tmp:
        for p in phrases:
            wav = os.path.join(tmp, p["id"] + ".wav")
            subprocess.run(
                [a.piper, "--model", a.model, "--output_file", wav, "--length_scale", a.length_scale],
                input=p["text"].encode("utf-8"), check=True, capture_output=True,
            )
            ogg = p["id"] + ".ogg"
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", wav,
                 # A touch of lead-in so Bluetooth speakers don't clip the first word.
                 "-af", "adelay=150,loudnorm=I=-14:TP=-1.5",
                 "-ac", "1", "-ar", "22050", "-c:a", "libvorbis", "-q:a", "4",
                 os.path.join(a.out, ogg)],
                check=True,
            )
            index[p["text"]] = ogg
    with open(os.path.join(a.out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    print(f"{len(index)} clips -> {a.out}")


if __name__ == "__main__":
    main()

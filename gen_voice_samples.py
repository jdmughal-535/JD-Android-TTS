"""Generate one short sample per Supertonic voice (sid 0-9), same sentence.

Output: app/src/main/assets/voice_samples/{f1..f5,m1..m5}.mp3
These are bundled in the APK so the user can preview a voice in the
dropdown BEFORE downloading the 129 MB model.
"""
import os
import subprocess
import wave

import numpy as np
import sherpa_onnx

MODEL_DIR = (
    "/home/hatch/workspace/sherpa-model/"
    "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
)
OUT = "/home/hatch/workspace/jd-android-tts/app/src/main/assets/voice_samples"
TEXT = "Hello! This is JD Android TTS, speaking offline."
NAMES = ["f1", "f2", "f3", "f4", "f5", "m1", "m2", "m3", "m4", "m5"]

config = sherpa_onnx.OfflineTtsConfig(
    model=sherpa_onnx.OfflineTtsModelConfig(
        supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
            duration_predictor=f"{MODEL_DIR}/duration_predictor.int8.onnx",
            text_encoder=f"{MODEL_DIR}/text_encoder.int8.onnx",
            vector_estimator=f"{MODEL_DIR}/vector_estimator.int8.onnx",
            vocoder=f"{MODEL_DIR}/vocoder.int8.onnx",
            tts_json=f"{MODEL_DIR}/tts.json",
            unicode_indexer=f"{MODEL_DIR}/unicode_indexer.bin",
            voice_style=f"{MODEL_DIR}/voice.bin",
        ),
        num_threads=2,
        debug=False,
    ),
)
tts = sherpa_onnx.OfflineTts(config)
os.makedirs(OUT, exist_ok=True)

for sid, name in enumerate(NAMES):
    audio = tts.generate(TEXT, sid=sid, speed=1.0)
    samples = np.asarray(audio.samples, dtype=np.float32)
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    wav_path = f"/tmp/sample-{name}.wav"
    with wave.open(wav_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        w.writeframes(pcm.tobytes())
    mp3_path = os.path.join(OUT, f"{name}.mp3")
    subprocess.run(
        ["ffmpeg", "-y", "-v", "error", "-i", wav_path,
         "-codec:a", "libmp3lame", "-b:a", "96k", "-ac", "1", mp3_path],
        check=True,
    )
    dur = len(samples) / audio.sample_rate
    print(
        f"{name} sid={sid} rate={audio.sample_rate} "
        f"dur={dur:.2f}s mp3={os.path.getsize(mp3_path)} bytes",
        flush=True,
    )

print("DONE")

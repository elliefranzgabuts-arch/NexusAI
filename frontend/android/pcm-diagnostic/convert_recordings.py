#!/usr/bin/env python3
"""Convert the two supplied NEXUS MP3 recordings into test-only raw PCM."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


SAMPLE_RATE = 16_000
CHANNELS = 1
BYTES_PER_SAMPLE = 2
DEFAULT_POSITIVE = "Record (online-voice-recorder.com) (1).mp3"
DEFAULT_NEGATIVE = "Record (online-voice-recorder.com).mp3"


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, check=True, text=True, capture_output=True)


def probe(path: Path) -> tuple[dict, float]:
    result = run([
        "ffprobe", "-v", "error", "-select_streams", "a:0",
        "-show_entries", "stream=codec_name,sample_rate,channels,duration",
        "-show_entries", "format=format_name,duration,size", "-of", "json", str(path),
    ])
    metadata = json.loads(result.stdout)
    streams = metadata.get("streams", [])
    if len(streams) != 1:
        raise ValueError(f"expected one audio stream, found {len(streams)}")
    duration = float(streams[0].get("duration") or metadata.get("format", {}).get("duration") or 0)
    if not path.is_file() or path.stat().st_size == 0 or duration <= 0:
        raise ValueError("input is empty or has no valid duration")
    return streams[0], duration


def convert(label: str, source: Path, destination: Path, ffmpeg: str) -> None:
    if not source.is_file() or not source.exists():
        raise ValueError(f"{label} input does not exist: {source}")
    if source.resolve() == destination.resolve():
        raise ValueError(f"refusing to overwrite source recording: {source}")
    if destination.exists():
        raise FileExistsError(f"output already exists; refusing to overwrite: {destination}")

    source_info, source_duration = probe(source)
    if source_info.get("codec_name") != "mp3":
        raise ValueError(f"{label} input is not MP3: codec={source_info.get('codec_name')}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent, delete=False
        ) as handle:
            temporary = Path(handle.name)

        run([
            ffmpeg, "-nostdin", "-v", "error", "-i", str(source),
            "-map", "0:a:0", "-vn", "-ac", str(CHANNELS), "-ar", str(SAMPLE_RATE),
            "-c:a", "pcm_s16le", "-f", "s16le", "-y", str(temporary),
        ])

        byte_count = temporary.stat().st_size
        if byte_count <= 0 or byte_count % BYTES_PER_SAMPLE:
            raise ValueError(f"decoded output is empty or not whole PCM16 samples ({byte_count} bytes)")
        sample_count = byte_count // BYTES_PER_SAMPLE
        duration = sample_count / SAMPLE_RATE
        if abs(duration - source_duration) > 0.1:
            raise ValueError(
                f"decoded duration differs from source ({duration:.3f}s vs {source_duration:.3f}s)"
            )
        if destination.exists():
            raise FileExistsError(f"output appeared during conversion; refusing to overwrite: {destination}")
        temporary.rename(destination)
        temporary = None

        print(f"{label}: {source} -> {destination}")
        print(f"  source: codec={source_info['codec_name']} rate={source_info['sample_rate']} "
              f"channels={source_info['channels']} duration={source_duration:.3f}s")
        print(f"  output: raw signed PCM16 little-endian, {SAMPLE_RATE} Hz, mono")
        print(f"  samples={sample_count} bytes={byte_count} duration={duration:.3f}s")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recordings-dir", type=Path, help="directory containing the original MP3s")
    parser.add_argument("--output-dir", type=Path, help="directory for new PCM outputs")
    parser.add_argument("--positive", default=DEFAULT_POSITIVE, help="positive MP3 filename")
    parser.add_argument("--negative", default=DEFAULT_NEGATIVE, help="negative MP3 filename")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[3]
    recordings_dir = (args.recordings_dir or repo_root / "backend/test-data/recordings").resolve()
    output_dir = (args.output_dir or repo_root / "backend/test-data/pcm-diagnostic").resolve()
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None or shutil.which("ffprobe") is None:
        print("ERROR: ffmpeg and ffprobe must already be installed; no packages were installed.", file=sys.stderr)
        return 2

    try:
        convert("POSITIVE", recordings_dir / args.positive, output_dir / "positive.pcm", ffmpeg)
        convert("NEGATIVE", recordings_dir / args.negative, output_dir / "negative.pcm", ffmpeg)
    except (OSError, ValueError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        print(f"ERROR: conversion failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

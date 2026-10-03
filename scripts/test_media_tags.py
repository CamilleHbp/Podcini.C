#!/usr/bin/env python3
"""Cross-read TagLib fixtures with Mutagen and compare encoded audio packets."""
import base64
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import shutil

import mutagen
from mutagen.id3 import APIC, CHAP, CTOC, TALB, TIT2, TXXX, USLT
from mutagen.flac import Picture
from mutagen.mp4 import MP4Cover, MP4FreeForm

CODEC = pathlib.Path(sys.argv[1]).resolve()
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jhRQAAAAASUVORK5CYII=")
GENRES = ["Rock/Alternative", "Jazz"]
TAGS = ["Activity/Focus", "Language/Français", r"Literal/AC\/DC", "Unicode/🎵", "Punctuation/one, two; three", r"Literal/a\\b"]


def run(*args):
    return subprocess.check_output(args, stderr=subprocess.PIPE)


def packets(path):
    data = json.loads(run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_packets", "-show_data_hash", "sha256", "-of", "json", str(path)))
    return [item["data_hash"] for item in data["packets"]]


def chapters(path):
    return json.loads(run("ffprobe", "-v", "error", "-show_chapters", "-of", "json", str(path))).get("chapters", [])


def unrelated(path):
    file = mutagen.File(path)
    tags = file.tags
    if path.suffix == ".mp3":
        def content(value):
            if isinstance(value, APIC):
                return hashlib.sha256(value.data).hexdigest(), value.mime, value.type, value.desc
            if isinstance(value, CHAP):
                return (value.element_id, value.start_time, value.end_time, value.start_offset, value.end_offset,
                        {key: str(frame) for key, frame in value.sub_frames.items()})
            return str(value)
        return {key: content(value)
                for key, value in tags.items() if not key.startswith(("TCON", "TMOO", "TXXX:TAGS", "TXXX:PODCINI_PATHS"))}
    if path.suffix in (".m4a", ".m4b"):
        return {key: repr(value) for key, value in tags.items() if key not in ("©gen", "----:com.apple.iTunes:MOOD", "----:com.apple.iTunes:TAGS", "----:com.apple.iTunes:PODCINI_PATHS")}
    data = {key: repr(value) for key, value in tags.items() if key.lower() not in ("genre", "mood", "tags", "podcini_paths")}
    if hasattr(file, "pictures"):
        data["pictures"] = [hashlib.sha256(picture.data).hexdigest() for picture in file.pictures]
    return data


def seed(path):
    file = mutagen.File(path)
    if path.suffix == ".mp3":
        file.tags.add(TALB(encoding=3, text=["Preserved album"]))
        file.tags.add(TXXX(encoding=3, desc="REPLAYGAIN_TRACK_GAIN", text=["-2.10 dB"]))
        file.tags.add(TXXX(encoding=3, desc="MusicBrainz Album Id", text=["test-album-id"]))
        file.tags.add(USLT(encoding=3, lang="eng", desc="", text="Preserved lyrics"))
        file.tags.add(APIC(encoding=3, mime="image/png", type=3, desc="Cover", data=PNG))
        file.tags.add(CHAP(element_id="chapter1", start_time=0, end_time=500, start_offset=0xFFFFFFFF, end_offset=0xFFFFFFFF, sub_frames=[TIT2(encoding=3, text=["Chapter one"])]))
        file.tags.add(CTOC(element_id="toc", flags=3, child_element_ids=["chapter1"], sub_frames=[]))
        file.save(v2_version=3)
    elif path.suffix in (".m4a", ".m4b"):
        file["©alb"] = ["Preserved album"]
        file["©lyr"] = ["Preserved lyrics"]
        file["covr"] = [MP4Cover(PNG, imageformat=MP4Cover.FORMAT_PNG)]
        file["----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN"] = [MP4FreeForm(b"-2.10 dB")]
        file["----:com.apple.iTunes:MusicBrainz Album Id"] = [MP4FreeForm(b"test-album-id")]
        file.save()
    else:
        file["album"] = ["Preserved album"]
        file["lyrics"] = ["Preserved lyrics"]
        file["replaygain_track_gain"] = ["-2.10 dB"]
        file["musicbrainz_albumid"] = ["test-album-id"]
        file["chapter001"] = ["00:00:00.000"]
        file["chapter001name"] = ["Chapter one"]
        picture = Picture(); picture.type = 3; picture.mime = "image/png"; picture.data = PNG
        if hasattr(file, "add_picture"):
            file.add_picture(picture)
        else:
            file["metadata_block_picture"] = [base64.b64encode(picture.write()).decode()]
        file.save()


def values(path):
    tags = mutagen.File(path).tags
    if path.suffix == ".mp3":
        assert tags.version == (2, 4, 0)
        return [list(tags["TCON"].text), list(tags["TMOO"].text), list(tags["TXXX:TAGS"].text), list(tags["TXXX:PODCINI_PATHS"].text)]
    if path.suffix in (".m4a", ".m4b"):
        return [tags["©gen"]] + [[bytes(value).decode() for value in tags["----:com.apple.iTunes:" + key]] for key in ("MOOD", "TAGS", "PODCINI_PATHS")]
    return [tags[key] for key in ("genre", "mood", "tags", "podcini_paths")]


with tempfile.TemporaryDirectory(prefix="podcini-tags-") as folder:
    root = pathlib.Path(folder)
    chapter_source = root / "chapters.ffmetadata"
    chapter_source.write_text(";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=500\ntitle=Opening\n"
                              "[CHAPTER]\nTIMEBASE=1/1000\nSTART=500\nEND=1000\ntitle=Second chapter\n")
    for extension, codec in (("mp3", "libmp3lame"), ("flac", "flac"), ("ogg", "vorbis"), ("opus", "libopus"), ("m4a", "aac"), ("m4b", "aac")):
        path = root / ("fixture." + extension)
        chapter_options = ["-f", "ffmetadata", "-i", str(chapter_source), "-map", "0:a:0", "-map_metadata", "1"] if extension in ("m4a", "m4b") else []
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", *chapter_options, "-ac", "2", "-strict", "-2", "-c:a", codec, str(path))
        seed(path)
        if len(sys.argv) > 2:
            fixtures = pathlib.Path(sys.argv[2]); fixtures.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, fixtures / path.name)
        audio, metadata = packets(path), unrelated(path)
        original_chapters = chapters(path)
        if extension in ("m4a", "m4b"):
            assert len(original_chapters) == 2
        run(str(CODEC), str(path), "write")
        assert values(path) == [GENRES, ["Calm"], TAGS, ["1"]], (extension, values(path))
        assert packets(path) == audio, extension + " audio changed"
        assert unrelated(path) == metadata, (extension, "unrelated metadata changed", unrelated(path), metadata)
        assert chapters(path) == original_chapters, extension + " chapters changed"
        # No extension is available when Android opens a document descriptor.
        anonymous = root / "descriptor"
        anonymous.write_bytes(path.read_bytes())
        assert run(str(CODEC), str(anonymous)) == run(str(CODEC), str(path))
        run(str(CODEC), str(path), "write")
        assert packets(path) == audio
        desktop = mutagen.File(path)
        if extension == "mp3":
            from mutagen.id3 import TCON
            desktop.tags.add(TCON(encoding=3, text=["Desktop/Édit"]))
        else:
            desktop["©gen" if extension in ("m4a", "m4b") else "genre"] = ["Desktop/Édit"]
        desktop.save()
        assert "GENRE=Desktop/Édit" in run(str(CODEC), str(path)).decode()
        run(str(CODEC), str(path), "clear")
        assert run(str(CODEC), str(path)).decode().strip() == "PODCINI_PATHS=1"
        assert packets(path) == audio
        assert chapters(path) == original_chapters
        print(extension + ": independent tags, unchanged audio, artwork, chapters/lyrics/identifiers and repeat save passed")

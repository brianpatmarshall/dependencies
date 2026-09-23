#!/usr/bin/env python3
"""
Render a Markdown document to .docx, with Mermaid diagrams as images.

Mermaid is the whole reason this is a script rather than one pandoc call. Word has no
idea what mermaid is, so every fenced ```mermaid block is rendered to a PNG first and the
fence replaced by an image reference. Left alone, the diagrams arrive as blocks of
unreadable source.

Two more things pandoc will not do on its own:

  * Its default reference document is A4 with no field-update flag. This patches a copy to
    US Letter with 1in margins and a code font small enough that an 80-column block does
    not wrap.

  * `--toc` emits a real Word TOC field, which shows nothing at all until someone updates
    it - Word only does that on open with updateFields set, and LibreOffice does not do it
    on PDF export at all. Since this document is regenerated whenever the Markdown
    changes, a field that recomputes buys nothing and costs a blank first page, so the
    contents list is generated statically instead. The trade is page numbers, which a
    generated outline cannot know.

Usage:  scripts/md2docx.py docs/ARCHITECTURE.md docs/Architecture.docx
"""
import re
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

# Word's text column on US Letter with 1in margins.
MAX_W_IN = 6.5
MAX_H_IN = 7.5

PUPPETEER = '{ "args": ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"] }'


def require(tool):
    if shutil.which(tool) is None:
        sys.exit(f"md2docx: {tool} is not on the PATH. Needs: pandoc, mmdc "
                 f"(npm i -g @mermaid-js/mermaid-cli).")


def png_size(path):
    """Width and height straight out of the IHDR chunk - no image library needed."""
    with open(path, "rb") as handle:
        return struct.unpack(">II", handle.read(24)[16:24])


def render_diagrams(markdown, work):
    """Replace every mermaid fence with an image sized to fit the page."""
    images = work / "img"
    images.mkdir(parents=True, exist_ok=True)
    config = work / "puppeteer.json"
    config.write_text(PUPPETEER, encoding="utf-8")

    blocks = list(re.finditer(r"```mermaid\n(.*?)```", markdown, re.S))
    print(f"  {len(blocks)} diagram(s)")

    replacements = []
    for i, block in enumerate(blocks, 1):
        source = work / f"diagram-{i:02d}.mmd"
        png = images / f"diagram-{i:02d}.png"
        source.write_text(block.group(1), encoding="utf-8")

        # 1600px wide at scale 2 renders crisply once Word scales it to ~6.5in.
        subprocess.run(
            ["mmdc", "-i", str(source), "-o", str(png), "-p", str(config),
             "-b", "white", "-w", "1600", "-s", "2"],
            check=True, capture_output=True)

        width, height = png_size(png)
        # Fit the column, but never let a tall diagram run past a single page.
        inches = min(MAX_W_IN, MAX_H_IN * width / height)
        if inches < 3.0:
            # Almost always a diagram mermaid has laid out as one tall column. It would
            # be unreadable at this width, so say so rather than shipping a thumbnail.
            print(f"  WARNING diagram {i} is {width}x{height}px -> only {inches:.2f}in "
                  f"wide. Consider restructuring it, or using a table.")
        replacements.append((block.span(),
                             f"![Figure {i}]({png.as_posix()}){{ width={inches:.2f}in }}"))

    # Apply back-to-front so earlier spans stay valid.
    for (start, end), text in reversed(replacements):
        markdown = markdown[:start] + text + markdown[end:]
    return markdown


def contents(markdown):
    """A static outline from the ## and ### headings, ignoring anything inside a fence."""
    lines = ["## Contents", ""]
    in_fence = False
    for line in markdown.split("\n"):
        if line.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if line.startswith("### "):
            lines.append(f"    - {line[4:].strip()}")
        elif line.startswith("## "):
            lines.append(f"- **{line[3:].strip()}**")
    return "\n".join(lines) + "\n"


def reference_doc(work):
    """Pandoc's default reference, patched to US Letter with a smaller code font."""
    default = work / "reference.docx"
    with open(default, "wb") as out:
        subprocess.run(["pandoc", "--print-default-data-file", "reference.docx"],
                       check=True, stdout=out)

    unpacked = work / "ref"
    unpacked.mkdir(exist_ok=True)
    subprocess.run(["unzip", "-qo", str(default), "-d", str(unpacked)], check=True)

    document = unpacked / "word" / "document.xml"
    xml = document.read_text(encoding="utf-8")
    xml = re.sub(r"<w:pgSz[^/]*/>", '<w:pgSz w:w="12240" w:h="15840"/>', xml)
    xml = re.sub(r"<w:pgMar[^/]*/>",
                 '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" '
                 'w:header="720" w:footer="720" w:gutter="0"/>', xml)
    document.write_text(xml, encoding="utf-8")

    styles = unpacked / "word" / "styles.xml"
    text = styles.read_text(encoding="utf-8")
    text = text.replace(
        '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>',
        '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>'
        '<w:sz w:val="16"/><w:szCs w:val="16"/>')
    styles.write_text(text, encoding="utf-8")

    patched = work / "reference-letter.docx"
    subprocess.run(["zip", "-Xqr", str(patched), "."], cwd=unpacked, check=True)
    return patched


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip().splitlines()[-1])
    require("pandoc")
    require("mmdc")

    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    markdown = source.read_text(encoding="utf-8")
    print(f"{source} -> {target}")

    with tempfile.TemporaryDirectory(prefix="md2docx-") as tmp:
        work = Path(tmp)
        markdown = render_diagrams(markdown, work)

        # The H1 and its bullets become Word's Title and subtitle instead of body text.
        title = re.match(r"# (.+)\n", markdown)
        title = title.group(1) if title else source.stem
        markdown = re.sub(r"\A# .+\n", "", markdown, count=1)

        marker = "\n---\n\n## 1. "
        if marker in markdown:
            head, rest = markdown.split(marker, 1)
            markdown = head + "\n---\n\n" + contents(markdown) + "\n---\n\n## 1. " + rest
        else:
            print("  note: no '## 1.' section, so no contents list was inserted")

        prepared = work / "pandoc-input.md"
        prepared.write_text(markdown, encoding="utf-8")

        subprocess.run([
            "pandoc", str(prepared),
            # commonmark_x, not gfm: gfm does not support link_attributes, without which
            # the image width attributes leak into the document as literal text.
            "-f", "commonmark_x",
            "-o", str(target),
            "--reference-doc", str(reference_doc(work)),
            "--metadata", f"title={title}",
            "--metadata", "subtitle=Aero estate · dependency analysis",
            "--resource-path", f"{work}:.",
        ], check=True)

    size = target.stat().st_size
    print(f"  wrote {target} ({size // 1024} KB)")


if __name__ == "__main__":
    main()

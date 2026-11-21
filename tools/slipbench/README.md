# slipbench: compare slip OCR setups on your own slips

Runs the app's own slip reading code (`SlipOcrEngine`, `SlipLayout`, `SlipParser`, compiled from
`app/src/main/java/.../slip`) on a folder of slip images, with four setups, on your PC:

| id | text finder | text reader |
|---|---|---|
| `current` | PaddleOCR v5 mobile (4.8 MB) | AksonOCR-Lite (4.5 MB): **the app now** |
| `tinydet` | iApp PP-OCRv6 tiny (1.8 MB) | AksonOCR-Lite |
| `iapp` | iApp PP-OCRv6 tiny | iApp OpenThai2.1-OCR-Text (2.4 MB) |
| `iapprec` | PaddleOCR v5 mobile | iApp OpenThai2.1-OCR-Text |

The iApp models ([OpenThai2.1-OCR-Text-20261118](https://huggingface.co/iapp/OpenThai2.1-OCR-Text-20261118),
Apache-2.0) are downloaded on the first run from Hugging Face, pinned to release `v2.1.4` and checked by SHA-256,
into `tools/slipbench/models/` (ignored by git). Everything else runs offline. Slip images never leave your PC.

## Run

Needs JDK 17, like the app build. From the repository root:

```sh
./gradlew :slipbench:run --args="/path/to/slips"
./gradlew :slipbench:run --args="/path/to/slips --truth answers.csv --out results"
```

Copying slips from the phone, keeping their file times (for `--file-dates`):

```sh
adb pull -a "/sdcard/Pictures/K PLUS" ../slips
```

Folders are searched recursively for `.jpg`, `.jpeg`, `.png`, `.bmp` and `.gif`.

| option | |
|---|---|
| `--out DIR` | where to write the results (default `slipbench-out`) |
| `--truth FILE` | right answers, see below |
| `--threads N` | CPU threads per model (default 1, closest to one phone core) |
| `--limit N` | only the first N images, e.g. for a quick try |
| `--setups LIST` | only some setups, e.g. `current,iapp` |
| `--file-dates` | use each file's modified time as the image's save time, like the app uses the photo library's date. It fills in a day that OCR lost. Copy slips with `adb pull -a` so they keep their times |

Each image is read by every setup, so expect roughly 2–4 seconds per image on a laptop. The progress line
shows the time left.

## Results

- **`summary.txt`**: counts, percentages and timings only, no text from your slips. **Safe to share.**
  - Speed per setup: median, 90th percentile and mean milliseconds, split into finding and reading text.
  - How often each setup found a slip, amount, date, payee, reference and note.
  - How often each setup agrees with `current`.
  - With `--truth`: how often each setup is right.
- **`details.txt`**: for slips where a date is missing or the setups disagree on the amount or date, only the
  recognized lines with a date, time or amount, and what the parser made of them. No file names, payees,
  notes, references or account numbers. **Meant to be shared**, to improve the parser; have a look first.
- **`results.csv`**: what every setup read from every image. **Private.**
- **`report.html`**: every slip where the setups disagree (or one is wrong against `--truth`), with the image,
  each setup's reading and its recognized text. Open it in a browser. **Private.**

## Right answers (optional)

Agreement isn't accuracy: when setups disagree, `report.html` shows which one is right, but for numbers a CSV
of right answers helps, even for a sample of slips. Columns `file` (the image file name), `amount`, and
optionally `date` (`yyyy-MM-dd HH:mm` or `yyyy-MM-dd`) and `payee`:

```csv
file,amount,date,payee
Screenshot_20261121_163912.jpg,120.00,2026-11-21 16:39,Kawa Matcha Teahouse
Screenshot_20261119_102455.jpg,55.00,2026-11-19,
```

A payee counts as right when one contains the other, ignoring case, spaces and punctuation.

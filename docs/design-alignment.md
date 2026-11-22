# Design alignment: board references, captures and comparison

Screens are aligned to the current mobile design (393x852 dp phone boards). This
page describes the measuring instrument: where the references come from, how the
app is captured, how the two are compared and how known deviations are tracked.

## References

`app/src/test/resources/shell-previews/` holds byte-for-byte copies of the design
PNG exports and `manifest.json`, the single list of board references. Each entry
names the board key, theme, locale, the Pen node id of the export, its path in the
design export folder, the reference file name (`<key>.<theme>--<locale>.png`) and
the sha256 of the reference. The manifest also records the design file hash, the
frame size, the comparison scale and the masked regions. Boards the client does not
render carry `"capture": false` and a reason.

```sh
# Import or refresh references from a design export folder (shell/ and module-B/).
python3 scripts/sync-board-references.py import app/src/test/resources/shell-previews <design-boards-dir> --design-sha <sha256>
# Check: fails on altered, missing or unlisted references; with the export folder, on stale references.
python3 scripts/sync-board-references.py check app/src/test/resources/shell-previews [<design-boards-dir>] [--design-sha <sha256>]
```

A new design revision is a new export: run `import` with the new design hash,
review the reference diff, then re-record the expected deviations.

## Comparison scale

The exports are 2 pixels per dp (786x1704 for a board). Boards are captured at
xhdpi, also 2 pixels per dp, and compared at that resolution: nothing is resampled.
One design pixel is one dp, so the 2 px deviation limit of the UI flow is 2 dp,
4 export pixels. Region positions and sizes are reported in dp.

## Captures

`ShellBoardScreenshotTest.boards` renders every captured manifest board at the
design frame with:

- the board's content as fixtures (names, rows, counts and times as on the PNG;
  module B list content lives in `test-support/fixtures/conversations` and the chat
  boards' content and clock in `test-support/fixtures/chat`, shared with the
  conversations and chat screenshot tests; the chat boards render the Kit chat
  screen inside the shell with the 24-hour clock);
- the board's system bars as window insets (59 dp status bar, 34 dp home indicator,
  from `mobile-kit-tokens.json`), so the app content sits where the board draws it;
- the real shell (`RamfluxShell` with its tab bar and panes) around tab screens;
- every locale that has a reference, in light and dark.

The status bar glyphs and the home indicator are drawn by the system, not the app;
those regions and the transparent frame corners are masked.

Board content the state model cannot produce today (rendered as far as the model
allows, and part of the expected deviations): message previews and the per-request
copy of the B01 message-requests row, the agent avatar kind, pinned and read
markers, relative day labels, the tab badge, the account subtitle suffix, the
list's more menu; the chat header's call and more actions and the
file bubble (B02, DK01); agent rows and agent search (D01, D01E); the `#channel`
suffix of group rows (C01). A07 (no client screen) and F03 (contacts) are not
captured.

## Comparison

```sh
CI=true ./gradlew :app:recordRoborazziDebug --tests org.ramflux.android.shell.ShellBoardScreenshotTest
python3 scripts/compare-board-captures.py app/src/test/resources/shell-previews \
  app/build/outputs/shell-roborazzi app/build/outputs/shell-compare \
  --expected app/src/test/resources/shell-previews/expected-deviations.json
```

Per board the script checks the reference hash and the capture size, then counts a
pixel as different when a channel differs by more than 10 and the anti-aliasing
test fails (the pixel's value does not occur within one export pixel in the other
image, in both directions). It reports the percentage of differing pixels and
groups differing pixels within 3 dp into regions with a bounding box, a kind
(missing, extra, shifted with its offset, different) and size in dp. Every region
larger than 2 dp in either direction is listed. Outputs per board: `--pair.png`
(capture | reference), `--diff.png` (reference faded, differing pixels red, masks
blue, listed regions boxed), plus `report.tsv` and `report.md`.

`scripts/test-board-compare.py` proves the behaviour: equal frames pass, a one
pixel anti-aliasing shift passes, masked regions are ignored, shifted, missing and
extra content fails with the right region, a reviewed deviation passes while a new
one fails, and altered or stale references are rejected. `compare-kit-previews.py`
remains the comparison for Kit component previews; it recognises locale variant
captures by the shipped resource locales, not by a list of languages. Kit variants
without a master (`roborazzi-variants`) and the board comparison output are CI
artefacts.

## Expected deviations

`expected-deviations.json` is the reviewed list of today's deviations per board,
with the differing percentage, the listed regions and a reason. It is the
alignment work list. CI reports a covered board as KNOWN and fails when a board
shows a region outside its reviewed regions (4 dp slack) or a differing share more
than 0.5 points above the reviewed value, or when a board has deviations and no
entry. A board that matches is reported as FIXED: delete its entry. After a
reviewed change, regenerate the file with `--write-expected <file>` (reasons are
kept) and review the diff.

## Screenshot matrix

The shared lists in `test-support/screenshot` (`ScreenshotMatrix`) are added to
every test module by the testing plugin and to the app's tests. Screens extend the
parameter lists instead of adding test classes or per-locale copies:

- sizes 360x800, 393x852 (the board frame), 700x840, 1000x760 and 393x400;
- locales en, zh-CN, en-XA (long text) and ar-XB (right to left; the layout
  direction comes from the platform's locale data);
- light and dark; font scale 1.0 and 2.0.

`review()` is every size, locale and theme at 1.0 plus 2.0 on the narrowest and the
short window; `compact()` is the phone frame only. Only 393x852 captures at 1.0
are compared with boards. `ShellBoardScreenshotTest.matrix` renders over
`review()`: the messages tab with a conversation open (the rail from 600 dp, two panes
from 700 dp), the groups and agents tabs, the settings, contacts safety number, tasks,
calls, cards and permissions screens inside the shell, the root-stack onboarding
page with the recovery phrase, and the unlock screens. It fails when security text
(unlock, safety number, recovery phrase) is ellipsized or overflows unless the line
is listed in `app/src/test/resources/shell-matrix/known-clipped-text.tsv`; the list
is empty since the wave-1 integration and an entry needs a reviewed reason.

Screenshot tests run only when `CI=true`: `AndroidTestingPlugin` and
`AndroidAppPlugin` exclude `*ScreenshotTest*` otherwise. This is deliberate, the
matrix renders hundreds of frames; run them locally with `CI=true ./gradlew
<module>:recordRoborazziDebug`.

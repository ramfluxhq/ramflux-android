# Mobile kit

Hand-written Compose components for the mobile design. Tokens live in
`src/main/kotlin/org/ramflux/designsystem/RFTokens.kt` and are checked by
`scripts/check-mobile-tokens.py`.

## Fonts

`RfFonts.ui` is the platform sans; it selects the face per script (Roboto for Latin, Noto Sans CJK,
Noto Naskh Arabic, Noto Sans Devanagari and so on) with that face's line metrics. `RfFonts.latin` is
bundled Hanken Grotesk and `RfFonts.mono` is bundled JetBrains Mono; both cover Latin only. Licenses
are in `NOTICE` and `third_party/`.

- The theme typography uses Hanken Grotesk only when the UI locale's likely script is Latin
  (`RfFonts.forLocale`); every other locale uses the platform sans. There is no per-language branch.
- Kit text goes through `KitText`. A string with any non-Latin letter drops the bundled Latin face and
  any letter spacing. Scripts with marks above or below the line (Arabic, Hebrew, Indic scripts, Thai,
  Lao, Tibetan, Myanmar, Khmer) keep the full line box and at least a 1.6 line height, so marks are not
  clipped.
- Rows and headers report the design line box when the platform font is taller (CJK), so their design
  frames hold at font scale 1.0; the glyphs are centred and not clipped.

## Right-to-left, bidirectional text and sizing

- Layout uses start and end only; custom layouts place with `placeRelative`.
- Directional icons (chevron-left, chevron-right, reply, forward, list-checks, clipboard-list) carry
  `autoMirrored` in their drawable; no other icon mirrors. `KitIconMirrorTest` pins the table. Icons are
  drawn only through the kit, so screens cannot flip them.
- Kit text takes its direction from its content (`TextDirection.Content`).
- `LtrCode` shows safety numbers, fingerprints, pairing codes, the six-digit code, device ids, recovery
  words and URLs: always left-to-right, mono, ASCII digits from the raw value, groups never reversed,
  one accessibility node per group. `KitPairingCode` and `KitMonoBlock` use it.
- `UntrustedText` shows display names, group names, file names and message excerpts as their own
  first-strong paragraph. `Bidi.sanitize` replaces embedding, override and isolate controls with U+FFFD
  in names and file names (marks are removed) and removes them from excerpts (marks are kept). A file
  name shows its real extension in a left-to-right isolate after the base name.
- To insert peer text or a code into a host sentence, pass `UiText.Untrusted` or `UiText.Code` as the
  resource argument; it is rendered inside an isolate. Never concatenate peer text with host text.
- Frames that hold text use minimum heights and grow with the font scale; widths come from the
  container. The context menu and approval card shrink below their design width in a narrow pane.
- Plurals use `UiText.Quantity`, which hands the real quantity to the platform rules, so every CLDR
  category works.

## States the design does not draw

- Pressed rows use the selected fill. Pressed filled buttons use 0.88 opacity.
- Focused controls, including text fields, draw a 2pt focus ring. A filled button whose fill matches the current rf-focus token uses the dark rf-focus token, so the ring stays visible on that dark fill.
- Loading replaces a leading icon with a spinner and keeps the control width. Buttons keep their frame and show the spinner beside the label.
- The disabled button master is a surface fill with control-colored label. Disabled text fields use 0.55 opacity. Other disabled controls use 0.45 opacity and do not hit.
- The tab bar has no selection pill. The selected tab uses the text color and a 600 label.
- Header icons, tabs, compact and text buttons, menu rows, switches, checkboxes, the composer send control, the search clear control, segmented cells, and reaction cells keep their drawn size and expose a 48dp hit frame. Icon-only controls use a Button, Switch, or Checkbox role and a content description.
- The QR tile and its glyph use the current theme, so dark mode is light ink on the dark surface.
- Radio, origin display, and charts are not in this kit.

Pushed page titles are 20/600. Large titles are 32/700. Chat header titles stay 17/600. Row chevrons are 14pt in the control color. Conversation rows are 72pt. Bubble width is at most min(300pt, 78% of the parent).

`scripts/compare-kit-previews.py` writes `{name}--pair.png`, `{name}--diff.png`, and `checklist.tsv`. The checklist measures the master frame. `kit-masters.json` supplies that frame when the export canvas is a crop or includes a shadow. A `mae` or `strong` field on one board overrides the pixel limit for that board, and the note says why. `scripts/check-mobile-tokens.py` pins the design token file at SHA-256 `5af83dc4aafa883c1bfaa4a33c222be577567eb4327953ba80a7f0d78d680632`.

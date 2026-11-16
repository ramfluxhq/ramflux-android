# Mobile kit

Hand-written Compose components for the mobile design. Tokens live in
`src/main/kotlin/org/ramflux/designsystem/RFTokens.kt` and are checked by
`scripts/check-mobile-tokens.py`.

## Fonts

`RfFonts.ui` is the platform sans (Roboto for Latin, Noto Sans CJK for Chinese).
`RfFonts.latin` is bundled Hanken Grotesk. `RfFonts.mono` is bundled JetBrains Mono.
Licenses are in `NOTICE` and `third_party/`.

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

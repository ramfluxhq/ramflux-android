# Mobile kit

Hand-written Compose components for the mobile design. Tokens live in
`src/main/kotlin/org/ramflux/generated/RFTokens.kt` and are checked by
`scripts/check-mobile-tokens.py`.

## Fonts

`RfFonts.ui` is the platform sans (Roboto for Latin, Noto Sans CJK for Chinese).
`RfFonts.latin` is bundled Hanken Grotesk. `RfFonts.mono` is bundled JetBrains Mono.
Licenses are in `NOTICE` and `third_party/`.

## States the design does not draw

- Pressed rows use the selected fill. Pressed filled buttons use 0.88 opacity.
- Focused text fields keep a 1pt stroke and switch it to the focus color. Other focused controls draw a 2pt focus ring.
- Loading replaces a leading icon with a spinner and keeps the control width. Buttons keep their frame and show the spinner beside the label.
- The disabled button master is a surface fill with control-colored label. Disabled text fields use 0.55 opacity. Other disabled controls use 0.45 opacity and do not hit.
- The tab bar has no selection pill. The selected tab uses the text color and a 600 label.
- Header icon buttons, tabs, and menu rows keep their 44pt drawn size. Controls drawn smaller than 44pt (switch, checkbox) keep that drawn size and add a 48dp hit wrapper.
- The QR tile and its glyph stay on the light-theme white and ink in dark mode.
- Radio, origin display, and charts are not in this kit.

Pushed page titles are 17/600. Large titles are 32/700. Row chevrons are 14pt in the control color. Conversation rows are 72pt. Bubble width is at most min(300pt, 78% of the parent).

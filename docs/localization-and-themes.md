# Localization and theme data

Host labels, notices and accessibility text use Android resources in each feature
implementation and the design system. Default resources are English
(`app/src/main/res/resources.properties`); every other locale is whatever
`values-*` folders exist (today Simplified Chinese, `values-zh-rCN`). No code,
script or check names the languages: the locale config is generated from the
resource folders, and adding a language is a resources-only change. `UiText.Resource` stores a resource reference and
immutable arguments in State. Rendering resolves the current configuration locale,
so a locale change does not repeat repository work. `UiText.Data` is only display
data, such as peer text; it is never interpreted as a resource key. Dates and exact
unsigned counts use locale-aware platform formatters, and quantities use plurals:
`UiText.Quantity` passes the real quantity to the platform plural rules, so every CLDR
category (zero, one, two, few, many, other) is selected per locale. Peer text and codes
inserted into a host sentence use `UiText.Untrusted` and `UiText.Code`, which render
inside bidirectional isolates; codes always use ASCII digits.

Codes users compare (safety numbers in groups of five, fingerprints, pairing codes,
URLs) render through `LtrCode`: left to right, mono, ASCII digits, groups that never
reverse. The recovery phrase never becomes a String; `LtrSecretWords` draws the
borrowed character arrays numbered and left to right at the font scale, with nothing
in the accessibility tree. The secret entry field is left to right in every locale
and asks for an ASCII keyboard for recovery words. Peer names, group names, senders
and previews keep their own content direction after bidi controls are neutralised.
Directional icons mirror through `android:autoMirrored`, written by
`scripts/sync-lucide-drawables.py` from `core/designsystem/kit-icons.json`, the table
`KitIconMirrorTest` pins.

Fonts follow the script, not the language. The bundled Hanken Grotesk and JetBrains
Mono faces cover Latin only; the theme uses Hanken Grotesk when the UI locale's likely
script is Latin and the platform sans otherwise. Kit text with non-Latin letters uses
the platform sans without letter spacing, and scripts with marks above or below the
line keep a full, taller line box. See `core/designsystem/KIT.md`.

Settings offers follow-system and every language of the app's locale config, with
an explicit Apply action. `AppLanguage` is a tag, not a list: `appLanguages` reads the
generated locale config (the same source as the platform's per-app language screen,
built from the resource folders), the data root hands it to the language
repositories, and the picker names each language by its own name. Debug builds list
the pseudo-locales too, because the build adds them to the locale config. On API 33 and later the platform per-app language is the single source of
truth: the picker writes it through `PlatformLanguageRepository`, the system's
per-app language screen offers the same locales, a change made there is read back
on the resulting configuration change, and a choice stored by earlier releases is
moved to the platform once. On API 28 to 32 the device-local language repository
confirms an IO preference commit before publishing the active selection. The
composition root observes the selection through `AppLanguageProvider`, supplying
the resource context, configuration and layout direction to the existing
navigation tree. Its
`ContextThemeWrapper` retains the Activity as its base, so Hilt and Activity
services stay reachable for system and explicit locales. Choosing a
language does not call the SDK, change peer data or modify account permissions.
Unknown stored locales fall back to the inherited configuration with a typed
notice; failed commits leave the active selection unchanged.

Debug builds enable Android pseudo-locales. The shared screenshot matrix covers
English, Simplified Chinese, expanded English (en-XA) and right-to-left (ar-XB) in
both shipped themes and at font scale 1.0 and 2.0 (see
[design alignment](design-alignment.md)). The chat and kit suites add Arabic peer
text and 360, 393 and 700 dp widths; kit variants are written to
`build/outputs/roborazzi-variants` because no design master exists for them. The
resource guard discovers the shipped locales from the resource folders and checks
matching keys/placeholders in every module for each of them, theme names in each of
them, direct host text and hard-coded colors/dimensions. Its regression check proves
that missing keys, an incomplete added locale, malformed formats and direct text are
rejected.

Theme definitions live in `core/designsystem/src/main/assets/ramflux/themes`.
Each JSON file has a stable identifier, a light/dark system-bar mode, localized
picker names, DTCG tokens and component-layout tokens. The shipped light/dark
files come from the shared token source; component geometry comes from the
reviewed generated conversation row. Their hashes identify those inputs.

The design-system convention plugin runs `scripts/build-theme-catalog.py`. It
validates bounded input, compiles all token sets into typed theme bindings and
localized resource names, and generates launcher/window colors. It does not edit
the shared generator output. Adding another valid theme file adds it to the
catalog and picker without changing application code. Every theme needs the same
complete name locales, including the default locale and every shipped locale. Any
BCP 47 tag maps to its Android resource folder.

`RamfluxTheme` maps every Material color role, typography and shape to the active
tokens. Dynamic color is disabled. Handwritten layout spacing derives from the
shared gap token; canonical row geometry is file data. The selected theme id is
stored privately per device through a confirmed IO commit. Follow-system clears
the override. Unknown stored theme ids fall back to the system palette and show
an unavailable notice. No theme setting is sent through secret account APIs.

Validation includes real preference restore and immediate recomposition without
replacing the root, equality with the canonical generated light/dark tokens,
Chinese and pseudo-locale rendering, locale-aware dates and peer-data isolation.
The token compiler test adds a third theme file and checks generated registration
and translations. Contrast checks cover shipped body, secondary text and control
pairs at a 4.5:1 minimum. Screenshot artifacts require visual acceptance in CI.

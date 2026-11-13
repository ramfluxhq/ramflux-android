# Localization and theme data

Host labels, notices and accessibility text use Android resources in each feature
implementation and the design system. Default resources are English; Simplified
Chinese uses `values-zh-rCN`. `UiText.Resource` stores a resource reference and
immutable arguments in State. Rendering resolves the current configuration locale,
so a locale change does not repeat repository work. `UiText.Data` is only display
data, such as peer text; it is never interpreted as a resource key. Dates and exact
unsigned counts use locale-aware platform formatters, and quantities use plurals.

Settings offers English, Simplified Chinese and follow-system, with an explicit
Apply action. The device-local language repository confirms an IO preference
commit before publishing the active selection. The composition root observes
that selection through `AppLanguageProvider`, supplying the resource context,
configuration and layout direction to the existing navigation tree. Choosing a
language does not call the SDK, change peer data or modify account permissions.
Unknown stored locales fall back to the inherited configuration with a typed
notice; failed commits leave the active selection unchanged.

Debug builds enable Android pseudo-locales. Screenshot cases cover English,
Simplified Chinese and expanded English in both shipped themes. The resource
guard checks matching launch-locale keys/placeholders, direct host text and
hard-coded colors/dimensions. Its regression check proves that missing keys,
malformed formats and direct text are rejected.

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
complete name locales, including English and Simplified Chinese. Additional
locale tags produce Android BCP 47 resource folders.

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

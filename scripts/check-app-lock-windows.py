#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Fail if a window can stay above the app-lock cover.

A dialog, popup, menu, sheet or toast is its own window, drawn above the activity window and so above the app-lock
cover, out of reach of the cover's focus, pointer and accessibility guards. The rule (core/designsystem AppLockWindows.kt):

- Production code composes windows only through the gated wrappers (CoverAwareDialog, CoverAwarePopup,
  CoverAwareDropdownMenu, CoverAwareModalBottomSheet) or inside the lambda of AppLockGatedWindow, which also passes
  the capture policy (securePolicy) on to the window. A raw window call anywhere else is refused, as is an import alias
  that would hide one.
- Only the cover provides LocalInAppLockCover (windows composed under it stay shown while covered).
- SecureFlagPolicy.SecureOff appears only in the realrun-only switches of the two account-code windows.

The scanner checks itself against inline samples first, so a regex change cannot silently pass everything.
"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
WRAPPERS = 'core/designsystem/src/main/kotlin/org/ramflux/designsystem/AppLockWindows.kt'
COVER_PROVIDER = 'feature/onboarding/impl/src/main/kotlin/org/ramflux/feature/onboarding/AppLockCover.kt'
SECURE_OFF_ALLOWED = {
    'feature/settings/impl/src/main/kotlin/org/ramflux/feature/settings/SettingsPages.kt',
    'feature/onboarding/impl/src/main/kotlin/org/ramflux/feature/onboarding/RemoveAccountDialog.kt',
}
WINDOWS = (
    'Dialog', 'Popup', 'ModalBottomSheet', 'DropdownMenu', 'ExposedDropdownMenu', 'AlertDialog', 'BasicAlertDialog',
    'DatePickerDialog', 'DateRangePickerDialog', 'TimePickerDialog', 'ModalBottomSheetLayout', 'PopupWindow',
    'ListPopupWindow', 'PopupMenu', 'BottomSheetDialog', 'BottomSheetDialogFragment', 'DialogFragment', 'Toast',
)
NAMES = '|'.join(WINDOWS)
RAW_CALL = re.compile(r'\b(' + NAMES + r')\s*(?:<[^>]*>)?\s*[({]')
VIEW_CALL = re.compile(r'\b(AlertDialog\s*\.\s*Builder|Toast\s*\.\s*makeText)\s*\(')
ALIAS = re.compile(r'^\s*import\s+[\w.]*\b(' + NAMES + r')\s+as\s+\w+', re.M)
DECLARATION = re.compile(r'\b(?:fun|class|interface|object)\s+(?:[\w.<>]+\.)?$')
GATE = re.compile(r'\bAppLockGatedWindow\s*\(')


def blank(text: str) -> str:
    """Comments and string literals (templates included) replaced by spaces; offsets and newlines are kept."""
    out = list(text)
    i, n = 0, len(text)

    def wipe(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != '\n':
                out[k] = ' '

    def string_end(j: int, raw: bool) -> int:
        """Index just past the string that starts at j (after its opening quote)."""
        while j < n:
            if not raw and text[j] == '\\':
                j += 2
                continue
            if raw and text.startswith('"""', j):
                return j + 3
            if not raw and text[j] == '"':
                return j + 1
            if text.startswith('${', j):
                j = code_end(j + 2)
                continue
            j += 1
        return n

    def code_end(j: int) -> int:
        """Index just past the '}' that closes a template opened before j."""
        depth = 1
        while j < n:
            if text.startswith('"""', j):
                j = string_end(j + 3, True)
                continue
            c = text[j]
            if c == '"':
                j = string_end(j + 1, False)
                continue
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    return j + 1
            j += 1
        return n

    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            j = n if j < 0 else j
            wipe(i, j)
            i = j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            j = n if j < 0 else j + 2
            wipe(i, j)
            i = j
        elif text.startswith('"""', i):
            j = string_end(i + 3, True)
            wipe(i, j)
            i = j
        elif text[i] == '"':
            j = string_end(i + 1, False)
            wipe(i, j)
            i = j
        elif text[i] == "'":
            m = re.match(r"'(?:\\.|[^\\'])*'", text[i:])
            j = i + (m.end() if m else 1)
            wipe(i, j)
            i = j
        else:
            i += 1
    return ''.join(out)


def matching(code: str, start: int, open_c: str, close_c: str) -> int:
    """Index of the bracket that closes the one at start, or len(code)."""
    depth = 0
    for k in range(start, len(code)):
        if code[k] == open_c:
            depth += 1
        elif code[k] == close_c:
            depth -= 1
            if depth == 0:
                return k
    return len(code)


def gate_spans(code: str) -> list[tuple[int, int]]:
    """Spans of AppLockGatedWindow calls: the argument list and the trailing lambda."""
    spans = []
    for m in GATE.finditer(code):
        close = matching(code, m.end() - 1, '(', ')')
        end = close
        k = close + 1
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1
        if k < len(code) and code[k] == '{':
            end = matching(code, k, '{', '}')
        spans.append((m.start(), end))
    return spans


def line_of(text: str, offset: int) -> int:
    return text.count('\n', 0, offset) + 1


def problems(rel: str, text: str) -> list[str]:
    found = []
    code = blank(text)
    for m in ALIAS.finditer(code):
        found.append(f'{rel}:{line_of(code, m.start())}: import alias of window API {m.group(1)}')
    if rel == WRAPPERS:
        return found
    spans = gate_spans(code)
    for start, end in spans:
        body = code[start:end]
        if RAW_CALL.search(body) and 'securePolicy' not in body:
            found.append(f'{rel}:{line_of(code, start)}: AppLockGatedWindow window does not pass securePolicy on')
    for m in list(RAW_CALL.finditer(code)) + list(VIEW_CALL.finditer(code)):
        if DECLARATION.search(code[max(0, m.start() - 80):m.start()]):
            continue
        if any(start <= m.start() <= end for start, end in spans):
            continue
        name = re.sub(r'\s+', '', m.group(1))
        found.append(f'{rel}:{line_of(code, m.start())}: raw window {name}( outside the app-lock gate; use a CoverAware wrapper or AppLockGatedWindow')
    if rel != COVER_PROVIDER and re.search(r'\bLocalInAppLockCover\s+provides\b', code):
        found.append(f'{rel}: only the app-lock cover provides LocalInAppLockCover')
    if rel not in SECURE_OFF_ALLOWED and re.search(r'\bSecureFlagPolicy\s*\.\s*SecureOff\b', code):
        found.append(f'{rel}: SecureFlagPolicy.SecureOff outside the realrun-only account-code windows')
    return found


SAMPLES_REFUSED = {
    'raw dialog': 'fun A() { if (open) Dialog(onDismissRequest = {}) { Text("x") } }',
    'qualified dialog': 'fun A() { androidx.compose.ui.window.Dialog(onDismissRequest = {}) {} }',
    'raw popup lambda': 'fun A() { Popup { Box() } }',
    'raw sheet': 'fun A() { ModalBottomSheet(onDismissRequest = {}) {} }',
    'raw menu': 'fun A() { DropdownMenu(expanded = true, onDismissRequest = {}) {} }',
    'material picker': 'fun A() { DatePickerDialog(onDismissRequest = {}, confirmButton = {}) {} }',
    'view dialog': 'fun A() { AlertDialog.Builder(context).show() }',
    'toast': 'fun A() { Toast.makeText(context, "x", 0).show() }',
    'view subclass': 'class D(c: Context) : Dialog(c)',
    'alias': 'import androidx.compose.ui.window.Dialog as Window\n',
    'gate without policy': 'fun A() { AppLockGatedWindow(onCovered = {}) { _ -> AlertDialog(onDismissRequest = {}) } }',
    'after a gate': 'fun A() { AppLockGatedWindow(onCovered = {}) { p -> Box() }\n Dialog(onDismissRequest = {}) {} }',
    'cover local': 'fun A() { CompositionLocalProvider(LocalInAppLockCover provides true) {} }',
    'secure off': 'val p = SecureFlagPolicy.SecureOff',
}
SAMPLES_ACCEPTED = {
    'wrapper': 'fun A() { CoverAwareDialog(onDismissRequest = {}) { Text("Dialog(") } }',
    'named composables': 'fun A() { HostConfirmationDialog({}) {}; KitConfirmDialog(); RemoveAccountDialog() }',
    'gated picker': (
        'fun A() { AppLockGatedWindow(onCovered = {}) { policy ->\n'
        '  DatePickerDialog(onDismissRequest = { x = "${f("}")}" }, properties = DialogProperties(securePolicy = policy)) {}\n'
        '} }'
    ),
    'comment': '// Dialog(onDismissRequest = {})\n/* Popup { } */ fun A() {}',
    'declaration': 'fun Dialog(x: Int) {}',
    'import': 'import androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n',
}


def self_test() -> None:
    for name, sample in SAMPLES_REFUSED.items():
        assert problems('feature/x/src/main/kotlin/X.kt', sample), f'self-test: sample "{name}" was not refused'
    for name, sample in SAMPLES_ACCEPTED.items():
        got = problems('feature/x/src/main/kotlin/X.kt', sample)
        assert not got, f'self-test: sample "{name}" was refused: {got}'


def main() -> int:
    self_test()
    failures = []
    files = 0
    for base in ('app', 'core', 'feature', 'push'):
        for path in sorted((root / base).glob('**/src/main/**/*.kt')):
            rel = path.relative_to(root).as_posix()
            if '/build/' in rel:
                continue
            files += 1
            failures += problems(rel, path.read_text())
    assert (root / WRAPPERS).is_file(), f'missing {WRAPPERS}'
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        print(f'APP_LOCK_WINDOWS_FAILED: {len(failures)} problem(s)', file=sys.stderr)
        return 1
    print(f'APP_LOCK_WINDOWS_OK: {files} production Kotlin files; every window is gated by the app-lock cover')
    return 0


if __name__ == '__main__':
    sys.exit(main())

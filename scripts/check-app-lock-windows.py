#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Span Brain
"""Fail if a window can stay above the app-lock cover.

A dialog, popup, menu, sheet, tooltip or toast is its own window, drawn above the activity window and so above the
app-lock cover, out of reach of the cover's focus, pointer and accessibility guards. The rule (core/designsystem
AppLockWindows.kt, AppLockLibraryWindows.kt):

- Production code composes windows only through the gated wrappers (CoverAwareDialog, CoverAwarePopup,
  CoverAwareDropdownMenu, CoverAwareModalBottomSheet) or directly in the window lambda of AppLockGatedWindow, which
  also passes the capture policy (securePolicy) on to the window.
- The rule is by name, not by call shape: outside the two wrapper files, every mention of a window API counts
  (a call, a constructor reference, a type, a typealias, a supertype, a backticked name). An import of one is allowed
  only as a plain import of a Compose window that the file then uses inside a gate. An import alias, a member import
  (a static import such as Toast.makeText) and a wildcard import of a package that holds window classes are refused.
- View-system windows (PopupWindow, Toast, platform and AndroidX dialogs, ...) and every way to reach a WindowManager
  are refused everywhere outside the wrapper files: they cannot be gated by composition.
- Library views that open windows by themselves are created with appLockWindowContext(...) (the runtime backstop then
  closes their windows while the cover is up). Named here: androidx.emoji2 EmojiPickerView (its skin-tone PopupWindow).
  Because a library view's windows are invisible to a source scan, the rule is fail-closed for views hosted in
  Compose: the view an AndroidView factory creates is either a first-party view class (declared in this repository on
  a windowless platform base, so its own window calls are scanned here) or is created with appLockWindowContext(...).
  Hosts that hide which view they create (AndroidViewBinding, AndroidFragment, LayoutInflater) are refused.
- AppLockGatedWindow and the CoverAware wrappers are declared only in AppLockWindows.kt; a declaration of the same name
  elsewhere, an import of it from another package, or an alias of it is refused, so a look-alike cannot stand in.
- Only the cover provides LocalInAppLockCover, and only the activity provides LocalAppLockCovered, LocalSecureWindows,
  LocalAppLockCoverFocus and LocalAppLockCoverReader: outside those files they may only be read (`.current`).
- SecureFlagPolicy.SecureOff appears only in the realrun-only switches of the two account-code windows.

Scanned: every Kotlin and Java file in every source set except test and androidTest, in every module.
The scanner checks itself against inline samples first, so a rule change cannot silently pass everything.
"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
DESIGNSYSTEM = 'core/designsystem/src/main/kotlin/org/ramflux/designsystem/'
WRAPPERS = DESIGNSYSTEM + 'AppLockWindows.kt'
EXEMPT = {WRAPPERS, DESIGNSYSTEM + 'AppLockLibraryWindows.kt'}
COVER_PROVIDER = 'feature/onboarding/impl/src/main/kotlin/org/ramflux/feature/onboarding/AppLockCover.kt'
HOST = 'app/src/main/kotlin/org/ramflux/android/MainActivity.kt'
DECLARES_LOCALS = {DESIGNSYSTEM + 'AppLockCovered.kt', WRAPPERS, DESIGNSYSTEM + 'AppLockResults.kt'}
# The in-app banner is a view of the activity window added above the content; it shows nothing while the lock is on or
# unread (InAppBanners.current).
ADD_CONTENT_VIEW_ALLOWED = {'app/src/main/kotlin/org/ramflux/android/notify/InAppBannerHost.kt'}
SECURE_OFF_ALLOWED = {
    'feature/settings/impl/src/main/kotlin/org/ramflux/feature/settings/SettingsPages.kt',
    'feature/onboarding/impl/src/main/kotlin/org/ramflux/feature/onboarding/RemoveAccountDialog.kt',
}

# Compose windows: allowed only as a direct call in an AppLockGatedWindow window lambda. Some names are also platform
# classes (android.app.AlertDialog, DatePickerDialog, TimePickerDialog, Dialog); those are refused by their package.
COMPOSE_WINDOWS = {
    'Dialog', 'Popup', 'ModalBottomSheet', 'ModalBottomSheetLayout', 'DropdownMenu', 'DropdownMenuPopup',
    'ExposedDropdownMenu', 'ExposedDropdownMenuBox', 'AlertDialog', 'BasicAlertDialog', 'DatePickerDialog',
    'DateRangePickerDialog', 'TimePickerDialog', 'TooltipBox', 'PlainTooltip', 'RichTooltip', 'BasicTooltipBox',
    'SearchBar', 'DockedSearchBar', 'ExpandedFullScreenSearchBar', 'ExpandedDockedSearchBar', 'TopSearchBar',
    'ModalWideNavigationRail', 'WideNavigationRail',
}
COMPOSE_PACKAGES = ('androidx.compose.',)
# View-system windows and window-manager access: refused everywhere outside the wrapper files.
VIEW_WINDOWS = {
    'PopupWindow', 'ListPopupWindow', 'PopupMenu', 'Toast', 'Snackbar', 'ProgressDialog', 'CharacterPickerDialog',
    'ComponentDialog', 'AppCompatDialog', 'AppCompatDialogFragment', 'BottomSheetDialog', 'BottomSheetDialogFragment',
    'DialogFragment', 'MaterialAlertDialogBuilder', 'MaterialDatePicker', 'MaterialTimePicker', 'Presentation',
    'AutoCompleteTextView', 'MultiAutoCompleteTextView', 'MaterialAutoCompleteTextView', 'Spinner', 'AppCompatSpinner',
    'MediaController', 'ZoomButtonsController', 'ViewManager', 'WINDOW_SERVICE', 'windowManager', 'getWindowManager',
    'WindowManagerImpl', 'WindowManagerGlobal', 'TYPE_APPLICATION_OVERLAY', 'TYPE_APPLICATION_PANEL',
}
# Ambiguous simple names whose non-window meaning is in use; they are refused only from these packages.
QUALIFIED_ONLY = {'Presentation': ('android.app.',)}
# Library views that open windows by themselves: created only with appLockWindowContext(...).
LIBRARY_WINDOW_VIEWS = {'EmojiPickerView'}
# View hosts that hide which view class they create: refused outright.
OPAQUE_VIEW_HOSTS = {'AndroidViewBinding', 'AndroidFragment', 'LayoutInflater'}
# Platform view bases that open no window of their own as first-party views use them (no selectable or editable text).
WINDOWLESS_VIEW_BASES = {'View', 'ViewGroup', 'FrameLayout', 'LinearLayout', 'Button', 'ImageView', 'SurfaceView', 'TextureView'}
APP_LOCK_CONTEXT_ARG = re.compile(r'\s*\(\s*[\w.]*?\bappLockWindowContext\s*\(\s*\)\s*[,)]')
CLASS_DECL = re.compile(r'\bclass\s+(\w+)\s*(?:<[^>{]*>)?\s*(?:(?:private|internal|public|protected)?\s*constructor\s*)?(\([^)]*\))?\s*:\s*([\w.]+)\s*\(')
# Packages that hold window classes: a wildcard import of one would hide which name is used.
WINDOW_PACKAGES = (
    'android.app', 'android.widget', 'android.view', 'android.text.method', 'androidx.activity', 'androidx.fragment.app',
    'androidx.appcompat.app', 'androidx.appcompat.widget', 'androidx.emoji2.emojipicker', 'androidx.compose.ui.window',
    'com.google.android.material.dialog', 'com.google.android.material.bottomsheet',
    'com.google.android.material.snackbar', 'com.google.android.material.datepicker',
    'com.google.android.material.timepicker',
)
GATES = {'AppLockGatedWindow', 'CoverAwareDialog', 'CoverAwarePopup', 'CoverAwareDropdownMenu', 'CoverAwareModalBottomSheet'}
HOST_LOCALS = {'LocalAppLockCovered', 'LocalSecureWindows', 'LocalAppLockCoverFocus', 'LocalAppLockCoverReader'}
COVER_LOCAL = 'LocalInAppLockCover'

IDENT = re.compile(r'`([^`\n]+)`|[A-Za-z_][A-Za-z0-9_]*')
IMPORT = re.compile(r'^[ \t]*import\s+(static\s+)?([\w.`]+(?:\s*\.\s*\*)?)\s*(?:as\s+(`[^`]+`|\w+))?\s*;?[ \t]*$', re.M)


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


def line_of(text: str, offset: int) -> int:
    return text.count('\n', 0, offset) + 1


def identifiers(code: str):
    """(name, start, end) of every identifier, backticked ones unquoted."""
    for m in IDENT.finditer(code):
        yield (m.group(1) if m.group(1) is not None else m.group(0)), m.start(), m.end()


def qualifier(code: str, start: int) -> str:
    """The dotted path written before the identifier at start ('android.app.' for android.app.Dialog), or ''."""
    m = re.search(r'((?:(?:`[^`\n]+`|[A-Za-z_]\w*)\s*\.\s*)+)$', code[max(0, start - 200):start])
    return re.sub(r'[\s`]', '', m.group(1)) if m else ''


def next_char(code: str, end: int) -> str:
    k = end
    while k < len(code) and code[k] in ' \t\r\n':
        k += 1
    return code[k] if k < len(code) else ''


def rest(code: str, end: int) -> str:
    return code[end:end + 200]


def gate_lambdas(code: str) -> list[tuple[int, int, str]]:
    """(body start, body end, policy parameter) of every AppLockGatedWindow window lambda (the trailing lambda only)."""
    spans = []
    for name, start, end in identifiers(code):
        if name != 'AppLockGatedWindow' or next_char(code, end) != '(':
            continue
        if qualifier(code, start) not in ('', 'org.ramflux.designsystem.'):
            continue
        open_paren = code.index('(', end)
        close = matching(code, open_paren, '(', ')')
        k = close + 1
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        body_end = matching(code, k, '{', '}')
        m = re.match(r'\{\s*(\w+)\s*->', code[k:body_end])
        spans.append((k + 1, body_end, m.group(1) if m else ''))
    return spans


def depth_in(code: str, start: int, pos: int) -> int:
    """Brace depth of pos inside a lambda body that starts at start (0: directly in the body)."""
    depth = 0
    for k in range(start, pos):
        if code[k] == '{':
            depth += 1
        elif code[k] == '}':
            depth -= 1
    return depth


def first_party_views(texts: list[str]) -> frozenset[str]:
    """Classes declared in production code, except a class built on a Context whose base is a library or non-windowless
    platform view (it may open windows the scan cannot see; an AndroidView must then wrap its context)."""
    codes = [blank(t) for t in texts]
    declared = {m.group(1) for code in codes for m in re.finditer(r'\bclass\s+(\w+)', code)}
    excluded = set()
    for code in codes:
        for m in CLASS_DECL.finditer(code):
            name, params, base = m.group(1), m.group(2) or '', m.group(3).split('.')[-1]
            if 'Context' in params and base not in declared and base not in WINDOWLESS_VIEW_BASES:
                excluded.add(name)
    return frozenset(declared - excluded)


def interop_problems(code: str, first_party: frozenset[str]) -> list[tuple[int, str]]:
    """The view every AndroidView factory creates is first-party or is created with appLockWindowContext(...)."""
    found = []
    for name, start, end in identifiers(code):
        if name != 'AndroidView' or next_char(code, end) != '(':
            continue
        if qualifier(code, start) not in ('', 'androidx.compose.ui.viewinterop.'):
            continue
        open_paren = code.index('(', end)
        close = matching(code, open_paren, '(', ')')
        args = code[open_paren + 1:close]
        named = re.search(r'\bfactory\s*=\s*', args)
        k = open_paren + 1 + (named.end() if named else 0)
        while k < close and code[k] in ' \t\r\n':
            k += 1
        if code.startswith('::', k):
            ref = re.match(r'::\s*([\w.]+)', code[k:])
            view = ref.group(1).split('.')[-1] if ref else ''
            if view not in first_party:
                found.append((start, f'AndroidView factory ::{view} is not a first-party view; construct it with appLockWindowContext(context)'))
            continue
        if k >= close or code[k] != '{':
            found.append((start, 'AndroidView factory must be a lambda that constructs its view, so the view class can be checked'))
            continue
        body_start = k + 1
        body_end = matching(code, k, '{', '}')
        param = re.match(r'\s*(?:\w+|_)\s*->', code[body_start:body_end])
        body_start += param.end() if param else 0
        view = None
        for ident, s, e in identifiers(code[body_start:body_end]):
            pos = body_start + s
            if ident[:1].isupper() and next_char(code, body_start + e) == '(' and depth_in(code, body_start, pos) == 0:
                view = (ident, body_start + e)
                break
        if view is None:
            found.append((start, 'AndroidView factory does not construct its view directly, so its class cannot be checked'))
            continue
        ident, after = view
        qual = qualifier(code, after - len(ident))
        if ident in first_party and (not qual or qual.startswith('org.ramflux.')):
            continue
        if APP_LOCK_CONTEXT_ARG.match(rest(code, after)):
            continue
        found.append((start, f'{ident} in an AndroidView is a library view that may open its own windows; create it with appLockWindowContext(context)'))
    return found


def problems(rel: str, text: str, first_party: frozenset[str] = frozenset()) -> list[str]:
    found = []
    code = blank(text)

    def report(offset: int, message: str) -> None:
        found.append(f'{rel}:{line_of(code, offset)}: {message}')

    windows = COMPOSE_WINDOWS | VIEW_WINDOWS | LIBRARY_WINDOW_VIEWS | OPAQUE_VIEW_HOSTS | {'AndroidView'}
    import_spans = []
    for m in IMPORT.finditer(code):
        import_spans.append((m.start(), m.end()))
        path = re.sub(r'[\s`]', '', m.group(2))
        alias = (m.group(3) or '').strip('`')
        parts = path.split('.')
        if rel in EXEMPT:
            continue
        if parts[-1] == '*':
            package = '.'.join(parts[:-1])
            if package in WINDOW_PACKAGES or any(package.endswith('.' + w) or package.split('.')[-1] == w for w in windows):
                report(m.start(), f'wildcard import of {package}, which holds window APIs; import the names used')
            continue
        hits = [p for p in parts if p in windows or p in GATES or p in HOST_LOCALS or p == COVER_LOCAL]
        if alias and (hits or alias in windows or alias in GATES):
            report(m.start(), f'import alias of {path} as {alias}')
            continue
        for k, part in enumerate(parts):
            if part in QUALIFIED_ONLY and not '.'.join(parts[:k + 1]).startswith(QUALIFIED_ONLY[part]):
                continue
            if part in windows and k < len(parts) - 1:
                report(m.start(), f'member import from window API {part} ({path})')
            elif part in COMPOSE_WINDOWS and k == len(parts) - 1 and not path.startswith(COMPOSE_PACKAGES):
                report(m.start(), f'import of platform window {path}')
            elif part in VIEW_WINDOWS and k == len(parts) - 1 and rel not in EXEMPT:
                report(m.start(), f'import of window API {path}')
        if parts[-1] in GATES and path != 'org.ramflux.designsystem.' + parts[-1]:
            report(m.start(), f'import of {path}: the app-lock gate is org.ramflux.designsystem.{parts[-1]}')

    if rel in EXEMPT:
        return found

    def in_import(offset: int) -> bool:
        return any(a <= offset < b for a, b in import_spans)

    gates = gate_lambdas(code)
    for body_start, body_end, policy in gates:
        body = code[body_start:body_end]
        uses_window = any(name in COMPOSE_WINDOWS for name, _, _ in identifiers(body))
        if uses_window and not (policy and re.search(r'\bsecurePolicy\s*=\s*' + re.escape(policy) + r'\b', body)):
            report(body_start, 'AppLockGatedWindow window does not pass its securePolicy parameter on')

    for offset, message in interop_problems(code, first_party):
        report(offset, message)

    for name, start, end in identifiers(code):
        if in_import(start):
            continue
        qual = qualifier(code, start)
        after = next_char(code, end)
        if name in GATES and re.search(r'\b(fun|class|object|interface|typealias|val|var)\s+(?:[\w.<>]+\.)?$', code[max(0, start - 60):start]):
            report(start, f'declaration of {name} outside {WRAPPERS}')
            continue
        if name in GATES and qual not in ('', 'org.ramflux.designsystem.'):
            report(start, f'{qual}{name} is not the app-lock gate')
            continue
        if name == COVER_LOCAL or name in HOST_LOCALS:
            owner = COVER_PROVIDER if name == COVER_LOCAL else HOST
            if rel in DECLARES_LOCALS or rel == owner:
                continue
            if not re.match(r'\s*\.\s*current\b', rest(code, end)):
                report(start, f'{name} may only be read (.current) here; only {owner} provides it')
            continue
        if name == 'SecureOff' and rel not in SECURE_OFF_ALLOWED:
            report(start, 'SecureFlagPolicy.SecureOff outside the realrun-only account-code windows')
            continue
        if name == 'addContentView' and rel not in ADD_CONTENT_VIEW_ALLOWED:
            report(start, 'addContentView draws above the app-lock cover; only the in-app banner host may')
            continue
        if name == 'WindowManager' and not re.match(r'\s*\.\s*LayoutParams\b', rest(code, end)):
            report(start, 'WindowManager outside the app-lock window files (only WindowManager.LayoutParams is allowed)')
            continue
        if name in QUALIFIED_ONLY:
            if qual.startswith(QUALIFIED_ONLY[name]):
                report(start, f'window API {qual}{name}')
            continue
        if name in LIBRARY_WINDOW_VIEWS:
            if re.match(r'\s*\(\s*[\w.]*?\bappLockWindowContext\s*\(\s*\)\s*[,)]', rest(code, end)):
                continue
            report(start, f'{name} opens its own windows; create it with appLockWindowContext(context)')
            continue
        if name in OPAQUE_VIEW_HOSTS:
            report(start, f'{name} hides which view it creates, so the views it hosts cannot be checked for windows')
            continue
        if name in VIEW_WINDOWS:
            report(start, f'window API {name} outside the app-lock gate; it cannot be gated by composition')
            continue
        if name in COMPOSE_WINDOWS:
            if qual and not qual.startswith(COMPOSE_PACKAGES):
                report(start, f'platform window {qual}{name}')
                continue
            gate = next(((s, e) for s, e, _ in gates if s <= start < e), None)
            direct = gate is not None and depth_in(code, gate[0], start) == 0 and after in ('(', '{', '<')
            if not direct:
                report(start, f'window {name} outside the app-lock gate; use a CoverAware wrapper or call it directly in an AppLockGatedWindow lambda')
    return found


SAMPLES_REFUSED = {
    'raw dialog': 'fun A() { if (open) Dialog(onDismissRequest = {}) { Text("x") } }',
    'qualified dialog': 'fun A() { androidx.compose.ui.window.Dialog(onDismissRequest = {}) {} }',
    'raw popup lambda': 'fun A() { Popup { Box() } }',
    'raw sheet': 'fun A() { ModalBottomSheet(onDismissRequest = {}) {} }',
    'raw menu': 'fun A() { DropdownMenu(expanded = true, onDismissRequest = {}) {} }',
    'material picker': 'fun A() { DatePickerDialog(onDismissRequest = {}, confirmButton = {}) {} }',
    'view dialog': 'fun A() { AlertDialog.Builder(context).show() }',
    'view dialog split': 'fun A(c: Context) { AlertDialog\n.Builder(c).show() }',
    'toast': 'fun A() { Toast.makeText(context, "x", 0).show() }',
    'toast ctor': 'fun A(c: Context) { Toast(c).show() }',
    'view subclass': 'class D(c: Context) : Dialog(c)',
    'object dialog': 'fun A(c: Context) { object : Dialog(c) {}.show() }',
    'fq platform dialog': 'fun A(c: Context) { android.app.Dialog(c).show() }',
    'fq platform alert builder': 'fun A(c: Context) { android.app.AlertDialog.Builder(c).show() }',
    'platform date picker': 'fun A(c: Context) { android.app.DatePickerDialog(c).show() }',
    'time picker view': 'fun A(c: Context) { TimePickerDialog(c, l, 1, 2, true).show() }',
    'alias': 'import androidx.compose.ui.window.Dialog as Window\n',
    'static import makeText': 'import android.widget.Toast.makeText\nfun A(c: Context) { makeText(c, "x", 0).show() }',
    'import platform dialog': 'import android.app.AlertDialog\n',
    'wildcard window package': 'import android.widget.*\nfun A(v: View) { }',
    'typealias platform dialog': 'typealias D = android.app.Dialog\nfun A(c: Context) { D(c).show() }',
    'typealias toast': 'typealias T = android.widget.Toast\nfun A(c: Context) { T.makeText(c, "x", 0).show() }',
    'typealias compose dialog': 'typealias D = androidx.compose.ui.window.Dialog\n',
    'constructor reference': 'fun A(c: Context) { val f: (Context) -> android.app.Dialog = ::Dialog; f(c).show() }',
    'compose function reference': 'fun A() { val f = ::Popup }',
    'backticked call': 'fun A() { `Dialog`(onDismissRequest = {}) {} }',
    'named-arg generic': 'fun A() { Dialog<Unit>(onDismissRequest = {}) {} }',
    'apply dialog': 'fun A(c: Context) { Dialog(c).apply { show() } }',
    'popup window': 'fun A(v: View) { PopupWindow(v, 1, 1).showAsDropDown(v) }',
    'list popup': 'fun A(c: Context) { ListPopupWindow(c).show() }',
    'popup menu': 'fun A(c: Context, v: View) { PopupMenu(c, v).show() }',
    'bottom sheet dialog': 'fun A(c: Context) { BottomSheetDialog(c).show() }',
    'bottom sheet fragment': 'class F : BottomSheetDialogFragment()',
    'dialog fragment': 'class F : DialogFragment()',
    'material builder': 'fun A(c: Context) { MaterialAlertDialogBuilder(c).show() }',
    'progress dialog': 'fun A(c: Context) { ProgressDialog(c).show() }',
    'appcompat dialog': 'fun A(c: Context) { AppCompatDialog(c).show() }',
    'component dialog': 'fun A(c: Context) { ComponentDialog(c).show() }',
    'character picker': 'fun A(c: Context) { CharacterPickerDialog(c, v, e, "ab", true).show() }',
    'presentation': 'fun A(c: Context, d: Display) { android.app.Presentation(c, d).show() }',
    'snackbar': 'fun A(v: View) { Snackbar.make(v, "x", 0).show() }',
    'tooltip': 'fun A() { TooltipBox(positionProvider = p, tooltip = { PlainTooltip { Text("x") } }, state = s) {} }',
    'basic tooltip': 'fun A() { BasicTooltipBox(positionProvider = p, tooltip = {}, state = s) {} }',
    'search bar': 'fun A() { SearchBar(inputField = {}, expanded = true, onExpandedChange = {}) {} }',
    'expanded search bar': 'fun A() { ExpandedFullScreenSearchBar(state = s, inputField = {}) {} }',
    'exposed menu': 'fun A() { ExposedDropdownMenu(expanded = true, onDismissRequest = {}) {} }',
    'm2 sheet layout': 'fun A() { ModalBottomSheetLayout(sheetContent = {}) {} }',
    'window manager add': 'fun A(wm: WindowManager, v: View) { wm.addView(v, WindowManager.LayoutParams()) }',
    'window service': 'fun A(c: Context) { (c.getSystemService(Context.WINDOW_SERVICE) as ViewManager).addView(v, p) }',
    'activity window manager': 'fun A(a: Activity) { a.windowManager.addView(v, p) }',
    'window manager class': 'fun A(c: Context) { c.getSystemService(WindowManager::class.java) }',
    'add content view': 'fun A(a: Activity, v: View) { a.addContentView(v, p) }',
    'emoji picker plain context': 'fun A(c: Context) { EmojiPickerView(c) }',
    'emoji picker fq plain context': 'fun A(c: Context) { androidx.emoji2.emojipicker.EmojiPickerView(c) }',
    'emoji picker unwrapped again': 'fun A(c: Context) { EmojiPickerView(c.appLockWindowContext().applicationContext) }',
    'emoji picker reference': 'fun A() { val f = ::EmojiPickerView }',
    'gate without policy': 'fun A() { AppLockGatedWindow(onCovered = {}) { _ -> AlertDialog(onDismissRequest = {}) } }',
    'gate ignores its policy': 'fun A() { AppLockGatedWindow(onCovered = {}) { p -> Dialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {} } }',
    'after a gate': 'fun A() { AppLockGatedWindow(onCovered = {}) { p -> Box() }\n Dialog(onDismissRequest = {}) {} }',
    'toast in gate args': 'fun A(c: Context) { AppLockGatedWindow(onCovered = { Toast.makeText(c, "x", 0).show() }) { p -> } }',
    'window in gate args': 'fun A() { AppLockGatedWindow(onCovered = {}, window = { p -> Dialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = p)) {} }) }',
    'toast in gate body': 'fun A(c: Context) { AppLockGatedWindow(onCovered = {}) { p -> Toast.makeText(c, "x", 0).show() } }',
    'platform dialog in gate': 'fun A(c: Context) { AppLockGatedWindow(onCovered = {}) { securePolicy -> android.app.AlertDialog.Builder(c).show() } }',
    'gate lambda stores window': 'fun A() { AppLockGatedWindow(onCovered = {}) { securePolicy -> holder = @Composable { Dialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = securePolicy)) {} } } }',
    'shadow gate declaration': (
        'fun AppLockGatedWindow(onCovered: () -> Unit, w: @Composable (SecureFlagPolicy) -> Unit) { w(SecureFlagPolicy.SecureOn) }\n'
        'fun B() { AppLockGatedWindow({}) { securePolicy -> Dialog(onDismissRequest = {}) {} } }'
    ),
    'shadow wrapper declaration': 'fun CoverAwareDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {}',
    'gate from another package': 'import org.evil.AppLockGatedWindow\n',
    'qualified look-alike gate': 'fun A() { org.evil.AppLockGatedWindow({}) { p -> Dialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = p)) {} } }',
    'gate alias': 'import org.ramflux.designsystem.AppLockGatedWindow as Gate\n',
    'cover local': 'fun A() { CompositionLocalProvider(LocalInAppLockCover provides true) {} }',
    'cover local provides call': 'fun A() { CompositionLocalProvider(LocalInAppLockCover.provides(true)) {} }',
    'cover local newline': 'fun A() { CompositionLocalProvider(LocalInAppLockCover\nprovides true) {} }',
    'cover local captured': 'val cover = LocalInAppLockCover\n',
    'covered local provided': 'fun A() { CompositionLocalProvider(LocalAppLockCovered provides false) {} }',
    'secure windows provided': 'fun A() { CompositionLocalProvider(LocalSecureWindows provides false) {} }',
    'secure off': 'val p = SecureFlagPolicy.SecureOff',
    'secure off import': 'import androidx.compose.ui.window.SecureFlagPolicy.SecureOff\nval p = SecureOff',
    'secure off in wrapper': 'fun A() { CoverAwareDialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOff)) {} }',
    'java dialog': 'class A { void a(Context c) { new android.app.Dialog(c).show(); } }',
    'java toast static import': 'import static android.widget.Toast.makeText;\nclass A { void a(Context c) { makeText(c, "x", 0).show(); } }',
    'library view in AndroidView': 'fun A() { AndroidView(factory = { c -> com.lib.FancyView(c) }) }',
    'platform editor in AndroidView': 'fun A() { AndroidView(factory = { EditText(it) }, modifier = Modifier) }',
    'AndroidView factory reference': 'fun A() { AndroidView(::FancyView) }',
    'AndroidView factory variable': 'fun A(f: (Context) -> View) { AndroidView(factory = f) }',
    'AndroidView factory helper': 'fun A() { AndroidView(factory = { make(it) }) }',
    'AndroidView unwrapped again': 'fun A() { AndroidView(factory = { c -> FancyView(c.appLockWindowContext().applicationContext) }) }',
    'first-party class on a library base': 'class Fancy(c: Context) : com.lib.LibView(c)\nfun A() { AndroidView(factory = { Fancy(it) }) }',
    'first-party name, library package': 'class Secret(c: Context) : View(c)\nfun A() { AndroidView(factory = { com.lib.Secret(it) }) }',
    'view binding': 'fun A() { AndroidViewBinding(FooBinding::inflate) }',
    'fragment host': 'fun A() { AndroidFragment<F>() }',
    'layout inflater': 'fun A(c: Context) { LayoutInflater.from(c).inflate(1, null) }',
}
SAMPLES_ACCEPTED = {
    'wrapper': 'fun A() { CoverAwareDialog(onDismissRequest = {}) { Text("Dialog(") } }',
    'named composables': 'fun A() { HostConfirmationDialog({}) {}; KitConfirmDialog(); RemoveAccountDialog() }',
    'gated picker': (
        'import androidx.compose.material3.DatePickerDialog\n'
        'fun A() { AppLockGatedWindow(onCovered = {}) { policy ->\n'
        '  val s = rememberDatePickerState()\n'
        '  DatePickerDialog(onDismissRequest = { x = "${f("}")}" }, confirmButton = { TextButton({}) {} },\n'
        '    properties = DialogProperties(securePolicy = policy)) { DatePicker(state = s) }\n'
        '} }'
    ),
    'gated qualified compose dialog': 'fun A() { org.ramflux.designsystem.AppLockGatedWindow(onCovered = {}) { p -> androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = DialogProperties(securePolicy = p)) {} } }',
    'comment': '// Dialog(onDismissRequest = {})\n/* Popup { } */ fun A() {}',
    'properties and policy': 'import androidx.compose.ui.window.DialogProperties\nimport androidx.compose.ui.window.PopupProperties\nimport androidx.compose.ui.window.SecureFlagPolicy\n',
    'compose import': 'import androidx.compose.ui.window.Dialog\n',
    'layout params': 'import android.view.WindowManager\nfun A(w: Window) { w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }',
    'reading the locals': 'fun A() { val c = LocalAppLockCovered.current; val i = LocalInAppLockCover.current }',
    'gate import': 'import org.ramflux.designsystem.AppLockGatedWindow\nimport org.ramflux.designsystem.CoverAwareDialog\n',
    'emoji picker tracked': 'fun A(c: Context) { androidx.emoji2.emojipicker.EmojiPickerView(c.appLockWindowContext()).apply { } }',
    'media3 presentation': 'import androidx.media3.effect.Presentation\nval p = Presentation.createForWidthAndHeight(1, 2, Presentation.LAYOUT_SCALE_TO_FIT)',
    'material wildcard': 'import androidx.compose.material3.*\nfun A() { Text("x") }',
    'start activity': 'fun A(c: Context) { c.startActivity(Intent(c, Other::class.java)) }',
    'first-party view in AndroidView': (
        'class Secret(context: Context) : View(context)\n'
        'fun A() { AndroidView(factory = { context -> Secret(context).apply { tag = "x" } }, modifier = Modifier) }'
    ),
    'positional first-party view': 'private class Btn(context: Context) : Button(context)\nfun A() { AndroidView({ Btn(it) }) }',
    'library view in a tracked context': 'fun A() { androidx.compose.ui.viewinterop.AndroidView(factory = { c -> com.lib.FancyView(c.appLockWindowContext()) }) }',
}


def self_test() -> None:
    for name, sample in SAMPLES_REFUSED.items():
        ext = '.java' if name.startswith('java ') else '.kt'
        got = problems('feature/x/src/main/kotlin/X' + ext, sample, first_party_views([sample]))
        assert got, f'self-test: sample "{name}" was not refused'
    for name, sample in SAMPLES_ACCEPTED.items():
        got = problems('feature/x/src/main/kotlin/X.kt', sample, first_party_views([sample]))
        assert not got, f'self-test: sample "{name}" was refused: {got}'
    assert not problems(HOST, 'fun A() { CompositionLocalProvider(LocalAppLockCovered provides c, LocalSecureWindows provides s) {} }')
    assert not problems(COVER_PROVIDER, 'fun A() { CompositionLocalProvider(LocalInAppLockCover provides true) {} }')
    assert problems(COVER_PROVIDER, 'fun A() { CompositionLocalProvider(LocalAppLockCovered provides false) {} }')


def sources():
    """Every Kotlin and Java file in a source set of a module, except test and androidTest sets and build output."""
    for path in sorted(root.glob('**/src/*/**/*')):
        if path.suffix not in ('.kt', '.java') or not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        parts = rel.split('/')
        if 'build' in parts or parts[0] in ('build-logic', 'test-support') or parts[0].startswith('.'):
            continue
        source_set = parts[parts.index('src') + 1]
        if source_set.startswith('test') or source_set.startswith('androidTest'):
            continue
        yield rel, path


def main() -> int:
    self_test()
    failures = []
    texts = {rel: path.read_text() for rel, path in sources()}
    first_party = first_party_views(list(texts.values()))
    files = len(texts)
    for rel, text in texts.items():
        failures += problems(rel, text, first_party)
    for required in EXEMPT:
        assert (root / required).is_file(), f'missing {required}'
    if failures:
        print('\n'.join(failures), file=sys.stderr)
        print(f'APP_LOCK_WINDOWS_FAILED: {len(failures)} problem(s)', file=sys.stderr)
        return 1
    print(f'APP_LOCK_WINDOWS_OK: {files} production Kotlin and Java files; every window is gated by the app-lock cover')
    return 0


if __name__ == '__main__':
    sys.exit(main())

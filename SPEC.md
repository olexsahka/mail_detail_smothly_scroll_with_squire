# MailStub — Architecture Specification

**Date:** 2026-08-26
**Status:** Design proposal — no code changes yet
**Author:** design pass driven by the "WebView-owns-scroll + native overlays" pattern from AOSP UnifiedEmail

---

## 1. Problem statement (short)

Current implementation:
- `Column + verticalScroll` owns all vertical scrolling.
- `SquireWebViewContainer` measures its own content height (via `Bridge.onHeightChanged`) and sits as a **fixed-height block** in the Column.
- Pinch-zoom is delegated to the WebView via `ScaleGestureDetector`. After zoom, height is recomputed from `contentHeight × scale`.

Observed problem (user):
> "webview show empty part of screen — I want a solution where the WebView controls all screen with dynamic spacers for native compose components, like in the open-source Gmail client (AOSP UnifiedEmail)."

Root cause: the current design creates a **height race**. The Column asks the WebView "how tall are you?" *before* Squire has finished layout / images have loaded / zoom has settled. The WebView therefore either reports too little (content is clipped, but no visible empty area) or, more commonly, gets sized to a stale `contentHeight` that no longer matches the true rendered content — leaving empty pixels above/below the HTML block that scroll but display nothing.

Adding zoom on top makes it worse: `onScaleChanged` fires *before* the WebView finishes re-laying out at the new scale, so `contentHeight × scale` is out of sync for one or more frames.

The fundamental issue: **when the WebView is a child of a Compose scroll, both sides need to agree on a height that neither has fully computed yet.** This is not a bug in the current code, it's a limitation of the model.

---

## 2. The proposed model (AOSP UnifiedEmail)

Invert the ownership:

```
BEFORE (current)                    AFTER (proposed)
─────────────────                   ─────────────────
Compose Column owns scroll          WebView owns scroll
  ├─ AppBar                         Compose overlays are positioned
  ├─ native header View               absolutely on top of the WebView
  ├─ WebView (fixed height)           at coordinates the WebView reports
  └─ Spacer

Height race: Compose asks WV        No race: WV is the source of truth
Zoom re-measure: broken             Zoom re-measure: WV re-flows, re-reports
```

### 2.1 How AOSP does it — verified from source

Files studied on `android.googlesource.com/platform/packages/apps/UnifiedEmail`:
- `src/com/android/mail/browse/ConversationContainer.java`
- `src/com/android/mail/browse/ConversationWebView.java`
- `src/com/android/mail/browse/ConversationViewAdapter.java`
- `src/com/android/mail/browse/ConversationOverlayItem.java`
- `assets/script.js`

The pattern:

1. **HTML template** = one big page containing:
   - An empty `<div class="mail-message-spacer">` per message header (its **height** is set from JS/native to match what the native View will render).
   - A `<div class="mail-message-content">` per message body (contains the sanitized email HTML).
   - Empty spacers for conversation header, footer, super-collapsed blocks, ads, etc.

2. **JavaScript** (`script.js`) — `measurePositions()`:
   ```js
   function measurePositions() {
     var overlayTops, overlayBottoms;
     var expandedBodyDivs =
         document.querySelectorAll(".expanded > .mail-message-content");
     for (var i = 0; i < expandedBodyDivs.length; i++) {
       var expandedBody = expandedBodyDivs[i];
       var headerSpacer  = expandedBody.previousElementSibling;
       overlayTops[i]    = prevBodyBottom;
       overlayBottoms[i] = getTotalOffset(headerSpacer).top
                         + headerSpacer.offsetHeight;
       prevBodyBottom    = getTotalOffset(expandedBody.nextElementSibling).top;
     }
     window.mail.onWebContentGeometryChange(overlayTops, overlayBottoms);
   }
   ```
   → JS reports arrays of top/bottom coordinates *in CSS px* for every overlay.

3. **Native side** (`ConversationContainer` extends `ViewGroup`, holds one `ConversationWebView` + N overlay Views):
   - Receives `onWebContentGeometryChange` via the `@JavascriptInterface`.
   - Stores each `OverlayPosition{top,bottom}` in web px.
   - On WebView scroll (`onScrollChanged` → `ScrollNotifier` interface → container's `onNotifierScroll()`), calls `positionOverlays()`.
   - `positionOverlays()` converts web px → screen px using the WebView's current `scale`:
     ```
     screenY = (webY × webViewScale) − webViewScrollY
     ```
   - Each overlay's `translationY` is set to place it above the WebView's spacer.

4. **View recycling** — overlays that scroll off-screen are moved to a **scrap heap** (`mScrapViews`, one pile per view type). Overlays that scroll back on-screen are pulled from the scrap heap and re-bound. Same pattern as `RecyclerView`.

5. **Zoom** — `WebView.onScaleChanged(old, new)` triggers a re-measure. The WebView re-lays out HTML at the new scale; JS re-reports positions; container re-positions overlays. **Overlays themselves do not scale** — a header renders at native text sizes regardless of body zoom. Body content grows/shrinks; header stays the same.

### 2.2 What this buys us

- **No height race.** WebView renders freely at whatever height it needs; overlays follow.
- **Zoom just works.** The WebView owns zoom natively. `onScaleChanged` triggers a re-measure, overlays reposition.
- **One scroll gesture.** The WebView is the only scroller. AppBar collapse hooks into WebView scroll events.
- **Real memory efficiency.** Overlays outside the viewport are recycled, not held in memory.

### 2.3 What it costs

- **The main scrollable surface is a WebView, not a Compose scroll.** Loses `Modifier.nestedScroll` composability, `LazyColumn` semantics, and Compose scroll animations. AppBar collapse must be wired manually via a Compose nested-scroll source that translates WebView scroll deltas into `TopAppBarState.heightOffset` updates.
- **Overlays are `View`, not `@Composable`.** In pure Compose you'd want `AndroidView` wrapping each overlay — which negates the recycling advantage. Practical answer: use classic `View` subclasses inside the container, or accept that the MVP holds all overlays in memory (fine for ≤10 messages/thread).
- **Custom `ViewGroup` in Compose land.** `ConversationContainer` has to be wrapped in `AndroidView`. Some plumbing.

---

## 3. Squire.js — verified findings

Studied `fastmail/Squire` source (`source/Editor.ts`).

| Question | Answer |
|---|---|
| Official readonly mode? | **No.** Constructor unconditionally runs `root.setAttribute('contenteditable', 'true')`. Current impl works around this by setting `false` *after* init — fragile. |
| HTML sanitisation on `setHTML`? | **Mandatory.** Squire requires DOMPurify or a custom `sanitizeToDOMFragment` callback. Without it, `setHTML` fails. Current `squire_editor.html` provides neither → any real email HTML risks a silent failure. |
| Viewport / zoom behavior? | Not addressed. Squire is DOM-only; zoom is 100% the host's problem. |
| Height reporting? | Not part of Squire. Current impl measures `document.body.scrollHeight` manually — correct approach. |

**Conclusion:** Squire is a rich-text **editor**. It has no reason to exist on a readonly view. For viewing messages, render sanitized HTML directly in a WebView — no Squire, no editor overhead, no `contenteditable` gymnastics.

**Squire belongs only on the Compose (edit) screen.**

---

## 4. Proposed architecture

### 4.1 High-level

Two rendering pipelines:

| Screen | Pipeline |
|---|---|
| **Inbox** (list of threads) | Pure Compose `LazyColumn`. No WebView. Unchanged. |
| **Single message / Thread** (viewing) | `ConversationContainer` — WebView owns scroll, native Compose overlays on top. **No Squire.** |
| **Compose** (edit) | Compose `Column + verticalScroll` (unchanged model — no scroll conflict because there's only one WebView at the bottom) + Squire in a `WebView` for the editor. Can *keep* current `SquireWebViewContainer` for this screen. |

### 4.2 Detail / Thread rendering pipeline

```
┌─ Scaffold ───────────────────────────────────────┐
│  ┌─ LargeTopAppBar (collapsible) ──────────────┐ │
│  │  driven by a custom NestedScrollSource      │ │
│  └─────────────────────────────────────────────┘ │
│  ┌─ AndroidView(ConversationContainer) ────────┐ │
│  │  ┌─ ConversationWebView (fills parent) ───┐ │ │
│  │  │  Renders template.html with:            │ │ │
│  │  │    · <div class="msg-header-spacer">×N  │ │ │
│  │  │    · <div class="msg-body">×N (HTML)    │ │ │
│  │  │    · <div class="msg-footer-spacer">×N  │ │ │
│  │  └─────────────────────────────────────────┘ │ │
│  │  ┌─ Overlay Views (positioned by container)┐ │ │
│  │  │   MessageHeader × N (Compose in         │ │ │
│  │  │      AndroidView, or classic View)      │ │ │
│  │  │   MessageFooter × N (Reply / actions)   │ │ │
│  │  └─────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────┘
```

### 4.3 Data flow

```
Kotlin                                    JS (in WebView)
──────                                    ──────────────
buildTemplate(thread) ──HTML string────▶ loadDataWithBaseURL
                                          │
                                          ▼
                                        DOMContentLoaded
                                          │
                                          ▼
                                        measurePositions()
                                          │
                          ┌───────────────┤
onWebContentGeometryChange(tops[], bottoms[])
   │
   ▼
container.setOverlayPositions(...)
   │
   ▼
onLayout() / onScrollChanged() ─▶ positionOverlays()

user pinches ─▶ WebView.onScaleChanged
                        │
                        ▼
                (JS re-measures automatically because
                 CSS heights reflow; also triggered by
                 window.addEventListener('resize', ...))
                        │
                        ▼
        onWebContentGeometryChange again
                        │
                        ▼
                positionOverlays() with new scale
```

### 4.4 Coordinate math (the only tricky part)

Three coordinate spaces:
- **CSS px** — what JS reports (`getBoundingClientRect().top`).
- **WebView content px** — CSS px × WebView `scale` (device pixels of the rendered content).
- **Screen px** — WebView content px − `scrollY`, plus WebView's own position on screen.

```kotlin
fun cssPxToScreenPx(cssPx: Int, webView: WebView): Int {
    val densityPx = (cssPx * webView.scale).toInt()   // CSS px → device px
    return densityPx - webView.scrollY                // subtract scroll
}
```

`webView.scale` is deprecated in modern WebView. Two replacements:
- Report `window.devicePixelRatio` from JS on every measure — carries current zoom.
- Track `WebViewClient.onScaleChanged(old, new)` and cache `currentScale`.

Recommended: **cache scale in Kotlin, update via `onScaleChanged`, pass through when converting**. Same approach AOSP uses.

### 4.5 AppBar — Outlook-style swap (not Material3 continuous collapse)

Target behavior (matches Outlook Android):
1. At the top of the thread: **large AppBar** is visible, showing thread title + metadata. It sits at the very top of the scroll surface as part of the content — no shadow/elevation, just header text.
2. User scrolls down. The large AppBar scrolls **up with the content** — it is *not* pinned.
3. Once the title has scrolled past the top edge (i.e. `scrollY > largeAppBarHeight − compactAppBarHeight`), a **compact AppBar** slides in from the top and stays pinned. It shows the same title in a smaller row.
4. Scrolling back up: at the reverse threshold, the compact AppBar slides out (up) and the large AppBar reappears in place.

This is a discrete swap, not a continuous interpolation. `LargeTopAppBar` from Material3 does **not** give this — it shrinks the same bar. We need two separate bars.

**Implementation in the AOSP overlay model — it fits naturally:**

- The **large AppBar** is just another `ConversationOverlay` at web-space position `y=0`, with a height (say 128dp) reserved by a `<div class="header-spacer">` at the very top of the HTML template. Because it's an overlay tied to a web-space position, it automatically translates up as the WebView scrolls — no extra code, same code path as message headers.
- The **compact AppBar** is a permanent Compose element rendered *outside* the `ConversationContainer` (in the Scaffold `topBar` slot, or as a sibling `Box` overlay). Its visibility is a state driven by the container's forwarded `onScrollChanged`:
  ```kotlin
  val swapThresholdPx = largeAppBarHeightPx - compactAppBarHeightPx
  compactAppBarVisible = webView.scrollY > swapThresholdPx
  ```
- Animate the compact AppBar's entry/exit with `AnimatedVisibility(slideInVertically / slideOutVertically)` — feels like Outlook.

**Why this is easier than Material3 `nestedScroll`:**

We don't need to teach the WebView to be a `NestedScrollingChild3`. The compact AppBar is just a boolean state flipped by a scroll listener we already have. Zero nested-scroll plumbing.

**Ship in v1.** This is a small addition on top of the overlay pattern, and it's a defining visual feature of the app.

---

## 5. Per-screen designs

### 5.1 Inbox
No change. Compose `LazyColumn` over `MockData.threads`. Tap → navigate to Detail or Thread.

### 5.2 Detail (single-message thread)

Special case of Thread with `messages.size == 1`. Uses the same `ConversationContainer` pipeline. Overlays:
- 1× `MessageHeader` (sender, date, chevron for "show details").
- Optional `MessageFooter` (Reply / Reply all).

HTML template (rendered by Kotlin):
```html
<html>
<head><meta viewport ...><style>...</style></head>
<body>
  <div class="msg-header-spacer" data-msg-id="m1"></div>
  <div class="msg-body">{{ sanitized HTML }}</div>
  <div class="msg-footer-spacer" data-msg-id="m1"></div>
</body>
</html>
```

### 5.3 Thread (multi-message)

Same pipeline, N messages. First message expanded, rest collapsed.

Collapsed message spacer: shorter (~64dp), no `.msg-body` between spacers → JS still measures the header spacer.

Expanding a message = mutate DOM via JS (`toggleExpanded(msgId)`) → re-run `measurePositions()` → native rebinds overlays.

```html
<div class="msg-header-spacer" data-msg-id="m1" data-expanded="true"></div>
<div class="msg-body" data-msg-id="m1">...</div>
<div class="msg-footer-spacer" data-msg-id="m1"></div>

<div class="msg-header-spacer" data-msg-id="m2" data-expanded="false"></div>
<!-- no body div when collapsed -->

<div class="msg-header-spacer" data-msg-id="m3" data-expanded="false"></div>
```

### 5.4 Compose (edit)

Keep current architecture — this is the case where it works:
- Static `TopAppBar` (no collapse conflict).
- `Column + verticalScroll`.
- Native fields (To, CC, Subject).
- Formatting toolbar.
- `SquireWebViewContainer(mode=EDITABLE)` at the bottom, height-reported.

Add the missing DOMPurify dependency to `squire_editor.html`. Fix now, avoid production bugs later.

**No inversion needed here.** Only one WebView, at the tail of the scroll, and the user is actively editing (so a stale height is briefly OK — cursor auto-scrolls). Zoom in the compose editor is not required; user can disable via viewport meta.

---

## 6. Zoom — how it fits

Since you chose **pinch-zoom, no reflow** and **HTML scales, native overlays stay fixed**:

- WebView's built-in zoom (`settings.setSupportZoom(true)`, `builtInZoomControls = true`, `displayZoomControls = false`) handles the gesture entirely.
- The current `ScaleGestureDetector` bridge is **not needed** in the new model, because the WebView owns the scroll surface — pinch never conflicts with an outer Compose scroll.
- After zoom: `WebViewClient.onScaleChanged(old, new)` → cache `currentScale` → trigger `measurePositions()` on JS side (via `webView.evaluateJavascript("measurePositions()")`) → overlays reposition. Overlays keep native size because we don't multiply them by `scale`.

This is a **major simplification** over the current model.

---

## 7. Comparison: current vs proposed

| Aspect | Current | Proposed (AOSP-style) |
|---|---|---|
| Scroll owner | Compose `Column` | `ConversationWebView` |
| Height source of truth | JS reports scrollHeight → Compose | WebView has authoritative height, JS reports spacer positions |
| Empty-space bug | Present (height race) | Solved |
| Zoom mechanism | `ScaleGestureDetector` + manual disallow-intercept + height recompute | Native WebView zoom + reposition overlays |
| Squire in view mode | Yes (workaround: contenteditable=false after init) | **No** — render HTML directly |
| Squire in edit mode | Yes | Yes (unchanged) |
| DOMPurify | Missing (silent risk) | Added |
| Multi-message thread | Multiple WebViews (one per expanded message) | **One** WebView for the whole thread |
| Memory (long thread) | N WebViews, all in memory | 1 WebView + recycled overlays |
| AppBar collapse | Free (Scaffold nestedScroll, Material continuous) | Outlook-style swap: large-as-overlay + compact-pinned, driven by scrollY threshold |
| Compose idiomatic | Yes | Partially (WebView-hosted) |
| Complexity to build | Low | Medium-High (custom ViewGroup + JS bridge + coordinate math) |

**Recommendation:** proposed model is correct for **viewing** (Detail + Thread). Keep current model for **Compose** (edit). This is a hybrid — it matches what AOSP UnifiedEmail actually does.

---

## 8. Risks & unknowns

1. **`WebView.scale` is deprecated.** Modern replacement is tracking via `onScaleChanged`. Verify that fires reliably on all Android 7+ versions (min SDK 24).
2. **`window.addEventListener('resize', ...)`** may or may not fire during pinch-zoom depending on WebView version. Belt-and-braces: also trigger `measurePositions()` from Kotlin after `onScaleChanged`.
3. **Compose overlays inside `AndroidView`.** Wrapping each Compose overlay in `AndroidView` is expensive and defeats recycling. Two options: (a) use classic `View`/`ViewHolder` subclasses (matches AOSP, but non-Compose), (b) render all overlays as one `ComposeView` positioned by the container (simpler, no recycling). For ≤10 msgs/thread, option (b) is fine.
4. **`nestedScroll` from a WebView.** WebView is not a `NestedScrollingChild` by default. Enabling collapse-on-scroll requires either a custom subclass implementing `NestedScrollingChild3`, or subscribing to `onScrollChanged` and manually dispatching to Compose. Non-trivial. Suggest deferring.
5. **AndroidView recomposition.** If any parent state changes, `AndroidView` factory re-runs and rebuilds the WebView (losing scroll state). Must key it stably (`remember { ... }`), and pass state via `update = { ... }`, not `factory`. Current code does this correctly — carry that discipline forward.
6. **`loadDataWithBaseURL` vs `loadUrl(file:///...)`.** Building the template in Kotlin means we need `loadDataWithBaseURL(...)` so images / resources can resolve. Base URL matters for CORS and DOMPurify's iframe workarounds.
7. **Real email HTML pathologies:** wide tables, huge inline images (base64), remote resources requiring auth, `<style>` with `@media`. Test with these early — the AOSP pattern shines here vs. per-message WebViews, but rendering quirks are real.

---

## 9. Task decomposition

Ordered, each task ≤1 day of focused work.

### Phase A — foundations
- **A1.** Create `assets/conversation_template.html` — skeleton with `<div class="msg-header-spacer">` / `<div class="msg-body">` / `<div class="msg-footer-spacer">`. CSS from current `squire_editor.html`. No JS yet.
- **A2.** Create `assets/conversation.js`:
  - `renderThread(json)` — writes DOM from message list.
  - `measurePositions()` — reports `tops[]`, `bottoms[]` per message via `Bridge.onGeometry(tops, bottoms, ids, contentHeight, scale)`.
  - `toggleExpanded(msgId)` — mutates DOM, re-measures.
  - `resize` listener.
- **A3.** Kotlin `ConversationTemplateBuilder` — builds JSON payload for `renderThread` from `EmailThread`.
- **A4.** Wire DOMPurify (either from CDN like Squire, or bundle in `assets/`). Sanitise per-message body before passing to `renderThread`.

### Phase B — container
- **B1.** `ConversationWebView : WebView` — exposes `ScrollListener` interface, forwards `onScrollChanged`, caches `currentScale` from `WebViewClient.onScaleChanged`.
- **B2.** `ConversationContainer : ViewGroup` — hosts one `ConversationWebView` + N overlay Views.
  - `setOverlays(items: List<OverlayItem>)`.
  - `onGeometry(tops, bottoms, ids)` — stores positions.
  - `onScroll()` / `onScaleChange()` — call `positionOverlays()`.
  - `positionOverlays()` — sets `translationY` on each overlay, culls off-screen (visibility GONE).
- **B3.** Compose wrapper: `@Composable fun ConversationView(thread, ...)` — uses `AndroidView` to host `ConversationContainer`.

### Phase C — overlays
- **C1.** `MessageHeaderOverlay` — extract current `MessageHeaderSection`. Render as `ComposeView` (simpler than classic View for MVP).
- **C2.** `MessageFooterOverlay` — reply/replyAll/more.
- **C3.** Bind overlays to spacer IDs.

### Phase D — Detail screen migration
- **D1.** New `ConversationScreen` (replaces `MessageDetailScreen` + `ThreadScreen` — one screen handles both since it's just N=1 vs N>1).
- **D2.** Add `<div class="header-spacer">` at top of HTML template with a fixed height (e.g. 128dp equivalent CSS px).
- **D3.** Implement `LargeAppBarOverlay` — bound to the `header-spacer` position (same code path as `MessageHeaderOverlay`).
- **D4.** Implement `CompactAppBar` as a Compose element in the Scaffold `topBar`; visibility state driven by container's forwarded `scrollY`. `AnimatedVisibility` with `slideInVertically` / `slideOutVertically`.
- **D5.** Delete `MessageDetailScreen.kt` and `ThreadScreen.kt` after parity confirmed.

### Phase E — Compose (edit) screen fixes
- **E1.** Add DOMPurify to `squire_editor.html`. Verify current setHTML behavior.
- **E2.** Keep current architecture; no changes to ComposeScreen.

### Phase F — polish (optional, post-MVP)
- **F1.** Overlay view recycling (scrap heap) — only needed if threads exceed ~15 messages.
- **F2.** Dark mode via CSS `@media (prefers-color-scheme: dark)`.
- **F3.** Elevation / shadow on the compact AppBar once it swaps in.

### Phase G — tests
- **G1.** Screenshot test: single message, verify overlay position matches spacer.
- **G2.** Screenshot test: 3-message thread, expand middle message, verify re-layout.
- **G3.** Pinch-zoom test: content scales, header does not.
- **G4.** Long-content test: 5000px body, scroll smoothly.

---

## 10. Open questions (please answer before Phase A)

1. **DOMPurify delivery** — CDN (like Squire currently) or bundle in `assets/`? Bundle is safer offline; CDN is smaller repo. Recommend bundle (`assets/dompurify.min.js`, ~20KB).
2. **Compact AppBar content** — same title as large, or shorter (e.g. large shows "Weekly design review • 3 messages", compact shows just the subject)? Recommend: same title, but truncated to one line.
3. **Overlay implementation** — Compose (`ComposeView` per overlay, no recycling, simple) or classic `View` with scrap heap (matches AOSP, more code)? Recommend Compose for MVP.
4. **`ThreadScreen` + `MessageDetailScreen` unification** — merge into one `ConversationScreen` that handles N=1 and N>1? Recommend yes (matches AOSP).
5. **Remote images** — block by default (privacy) with a "show images" button, or allow immediately? Gmail blocks by default.
6. **Squire in the reply flow inside a thread** — future work: inline reply *below* the last message? For MVP, reply navigates to standalone `ComposeScreen`. Confirm.
7. **Test data** — do we need larger / uglier mock HTML (wide tables, images, deep quotes) to stress-test the new pipeline? Recommend yes, add 2–3 pathological samples to `MockData.kt`.

---

## 11. What survives from the current codebase

- `InboxScreen.kt` — unchanged.
- `ComposeScreen.kt` — unchanged (add DOMPurify to its HTML asset).
- `SquireWebViewContainer` — kept for the Compose screen only. Remove pinch-zoom `ScaleGestureDetector` code — it's dead in edit mode.
- `MockData.kt`, `EmailModels.kt` — unchanged, possibly extended with pathological samples.
- `MessageDetailScreen.kt`, `ThreadScreen.kt` — deleted after `ConversationScreen` lands.
- `squire_editor.html` — kept, DOMPurify added, pinch-zoom viewport meta simplified.

---

## 12. TL;DR

- **Current architecture is fundamentally right for compose/edit, fundamentally wrong for viewing.** The empty-space bug is a symptom, not the disease.
- **Fix: invert ownership for viewing.** WebView owns scroll, native overlays sit on top at coordinates the WebView reports. Exact AOSP UnifiedEmail pattern.
- **Drop Squire from view mode.** It's an editor. Use it only where you edit.
- **Zoom becomes trivial:** WebView owns it natively, overlays reposition, done.
- **AppBar behaves like Outlook:** large bar is an overlay at the top of the content (scrolls away with it), a compact bar slides in from the top once the title is out of view. Same overlay code path — no Material3 continuous collapse, no nested-scroll bridge.

/# Smooth Scroll & Zoom in a WebView-Based Reader

Field notes from an Android email-viewer project modelled on AOSP UnifiedEmail: one full-height `WebView` renders the message HTML and owns all vertical scrolling; native (Compose) surfaces are positioned on top as overlays for headers/footers/appbar. Pinch-zoom is native; wide email content is down-scaled by a per-message JS pass.

The rules below are what actually kept scroll + zoom smooth in practice, plus the traps that force you to re-learn them.

Audience: an agent implementing a similar pattern in a different codebase. Nothing here is Kotlin- or Compose-specific — the same rules apply to any UIView/View overlay stack over a scrollable web surface.

---

## 1. Scroll architecture

### 1.1 Give scroll ownership to one surface

Pick the WebView, not the native scroll container. If both scroll, you fight the browser compositor forever (touch-slop mismatch, momentum races, double-fling). One `verticalScroll(state)` around a measured WebView also runs into a **height race**: the layout system asks the WebView for its intrinsic height before images/fonts/pinch have settled → empty pixel gaps.

Concretely:

- WebView fills its parent (`match_parent`).
- Native "headers/footers" are `View`s stacked in a custom `ViewGroup`.
- Overlays are positioned via `translationY`, never via layout.

### 1.2 Position overlays from a scroll listener

```kotlin
override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
    super.onScrollChanged(l, t, oldl, oldt)
    positionOverlays(scrollY = t)
}
```

`positionOverlays` is O(n) in overlay count and runs on the main thread — keep it math, not allocations. Use a `MutableList<OverlayDescriptor>` you own, not fresh lists per frame.

### 1.3 Screen Y = cssTop × effectiveScale − scrollOffset

The JS side reports each overlay anchor (a placeholder `<div>` in the HTML) as `{ id, top, height }` in **CSS pixels, document-relative**. Native converts:

```
screenY = topCss * effectiveScale - scrollOffsetPx
```

`effectiveScale` = the WebView's current zoom (density × pinchFactor). If you use raw `scrollY` without dividing/multiplying by scale, overlays drift during pinch.

### 1.4 Suppress pinch-transient scroll deltas

While a pinch gesture is active, `scrollY` twitches by 1–2 px per frame as Chromium reconciles the layout viewport. If your UI reacts to *any* scroll delta (compact bar swap, hide-on-scroll FAB), you get flapping.

Fix: a `pinchActive: Boolean` flag set from JS `visualViewport` events (see §2.2). Reset "last stable scrollY" logic ignores deltas while `pinchActive`.

### 1.5 Compress accumulated spacer overshoot

Each overlay anchor is a spacer `<div>` in the HTML with a CSS height that matches the native overlay's density-px size. When the user pinches out, each spacer's device-pixel size grows by `pinchFactor` — but the overlays themselves stay at density size. The gap accumulates per spacer.

Fix: don't try to shrink each spacer individually (that mutates DOM every frame → see §2.5). Instead, walk contiguous spacer chains in the position pass and "compress" them: place each overlay flush against its predecessor, push the accumulated overshoot below the chain so real content still lands in the right place. Pure math, no DOM writes.

---

## 2. Zoom (pinch)

### 2.1 Use the native compositor's pinch, don't reflow

```kotlin
settings.setSupportZoom(true)
settings.builtInZoomControls = true
settings.displayZoomControls = false
settings.useWideViewPort = false
settings.loadWithOverviewMode = false
```

Chromium's compositor visually scales the rasterised page bitmap. HTML layout is not recomputed. This is what preserves wide tables and email formatting under pinch — the moment you enable `useWideViewPort` / reflow-on-zoom, wide content collapses into unreadable columns.

### 2.2 Real-time viewport via `visualViewport`, not `onScaleChanged`

`WebViewClient.onScaleChanged` is sparse — it fires once when the gesture settles, 1–2 frames after the compositor already moved. Overlays lag visibly during the gesture.

Instead, wire JS `visualViewport`:

```js
const vv = window.visualViewport;
function report() {
    Bridge.onViewport(vv.scale, vv.pageTop);
}
if (vv) {
    vv.addEventListener('scroll', report);
    vv.addEventListener('resize', report);
}
window.addEventListener('scroll', report, { passive: true }); // fallback
```

This fires per compositor frame during pinch. Native updates `effectiveScale` and re-runs `positionOverlays`.

### 2.3 Predict pageTop between JS reports

Even at 60 Hz, `visualViewport.scroll` doesn't fire every native scroll frame. Between reports, the overlay position would freeze while the WebView keeps scrolling. Predict:

```
bridgePageTopCss = scrollY / effectiveScale     // when NOT pinching
```

Only when `pinchActive` is true (last JS report ≤ ~100 ms ago) do you use the JS-supplied `pageTop` directly. Otherwise the native `scrollY` is the authoritative source.

### 2.4 Overlays stay at density size

They don't visually scale with pinch. Users expect the app-bar/header buttons to stay the same tap size regardless of the page zoom. `screenY` already accounts for scale (see §1.3); the overlay's own width/height stays constant.

### 2.5 Do NOT mutate CSS/DOM during pinch

If you rewrite spacer `style.height` on every `Bridge.onViewport` tick, you race the compositor's raster pipeline. Symptom: visible flicker of a strip of content at each frame while pinching. The compositor is reading the previous layout in parallel with your write.

Handle spacer size drift natively (see §1.5). Only push `setSpacerHeight(id, cssPx)` from native at DOM-quiet moments: after `renderThread`, after `toggleExpanded`, after `setMessageLoaded` — never inside a pinch gesture.

### 2.6 Emulate the initial `onScaleChanged`

WebView does NOT fire `onScaleChanged` for the initial density scale (e.g., `2.625` on a 420 dpi device). Overlays that depend on `effectiveScale` render with a placeholder `1.0` on first paint and jump on first touch.

Fix: in `onPageFinished`, read `view.scale` yourself and notify listeners:

```kotlin
override fun onPageFinished(view: WebView, url: String?) {
    val s = view.scale.takeIf { it > 0f } ?: 1f
    currentScale = s
    if (!initialScaleSet) { initialScale = s; initialScaleSet = true }
    scaleListener?.onScaleChanged(s)
}
```

Cache `initialScale` — the density-only baseline. Pure pinch factor is `currentScale / initialScale`.

### 2.7 Long DOM-affecting animations live in CSS, not in Compose

Any animation that changes the height/width of a DOM element and needs to shift HTML content below (message header details expand, message body expand/collapse, etc.) belongs in a CSS transition on the browser side, NOT in a Compose `AnimatedVisibility` / `animateContentSize` on the overlay side.

**Why Compose loses.** Compose size animation remeasures the overlay each frame. At 60 fps × 220 ms that's ~13 `evaluateJavascript` bridge calls to push the new spacer height, each triggering a full browser reflow. The bridge is async, so the DOM lags the Compose overlay by 1–2 frames on every one of those pushes. Symptom: visible jerk on the header/footer of the *next* message in the thread while your target message is expanding.

**How CSS wins.**

1. Compose overlay content mounts/unmounts in **one** frame (no enter/exit transition).
2. Overlay `measuredHeight` jumps → `ConversationContainer.pushSpacerHeights` sends **one** `setSpacerHeight` call.
3. JS detects a "significant" change (`|new − old| ≥ 20 CSS px` + `oldHeight > 0` to skip first render / pinch drift), adds an `.animating-height` class on the spacer, sets the target height inline. The CSS transition on `.animating-height` animates the actual layout shift in the browser.
4. A bounded `requestAnimationFrame` pump inside JS calls `measurePositions()` every frame until `transitionend`. This keeps native positioning of *other* overlays (chained neighbours, body content, next message's header) in sync with the CSS transition — otherwise they'd freeze at pre-animation geometry until the transition finished.

**Reversal (mid-animation re-toggle).** If the user re-toggles while an animation is running, freeze the current transitioning height (`getComputedStyle(el).height`) as the start point for the new direction. Don't reset to auto or the old target — the visible height will snap, then re-animate from a mismatched position.

**Where the overlay's background matters.** Overlay Compose height jumps to final size instantly, but the DOM below takes 220–240 ms to slide down (or up). During that window the overlay's bottom overlaps content that hasn't moved yet. Give the overlay an opaque `surface`-colour background — the overlap then looks like normal layout instead of a visual glitch. Overlays without an opaque background will show ghost content underneath during animation.

**When NOT to use this pattern.**
- One-shot animations that fire before the user can see them (e.g., fixLayout applying `transform: scale` on a table during the first render of a message body).
- Animations that don't affect layout (opacity / colour / border) — those are Compose-native, GPU-accelerated, and stay far away from the bridge.
- Native `visualViewport` transient reactions (pinch scroll deltas) — those should be handled without any DOM writes, see §2.5.
- **Elements with heavy content subtrees.** CSS `transition: height` works on *empty* spacer divs (the header/footer overlay zones — one number to interpolate, one empty box to reflow). It does NOT work on a message body which owns a full sanitized HTML subtree (tables, images, scale-wrapped children). Every frame the browser reflows the whole subtree, clips content via `overflow: hidden`, recomputes document `scrollHeight` for the scrollbar, and may re-fire fixLayout via queued image-load listeners. `height` is not a compositor-only property, `contain: layout` only isolates *internal* propagation (siblings still move), and `will-change: height` is a no-op because layout isn't accelerated. Result: the animation itself drops frames on any real content, which visually reads as "freezing/stuttering". Snap heavy elements. If you need polish, animate an empty spacer around them or a lightweight overlay indicator — not the content wrapper itself.

---

## 3. Wide-content down-scaling (fixLayout-style)

Emails routinely ship 1200–2000 px fixed-width tables, `<img width="1400">`, `<div style="width:1500px">`. Under the "compositor pinch, no reflow" model those overflow the viewport and clip. A per-message JS pass fixes this by transforming/wrapping content to fit.

### 3.1 Per-message scope, never `document.body`

The classic implementation of this pattern targets `document.body` and a singleton `#mail-scale-wrapper`. It breaks the moment you render more than one message body in a single document (which any threaded email view does).

Instead: expose `formatMessageBody(scopeEl)` that takes a specific `.msg-body` element. Every mutation is scoped to that element's `.mail-scale-wrapper` child. Per-scope state (debounce timer, "first call" flag, computed bounds) lives in a `WeakMap<HTMLElement, State>` so parallel messages don't clobber each other's timers.

### 3.2 Reset previous transforms BEFORE measuring

Any subsequent pass must undo its own previous output before measuring, or you spiral:

```js
// At the top of layout():
scaleWrapper.style.zoom = '';   // clear prior addScale
// zoomOutTables() removes .zoom-out class + transform on each table
```

Otherwise `getBoundingClientRect()` returns dimensions already scaled by the last pass, so the "widest element" computation is against zoomed sizes. You either double-shrink (multiplicative zoom) or leave stale zoom that no longer matches the current viewport.

### 3.3 Immediate first pass, debounce the rest

First call fires via `setTimeout(0)` so content that just landed in the DOM gets sized before the user sees the pre-scaled version. Subsequent calls (image load events, resize) debounce at ~300 ms so streams of events don't thrash.

### 3.4 Above the safety threshold, fall back to horizontal scroll

If `scrollWidth > MAX_DOCUMENT_WIDTH_TO_TRANSFORM` (reference project used 3000 px), skip transforms — the resulting zoom would be unreadable. Instead put `overflow-x: auto` on the wrapper so the user can pan. Don't leave it clipped by a parent `overflow-x: hidden` — that silently hides content.

### 3.5 Bail on 0-width or detached scope

- `if (rect.width <= 0) return;` — width 0 makes the scale factor `Infinity` → content collapses to `zoom: 0`.
- `if (!scopeEl.isConnected) return;` inside the debounced timeout — the message may have been collapsed between scheduling and firing.

### 3.6 Idempotent image listeners

Use a `WeakSet<HTMLImageElement>` to track which images already have `load`/`error` listeners attached, or repeated `formatMessageBody` calls (from resize) will stack N copies of the same handler. Don't mutate the image node with a `_customFlag` property.

### 3.7 Handle rotation / split-screen

`formatMessageBody` picks a scale from `viewport.width`. On rotation, all those scales are stale. Register a debounced (~300 ms) `window.resize` handler that iterates every loaded `.msg-body` and re-runs `formatMessageBody(body)`. If you don't, landscape → portrait leaves tables too small or overflowing.

### 3.8 Trigger points

Call `formatMessageBody(body)` at every DOM-write that produces a body:

- Initial render, for any pre-loaded + pre-expanded message. (The classic bug: implementations only hook lazy-load and forget the first message.)
- Lazy body arrival (`setMessageLoaded`).
- Expand of an already-loaded body (`toggleExpanded → expand`).

Do NOT call it on collapse — nothing to size.

---

## 4. JS ↔ Native bridge

### 4.1 Base64 UTF-8 for all HTML payloads

`evaluateJavascript("renderThread({html: '$html'})")` breaks on the first apostrophe, backslash, or `</script>` in an email body. Even careful escaping breaks on Cyrillic / emoji via the WebView string boundary.

Encode HTML as UTF-8 → base64 on the native side, decode on JS. On JS, **do not** use `atob(b64)` alone — it returns a Latin-1 binary string. Multi-byte UTF-8 gets mangled *before* your JSON parser sees it. Use one of:

```js
// Option A: escape/decodeURIComponent hack (works everywhere)
function b64ToUtf8(b64) {
    return decodeURIComponent(escape(atob(b64)));
}

// Option B: TextDecoder (modern, preferred)
function b64ToUtf8(b64) {
    return new TextDecoder('utf-8').decode(
        Uint8Array.from(atob(b64), c => c.charCodeAt(0))
    );
}
```

This applies to BOTH the initial full-thread render and per-message lazy loads. The bug is easy to miss because Latin-1 text (all ASCII English mocks) survives `atob` untouched — problems only surface once someone tests with real user content.

### 4.2 Double `requestAnimationFrame` before geometry reads

```js
function scheduleMeasure() {
    if (measureScheduled) return;
    measureScheduled = true;
    requestAnimationFrame(() => {
        requestAnimationFrame(() => {
            measureScheduled = false;
            measurePositions();  // reads getBoundingClientRect for every overlay
        });
    });
}
```

First rAF gives the browser a chance to commit pending DOM mutations. Second rAF is a layout-read tick. Single rAF is a coin flip — sometimes you read pre-layout values and overlays land 8 px off.

### 4.3 Don't fire `scheduleMeasure` twice back-to-back

If you `scheduleMeasure()` immediately after inserting body HTML, and *then* kick off a scaling pass that also `scheduleMeasure()`s at the end, overlays snap into place twice: once at the raw-content position, once at the scaled position. Users see the jump.

Choose one owner per DOM-mutation path. If a wide-content pass will run, let it own the follow-up measure — skip the earlier one.

### 4.4 Reset per-message caches on full re-render

Native tends to cache "last spacer height pushed" to skip no-op JS calls. When you rebuild the DOM (`renderThread(...)`), those cached heights no longer correspond to any node in the fresh DOM. Reset the cache before issuing the render call, or the first `setSpacerHeight` for the new DOM will be silently skipped.

### 4.5 External URL schemes go to system handler

In `shouldOverrideUrlLoading`, route `http/https/mailto/tel/sms/geo` out to the system. Without this, taps on links inside sanitized email HTML load the destination *inside* your content WebView — the URL bar isn't visible, which is both a UX regression and a phishing surface.

---

## 5. Troubleshooting — when it goes wrong

Symptoms → likely cause → fix.

**Overlays flicker/twitch during pinch.** You're mutating CSS or spacer heights inside a `visualViewport` handler. Move mutations to DOM-quiet events (see §2.5). Do overshoot compression in native pure-math (see §1.5).

**Overlays jump twice when a message expands.** Two `scheduleMeasure` fires on the same DOM change (see §4.3). Pick one owner — usually the last mutation in the chain.

**Empty pixel strip inside the content on first render.** Height race — you measured before images/fonts/pinch settled. Add a `load` listener on non-sized images that triggers a re-measure (see §3.6). For fonts, `document.fonts.ready.then(scheduleMeasure)` if you need absolute precision.

**Text is mojibake / question marks.** `atob` → Latin-1 problem (see §4.1). Applies to *every* JSON/HTML payload crossing the bridge, not just body content.

**Wide content clips silently on the right, no scroll.** `overflow-x: hidden` on the message container is masking overflow. Either scale it down or expose a horizontal scroll fallback on the wrapper. Never leave overflow silently hidden.

**Wide content stays tiny after rotation.** No `resize` handler re-running the down-scale pass (see §3.7). Also check: are you resetting `wrapper.style.zoom = ''` at the start of every pass, or measuring through stale zoom (see §3.2)?

**First message in the thread ignores the down-scale pass.** Initial-render code path is missing the `formatMessageBody` call. Lazy load / toggle-expand paths are separate and don't cover it (see §3.8).

**Overlay positions drift as the user zooms out (pinch-in).** `effectiveScale` is stale — WebView never fired `onScaleChanged` for the initial density scale, so you're computing against `1.0` instead of the real density (e.g. 2.625). Emulate the initial call from `onPageFinished` (see §2.6).

**Compact bar swaps in/out while pinching.** Scroll deltas during pinch are compositor noise. Suppress them with a `pinchActive` flag (see §1.4).

**Overlay for a message below the fold never appears.** Overlay descriptor list and DOM spacer order got out of sync — your "previous neighbour" logic in `positionOverlays` skipped it. Rebuild descriptors from a pure `(thread, expandedIds, loadedIds)` reducer so order is always deterministic and matches the render.

**Body is empty (zoom-collapsed to 0).** `formatMessageBody` ran while `getBoundingClientRect().width === 0` (detached, hidden, or pre-layout). Bail early (see §3.5).

**Every image in the body loads then the layout "jitters" once.** That's the expected first-pass → image-load → re-layout cycle. If it happens more than once per image, you're attaching duplicate `load` listeners on repeated `formatMessageBody` calls (see §3.6).

**Message body expand/collapse animation stutters or "freezes" mid-way.** You're transitioning `height` on a content-heavy element (body with tables/images/wrapped children). Every frame the browser reflows the whole subtree and recomputes document scroll height; the transition itself drops frames. Snap the body insert/remove and only animate the empty header/footer spacer siblings via the CSS pattern in §2.7. If the header spacer transitions cleanly and the body one doesn't, you've hit the "not-for-heavy-content" caveat — see the last bullet of §2.7 "When NOT to use this pattern".

**Content flashes to full height and then animates smoothly** on a mid-animation reverse toggle (user tapped again before the first animation finished). Reversal path is resetting the inline `height` before starting the new animation instead of freezing at the current transitioning value. Read `getComputedStyle(el).height` at the freeze point, set it as the inline height, then start the new transition (see §2.7 "Reversal").

---

## 6. Potential further improvements

Things to try if the current model doesn't hold up. These weren't needed in the reference project but are the obvious next steps if you hit the corresponding pain point.

### 6.1 BridgePageTopMath is unaware of CSS `zoom`

The wide-content pass may set `zoom: 0.6` on a wrapper. `scrollY` (device pixels) then no longer maps linearly to `pageTopCss` for anchors inside that wrapper. The reference project accepted this — overlays are anchored to spacers *outside* the scaling wrapper, so drift doesn't show. If you place overlays *inside* scaled content, you'll need a per-anchor `localZoom` factor to correct.

### 6.2 CSS `zoom` × user pinch = multiplicative

If the down-scale pass sets `zoom: 0.6` and the user pinches to 2×, they see 1.2× — usually fine, but "reset zoom" gestures now return to 0.6×, not 1×. Consider a pinch listener that resets your CSS `zoom` when the user zooms in past a threshold (they clearly want to see it larger).

### 6.3 `<span display:block>` around `<table>` is technically invalid

The reference `fixLayout` wrapper is `<span class="span-scaling-wrapper" style="display:block">` around a `<table>`. Chromium tolerates it; strict validators / other engines may not. If you port to a non-Chromium engine (WKWebView on iOS, GeckoView), swap to `<div>`.

### 6.4 Nested wide content

`transformBlockElements` only matches `div[style]` / `textarea[style]` — elements with fixed width in an *inline* style attribute. Content that sets width via a `<style>` block or an external stylesheet is invisible to it. If you need coverage, either query `getComputedStyle` for every descendant (expensive) or run DOMPurify with a hook that promotes any CSS width rule to an inline style before the scan.

### 6.5 Native pinch is one scale for the whole document

Every message body zooms together. Per-message pinch would require per-body sub-WebViews or CSS `transform: scale` on each body driven from native gesture recognisers — both are considerably more work and lose some of the compositor's smoothness.

### 6.6 `visualViewport` gaps on some Android WebView versions

Old WebView (< M77-ish) doesn't dispatch `visualViewport.scroll` when the layout viewport moves without a pinch. The reference project handles this by also listening to `window.addEventListener('scroll', report)`. If you still see per-frame lag, poll `view.scale` from `Choreographer` while a gesture is in progress (`isInPinch` derived from `MotionEvent.pointerCount == 2`) — expensive but bulletproof.

### 6.7 Skip re-render when nothing changed

`renderThread` currently rebuilds the DOM even when the thread is identical to what's already rendered (e.g., a config change re-triggers the effect). Track "last sent thread id" and short-circuit — the reference project does this for `renderThread` but the same idempotency check is worth applying to `toggleExpanded` deltas and `setSpacerHeight` pushes.

### 6.8 Prefer `TextDecoder` over `escape/decodeURIComponent`

`escape()` is a deprecated JS built-in. It works, but if you hit a transpiler / linter that removes it, switch to the `TextDecoder` form (see §4.1 Option B). Works in every Chromium WebView.

### 6.9 Font-load timing

For pixel-precise overlay alignment, call `scheduleMeasure()` after `document.fonts.ready` completes. Otherwise the first paint uses fallback fonts (different line-height) and overlays snap ~4 px when the real font lands. The reference project didn't bother — email content uses system-font stacks — but any custom-font UI should.

### 6.10 Testable pure-Kotlin logic layers

Anything that's math (overlay layout, compact bar threshold, page-top prediction, template building, state reduction) — extract to a pure Kotlin object with no Android/View dependencies and unit-test it. The reference project has separate `*Math.kt` files for each. This is where regressions hide; UI tests won't catch a scale-arithmetic bug that happens 2 frames into a pinch.

### 6.11 Native-side animation for heavy content when snap feels too abrupt

CSS `transition: height` on a body-sized element stutters (§2.7 caveat). Snap works but the layout jump on expand/collapse is visible. If that jump is unacceptable, animate on the *native* side instead of the browser side:

- Snap the DOM change (body insert/remove) so document reflow is one-shot, cheap, and off the critical path.
- On native, capture the affected overlays' `translationY` values BEFORE the snap (from the pre-snap geometry) and AFTER the snap (from the post-snap geometry).
- Run an `ObjectAnimator` / Compose `Animatable` on each overlay's `translationY` from old to new — 200-240 ms, same easing as your other UI. Native animations run on Android's Choreographer and are GPU-composited on the RenderThread, they don't fight the WebView main thread.

Trade-off: the WebView content beneath the overlay moves instantly (snap); only the overlays glide. Users read this as "the app placed the content, the interactive buttons slid to their new positions" — a familiar pattern from Gmail and Outlook. The DOM stays consistent throughout, so pinch/scroll behave normally.

Do this only if snap actually shows up in feedback. It's ~50 lines of Kotlin plus a hook to grab pre-snap geometry — not free effort, and users usually accept snap for content-heavy reveals.

---

## 7. Reference / prior art

- **AOSP UnifiedEmail** — the pattern of "WebView owns scroll, native overlays on top" originates here. The Java source in AOSP (email/Gmail before it moved to closed source) is worth reading if you get stuck on overlay ordering or spacer geometry.
- **Chromium `visualViewport` docs** — the authoritative reference for the JS API that makes pinch reporting cheap.
- **DOMPurify** — for HTML sanitisation. Bundle locally, don't fetch. Wire the `uponSanitizeAttribute` hook to strip `http(s)` image srcs (remote images = tracking pixels).

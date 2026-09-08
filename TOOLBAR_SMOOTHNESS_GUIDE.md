# Toolbar Smoothness — Handoff Notes

Focused companion to `SCROLL_ZOOM_GUIDE.md`. Everything here is about one thing: keeping the top-of-screen bars steady while the user scrolls, pinches, or flings. No jitter on the expanded (large) bar, no visible gap on the compact ↔ expanded handoff, no flapping during pinch.

Audience: another agent about to touch `LargeAppBarOverlay`, `CompactBarThreshold`, `ConversationContainer.onScrollChanged`, or `AnimatedVisibility` around the compact bar. Read this before changing any of them.

---

## 0. The architecture in one paragraph

There are two bars, drawn independently:

1. **LargeAppBarOverlay** — a native Compose overlay bound to the `app-bar-spacer` DOM node at `topCss = 0`. Positioned by `ConversationContainer.positionOverlays()` as `translationY = -scrollY / effectiveScale * effectiveScale` (≈ `-scrollY`). It scrolls up with the content because its anchor is at the top of the document.
2. **CompactAppBar** — a Material3 `TopAppBar` inside `ConversationScreen`'s Box, pinned at `Alignment.TopCenter`, wrapped in `AnimatedVisibility`. Toggled by `CompactBarThreshold.shouldShowCompact(scrollY, appBarHeightPx, compactBarHeightPx)`. It is drawn **on top** of the WebView + overlays.

The invariant: when the compact bar is fully visible, it covers exactly the top `compactBarHeightPx` of the viewport — which is precisely the still-visible slice of the large bar at the swap threshold. If any of these values drift, the seam shows.

```
scrollY = 0          scrollY = threshold        scrollY > threshold
┌────────────────┐   ┌────────────────┐         ┌────────────────┐
│                │   │  compact bar  ─┼──────►  │  compact bar   │
│    LARGE       │   │────────────────│         │────────────────│
│    APP BAR     │   │  (last 64px of │         │  message body  │
│                │   │   large bar)   │         │                │
├────────────────┤   ├────────────────┤         │                │
│  message body  │   │  message body  │         │                │
```

---

## 1. Bar jitter (a few px per frame)

### 1.1 Symptom

While flinging or scrolling the large app bar wobbles ±1–3 device px per frame. Most visible on the expanded bar because its `topCss = 0`: any error in the pageTop → translationY conversion lands directly in the overlay position.

### 1.2 Cause

Two writers race for `bridgePageTopCss`:

- **Native** `WebView.onScrollChanged` handler — synchronous with the compositor commit, freshest value: `bridgePageTopCss = scrollY / effectiveScale`.
- **JS** `visualViewport.scroll` / `window.scroll` → `Bridge.onViewport(scale, pageTopCss)` — crosses the bridge via `post {}`, arrives 1 frame late relative to native.

If both write and last-write-wins per frame, the overlay bounces between:
- Native-fresh: `translationY ≈ -scrollY` (correct).
- JS-stale: `translationY ≈ -pageTopStale * effectiveScale` (a few px off).

### 1.3 Rule

Split ownership by phase; the two writers must never race:

- **Pinch active** → JS owns `bridgePageTopCss`. Scale and pageTop are one atomic snapshot from the compositor; you need both together.
- **Not pinching** → native `scrollListener` owns `bridgePageTopCss` via `scrollY / effectiveScale`. JS `pageTopCss` is **ignored** — it only lags.
- **Any phase** → JS is still the source of truth for `bridgeScale`. Update it every viewport event.
- **Scale change without an active pinch** (double-tap zoom, first-paint density scale) → re-anchor `bridgePageTopCss` from current `scrollY` at the *new* scale. Otherwise you carry over a prediction computed at the old scale.

Reference impl: `ConversationContainer.onViewportUpdate`.

### 1.4 What NOT to do

- Don't "average" or "damp" between JS and native samples. Damping introduces persistent lag; averaging turns a 1-frame race into a 2-frame race.
- Don't call `positionOverlays()` from a `postOnAnimation` inside `onScrollChanged`. Deferring by one frame is exactly what makes the overlay lag the DOM — the whole point of the sync path is same-frame reposition.
- Don't `roundToInt()` on `translationY`. Sub-pixel float values are correct; the renderer snaps to device pixels at draw time. Rounding introduces its own ±1 px per-frame flicker at fractional scales.

---

## 2. Compact ↔ expanded handoff (visible delay / occlusion)

### 2.1 Symptom

When the user scrolls back to the top the expanded bar appears "late" — you see the compact bar linger for ~200 ms before the large bar shows up. Or in reverse (scrolling down): the compact bar sticks briefly before it slides.

### 2.2 Cause

The compact bar is drawn **on top of** the WebView + overlays. If it uses `slideInVertically` / `slideOutVertically`, during the exit animation the compact bar is still occupying the top ~64 dp — but translated a bit — for the whole slide duration. The large bar has already moved into place underneath; you just can't see it because the sliding compact bar is on top.

### 2.3 Rule

Cross-fade the compact bar, don't slide it.

```kotlin
AnimatedVisibility(
    visible = showCompact,
    enter = fadeIn(tween(180)),
    exit  = fadeOut(tween(180))
) { CompactAppBar(...) }
```

During the fade both bars are drawn; the large bar bleeds through the compact bar's dropping opacity. No occlusion, no perceived delay.

Keep the duration short (~150–200 ms). Longer feels sluggish; shorter is indistinguishable from a snap and reveals the swap seam.

### 2.4 What NOT to do

- Don't move the compact bar out of the top-overlay position. Putting it in `Scaffold.topBar` reflows the WebView on every visibility toggle → WebView remeasure → geometry churn → overlay flicker.
- Don't drop `AnimatedVisibility` and swap instantly. The `CompactBarThreshold` triggers on a single `scrollY` value; without a transition you get a hard flip at the threshold pixel that's very visible on a slow scroll.
- Don't try to animate the swap threshold itself (e.g., pull `appBarHeightPx` toward `compactBarHeightPx` over time). Both are measurements, not animatable values; the threshold must be exact so the visible slice of the large bar matches the compact bar's height at the swap moment.

---

## 3. Pinch transients — freeze the compact-bar swap

### 3.1 Symptom

Pinching flaps the compact bar in and out mid-gesture, even without any real vertical scroll intent.

### 3.2 Cause

WebView's compositor adjusts `scrollY` per pinch tick to keep the focal point stable — those are 1–2 px transients, not real user scrolls. If the compact bar swap listens to *any* `scrollY` delta, threshold crossings during pinch trigger the animation.

### 3.3 Rule

`ConversationContainer` owns a `pinchActive: Boolean` set from `MotionEvent.ACTION_POINTER_DOWN` (≥ 2 pointers) and cleared from `ACTION_POINTER_UP` / `ACTION_UP` / `ACTION_CANCEL`. The scroll listener suppresses `onScrollChanged` propagation to the outer callback while `pinchActive`:

```kotlin
if (!pinchActive) onScrollChanged(newY)
```

When pinch ends (`endPinch`), re-anchor `bridgePageTopCss` from the freshly settled `scrollY`, then fire one final `onScrollChanged(webView.scrollY)` so `ConversationScreen` catches up to the true post-pinch scroll position (which decides whether the compact bar should now be visible).

Overlays themselves *do* follow the pinch every frame (via JS `visualViewport`) — they must, or the large bar drifts under your fingers. It's only the compact-bar swap threshold that freezes.

### 3.4 What NOT to do

- Don't use `MotionEvent.pointerCount` inside `onScrollChanged` to detect pinch. `pointerCount` on the WebView's scroll callback is unreliable during nested-scroll / fling scenarios. Track pinch from `dispatchTouchEvent` on the container instead — it sees the raw pointer stream regardless of who's currently handling the gesture.
- Don't clear `pinchActive` from `ACTION_POINTER_UP` when `pointerCount > 2` (three fingers, one lifted — still pinching). Only clear when dropping to 1 finger.

---

## 4. Large-bar visibility flag

`OverlayLayoutMath.layout` computes `visible = positioned && onScreen`, where `onScreen = topPx + measuredHeight > 0 && topPx < viewportHeightPx`. For the large bar (`topCss = 0`, height ≈ `appBarHeightPx`), that becomes `scrollY < appBarHeightPx`.

So the bar flips to `View.INVISIBLE` once fully scrolled off, and back to `VISIBLE` as soon as the bottom edge enters the viewport. That transition is exact and same-frame with the scroll — do not add hysteresis / debouncing to it. The visible flag is what lets the compact bar's fade look natural: the large bar is already `VISIBLE` (positioned correctly) *before* the compact bar starts fading, so the reveal has something to reveal.

If you ever see the large bar pop in visibly, the culprit is almost always:
- Stale `bridgePageTopCss` (see §1) → wrong `topPx` → visibility flag flips late.
- `View.GONE` used instead of `View.INVISIBLE` → GONE removes the view from layout, next frame's measure/layout costs a full pass. Use INVISIBLE.

---

## 5. Focus threshold coupling

`ConversationContainer.focusThresholdPx` is used both for prev/next arrow navigation (which header is "current") and — indirectly — for the compact bar swap timing. The screen typically sets it to `compactBarHeightPx`.

If you change what `focusThresholdPx` represents, the compact-bar swap threshold in `CompactBarThreshold.shouldShowCompact(scrollY, appBarHeightPx, compactBarHeightPx)` must still receive the *same* `compactBarHeightPx` value — they're independent code paths but they both must agree that "the compact bar occupies the top N device px". Any drift here shows as either:
- Compact bar swaps before the large bar's visible slice matches its height → visible seam.
- Focus pins on the wrong header at the swap threshold → next-tap arrow becomes a no-op.

---

## 6. Manual verification (before declaring smooth)

The compile passes. The unit tests pass. Neither catches jitter. Run through this on a physical device (emulators smooth over compositor timing):

1. **Slow scroll down.** Watch the top edge of the large bar as it scrolls off. It should move exactly 1:1 with the content — no wobble, no lag, no drift. If it wobbles: §1.
2. **At the swap threshold.** Watch the transition compact ↔ expanded. There should be a brief cross-fade window where both are visible; the large bar should never look like it "pops in from behind" the compact one. If it does: §2.
3. **Fling down, then immediately back up.** The compact bar should appear and disappear cleanly with no ghosting mid-fling. Fast direction changes are where slide animations expose their occlusion; cross-fade doesn't.
4. **Pinch out at mid-scroll.** The compact bar should stay in whichever state it was — no flap during the gesture. Large bar overlay should stay flush with its DOM spacer at every scale during the pinch. On release, the compact bar should snap-check once against the settled `scrollY`. If it flaps mid-pinch: §3.
5. **Double-tap to zoom.** Both bars should stay in their expected states — no jump. This exercises §1's "scale changed without an active pinch" path.
6. **Rotate the device with the compact bar visible.** After the config change settles, the compact bar's visibility should still match `CompactBarThreshold`'s rule for the new `appBarHeightPx` (subject wraps to more/fewer lines). If it doesn't, someone forgot to re-read `appBarHeightPx` from `onAppBarHeightChanged` after the WebView remeasured.

---

## 7. If you're adding a new bar or a third state

- **Every bar drawn on top of the WebView is a candidate for occluding the reveal of a bar underneath it.** Use fade, not slide, for its `AnimatedVisibility`, unless you can prove the bar has no bar underneath it that ever needs to be seen through the transition.
- **Every new "watch scrollY and decide visibility" rule must also honour `pinchActive`.** If it lives inside `ConversationContainer`, gate it on the same flag. If it lives outside (e.g., another Composable observing `scrollY`), the scrollY it receives is already gated by the container — but any *derivative* state (e.g., "average scroll velocity over 100 ms") will drift during pinch unless you also freeze it.
- **Every new overlay you register with `ConversationContainer.setOverlays` must include a fresh DOM spacer**, and its `id` must match the spacer's `data-overlay` attribute. `positionOverlays` walks in insertion order and pairs by index into the DOM order — see `OverlayDescriptorBuilder` for the canonical construction. Mismatched order = chain-compression pulls the wrong neighbour and overlays disappear off the bottom (see `ConversationContainer.setOverlays` KDoc).

---

## 8. Files at a glance

| Concern | File |
| --- | --- |
| Where the large bar Compose lives | `ui/conversation/LargeAppBarOverlay.kt` |
| Compact-bar swap threshold (pure math) | `ui/conversation/CompactBarThreshold.kt` |
| `AnimatedVisibility` wrapping the compact bar | `ui/screen/ConversationScreen.kt` |
| Overlay positioning per frame | `ui/conversation/ConversationContainer.kt` (`positionOverlays`) |
| Bridge race arbitration | `ui/conversation/ConversationContainer.kt` (`onViewportUpdate`, `scrollListener`) |
| Pinch state | `ui/conversation/ConversationContainer.kt` (`dispatchTouchEvent`, `endPinch`) |
| `scrollY / effectiveScale` prediction (pure math) | `ui/conversation/BridgePageTopMath.kt` |
| Overlay chain layout (pure math) | `ui/conversation/OverlayLayoutMath.kt` |

Unit tests for every `*Math.kt` live under `app/src/test/java/com/alex/mailstubdetails/ui/conversation/`. Anything you extract to pure Kotlin, test there — the container itself needs a real WebView so it's manual-only.

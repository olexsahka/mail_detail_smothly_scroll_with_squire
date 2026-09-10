# App Bar Title Fade — Handoff Notes

Focused guide for the *statically pinned top bar + scroll-linked subject fade* behavior in `ConversationScreen`.

Audience: another agent about to touch `LargeAppBarOverlay`, `AppBarTitleFadeProgress`, `CompactAppBar` in `ConversationScreen`, or the `onSubjectBoundsChanged` / `onSizeChanged` measurements that feed the fade. Read this before changing any of them.

**Supersedes** the "compact swap" description in `TOOLBAR_SMOOTHNESS_GUIDE.md` §0 / §2 — the `AnimatedVisibility`-driven bar swap and `CompactBarThreshold` were removed. Jitter/pinch notes (§1, §3) in that guide are still current.

---

## 0. The architecture in one paragraph

There is one bar. It is always visible.

1. **`CompactAppBar`** — a Material3 `TopAppBar` inside `ConversationScreen`'s `Box`, pinned at `Alignment.TopCenter`. Always drawn (no `AnimatedVisibility` wrapper). Contains: back, prev/next, more icons, and a title slot that starts empty and fades the subject in as the user scrolls.
2. **`LargeAppBarOverlay`** — the hero header: big subject + "N messages" line + a duplicate copy of the icons. It's a native Compose overlay bound to the `app-bar-spacer` at `topCss = 0`, so `translationY ≈ -scrollY`. It travels up with the content.

The two overlap exactly at `scrollY = 0`: the pinned bar covers the hero's icon row (icons are intentionally duplicated so the visual is a single icon strip), and the hero subject peeks out below it. As the user scrolls up, the pinned bar's title cross-fades in **coupled to the scroll position** — the visual is: the last of the hero title slides under the bar, and the pinned title emerges from below the bar simultaneously.

```
scrollY = 0                    scrollY = fadeStart              scrollY = fadeEnd
┌────────────────┐             ┌────────────────┐               ┌────────────────┐
│ ← icons ↑↓ ⋮   │  pinned     │ ← icons ↑↓ ⋮   │               │ ← subject  ↑↓ ⋮│  pinned
│────────────────│             │────────────────│               │────────────────│  (title on)
│ Big Subject    │  hero       │ Big Subject    │  hero          (hero fully under)
│ 3 messages     │             │ (bottom under) │
├────────────────┤             ├────────────────┤
│  message body  │             │  message body  │
```

At `fadeStart` the hero subject's *top* has just crossed under the bar's bottom edge; at `fadeEnd` the hero subject's *bottom* has too. In-between the pinned title is at `alpha = progress` and translated up by `(1 − progress) × 12dp`.

---

## 1. Fade math (`AppBarTitleFadeProgress`)

Pure Kotlin. Given four device-px inputs, returns `Float` in `[0f, 1f]`:

```
span         = heroSubjectBottomPx − heroSubjectTopPx      // subject text height
fadeStart    = heroSubjectTopPx    − staticBarHeightPx     // scrollY at which fade begins
raw          = (scrollYPx − fadeStart) / span
progress     = raw.coerceIn(0f, 1f)
```

Screen-space derivation: because the hero overlay is bound to a `y = 0` CSS spacer, its top on screen ≈ `−scrollY` at pinchFactor = 1 (during pinch, `ConversationContainer` suppresses `onScrollChanged`, so the fade freezes rather than flapping — same trick as the old compact bar swap). Therefore `subjectTop_screen = heroSubjectTopPx − scrollY`; the fade start condition `subjectTop_screen ≤ staticBarHeightPx` rearranges to `scrollY ≥ heroSubjectTopPx − staticBarHeightPx`. Same for the bottom edge / fade end.

**Guard:** `span ≤ 0` (subject not measured yet) returns `0f`. This is why a zero `heroSubject*` is safe: the fade stays hidden until real geometry arrives.

Full unit coverage lives in `AppBarTitleFadeProgressTest` — start there when tuning the mapping.

---

## 2. Where the numbers come from

| Value | Source | Callback | Notes |
|-------|--------|----------|-------|
| `scrollY` | `ConversationWebView.ScrollListener` → `ConversationContainer.onScrollChanged` → `ConversationView` | `onScrollChanged` | Fires every frame the WebView scrolls. Suppressed during pinch. |
| `heroSubjectTopPx` / `heroSubjectBottomPx` | `LargeAppBarOverlay` subject `Text` | `Modifier.onGloballyPositioned { positionInRoot().y (+ size.height) }` → `onSubjectBoundsChanged` | Layout-only. Fires on first composition + font/text-size changes. Not per frame. |
| `staticBarHeightPx` | `CompactAppBar` outer `TopAppBar` | `Modifier.onSizeChanged { it.height }` | Layout-only. Also feeds `ConversationView.focusThresholdPx`. **No** hardcoded `64.dp` anywhere — swapping `TopAppBar` for `CenterAlignedTopAppBar` / larger variant recalibrates automatically. |

All three inputs are stored as `mutableIntStateOf` in `ConversationScreen`. Only `scrollY` updates per frame; the others are effectively constant during scroll — so a per-frame `progress()` recompute is one subtraction + one division.

---

## 3. How the fade is applied

The Text in `CompactAppBar.title` reads `titleFadeProgress` through `Modifier.graphicsLayer`:

```kotlin
Modifier.graphicsLayer {
    alpha = titleFadeProgress
    translationY = (1f - titleFadeProgress) * TITLE_SLIDE_DISTANCE.toPx()
}
```

`graphicsLayer` is a **draw-only** modifier — no measure/layout invalidation. Even though `scrollY` triggers a recomposition of `ConversationScreen` every frame, the toolbar's layout is stable; only the title's paint parameters change.

`TITLE_SLIDE_DISTANCE = 12.dp` is a design constant (how far the title rises during the fade). It's the one magic number remaining — tune it if the "rising from below" impression feels too subtle or too much.

---

## 4. Icons: why duplicated

The user selected "icons in both bars" in the design review. Reason: at `scrollY = 0` the pinned bar covers the hero's icon row **pixel-for-pixel** (icons are the topmost `56dp` of the hero, sitting under the ~`64dp` static bar). So the visual at rest is a single icon strip. As you scroll, the hero's copy travels up with the content (never visible again above the bar), and the pinned copy stays put. There's no cost to the duplicate: both are the same Compose composable rendered twice.

**Don't** try to "hide" the hero icons at `scrollY = 0` and reveal them mid-scroll: any conditional visibility would cause a transient bare-top-edge flash during scroll, and the DOM spacer measurement would race with the visibility change. Keep them dumb.

---

## 5. Interaction with focus-tracking

`staticBarHeightPx` is *also* passed to `ConversationView.focusThresholdPx` — same value, same source. That threshold decides which message is "currently focused" (last header whose `translationY ≤ threshold`), which drives the prev/next arrow enabled state. Changing the pinned bar's height therefore also shifts the focus rule. This is intentional: a header that has slid under the bar shouldn't count as focused, since the user can't see it.

Before layout completes both values are `0`. `ConversationContainer` handles a zero threshold ("top of container"), and `AppBarTitleFadeProgress` returns `0f`. Nothing needs a "default until measured" fallback.

---

## 6. Common pitfalls

- **Don't read `LocalConfiguration` / `TopAppBarDefaults` height instead of measuring.** The whole point of `onSizeChanged` is to survive Material3 spec drifts and TopAppBar variant swaps. If you re-introduce a Dp constant here, the fade timing decouples from the actual bar.
- **Don't fire the fade from `AnimatedVisibility` / `animateFloatAsState`.** That was the previous design; user rejected it because the animation ran *after* the hero title disappeared, leaving a visible gap between "hero gone" and "pinned title arrived". The current design ties `progress` to `scrollY` directly — the gap is mathematically impossible.
- **Don't put state reads outside `graphicsLayer { }` on the hot path if you extend the effect.** `translationY = expression outside` recomposes; `graphicsLayer { translationY = expression }` doesn't measure/layout. Same trade-off as `Modifier.offset` vs `Modifier.graphicsLayer`.
- **Don't touch `bridgePageTopCss` handling.** The hero-overlay tracking already accounts for pinch atomicity (`ConversationContainer` — see `TOOLBAR_SMOOTHNESS_GUIDE.md §1`). The fade rides on top of that; it doesn't need its own pinch handling.
- **Don't remove the `remember(thread.id)` reset on `heroSubject*` state.** When switching threads, the new subject may be shorter/taller, and stale bounds would leave the pinned title mid-fade until the new hero paints. Resetting to `0` triggers the guard until the new geometry arrives.

---

## 7. Files at a glance

| File | Role |
|------|------|
| `ui/conversation/AppBarTitleFadeProgress.kt` | Pure math. Fully unit-tested. |
| `ui/conversation/LargeAppBarOverlay.kt` | Hero: reports subject bounds via `onSubjectBoundsChanged`. Icons duplicated. |
| `ui/conversation/ConversationOverlays.kt` | Wiring: forwards `onHeroSubjectBoundsChanged` from `APP_BAR` descriptor only. |
| `ui/screen/ConversationScreen.kt` | Owns `scrollY`, `heroSubject*Px`, `staticBarHeightPx` state; computes `titleFadeProgress`; renders `CompactAppBar` with `graphicsLayer` on the title. |

Tests: `app/src/test/java/com/alex/mailstubdetails/ui/conversation/AppBarTitleFadeProgressTest.kt` — guards + edge cases + linear interpolation.

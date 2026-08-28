# MailStub — WebView + Native Overlays: механика и принципы

Документ описывает архитектурный паттерн, реализованный в этом проекте, и служит справочником для интеграции того же механизма в другой продукт (мессенджер, читалка, комментарии, лента постов с HTML-контентом и т.д.).

Проект — Android-стаб почтового клиента: список тредов, экран разговора с HTML-письмами и native-оверлеями (шапки/футеры сообщений, большой app bar), и compose-экран с редактором Squire.js. Основная ценность — **механика единой прокрутки/зума WebView + плавающих над ним нативных вьюх**. Всё остальное (модель писем, mock-данные, стилизация) — обёртка вокруг этой механики.

---

## 1. Задача, которую решает паттерн

Нужно показать длинный HTML-контент (тело письма, статья, документ) вместе с нативным UI поверх него — так, чтобы **пользователь воспринимал это как одну поверхность**:

1. Скроллит пальцем в любой точке — двигается всё разом (HTML и native).
2. Пинчит — HTML-контент зумится через компоузитор WebView (векторно, без reflow), а native-оверлеи остаются в density-размере (не пикселизуются).
3. Native-оверлеи (шапки сообщений, кнопки reply, app bar) точно позиционированы относительно HTML — например, шапка сообщения зафиксирована ровно над его телом.

Тривиальные подходы, которые **не работают**:

| Подход | Проблема |
|---|---|
| `Column(verticalScroll) { ... WebView(fixed height) ... }` | Гонка высот — WebView сообщает `contentHeight` до окончательного layout; пинч ломает высоту; после зума видны пустые области. |
| `NestedScrollConnection` перекидывает скролл в WebView | Работает для скролла, но пинч-жесты и `getBoundingClientRect` не сходятся: native не знает, куда пропала часть контента. |
| Overlays через `AbsoluteLayout` над WebView с фиксированным Y | При скролле не двигаются вместе с DOM; при зуме позиция «уплывает». |

**Правильное решение**: WebView владеет всем скроллом. HTML содержит невидимые `<div>`-спейсеры, зарезервированные под каждый native-оверлей. Native-оверлеи лежат в родительском ViewGroup поверх WebView, их Y-позиция за каждый кадр вычисляется по данным от WebView (scrollY, scale + DOM-геометрия спейсеров). Паттерн взят из AOSP UnifiedEmail (`ConversationContainer.java`).

---

## 2. Компоненты и их роли

```
ui/conversation/
├─ ConversationView.kt            ← Compose-точка входа. AndroidView-обёртка над ConversationContainer.
├─ ConversationContainer.kt       ← Custom ViewGroup — «мозг». Держит WebView + N оверлеев.
├─ ConversationWebView.kt         ← WebView-подкласс. Прокидывает наружу scrollY/scale + client delegate.
├─ ConversationTemplateBuilder.kt ← Сериализация EmailThread → JSON-пейлоад для JS.
├─ ConversationOverlays.kt        ← Диспетчер OverlayDescriptor → Compose-composable оверлея.
├─ LargeAppBarOverlay.kt          ← Один из оверлеев: большой app bar над тредом.
├─ MessageHeaderOverlay.kt        ← Шапка сообщения (аватар, from, дата, кнопка expand).
└─ MessageFooterOverlay.kt        ← Футер сообщения (reply/reply-all/forward).

assets/
├─ conversation_template.html     ← Пустой HTML-скелет с <div id="conversation"> + подключением JS.
├─ conversation.js                ← Рендерит thread в DOM, репортит геометрию, слушает viewport.
└─ dompurify.min.js               ← Санитайзер (обязателен для Squire 2.x, полезен для тел писем).
```

**Разделение ответственности:**

- **`ConversationContainer`** — единственное место, где принимаются решения о позиционировании оверлеев. Все callback'и WebView и JS-моста приходят сюда.
- **`ConversationWebView`** — тонкий wrapper над `WebView`. Даёт `scrollListener` и `scaleListener`, кеширует `currentScale`/`initialScale`. Никакой логики позиционирования.
- **`conversation.js`** — единственное место, где мутируется DOM. Слушает `visualViewport` и репортит его состояние в Kotlin.
- **Compose-слой** (`ConversationView`, `ConversationScreen`) — знает про thread, expanded-ids, callbacks; строит список `OverlayDescriptor` и передаёт их в контейнер. Ничего не знает про пиксели.

---

## 3. Три пространства координат

Одно из самых частых мест путаницы. Держим в голове чётко.

| Пространство | Кто использует | Единицы | Пример |
|---|---|---|---|
| **CSS px** | JS, DOM (`getBoundingClientRect`, `scrollHeight`, `visualViewport.pageTop`) | CSS-пиксели | `topCss = 245.5` |
| **Device px** | Android View system, `View.translationY`, `WebView.scrollY`, `measuredHeight` | Физические пиксели устройства | `topPx = 736` |
| **Container-local px** | Layout ViewGroup (положение оверлея на экране относительно контейнера) | Device px | `translationY = topPx` (у нас контейнер = экран) |

**Переход между пространствами:**

```
devicePx = cssPx × effectiveScale
cssPx    = devicePx / effectiveScale

где effectiveScale = pinchFactor × initialScale
    initialScale   = плотность экрана (≈ Resources.displayMetrics.density на стандартном viewport)
    pinchFactor    = мультипликатор от пользовательского щипка (1.0 = без зума)
```

`initialScale` устанавливается **однократно** в `WebViewClient.onPageFinished` — WebView не файрит `onScaleChanged` для начальной density-скалы, только для последующих пинчей. Плюс во время первого render контент ещё не полностью улёгся, так что `view.scale` до `onPageFinished` даёт placeholder 1.0 → координаты сломаются, если использовать раньше времени.

---

## 4. Bridge-контракт JS ↔ Kotlin

Двунаправленный. Одиночный `@JavascriptInterface`-объект зарегистрирован под именем `"Bridge"`.

### 4.1 JS → Kotlin (вызывается из `conversation.js`)

| Метод | Когда вызывается | Что делает Kotlin |
|---|---|---|
| `Bridge.onReady()` | `window.load` после того, как загружен DOMPurify + скрипт | Флажок в `BridgeState.ready = true`, триггерит первый `syncWebView()` |
| `Bridge.onGeometry(payloadJson: String)` | После `renderThread` / `toggleExpanded` / `setSpacerHeight` / `resize` — через `scheduleMeasure` (double-rAF) | `ConversationContainer.onGeometryJson()` — обновляет `topCss`, `heightCss` каждого оверлея; пушит спейсер-высоты; репозиционирует |
| `Bridge.onViewport(scale: Float, pageTopCss: Float)` | На каждое событие `visualViewport.scroll` / `resize`, плюс fallback `window.scroll` | Обновляет `bridgeScale`, `bridgePageTopCss`; репозиционирует |

**Формат `onGeometry`:**

```json
{
  "contentHeight": 3450.5,
  "devicePixelRatio": 3,
  "overlays": [
    { "id": "app-bar",       "msgId": null,   "top": 0,     "height": 128, "expanded": true  },
    { "id": "header:msg1a",  "msgId": "msg1a","top": 128,   "height": 64,  "expanded": true  },
    { "id": "footer:msg1a",  "msgId": "msg1a","top": 1204,  "height": 48,  "expanded": true  },
    { "id": "header:msg1b",  "msgId": "msg1b","top": 1252,  "height": 64,  "expanded": false }
  ]
}
```

Все числа — в **CSS px**.

### 4.2 Kotlin → JS (через `WebView.evaluateJavascript`)

| Вызов | Когда | Что делает JS |
|---|---|---|
| `renderThread(payload)` | При смене `threadId` в `syncWebView` | Полный rebuild DOM: очищает `#conversation`, создаёт спейсеры + тела сообщений. Сбрасывает `messagesById`. |
| `toggleExpanded(msgId)` | При изменении `expandedIds` для того же треда | Точечная мутация: вставляет/удаляет `.msg-body` и `.msg-footer-spacer`. |
| `setSpacerHeight(overlayId, cssPx)` | Из `pushSpacerHeights` в контейнере после того, как оверлей смерил свою высоту | Устанавливает `style.height` спейсера. Триггерит `scheduleMeasure`. |
| `measurePositions()` | Не вызывается напрямую — вспомогательная функция, использованная через `scheduleMeasure` | Собирает `getBoundingClientRect()` всех `[data-overlay]` элементов и репортит через `Bridge.onGeometry`. |

**Пейлоады передаются base64-кодированными** — избегает всех головных болей с экранированием кавычек, переносов, `</script>` и т.д.:

```kotlin
val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
webView.evaluateJavascript("renderThread(JSON.parse(atob('$b64')))", null)
```

Для «безопасных» коротких значений (id оверлея, msg id) используется простое экранирование одинарной кавычки — работает, потому что id генерируются приложением, но **не использовать этот путь для контента из внешних источников**.

---

## 5. Модель скролла

Ключевой инвариант: **WebView владеет всей вертикальной прокруткой**.

- Никакого `Modifier.verticalScroll` вокруг WebView.
- Никакого `NestedScrollConnection`.
- Compose держит WebView через `AndroidView` с `Modifier.fillMaxSize()` — только это.

**Поток события скролла:**

```
Palец                                    Compose-стейт
 ↓                                       (для CompactAppBar swap threshold)
 dispatchTouchEvent (ConversationContainer)  ↑
 ↓ (может интерсептировать, см. §7)          │
 WebView compositor scrolls                  │
 ↓                                           │
 onScrollChanged(t, oldt)  ─── scrollY updated (device px) ┐
 ↓                                                          │
 scrollListener callback (в ConversationContainer.init)     │
 ├─ Если НЕ pinchActive: обновить bridgePageTopCss           │
 │                       = newY / effectiveScale             │
 ├─ positionOverlays()  ← синхронно, в том же кадре          │
 └─ Если НЕ pinchActive: onScrollChanged(newY) наружу ───────┘
```

**Почему синхронно, а не через `postOnAnimation`**: любой отложенный вызов означает, что HTML-контент отрисуется на новом `scrollY` кадром раньше, чем оверлей передвинется. Пользователь увидит один кадр рассинхрона — то самое «дёрганье».

**Почему во время пинча наружу не пробрасывается `onScrollChanged`**: компоузитор WebView за кадр пинча дёргает `scrollY` туда-сюда, удерживая фокальную точку. Пропускать эти транзиенты в CompactAppBar swap-логику — значит заставить бар мигать во время пинча. `endPinch()` руками фаерит один финальный `onScrollChanged(webView.scrollY)`.

---

## 6. Модель зума и спейсеров

### 6.1 Что зумится, что нет

- **HTML-контент** — зумится компоузитором WebView. Это **визуальный** зум: DOM не перерасчитывается (никакого reflow таблиц/изображений), просто битмап масштабируется. Так работает Gmail на Android.
- **Native-оверлеи** — **не зумятся**. Остаются в density-размере. Изнутри они — Compose-контент, `scaleX/scaleY = 1` жёстко.

Это осознанное решение. Если бы native-оверлеи зумились вместе с HTML, они бы пикселизовались или становились нечитаемо-мелкими. Пример из Gmail: шапка сообщения (аватар, имя, дата) остаётся крупной и чёткой на любом зуме, а тело письма зумится.

### 6.2 Спейсеры и pinch overshoot

CSS-высота спейсера рассчитывается как:

```
spacerCssPx = overlay.measuredHeight / initialScale
```

При зумe `P` (pinchFactor):
- Спейсер в DOM → `spacerCssPx × effectiveScale = spacerCssPx × P × initialScale = overlay.measuredHeight × P` device px.
- Оверлей → `overlay.measuredHeight` device px (фикс).

Спейсер вырос в `P` раз, оверлей не вырос. **`overlay.measuredHeight × (P-1)` device px «излишка»** должно куда-то деться. Иначе оверлей закроет только верхнюю часть спейсера, снизу останется пустая полоса.

### 6.3 Chain compression (компрессия цепочек)

Решение: когда в DOM подряд идут спейсеры без body-контента между ними (типичный кейс — стек шапок свёрнутых сообщений в треде), их излишки суммируются и «сваливаются» ниже цепочки.

Алгоритм в `positionOverlays()`:

```
для каждого оверлея по порядку в DOM:
  если предыдущий оверлей "прилегает" (gap в CSS < 0.5px):
    → топ = prevTop + prev.measuredHeight   (компрессия — сразу под предыдущим)
  иначе:
    → топ = топ по DOM                        (обычное позиционирование)
```

Излишек цепочки утекает вниз — либо в пустую область до следующего body, либо в пустое пространство после треда (пользователь его видит только если проскроллит вниз, что естественно).

**Что мы НЕ делаем**: не мутируем CSS-высоту спейсеров на каждый тик пинча. Пробовали — рейсит с раст-пайплайном компоузитора и вызывает видимое мерцание содержимого. Спейсеры имеют константную CSS-высоту (обновляется только при resize оверлея), а компрессия делается на native-стороне.

---

## 7. Позиционирование оверлеев — сердце системы

Единственная формула:

```
naturalTopPx = topCss × effectiveScale − bridgePageTopCss × effectiveScale
             = (topCss − bridgePageTopCss) × effectiveScale
```

Где:
- `topCss` — DOM-позиция верха спейсера в CSS px (репортится через `Bridge.onGeometry`).
- `effectiveScale` — `bridgeScale × initialScale`. Пинч-фактор из JS × density.
- `bridgePageTopCss` — текущая позиция viewport top в CSS px.

**Ключевой момент: `bridgePageTopCss` пишется двумя источниками.** Это то, что мы вылизывали в последних итерациях.

### 7.1 Два писателя `bridgePageTopCss`

**Писатель A — JS (`onViewportUpdate`)**. Срабатывает на события `visualViewport.scroll` / `resize`. Даёт **атомарную пару** `(scale, pageTopCss)` из одного JS-кадра. Незаменимо во время пинча, где `scale` меняется каждый кадр — использование не-атомарной пары даёт видимый дрейф оверлея относительно DOM.

**Писатель B — native `scrollListener` (`ConversationContainer.init`)**. Срабатывает на каждый `onScrollChanged` в WebView. Предсказывает `bridgePageTopCss = scrollY / effectiveScale` из свежего `scrollY`, используя последний известный `bridgeScale`.

**Правило переключения:**

| Состояние | Кто пишет | Почему |
|---|---|---|
| Не пинч, обычный скролл | Писатель B (предсказание из scrollY) | `scrollY` — целое число, обновляется в том же кадре, что и `onScrollChanged`. JS-события отстают на 1 кадр из-за биндера. Предсказание закрывает этот gap. |
| Пинч | Писатель A (JS-атомарно) | `scale` меняется каждый кадр. Комбинация «свежий `scrollY` + позапрошлый `scale`» даёт видимый дрейф. Атомарность важнее свежести. |
| Момент `endPinch` | Native рекалибровка | В `endPinch()` руками: `bridgePageTopCss = webView.scrollY / effectiveScale`. Это выравнивает bridge-значение под фактический настилы компоузитора → следующий `positionOverlays()` даст непрерывную позицию без скачка. |

Гард в `scrollListener`:

```kotlin
if (!pinchActive && bridgeHasValue) {
    val effectiveScale = bridgeScale * initial
    if (effectiveScale > 0f) {
        bridgePageTopCss = newY / effectiveScale
    }
}
```

**Почему одна формула, а не «переключение путей» (bridge-путь при пинче, native-путь при скролле)**: раньше было так с bias-decay на переходе. На границе пинч → скролл переключение путей создавало разовый мелкий скачок, потому что «bridge-target» и «native-target» отличались на 1-3 px из-за биндерного лага. Единая формула + перезапись значения одного и того же поля устраняет источник разрыва.

### 7.2 Полный алгоритм `positionOverlays()`

```
effectiveScale = (bridgeHasValue ? bridgeScale : currentScale/initialScale) × initialScale
scrollOffsetPx = bridgePageTopCss × effectiveScale

prev = null
prevTopPx = 0
для каждого оверлея o в DOM-порядке:
    naturalTopPx = o.topCss × effectiveScale − scrollOffsetPx

    если prev existisc и gap < 0.5 CSS px:
        topPx = prevTopPx + prev.measuredHeight   // chain compression
    иначе:
        topPx = naturalTopPx

    o.translationY = topPx                        // NB: sub-px, без округления
    o.visibility = (topPx + h > 0 && topPx < viewportH) ? VISIBLE : INVISIBLE
    prev = o
    prevTopPx = topPx
```

Про округление `translationY`: осознанно **не округляем** до целых пикселей. Rounding в комбинации с subpixel-скоростями скролла даёт ±1px jitter. Renderer сам снапнется на device pixels при отрисовке.

Про `visibility` — новый оверлей стартует с `positioned = false`, ставится в `INVISIBLE`, пока JS не сообщит его реальную DOM-позицию через `onGeometry`. Иначе он на один кадр отрисуется на `translationY = 0` поверх app bar — видимая вспышка при expand сообщения.

---

## 8. Spacer heights — жизненный цикл

```
[1] Оверлей добавлен → onMeasure ставит его measuredHeight
[2] onLayout → requestGeometryUpdate() → postOnAnimation → pushSpacerHeights()
[3] pushSpacerHeights: cssPx = measured / initialScale; сравниваем с lastSentSpacerCssPx
[4] Если отличается → webView.evaluateJavascript("setSpacerHeight('$id', $cssPx)", null)
[5] В JS: setSpacerHeight ставит style.height, вызывает scheduleMeasure (double-rAF)
[6] measurePositions → Bridge.onGeometry(payload) → applyGeometry на Kotlin
[7] applyGeometry обновляет topCss/heightCss оверлеев, вызывает pushSpacerHeights ЕЩЁ РАЗ
[8] На шаге [3] cssPx НЕ отличается → dedup, цикл разрывается
```

**Дедупликация `lastSentSpacerCssPx`** — обязательна, иначе JS ↔ native пинг-понг зациклится.

**Сброс `resetSpacerHeightCache()`** — вызывается перед `renderThread(...)`, потому что DOM полностью пересобирается и предыдущие `style.height` теряются.

**Почему через `postOnAnimation` (`requestGeometryUpdate`), а не сразу**: чтобы за один кадр не улетело три `setSpacerHeight` вызова из разных источников (layout, scroll, scale) — coalesce до одного.

**Почему не пушим на пинче**: `style.height` изменение = layout invalidation = compositor reflow. За кадр пинча компоузитор уже занят раст-пайплайном; ещё один reflow на нём даёт видимое мерцание. Спейсеры имеют постоянную CSS-высоту, а излишки компенсируются chain compression на native (см. §6).

---

## 9. Touch delegation — как разрулены жесты

Оверлеи лежат поверх WebView. Пользователь может тапнуть по кнопке reply (Compose), а может пальцем начать скролл в области под шапкой сообщения — оба должны работать.

**Правила в `onInterceptTouchEvent`:**

```
ACTION_DOWN:
    interceptForCurrentGesture = isTouchOnOverlay(x, y)?
    // Если тап на голом WebView — не интерсептим, WebView сам разберётся

ACTION_POINTER_DOWN (второй палец):
    Если interceptForCurrentGesture И не gestureClaimed:
        claimGesture() → синтезируем ACTION_DOWN на WebView.dispatchTouchEvent
        → WebView видит пинч с самого начала, даже если первый палец был на оверлее

ACTION_MOVE (первый палец):
    Если движение по Y > touchSlop и dy > dx (вертикальный дрейф):
        claimGesture() → передаём WebView для скролла

ACTION_UP/CANCEL:
    сбрасываем флаги
```

**Пинч-стейт (`pinchActive`) отслеживается в `dispatchTouchEvent`, не в `onInterceptTouchEvent`**. После того как мы интерсептнули жест, `onInterceptTouchEvent` уже не вызывается для этой цепочки событий — но пользователь может воткнуть второй палец посреди скролла. `dispatchTouchEvent` всегда видит все POINTER_DOWN/UP → `pinchActive` актуален.

**После `endPinch`** — вызываем `onScrollChanged(webView.scrollY)` наружу, чтобы обновилась логика CompactAppBar swap.

---

## 10. Рендеринг и жизненный цикл треда

### 10.1 Инициализация

```
ConversationView (Compose) 
  → AndroidView { factory = { ConversationContainer(ctx) } }
     → ConversationContainer.init:
        - addView(webView, MATCH_PARENT)
        - webView.scrollListener = ... (см. §5)
        - webView.scaleListener = ...
     → addJavascriptInterface(Bridge, "Bridge")
     → webView.loadUrl("file:///android_asset/conversation_template.html")

conversation.js загружается:
  - window.load → Bridge.onReady()
    → Kotlin: BridgeState.ready = true → syncWebView(...)
```

### 10.2 Смена треда

```
syncWebView:
  если thread.id != lastSentThreadId:
    resetSpacerHeightCache()     // DOM пересобирается, кеш теряется
    renderThread(payload)        // JS перерисовывает всё
    lastSentThreadId = thread.id
```

### 10.3 Expand / collapse сообщения

```
пользователь тапает на шапку → OverlaySlot.onToggleMessage(msgId) →
Compose-state expandedIds меняется → recomposition → syncWebView:
  если expandedIds != lastSentExpandedIds:
    для каждого добавленного/удалённого id:
      toggleExpanded(id)         // JS точечно вставляет/удаляет .msg-body
    lastSentExpandedIds = expanded
```

Не делаем `renderThread` при expand/collapse — это порвало бы скролл-позицию. Точечная мутация в JS сохраняет её.

### 10.4 Descriptor cache

В `ConversationView.kt` кешируются `ComposeView`-ы оверлеев по id, чтобы `setContent` не вызывался лишний раз (это перезапускает композицию). Гард: `if (lastRenderedDescriptors[d.id] != d) { setContent(...) }`. Иначе при пинче, где `syncWebView` может тригериться, шли бы constantly re-composition оверлеев.

---

## 11. Ключевые gotcha'и и отвергнутые подходы

### 11.1 Что мы пробовали и отвергли

| Подход | Почему не работает |
|---|---|
| `Column + verticalScroll` + WebView как фикс-высота (старый `SquireWebView`-стиль) | Гонка высот, пустые области после пинча — родная проблема, ради которой всё это писалось. Оставили только для compose-экрана, где нет длинного HTML и нет пинча. |
| `bridgePageTopCss` как единственный источник scrollTop | Отстаёт на 1 кадр от native scrollY во время обычного скролла → видимый ~1-3 px дрейф оверлея относительно контента. |
| `webView.scrollY` как единственный источник scrollTop | Во время пинча `bridgeScale` (JS) и `scrollY` (native) приходят из разных кадров → дрейф внутри пинча. |
| Переключение путей + bias decay на переходе (variant B) | На границе пинч → скролл остаётся разовый скачок на 1-3 px, потому что «bridge-target» ≠ «native-target» из-за биндерного лага. |
| Мутация `style.height` спейсеров на каждый тик пинча | Компоузитор WebView flicker'ит содержимое — reflow конкурирует с raster pipeline. |
| Округление `translationY` до целых px | Дает ±1px jitter на низких скоростях скролла из-за субпиксельных значений scale. |
| `WebViewClient.onScaleChanged` как источник scale в реальном времени | Разреженный, лагает на 1-2 кадра. Только для «настил после того, как компоузитор устоялся». |

### 11.2 Что обязательно нужно помнить

- **`initialScale` доступен только с `onPageFinished`**. До этого использовать нельзя, `pushSpacerHeights` пропускает работу если `initial <= 0`.
- **JS-события приходят на binder-thread**. Всегда `post { ... }` в main. Наш код это делает в `onGeometryJson`, `onViewportUpdate`, `onReady`.
- **DOMPurify обязателен для Squire 2.x** — `setHTML()` вызывает `sanitizeToDOMFragment()` и если санитайзер не передан, молча выкидывает контент.
- **Base64 для `evaluateJavascript` пейлоадов**. Всегда. Никаких `\'` ручных экранирований для реальных данных.
- **`bridgeHasValue` guard**. Первый рендер до JS-события не должен упасть — фолбэк на `webView.currentScale / initialScale`.
- **`postOnAnimation` для «coalesce» апдейтов**. Layout + scroll + AppBar swap в одном кадре → одна позиция overlay, не три конкурирующих.

---

## 12. Как переиспользовать в другом продукте

Паттерн ортогонален email-специфике. Ниже — mapping на другие сценарии.

### 12.1 Что заменить

| Домен исходного проекта | Что менять |
|---|---|
| `EmailThread` / `EmailMessage` | Ваша модель данных треда/списка блоков контента. |
| `OverlayDescriptor` / `OverlayKind.APP_BAR/MESSAGE_HEADER/MESSAGE_FOOTER` | Ваш перечень типов оверлеев (например: пост, комментарий, заголовок статьи, ad-block). |
| `ConversationTemplateBuilder` | Строит JSON с массивом `messages` (или `posts`, `blocks`) → каждый идёт как `msg.id, msg.html, msg.expanded`. |
| `conversation.js` — функция `appendMessage` | Логика построения DOM (какие оверлеи-спейсеры вокруг каждого блока контента). |
| `ConversationOverlays.kt` — `ConversationOverlaySlot` | Рендер конкретных Compose-оверлеев для каждого типа. |

### 12.2 Что оставить без изменений

- `ConversationContainer.kt` — механика позиционирования, chain compression, touch delegation, синхронизация pageTopCss. Универсальна.
- `ConversationWebView.kt` — прокидывание scrollListener/scaleListener, кеширование scale.
- Bridge-контракт `onGeometry` / `onViewport` / `onReady` — универсален.
- Механизм spacer heights (`pushSpacerHeights` + `setSpacerHeight` + dedup) — универсален.

### 12.3 Обязательный чеклист интеграции

1. **HTML template**: обязателен пустой контейнер с `<div id="conversation">` (можно назвать иначе, поправить в JS), подключён `conversation.js`, санитайзер (если рендерите untrusted HTML).
2. **Meta viewport**: `<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes, minimum-scale=0.25, maximum-scale=5.0">` — без этого пинч не будет работать корректно.
3. **CSS для спейсеров**: `.overlay-spacer { height: 128px; }` (или сколько нужно как default). Точная высота потом придёт через `setSpacerHeight`.
4. **`ConversationContainer.setOverlays(items)`** — вызывать при каждом изменении набора оверлеев. Он сам разберётся, что добавилось, что удалилось.
5. **Compose-обёртка** (аналог `ConversationView`): передавать через `AndroidView`, обрабатывать `onScrollChanged` наружу для внешней логики (например, скрывать/показывать collapsed-header при глубоком скролле).
6. **WebView settings**: обязательно `javaScriptEnabled=true`, `domStorageEnabled=true`, `setSupportZoom(true)`, `builtInZoomControls=true`, `displayZoomControls=false`, `useWideViewPort=false`, `loadWithOverviewMode=false`. Именно эта комбинация даёт «Gmail-style» компоузиторный зум.
7. **Разрешения**: `INTERNET` только если контент подтягивает ресурсы извне. В идеале бандлить санитайзер локально.

### 12.4 Что уже сделано в этом коде, на что смотреть при переиспользовании

- **WebView-утечки закрыты** — `SquireWebViewContainer` и `ConversationView` в своих `AndroidView` передают `onRelease`, который снимает `Bridge` через `removeJavascriptInterface`, детачит вьюху и вызывает `WebView.destroy()`. При копировании паттерна не забудьте перенести `onRelease` целиком — без него утечка активити гарантирована.
- **Внешние ссылки открываются системным браузером** — `ConversationWebView.shouldOverrideUrlLoading` роутит `http/https/mailto/tel/sms/geo` через `Intent.ACTION_VIEW`. Если в вашем продукте нужен in-app browser — прокиньте свой делегат через `clientDelegate` и верните `true` там, где хотите перехватить (см. код).
- **`allowUniversalAccessFromFileURLs` не включается** — в `SquireWebView` эта строка удалена. Squire бандлится локально в `assets/squire.js` (см. ниже), кросс-оригин-XHR не нужен. Не включайте её обратно без веских причин: в паре с любым `@JavascriptInterface` это RCE-канал при компрометации любого подключаемого скрипта.
- **Squire.js бандлится локально** в `assets/squire.js` (минифицированная сборка `squire-rte@2.3.0`). Никакой CDN-зависимости, работает офлайн, нет supply-chain риска. При обновлении версии — просто перекачайте файл.

Что осталось нерешённым (при переиспользовании — учитывать самим):
- **Dark mode**: HTML-стили в `conversation_template.html` и `squire_editor.html` захардкожены светлыми. Для реакции на системную тему — `@media (prefers-color-scheme: dark)` в CSS + синхронизация через Bridge (передать `isDark` из Kotlin в JS одним вызовом).
- **Deprecated `escape/unescape`** в `squire_editor.html` (функция `setHtmlBase64`) — заменить на `TextDecoder`.
- **Тесты** в неправильном пакете (`com.example.myapplication` вместо `com.alex.mailstubdetails`).
- **Release-сборка** без signing config / R8 / proguard-rules для анонимных `@JavascriptInterface`.

---

## 13. Диагностика и отладка

### 13.1 Быстрые sanity-check'и

Если оверлей «не там»:
1. Включить debug outline: `<body class="debug-spacers">` в HTML → красная пунктирная рамка вокруг спейсеров, видно где реально DOM их разместил.
2. Проверить, что `initialScale > 0f` (через логи в `pushSpacerHeights`). Если 0 — `onPageFinished` ещё не сработал.
3. Проверить `bridgeHasValue` — если `false`, JS вообще не отвечает (проверить, что скрипт загрузился без ошибок).
4. Проверить, что `topCss` в `onGeometry`-payload разумный (растёт монотонно, соответствует ожидаемым позициям спейсеров).

### 13.2 «Оверлей отстаёт от контента на N px при скролле»

- Если N постоянный: биндерный лаг + промах с `bridgePageTopCss`. Проверьте, что `scrollListener` действительно обновляет `bridgePageTopCss` при `!pinchActive`.
- Если N растёт по мере скролла: `effectiveScale` неправильно посчитан. Скорее всего `initialScale` не установлен, и вы делите на 1 вместо density.

### 13.3 «Мерцание HTML-контента при пинче»

- Смотреть, не вызывается ли `setSpacerHeight` в реакции на пинч (`onScaleChanged` / `onViewport`). Не должно.
- Проверить, что overlay `scaleX/Y = 1` жёстко в `positionOverlays`.

### 13.4 «Скачок оверлея при отпускании пальца после пинча»

- Убедиться, что `endPinch()` вызывает рекалибровку `bridgePageTopCss = webView.scrollY / effectiveScale`. Без этого — источники pageTopCss рассинхронизированы на границе.
- Если скачок остался — вероятно, компоузитор WebView сам двигает `scrollY` на 1-2 px в момент отпускания (settling). Это фундаментально; workaround — задержать первый `positionOverlays` в `endPinch` на 1 кадр через `postOnAnimation`, дать компоузитору устояться.

---

## 14. Итог

**Один WebView владеет скроллом. Native-оверлеи следуют за DOM-спейсерами через `translationY`. Позиция считается по одной формуле — разница только в том, как поддерживается свежесть входного значения `pageTopCss`.**

Все сложности сводятся к согласованию трёх независимых сигналов:
1. Native `scrollY` (свежий, целый, но не знает про scale).
2. JS `visualViewport.pageTop` + `scale` (атомарные, но лагают на 1 кадр).
3. DOM-позиции спейсеров (`getBoundingClientRect().top`) — стабильны между reflow'ами.

Правильный порядок приоритизации:
- Для scroll — native `scrollY` (через предсказание `pageTopCss`).
- Для scale — JS `bridgeScale`.
- Для геометрии оверлеев — JS `getBoundingClientRect` через `onGeometry`.

Всё остальное — bookkeeping.

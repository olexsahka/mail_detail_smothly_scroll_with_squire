/*
 * conversation.js — DOM management + native bridge for the ConversationContainer.
 *
 * Owned by ConversationWebView.kt (Kotlin). This script:
 *   1. Renders the thread's HTML into #conversation.
 *   2. Reports overlay spacer positions to native on layout / scale / DOM changes.
 *   3. Toggles message expand/collapse on native command.
 *
 * Bridge contract (JS -> Kotlin, injected as `Bridge`):
 *   Bridge.onReady()
 *   Bridge.onGeometry(jsonPayload)
 *   Bridge.onViewport(scale, pageTopCss)  — realtime pinch/scroll from compositor
 *
 * Native -> JS (invoked via WebView.evaluateJavascript):
 *   renderThread(jsonPayload)
 *   toggleExpanded(msgId)
 *   setSpacerHeight(overlayId, cssPx)
 *   measurePositions()
 */

(function () {
    'use strict';

    /* ── State ─────────────────────────────────────────────────────────── */

    // Cache of the last-rendered messages, keyed by msg id.
    // Kept so toggleExpanded can rebuild a body without a full re-render.
    var messagesById = Object.create(null);
    var messageOrder = [];

    var measureScheduled = false;

    /* ── DOMPurify: block remote images ────────────────────────────────── */

    if (window.DOMPurify) {
        DOMPurify.addHook('uponSanitizeAttribute', function (node, data) {
            if (node.nodeName === 'IMG' && data.attrName === 'src') {
                var v = data.attrValue || '';
                if (/^https?:/i.test(v)) {
                    // Strip the src; CSS renders a placeholder.
                    node.setAttribute('data-blocked', v);
                    data.keepAttr = false;
                }
            }
        });
    }

    function sanitize(html) {
        if (window.DOMPurify) {
            return DOMPurify.sanitize(html, {
                RETURN_DOM_FRAGMENT: true,
                FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed'],
                FORBID_ATTR: ['onclick', 'onload', 'onerror']
            });
        }
        // Fallback (should never happen in prod — DOMPurify is bundled)
        var d = document.createElement('div');
        d.textContent = html;
        return d;
    }

    /* ── Rendering ─────────────────────────────────────────────────────── */

    // atob → Latin-1 binary string; the escape/decodeURIComponent hack
    // reinterprets those bytes as UTF-8 so Cyrillic/emoji survive intact.
    // Same pattern setMessageLoaded already uses for the lazy-body path.
    function b64ToUtf8(b64) {
        return decodeURIComponent(escape(atob(b64)));
    }

    function renderThread(payloadOrB64) {
        try {
            var data;
            if (typeof payloadOrB64 === 'string') {
                // Kotlin path: base64-encoded UTF-8 JSON. Legacy fallback:
                // if the caller passed raw JSON (starts with '{'), parse it
                // directly.
                var raw = payloadOrB64.charAt(0) === '{'
                    ? payloadOrB64
                    : b64ToUtf8(payloadOrB64);
                data = JSON.parse(raw);
            } else {
                data = payloadOrB64;
            }

            var root = document.getElementById('conversation');
            root.innerHTML = '';
            messagesById = Object.create(null);
            messageOrder = [];

            // Large AppBar spacer — always at top, always present.
            var appBar = document.createElement('div');
            appBar.className = 'app-bar-spacer';
            appBar.dataset.overlay = 'app-bar';
            root.appendChild(appBar);

            (data.messages || []).forEach(function (msg) {
                messagesById[msg.id] = msg;
                messageOrder.push(msg.id);
                appendMessage(root, msg);
            });

            scheduleMeasure();
        } catch (e) {
            console.error('renderThread error', e);
        }
    }

    // ── Body builders ──────────────────────────────────────────────────
    //
    // A message body has two DOM shapes, chosen by `msg.loaded`:
    //   loaded=false → an empty spacer div with `data-overlay="body:id"`
    //                  so a native shimmer Compose overlay is positioned
    //                  above it (like message headers/footers).
    //   loaded=true  → sanitized HTML content (no overlay).
    //
    // On load completion, setMessageLoaded() replaces the spacer with
    // the real content in place.

    function buildLoaderSpacer(msgId) {
        var el = document.createElement('div');
        el.className = 'msg-body-spacer';
        el.dataset.overlay = 'body:' + msgId;
        el.dataset.msgId = msgId;
        return el;
    }

    function buildLoadedBody(msg) {
        var body = document.createElement('div');
        body.className = 'msg-body';
        body.dataset.msgId = msg.id;
        // Content wrapper for fixLayout: it walks/mutates children of
        // .mail-scale-wrapper (transform tables, scale images) and applies
        // CSS `zoom` on the wrapper as a last-resort down-scaling. Keeping
        // the wrapper separate from .msg-body lets msg-body's padding /
        // measurement stay stable while wrapper geometry changes.
        var scaleWrapper = document.createElement('div');
        scaleWrapper.className = 'mail-scale-wrapper';
        scaleWrapper.appendChild(sanitize(msg.html || ''));
        body.appendChild(scaleWrapper);
        return body;
    }

    function buildBody(msg) {
        return msg.loaded ? buildLoadedBody(msg) : buildLoaderSpacer(msg.id);
    }

    function appendMessage(root, msg) {
        var header = document.createElement('div');
        header.className = 'msg-header-spacer';
        header.dataset.overlay = 'header:' + msg.id;
        header.dataset.msgId = msg.id;
        header.dataset.expanded = String(!!msg.expanded);
        root.appendChild(header);

        if (msg.expanded) {
            var body = buildBody(msg);
            root.appendChild(body);

            var footer = document.createElement('div');
            footer.className = 'msg-footer-spacer';
            footer.dataset.overlay = 'footer:' + msg.id;
            footer.dataset.msgId = msg.id;
            root.appendChild(footer);

            // Preloaded, pre-expanded messages skip both setMessageLoaded and
            // toggleExpanded — kick fixLayout here so wide content in the
            // first-open body is scaled instead of clipping.
            if (msg.loaded && typeof window.formatMessageBody === 'function') {
                window.formatMessageBody(body);
            }
        }
    }

    // Body expand/collapse is a SNAP, deliberately not a CSS transition.
    //
    // We tried transitioning `.msg-body { height }` and it stuttered visibly
    // on threads with any real content. Reason: a message body owns a full
    // sanitized HTML subtree (tables, images, .mail-scale-wrapper with
    // possible zoom, sanitized paragraphs). Each animation frame the browser:
    //   • reflows the entire body subtree (contain: layout only isolates
    //     internal propagation — siblings & document scroll height still
    //     recompute),
    //   • clips content via overflow: hidden (paint cost proportional to
    //     body width × delta height),
    //   • may re-fire fixLayout via queued image-load listeners.
    // Neither height nor overflow are compositor-only properties, so this
    // all lands on the browser main thread. On heavy bodies the transition
    // itself drops frames — which reads as "the animation freezes".
    //
    // Header details expand/collapse stays animated (see animateSpacerHeight)
    // because those spacers are empty divs — animating their height is one
    // reflow of one empty box per frame, cheap enough.
    function toggleExpanded(msgId) {
        var msg = messagesById[msgId];
        if (!msg) return;
        msg.expanded = !msg.expanded;

        var header = document.querySelector(
            '.msg-header-spacer[data-msg-id="' + msgId + '"]'
        );
        if (!header) return;
        header.dataset.expanded = String(msg.expanded);

        if (msg.expanded) {
            var body = buildBody(msg);

            var footer = document.createElement('div');
            footer.className = 'msg-footer-spacer';
            footer.dataset.overlay = 'footer:' + msgId;
            footer.dataset.msgId = msgId;

            var parent = header.parentNode;
            var afterHeader = header.nextSibling;
            parent.insertBefore(body, afterHeader);
            parent.insertBefore(footer, body.nextSibling);

            if (msg.loaded && typeof window.formatMessageBody === 'function') {
                // fixLayout will scheduleMeasure once its layout pass settles.
                window.formatMessageBody(body);
            } else {
                scheduleMeasure();
            }
        } else {
            var body = document.querySelector(
                '.msg-body[data-msg-id="' + msgId + '"]'
            );
            var footer = document.querySelector(
                '.msg-footer-spacer[data-msg-id="' + msgId + '"]'
            );
            if (body) body.parentNode.removeChild(body);
            if (footer) footer.parentNode.removeChild(footer);
            scheduleMeasure();
        }
    }

    /**
     * Native-triggered: fake "network fetch" for a message body has
     * completed. Swap the shimmer skeleton (if currently visible) for
     * the real sanitized HTML in-place. If the message was collapsed
     * before load finished, we only update the cache — the next
     * toggleExpanded() will render straight to loaded content with no
     * skeleton flash.
     *
     * htmlBase64 is UTF-8 base64 to avoid all JS string escaping edge
     * cases (single quotes, backslashes, "</script>" in email bodies).
     */
    function setMessageLoaded(msgId, htmlBase64) {
        var msg = messagesById[msgId];
        if (!msg) return;
        try {
            msg.html = decodeURIComponent(escape(atob(htmlBase64)));
        } catch (e) {
            msg.html = '';
        }
        msg.loaded = true;

        // Locate the loader spacer for this message (if the message is
        // still expanded — otherwise nothing to swap, cache alone is
        // enough for the next expand).
        var spacer = document.querySelector(
            '[data-overlay="body:' + msgId + '"]'
        );
        if (!spacer) return;

        var body = buildLoadedBody(msg);
        spacer.parentNode.replaceChild(body, spacer);
        if (typeof window.formatMessageBody === 'function') {
            // See toggleExpanded — fixLayout owns the follow-up scheduleMeasure
            // to avoid a two-phase overlay jump (pre-scale → post-scale).
            window.formatMessageBody(body);
        } else {
            scheduleMeasure();
        }
    }

    /* ── Spacer height sync (native -> HTML) ───────────────────────────── */

    // Called by native once an overlay has been measured on the Compose side.
    // We do NOT rewrite this on pinch — mutating the DOM every viewport tick
    // races the WebView compositor's raster pipeline and produces visible
    // content flicker. Instead, native takes care of visually collapsing
    // the per-spacer pinch overshoot via a chain-walk in positionOverlays()
    // — see ConversationContainer.kt.
    //
    // Smooth intentional expand/collapse: when the pushed height differs from
    // the current one by more than a small drift threshold (i.e., the user
    // opened the "details" chevron on a message header, not just a pinch
    // adjustment), we hand the animation off to the browser via a CSS
    // `transition: height` class. This is dramatically smoother than trying
    // to animate on the Compose side (which would push 13 evaluateJavascript
    // calls at 60 fps over 220ms — each one triggering a full reflow and
    // fighting the bridge latency). Compose overlay jumps to final height
    // instantly; only body content beneath the spacer animates.
    var HEIGHT_ANIMATE_THRESHOLD_PX = 20;
    var HEIGHT_ANIMATE_DURATION_MS = 220;

    function setSpacerHeight(overlayId, cssPx) {
        var el = document.querySelector('[data-overlay="' + overlayId + '"]');
        if (!el) return;

        var oldHeight = parseFloat(el.style.height) || 0;
        var isHeaderOrFooter = /^(header|footer):/.test(overlayId);
        var significantChange = oldHeight > 0
            && Math.abs(cssPx - oldHeight) >= HEIGHT_ANIMATE_THRESHOLD_PX;

        if (isHeaderOrFooter && significantChange) {
            animateSpacerHeight(el, cssPx);
        } else {
            el.style.height = cssPx + 'px';
            scheduleMeasure();
        }
    }

    // Enable CSS transition on this spacer, set the target height, then keep
    // measurePositions() flowing until the transition ends so native
    // positioning of *other* overlays (chained neighbours, body-below)
    // tracks the animation smoothly instead of freezing at pre-animation
    // geometry.
    //
    // We drive updates via scheduleMeasure() (not direct measurePositions
    // per frame) so the built-in double-rAF coalescing throttles the actual
    // work to ~30 fps effective. At 60 fps we hammered the main thread with
    // getBoundingClientRect + JSON + IPC every frame → CSS transition
    // started dropping frames of its own, and the animation looked frozen.
    function animateSpacerHeight(el, cssPx) {
        el.classList.add('animating-height');
        el.style.height = cssPx + 'px';

        var deadlineTs = performance.now() + HEIGHT_ANIMATE_DURATION_MS + 80;
        var stopped = false;

        function tick() {
            if (stopped) return;
            scheduleMeasure();
            if (performance.now() < deadlineTs) {
                requestAnimationFrame(tick);
            }
        }
        requestAnimationFrame(tick);

        // transitionend is authoritative — if it fires early (e.g., browser
        // decided to snap), stop the rAF pump; if it never fires (spacer
        // detached mid-animation), the deadlineTs fallback still stops us.
        function onEnd(ev) {
            if (ev.propertyName !== 'height') return;
            el.removeEventListener('transitionend', onEnd);
            el.classList.remove('animating-height');
            stopped = true;
            scheduleMeasure();
        }
        el.addEventListener('transitionend', onEnd);
    }

    /* ── Measure & report ──────────────────────────────────────────────── */

    function measurePositions() {
        var spacers = document.querySelectorAll('[data-overlay]');
        var overlays = [];
        for (var i = 0; i < spacers.length; i++) {
            var el = spacers[i];
            var rect = el.getBoundingClientRect();
            overlays.push({
                id: el.dataset.overlay,
                msgId: el.dataset.msgId || null,
                top: rect.top + window.scrollY,     // CSS px, document-relative
                height: rect.height,                 // CSS px
                expanded: el.dataset.expanded === 'true'
            });
        }

        var payload = JSON.stringify({
            contentHeight: Math.max(
                document.body.scrollHeight,
                document.documentElement.scrollHeight
            ),
            devicePixelRatio: window.devicePixelRatio || 1,
            overlays: overlays
        });

        if (window.Bridge && typeof Bridge.onGeometry === 'function') {
            Bridge.onGeometry(payload);
        }
    }

    function scheduleMeasure() {
        if (measureScheduled) return;
        measureScheduled = true;
        // Double rAF: first frame commits DOM mutations, second frame reads layout.
        requestAnimationFrame(function () {
            requestAnimationFrame(function () {
                measureScheduled = false;
                measurePositions();
            });
        });
    }

    /* ── Public API (called by native via evaluateJavascript) ──────────── */

    window.renderThread = renderThread;
    window.toggleExpanded = toggleExpanded;
    window.setSpacerHeight = setSpacerHeight;
    window.setMessageLoaded = setMessageLoaded;
    window.measurePositions = measurePositions;
    // Exposed so fixLayout.js can request a fresh geometry report after it
    // finishes mutating message-body DOM (wrapping tables, scaling images).
    window.scheduleMeasure = scheduleMeasure;

    /* ── Realtime viewport reporter (visualViewport) ───────────────────── */

    // Push actual compositor scale + scroll offset to native every time the
    // visual viewport changes. Fires per frame during pinch, so overlays can
    // follow the DOM without waiting for WebViewClient.onScaleChanged (which
    // is sparse and lags the compositor by 1-2 frames). Layout-viewport
    // scrolls also arrive here via the window 'scroll' fallback for WebView
    // implementations that don't dispatch visualViewport 'scroll' when only
    // the layout viewport moves.
    var vv = window.visualViewport;
    function reportViewport() {
        if (!window.Bridge || typeof Bridge.onViewport !== 'function') return;
        var scale = vv ? vv.scale : 1;
        var pageTop = vv ? vv.pageTop : (window.scrollY || 0);
        Bridge.onViewport(scale, pageTop);
    }
    if (vv) {
        vv.addEventListener('scroll', reportViewport);
        vv.addEventListener('resize', reportViewport);
    }
    window.addEventListener('scroll', reportViewport, { passive: true });

    /* ── Lifecycle ─────────────────────────────────────────────────────── */

    window.addEventListener('resize', scheduleMeasure);

    // Signal readiness once DOMPurify and this script have both loaded.
    window.addEventListener('load', function () {
        reportViewport();
        if (window.Bridge && typeof Bridge.onReady === 'function') {
            Bridge.onReady();
        }
    });
})();

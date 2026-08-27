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

    function renderThread(payloadJson) {
        try {
            var data = typeof payloadJson === 'string'
                ? JSON.parse(payloadJson) : payloadJson;

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

    function appendMessage(root, msg) {
        var header = document.createElement('div');
        header.className = 'msg-header-spacer';
        header.dataset.overlay = 'header:' + msg.id;
        header.dataset.msgId = msg.id;
        header.dataset.expanded = String(!!msg.expanded);
        root.appendChild(header);

        if (msg.expanded) {
            var body = document.createElement('div');
            body.className = 'msg-body';
            body.dataset.msgId = msg.id;
            body.appendChild(sanitize(msg.html || ''));
            root.appendChild(body);

            var footer = document.createElement('div');
            footer.className = 'msg-footer-spacer';
            footer.dataset.overlay = 'footer:' + msg.id;
            footer.dataset.msgId = msg.id;
            root.appendChild(footer);
        }
    }

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
            // Insert body + footer immediately after the header spacer.
            var body = document.createElement('div');
            body.className = 'msg-body';
            body.dataset.msgId = msgId;
            body.appendChild(sanitize(msg.html || ''));

            var footer = document.createElement('div');
            footer.className = 'msg-footer-spacer';
            footer.dataset.overlay = 'footer:' + msgId;
            footer.dataset.msgId = msgId;

            var parent = header.parentNode;
            var afterHeader = header.nextSibling;
            parent.insertBefore(body, afterHeader);
            parent.insertBefore(footer, body.nextSibling);
        } else {
            var body = document.querySelector(
                '.msg-body[data-msg-id="' + msgId + '"]'
            );
            var footer = document.querySelector(
                '.msg-footer-spacer[data-msg-id="' + msgId + '"]'
            );
            if (body) body.parentNode.removeChild(body);
            if (footer) footer.parentNode.removeChild(footer);
        }
        scheduleMeasure();
    }

    /* ── Spacer height sync (native -> HTML) ───────────────────────────── */

    // Called by native once an overlay has been measured on the Compose side.
    function setSpacerHeight(overlayId, cssPx) {
        var el = document.querySelector('[data-overlay="' + overlayId + '"]');
        if (!el) return;
        el.style.height = cssPx + 'px';
        scheduleMeasure();
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
    window.measurePositions = measurePositions;

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

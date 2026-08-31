/*
 * fixLayout.js — подгонка широкого email-контента под ширину экрана.
 *
 * Портировано из внешнего клиента; переделано под per-message scope:
 *   • formatMessageBody(msgBodyEl) — работает внутри одного .msg-body,
 *     а не всего document.body.
 *   • ищет .mail-scale-wrapper внутри переданного scope; общего
 *     document.getElementById('mail-scale-wrapper') больше нет.
 *   • per-scope state (timeout, флаг первого вызова, horizontalBounds)
 *     хранится в WeakMap, чтобы параллельные вызовы не гонялись.
 *   • по завершении layout зовёт window.scheduleMeasure (экспортируется
 *     из conversation.js), чтобы нативный мост пересчитал позиции
 *     оверлеев после мутаций DOM.
 */
(function () {
    'use strict';

    const WRAPPER_ELEMENT_CLASS = "span-scaling-wrapper";
    const MIN_RELAYOUT_INTERVAL = 300;
    const MAX_DOCUMENT_WIDTH_TO_TRANSFORM = 3000;

    const scopeState = new WeakMap();
    const listenedImages = new WeakSet();
    let resizeTimeoutId = null;

    function getState(scopeEl) {
        let s = scopeState.get(scopeEl);
        if (!s) {
            s = { timeoutId: null, firstCall: true, horizontalBounds: null };
            scopeState.set(scopeEl, s);
        }
        return s;
    }

    function formatMessageBody(scopeEl) {
        if (!scopeEl) return;
        const rect = scopeEl.getBoundingClientRect();
        // If the body isn't measured yet (rect.width == 0, e.g., detached or
        // display:none), horizontalBounds.remaining would be ≤ 0 and the
        // scale factor Infinity — collapsing content to zoom=0. Bail; the
        // next visible relayout (resize handler / caller-triggered) will
        // pick this body up.
        if (rect.width <= 0) return;
        const state = getState(scopeEl);
        const cs = window.getComputedStyle(scopeEl);
        const paddingLeft = parseFloat(cs.paddingLeft) || 0;
        const paddingRight = parseFloat(cs.paddingRight) || 0;
        state.horizontalBounds = {
            left: rect.left + paddingLeft,
            right: rect.right - paddingRight,
            remaining: rect.width - paddingLeft - paddingRight
        };
        setupImageLoadListeners(scopeEl, state);
        requestLayout(scopeEl, state);
    }

    function layout(scopeEl, state) {
        // scopeEl may have been removed (toggleExpanded → collapse) between
        // requestLayout scheduling the timeout and it firing.
        if (!scopeEl.isConnected) return;

        const scaleWrapper = scopeEl.querySelector('.mail-scale-wrapper');
        // Reset any zoom from the previous run BEFORE measuring. Otherwise
        // getBoundingClientRect() returns dimensions already scaled, so
        // widestElement/remaining computes against zoomed sizes and either
        // spirals (each pass multiplies) or leaves stale zoom that no longer
        // matches the current viewport.
        if (scaleWrapper) scaleWrapper.style.zoom = '';

        if (scopeEl.scrollWidth <= MAX_DOCUMENT_WIDTH_TO_TRANSFORM) {
            if (scaleWrapper) scaleWrapper.style.overflowX = '';
            transformContent(scopeEl, state);
            fixLayout(scopeEl, state);
        } else if (scaleWrapper) {
            // Content too wide to safely down-scale (resulting zoom would be
            // unreadable). Fall back to horizontal scroll on the wrapper so
            // the user can pan — the .msg-body { overflow-x: hidden } clip
            // otherwise silently hides the right half.
            scaleWrapper.style.overflowX = 'auto';
        }

        // Notify native so overlay geometry catches up with our DOM mutations.
        if (typeof window.scheduleMeasure === 'function') {
            window.scheduleMeasure();
        }
    }

    function fixLayout(scopeEl, state) {
        const allElements = scopeEl.getElementsByTagName("*");
        const widestElement = getWidestElement(allElements, state.horizontalBounds);
        if (widestElement > state.horizontalBounds.remaining) {
            addScale(scopeEl, state.horizontalBounds.remaining / widestElement);
        }
    }

    function getWidestElement(elements, hb) {
        let currentMaxWidth = hb.remaining;
        for (let i = 0; i < elements.length; i++) {
            const rect = elements[i].getBoundingClientRect();
            if (rect.right > hb.right || rect.left < hb.left) {
                currentMaxWidth = Math.max(rect.right - rect.left, currentMaxWidth);
            }
        }
        return currentMaxWidth;
    }

    function transformContent(scopeEl, state) {
        const scaleWrapper = scopeEl.querySelector('.mail-scale-wrapper');
        if (!scaleWrapper) return;
        const hb = state.horizontalBounds;

        zoomOutTables(scaleWrapper.querySelectorAll('table'), hb);
        transformBlockElements(scaleWrapper.querySelectorAll('div[style], textarea[style]'), hb);
        transformImages(scaleWrapper.querySelectorAll('img'), hb);
    }

    function transformImages(images, hb) {
        for (let i = 0; i < images.length; i++) {
            const image = images[i];
            // Пропускаем уже отмасштабированные изображения — иначе при
            // пользовательском pinch-зуме WebView мы бы снова сбрасывали
            // maxWidth на ширину контейнера.
            if (image.style.maxWidth) continue;
            if (!getAncestorByTagName(image, 'table') && isImageElementSized(image)) {
                const rect = image.getBoundingClientRect();
                const newWidth = Math.min(hb.right - rect.left, hb.right);
                const originalWidth = image.offsetWidth > 0 ? image.offsetWidth : newWidth;
                if (originalWidth >= newWidth) {
                    image.style.maxWidth = `${newWidth}px`;
                    image.style.height = 'auto';
                }
            }
        }
    }

    function zoomOutTables(tables, hb) {
        for (let i = 0; i < tables.length; i++) {
            const table = tables[i];
            if (table.classList.contains('zoom-out')) {
                table.classList.remove('zoom-out');
                table.style.webkitTransform = '';
                table.style.webkitTransformOrigin = '';
                if (table.parentNode.className === WRAPPER_ELEMENT_CLASS) {
                    table.parentNode.removeAttribute('style');
                }
            }
            if (!getAncestorByClassName(table, 'zoom-out')) {
                const rect = table.getBoundingClientRect();
                if (rect.right > hb.right) {
                    const scaleFactor = (hb.right - rect.left) / table.offsetWidth;
                    if (scaleFactor < 1 && scaleFactor > 0) {
                        const outerBounds = getElementOuterBounds(table);
                        wrapElement(table, outerBounds, scaleFactor, hb);
                        table.style.webkitTransform = `scale(${scaleFactor})`;
                        const transformOriginX = outerBounds.left - rect.left;
                        const transformOriginY = outerBounds.top - rect.top;
                        table.style.webkitTransformOrigin = `${transformOriginX}px ${transformOriginY}px`;
                        table.classList.add('zoom-out');
                    }
                }
            }
        }
    }

    function wrapElement(element, bounds, scaleFactor, hb) {
        const wrapperWidth = Math.max(hb.right - Math.max(bounds.left, hb.left), 0);
        const wrapperHeight = Math.max(1, Math.floor((bounds.bottom - bounds.top) * scaleFactor));
        let wrapper = element.parentNode.className === WRAPPER_ELEMENT_CLASS
            ? element.parentNode
            : document.createElement('span');
        wrapper.style.display = 'block';
        wrapper.style.overflow = 'hidden';
        wrapper.style.width = `${wrapperWidth}px`;
        wrapper.style.height = `${wrapperHeight}px`;
        wrapper.style.setProperty('margin', '0', '!important');
        wrapper.style.setProperty('padding', '0', '!important');
        wrapper.className = WRAPPER_ELEMENT_CLASS;
        if (element.parentNode !== wrapper) {
            element.parentNode.replaceChild(wrapper, element);
            wrapper.appendChild(element);
        }
        return bounds;
    }

    function transformBlockElements(blockElements, hb) {
        for (let i = 0; i < blockElements.length; i++) {
            const element = blockElements[i];
            const rect = element.getBoundingClientRect();
            const widthIndex = (element.style.width || element.style.minWidth).indexOf('px');
            const availableWidth = hb.right - Math.max(rect.left, hb.left);
            if (!getAncestorByClassName(element, 'zoom-out')
                && widthIndex >= 0
                && element.style.width.slice(0, widthIndex) > availableWidth) {
                element.style.minWidth = '';
                element.style.width = '';
                element.style.maxWidth = `${availableWidth}px`;
                element.style.boxSizing = 'border-box';
            }
        }
    }

    function getAncestorByClassName(element, className) {
        while (element && (!element.classList || !element.classList.contains(className))) {
            element = element.parentNode;
        }
        return element || null;
    }

    function getAncestorByTagName(element, tagName) {
        while (element && (!element.tagName || element.tagName.toLowerCase() !== tagName)) {
            element = element.parentNode;
        }
        return element || null;
    }

    function addScale(scopeEl, scaleFactor) {
        const wrapper = scopeEl.querySelector('.mail-scale-wrapper');
        if (wrapper) wrapper.style.zoom = scaleFactor;
    }

    function setupImageLoadListeners(scopeEl, state) {
        const images = scopeEl.getElementsByTagName('img');
        for (let i = 0; i < images.length; i++) {
            const image = images[i];
            if (isImageElementSized(image)) continue;
            // Idempotent: don't attach twice across repeated formatMessageBody
            // calls (e.g., resize handler). WeakSet so removed images GC.
            if (listenedImages.has(image)) continue;
            listenedImages.add(image);
            const onEvent = () => requestLayout(scopeEl, state);
            image.addEventListener('load', onEvent);
            image.addEventListener('error', onEvent);
        }
    }

    function isImageElementSized(image) {
        if (image.complete || image.clientWidth > 0 && image.clientHeight > 0) return true;
        const widthAttr = image.getAttribute('width');
        const heightAttr = image.getAttribute('height');
        return !isNaN(parseFloat(widthAttr)) && !isNaN(parseFloat(heightAttr));
    }

    function requestLayout(scopeEl, state) {
        clearTimeout(state.timeoutId);
        state.timeoutId = setTimeout(() => {
            state.timeoutId = null;
            layout(scopeEl, state);
        }, state.firstCall ? 0 : MIN_RELAYOUT_INTERVAL);
        state.firstCall = false;
    }

    function getElementOuterBounds(element) {
        const rect = element.getBoundingClientRect();
        const marginTop = parseFloat(element.style.marginTop);
        const marginRight = parseFloat(element.style.marginRight);
        const marginBottom = parseFloat(element.style.marginBottom);
        const marginLeft = parseFloat(element.style.marginLeft);
        return {
            left: rect.left - getValue(marginLeft),
            right: rect.right + getValue(marginRight),
            top: rect.top - getValue(marginTop),
            bottom: rect.bottom + getValue(marginBottom)
        };
    }

    function getValue(value) {
        return isNaN(value) ? 0 : Math.max(value, 0);
    }

    /* Rotation / split-screen: viewport width changes ⇒ every loaded body's
     * fixLayout output (wrapper zoom, table transforms) is now stale. Debounce
     * so continuous resize events don't thrash. Only .msg-body elements
     * carrying a data-msg-id are our loaded targets — spacers/skeletons are
     * skipped naturally by the selector. */
    function onWindowResize() {
        clearTimeout(resizeTimeoutId);
        resizeTimeoutId = setTimeout(() => {
            resizeTimeoutId = null;
            const bodies = document.querySelectorAll('.msg-body[data-msg-id]');
            for (let i = 0; i < bodies.length; i++) {
                formatMessageBody(bodies[i]);
            }
        }, MIN_RELAYOUT_INTERVAL);
    }
    window.addEventListener('resize', onWindowResize);

    window.formatMessageBody = formatMessageBody;
})();

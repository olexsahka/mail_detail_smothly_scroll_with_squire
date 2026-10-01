package com.alex.mailstubdetails.perf

import android.os.Trace

/**
 * Prefix for every [android.os.Trace] section emitted by this app. Keeps
 * MailStub slices filterable in Perfetto / Android Studio Profiler via
 * `name:MailStub.*`.
 */
@PublishedApi
internal const val TRACE_SECTION_PREFIX: String = "MailStub."

/**
 * Wraps [block] in an `android.os.Trace` section. The section shows up
 * as a named slice on the UI thread in Perfetto / Android Studio
 * Profiler without any extra tracing-API setup — Trace.beginSection is
 * backed by atrace on the system side and costs ~a few hundred ns when
 * no tracer is attached.
 *
 * Use short, stable names — `MailStub.positionOverlays`, not
 * `MailStub.positionOverlays(scrollY=1234)` — because the trace
 * grouper collapses identical names into one row.
 */
inline fun <R> traced(section: String, block: () -> R): R {
    Trace.beginSection(TRACE_SECTION_PREFIX + section)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

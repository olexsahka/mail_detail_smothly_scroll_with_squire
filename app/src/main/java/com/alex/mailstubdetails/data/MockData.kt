package com.alex.mailstubdetails.data

import com.alex.mailstubdetails.model.EmailMessage
import com.alex.mailstubdetails.model.EmailThread

// ─── HTML bodies ────────────────────────────────────────────────────────────

private val BODY_MEETING_INVITE = """
<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#202124;line-height:1.6;max-width:640px;">

  <div style="background:#f8f9fa;border-left:4px solid #1a73e8;padding:16px 20px;margin-bottom:24px;border-radius:0 4px 4px 0;">
    <p style="margin:0;font-size:12px;color:#5f6368;text-transform:uppercase;letter-spacing:.5px;font-weight:600;">Q4 Planning — Action Required</p>
    <p style="margin:4px 0 0;font-size:20px;font-weight:bold;color:#1a1a1a;">Friday, November 1 · 2:00–4:00 PM EST</p>
  </div>

  <p>Hi Alex,</p>

  <p>I hope this message finds you well. Following our preliminary discussions last week, I'm formally inviting you to our <strong>Q4 strategy session</strong>. This meeting is critical for aligning all teams before the holiday sprint and setting realistic targets for Q1.</p>

  <h3 style="color:#1a73e8;margin-top:24px;margin-bottom:8px;font-size:14px;text-transform:uppercase;letter-spacing:.5px;">Meeting Agenda</h3>

  <table style="width:100%;border-collapse:collapse;font-size:13px;margin-bottom:20px;">
    <thead>
      <tr style="background:#1a73e8;color:#fff;">
        <th style="padding:10px 14px;text-align:left;">Time</th>
        <th style="padding:10px 14px;text-align:left;">Topic</th>
        <th style="padding:10px 14px;text-align:left;">Owner</th>
      </tr>
    </thead>
    <tbody>
      <tr style="border-bottom:1px solid #e8eaed;">
        <td style="padding:10px 14px;color:#5f6368;">2:00 PM</td>
        <td style="padding:10px 14px;">Welcome &amp; Q3 Recap</td>
        <td style="padding:10px 14px;color:#1a73e8;">Michael Chen</td>
      </tr>
      <tr style="background:#f8f9fa;border-bottom:1px solid #e8eaed;">
        <td style="padding:10px 14px;color:#5f6368;">2:20 PM</td>
        <td style="padding:10px 14px;">Budget Review &amp; Q4 Forecast</td>
        <td style="padding:10px 14px;color:#1a73e8;">Finance Team</td>
      </tr>
      <tr style="border-bottom:1px solid #e8eaed;">
        <td style="padding:10px 14px;color:#5f6368;">3:00 PM</td>
        <td style="padding:10px 14px;">Product Roadmap — Q4 &amp; Q1</td>
        <td style="padding:10px 14px;color:#1a73e8;">Sarah Kim</td>
      </tr>
      <tr style="background:#f8f9fa;border-bottom:1px solid #e8eaed;">
        <td style="padding:10px 14px;color:#5f6368;">3:30 PM</td>
        <td style="padding:10px 14px;">Engineering Hiring Plan</td>
        <td style="padding:10px 14px;color:#1a73e8;">HR + Engineering</td>
      </tr>
      <tr>
        <td style="padding:10px 14px;color:#5f6368;">3:50 PM</td>
        <td style="padding:10px 14px;">Open Q&amp;A</td>
        <td style="padding:10px 14px;color:#1a73e8;">All</td>
      </tr>
    </tbody>
  </table>

  <h3 style="color:#1a73e8;margin-top:24px;margin-bottom:8px;font-size:14px;text-transform:uppercase;letter-spacing:.5px;">Key Discussion Points</h3>

  <ul style="padding-left:20px;margin-bottom:20px;">
    <li style="margin-bottom:10px;"><strong>Budget Allocation:</strong> Finance will present the proposed Q4 breakdown. We're projecting a 15% increase in infrastructure costs due to the new data center expansion. Teams should come prepared with their top 3 cost-saving proposals.</li>
    <li style="margin-bottom:10px;"><strong>Product Roadmap:</strong> Sarah's team has submitted a revised roadmap that prioritises the mobile payments feature and the new analytics dashboard. The enterprise SSO integration has been pushed to Q1 due to vendor delays.</li>
    <li style="margin-bottom:10px;"><strong>Hiring Plan:</strong> We're targeting 12 new engineering positions to be filled before year-end — 8 backend/infrastructure and 4 frontend/mobile. Referral bonuses have increased to ${'$'}5,000.</li>
    <li style="margin-bottom:10px;"><strong>OKR Review:</strong> Please review the Q3 OKR completion report shared in last week's company update. We'll discuss which objectives carry forward to Q4.</li>
  </ul>

  <div style="background:#fff8e1;border:1px solid #ffd54f;border-radius:4px;padding:14px 18px;margin:20px 0;">
    <p style="margin:0;font-size:13px;"><strong>⚠ Action Required:</strong> Please review the pre-read materials in the shared Drive folder before the meeting — especially the Q3 financial summary and the updated product roadmap deck.</p>
  </div>

  <h3 style="color:#1a73e8;margin-top:24px;margin-bottom:12px;font-size:14px;text-transform:uppercase;letter-spacing:.5px;">Meeting Details</h3>

  <p style="margin:0;"><strong>Date:</strong> Friday, November 1, 2024</p>
  <p style="margin:4px 0;"><strong>Time:</strong> 2:00–4:00 PM Eastern Time</p>
  <p style="margin:4px 0;"><strong>Location:</strong> Conference Room A (3rd Floor) + Zoom for remote attendees</p>
  <p style="margin:4px 0;"><strong>Zoom link:</strong> <a href="#" style="color:#1a73e8;">https://techcorp.zoom.us/j/84392017456</a></p>
  <p style="margin:4px 0 20px;"><strong>Passcode:</strong> Q4Plan2024</p>

  <p>Please confirm your attendance by <strong>Monday, October 28th</strong>. If you have agenda items to add, send them over by Wednesday.</p>

  <p>Looking forward to a productive session.</p>

  <hr style="border:none;border-top:1px solid #e8eaed;margin:20px 0;">
  <p style="margin:0;font-weight:bold;font-size:15px;">Michael Chen</p>
  <p style="margin:2px 0;font-size:13px;color:#5f6368;">VP of Product · TechCorp Inc.</p>
  <p style="margin:2px 0;font-size:13px;color:#5f6368;">m.chen@techcorp.com · +1 (555) 234-5678</p>
</div>
""".trimIndent()

private val BODY_MEETING_REPLY = """
<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#202124;line-height:1.6;">
  <p>Hi Michael,</p>
  <p>Thanks for the invite and the detailed agenda. I'll be there.</p>
  <p>One thing to flag — the budget figures you mentioned might conflict with the projections I shared with the CFO last Tuesday. I'll pull together a short comparison doc and share it before EOD Wednesday so Finance has it ahead of the meeting.</p>
  <p>Also, is there flexibility to move the Hiring Plan segment earlier? The Engineering leads have a hard stop at 3:45 for another call.</p>
  <p>Best,<br><strong>Alex</strong></p>

  <div style="border-top:1px solid #e8eaed;margin-top:20px;padding-top:12px;color:#5f6368;font-size:13px;">
    <p style="margin:0;"><em>On Oct 25, 9:42 AM, Michael Chen wrote:</em></p>
    <blockquote style="border-left:3px solid #dadce0;margin:8px 0;padding:4px 12px;">
      <p>Hi Alex, I hope this message finds you well...</p>
    </blockquote>
  </div>
</div>
""".trimIndent()

private val BODY_MEETING_FOLLOWUP = """
<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#202124;line-height:1.6;">
  <p>Alex,</p>
  <p>Good catch on the budget numbers — please do share that comparison doc. We'll include it in the pre-read pack.</p>
  <p>Regarding the schedule: I've moved the Hiring Plan to <strong>2:45 PM</strong> so your Engineering leads can drop off at 3:45 without missing anything critical. Updated calendar invite is on its way.</p>

  <h4 style="color:#333;margin-top:20px;margin-bottom:8px;">Pre-read Checklist</h4>
  <ul style="padding-left:20px;margin-bottom:16px;">
    <li style="margin-bottom:6px;"><input type="checkbox" disabled> Q3 Financial Summary (Finance_Q3_Summary.pdf)</li>
    <li style="margin-bottom:6px;"><input type="checkbox" disabled> Product Roadmap Deck v3 (Product_Roadmap_Q4_v3.pptx)</li>
    <li style="margin-bottom:6px;"><input type="checkbox" disabled> Engineering OKR Report (ENG_OKRs_Q3.xlsx)</li>
    <li style="margin-bottom:6px;"><input type="checkbox" disabled> Alex's Budget Comparison (TBD)</li>
  </ul>

  <p>All files are in the shared Drive folder: <a href="#" style="color:#1a73e8;">Q4 Planning / Pre-reads</a></p>

  <p>See you Friday.</p>
  <hr style="border:none;border-top:1px solid #e8eaed;margin:20px 0;">
  <p style="margin:0;font-weight:bold;">Michael Chen</p>
  <p style="margin:2px 0;font-size:13px;color:#5f6368;">VP of Product · TechCorp Inc.</p>
</div>
""".trimIndent()

private val BODY_NEWSLETTER = """
<div style="font-family:'Segoe UI',Arial,sans-serif;max-width:640px;color:#1a1a1a;">

  <div style="background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);padding:32px 24px;border-radius:8px 8px 0 0;text-align:center;">
    <p style="margin:0;font-size:11px;color:rgba(255,255,255,.7);letter-spacing:2px;text-transform:uppercase;">Weekly Tech Digest</p>
    <h1 style="margin:8px 0 0;font-size:26px;font-weight:800;color:#fff;">Issue #47 — October 2024</h1>
    <p style="margin:8px 0 0;font-size:13px;color:rgba(255,255,255,.8);">The week's most important stories in software engineering</p>
  </div>

  <div style="background:#fff;padding:24px;border:1px solid #e8eaed;border-top:none;">

    <h2 style="font-size:18px;color:#333;margin-top:0;">👋 This week in tech</h2>
    <p>It's been a packed week — from a major update to the Kotlin coroutines API to a breakthrough in WebAssembly performance. Let's dive in.</p>

    <hr style="border:none;border-top:2px solid #667eea;margin:24px 0;">

    <!-- Story 1 -->
    <div style="margin-bottom:28px;">
      <span style="background:#e8f5e9;color:#2e7d32;font-size:11px;font-weight:700;padding:3px 8px;border-radius:12px;text-transform:uppercase;">Android</span>
      <h3 style="font-size:16px;margin:10px 0 6px;"><a href="#" style="color:#1a73e8;text-decoration:none;">Jetpack Compose 1.7 ships with dramatically improved scroll performance</a></h3>
      <p style="margin:0;font-size:13px;color:#5f6368;line-height:1.6;">The Compose team announced a complete rewrite of the lazy list implementation, cutting first-frame latency by up to 40% on mid-range devices. The update also includes a new <code>rememberLazyListState</code> API that makes programmatic scrolling more predictable...</p>
      <p style="margin:8px 0 0;font-size:12px;color:#9aa0a6;">5 min read · Android Developers Blog</p>
    </div>

    <!-- Story 2 -->
    <div style="margin-bottom:28px;">
      <span style="background:#e3f2fd;color:#1565c0;font-size:11px;font-weight:700;padding:3px 8px;border-radius:12px;text-transform:uppercase;">AI</span>
      <h3 style="font-size:16px;margin:10px 0 6px;"><a href="#" style="color:#1a73e8;text-decoration:none;">On-device LLMs: a practical guide for mobile engineers</a></h3>
      <p style="margin:0;font-size:13px;color:#5f6368;line-height:1.6;">Running quantised language models locally is now feasible on flagship Android devices. This deep-dive covers the trade-offs between model size, inference speed, and accuracy — and when you should (and shouldn't) leave the cloud behind.</p>
      <p style="margin:8px 0 0;font-size:12px;color:#9aa0a6;">12 min read · Medium Engineering</p>
    </div>

    <!-- Story 3 -->
    <div style="margin-bottom:28px;">
      <span style="background:#fce4ec;color:#c62828;font-size:11px;font-weight:700;padding:3px 8px;border-radius:12px;text-transform:uppercase;">Security</span>
      <h3 style="font-size:16px;margin:10px 0 6px;"><a href="#" style="color:#1a73e8;text-decoration:none;">Critical WebView vulnerability affects 2B Android devices — patch now</a></h3>
      <p style="margin:0;font-size:13px;color:#5f6368;line-height:1.6;">Google's Project Zero team disclosed a high-severity flaw in the WebView component that could allow remote code execution via a malicious website. The patch is included in the October security update. If you ship an app with a WebView, update your minimum SDK or add runtime checks.</p>
      <p style="margin:8px 0 0;font-size:12px;color:#9aa0a6;">8 min read · Project Zero Blog</p>
    </div>

    <hr style="border:none;border-top:1px solid #e8eaed;margin:24px 0;">

    <h2 style="font-size:16px;color:#333;">🛠 Code snippet of the week</h2>
    <p style="font-size:13px;color:#5f6368;">Custom <code>NestedScrollConnection</code> to collapse a toolbar while leaving a WebView scrollable:</p>

    <pre style="background:#1e1e2e;color:#cdd6f4;padding:16px;border-radius:6px;font-size:12px;line-height:1.6;overflow-x:auto;">
val scrollBehavior = TopAppBarDefaults
    .exitUntilCollapsedScrollBehavior()

Scaffold(
    modifier = Modifier.nestedScroll(
        scrollBehavior.nestedScrollConnection
    ),
    topBar = {
        LargeTopAppBar(
            title = { Text("Inbox") },
            scrollBehavior = scrollBehavior
        )
    }
) { padding ->
    Column(
        modifier = Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
    ) {
        // native content + WebView go here
    }
}</pre>

    <hr style="border:none;border-top:1px solid #e8eaed;margin:24px 0;">

    <h2 style="font-size:16px;color:#333;">📊 Numbers this week</h2>
    <table style="width:100%;border-collapse:collapse;font-size:13px;margin-bottom:8px;">
      <tr style="background:#f8f9fa;">
        <td style="padding:10px 14px;font-weight:600;border-bottom:1px solid #e8eaed;">Stack Overflow Dev Survey respondents</td>
        <td style="padding:10px 14px;text-align:right;color:#1a73e8;font-weight:700;border-bottom:1px solid #e8eaed;">65,437</td>
      </tr>
      <tr>
        <td style="padding:10px 14px;font-weight:600;border-bottom:1px solid #e8eaed;">% using Kotlin for Android</td>
        <td style="padding:10px 14px;text-align:right;color:#1a73e8;font-weight:700;border-bottom:1px solid #e8eaed;">83%</td>
      </tr>
      <tr style="background:#f8f9fa;">
        <td style="padding:10px 14px;font-weight:600;border-bottom:1px solid #e8eaed;">New GitHub repos this week</td>
        <td style="padding:10px 14px;text-align:right;color:#1a73e8;font-weight:700;border-bottom:1px solid #e8eaed;">1.2M</td>
      </tr>
      <tr>
        <td style="padding:10px 14px;font-weight:600;">Avg. salary for senior Android engineer (US)</td>
        <td style="padding:10px 14px;text-align:right;color:#1a73e8;font-weight:700;">${'$'}178k</td>
      </tr>
    </table>

    <hr style="border:none;border-top:1px solid #e8eaed;margin:24px 0;">

    <p style="font-size:13px;color:#9aa0a6;text-align:center;margin:0;">You're receiving this because you subscribed at techdigest.dev · <a href="#" style="color:#9aa0a6;">Unsubscribe</a></p>
  </div>
</div>
""".trimIndent()

private val BODY_DESIGN_REVIEW = """
<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#202124;line-height:1.7;">

  <p>Hi team,</p>

  <p>Here are my notes from today's <strong>Project Artemis — Design Review</strong> session. I've tried to capture all the action items; let me know if I missed anything.</p>

  <h3 style="font-size:15px;color:#333;margin-top:20px;">Overview</h3>
  <p>We reviewed the full Figma prototype for the new onboarding flow. Overall the design is strong — the main feedback centred on the transition animations and the colour accessibility on the summary screen.</p>

  <h3 style="font-size:15px;color:#333;margin-top:20px;">Feedback by Section</h3>

  <div style="background:#f8f9fa;border-radius:6px;padding:16px 20px;margin-bottom:16px;border-left:4px solid #34a853;">
    <p style="margin:0 0 6px;font-weight:700;color:#1e8e3e;">✅ Approved — Welcome Screen</p>
    <p style="margin:0;font-size:13px;">The animated logo entrance and the three-step value prop cards were unanimously approved. Ship as-is.</p>
  </div>

  <div style="background:#f8f9fa;border-radius:6px;padding:16px 20px;margin-bottom:16px;border-left:4px solid #fbbc04;">
    <p style="margin:0 0 6px;font-weight:700;color:#e37400;">⚠ Needs Revision — Account Setup (Step 2)</p>
    <ul style="margin:4px 0 0;padding-left:18px;font-size:13px;">
      <li style="margin-bottom:4px;">The email field auto-focus causes the keyboard to obscure the "Continue" button on small screens. Move the button above the keyboard or use a sticky footer.</li>
      <li style="margin-bottom:4px;">Password strength indicator contrast ratio is 2.8:1 — needs to be ≥ 4.5:1 for WCAG AA compliance.</li>
      <li>Consider adding a "Paste from clipboard" affordance for the verification code field.</li>
    </ul>
  </div>

  <div style="background:#f8f9fa;border-radius:6px;padding:16px 20px;margin-bottom:16px;border-left:4px solid #ea4335;">
    <p style="margin:0 0 6px;font-weight:700;color:#c5221f;">🚫 Blocked — Summary Screen</p>
    <p style="margin:0;font-size:13px;">The dark-mode summary card uses <code>#6b6b6b</code> text on a <code>#1c1c1c</code> background — contrast ratio 3.1:1. This is a launch blocker. Proposed fix: use <code>#a8a8a8</code> on <code>#1c1c1c</code> (5.9:1).</p>
  </div>

  <h3 style="font-size:15px;color:#333;margin-top:20px;">Action Items</h3>
  <table style="width:100%;border-collapse:collapse;font-size:13px;">
    <thead>
      <tr style="background:#e8eaed;">
        <th style="padding:8px 12px;text-align:left;font-weight:600;">Task</th>
        <th style="padding:8px 12px;text-align:left;font-weight:600;">Owner</th>
        <th style="padding:8px 12px;text-align:left;font-weight:600;">Due</th>
      </tr>
    </thead>
    <tbody>
      <tr style="border-bottom:1px solid #e8eaed;">
        <td style="padding:8px 12px;">Fix button visibility on small screens</td>
        <td style="padding:8px 12px;">David Park</td>
        <td style="padding:8px 12px;color:#ea4335;">Oct 28</td>
      </tr>
      <tr style="background:#f8f9fa;border-bottom:1px solid #e8eaed;">
        <td style="padding:8px 12px;">Fix password indicator contrast</td>
        <td style="padding:8px 12px;">Sarah Kim</td>
        <td style="padding:8px 12px;color:#ea4335;">Oct 28</td>
      </tr>
      <tr style="border-bottom:1px solid #e8eaed;">
        <td style="padding:8px 12px;">Update summary screen dark-mode colours</td>
        <td style="padding:8px 12px;">Sarah Kim</td>
        <td style="padding:8px 12px;color:#ea4335;">Oct 28</td>
      </tr>
      <tr style="background:#f8f9fa;">
        <td style="padding:8px 12px;">Re-review revised designs</td>
        <td style="padding:8px 12px;">Full team</td>
        <td style="padding:8px 12px;">Oct 30</td>
      </tr>
    </tbody>
  </table>

  <p style="margin-top:20px;">Next review is scheduled for <strong>Wednesday, Oct 30 at 11 AM</strong>. Updated Figma link will be shared by Tuesday EOD.</p>

  <p>Thanks everyone — great session today.</p>
  <p style="margin:0;">— Sarah</p>
</div>
""".trimIndent()

private val BODY_DESIGN_RESPONSE = """
<div style="font-family:Arial,Helvetica,sans-serif;font-size:14px;color:#202124;line-height:1.7;">
  <p>Sarah, thanks for the thorough notes!</p>

  <p>I've already addressed the button visibility issue — used a <code>BottomAppBar</code> pattern so the "Continue" button stays pinned above the keyboard regardless of screen size. PR is up for review: <a href="#" style="color:#1a73e8;">#1247 — Fix onboarding button visibility</a>.</p>

  <p>On the contrast issue: I ran the updated palette through the <strong>Material Colour Tool</strong> and the proposed <code>#a8a8a8 / #1c1c1c</code> combo passes at 5.9:1. I'll also add it to the design tokens doc so we don't regress.</p>

  <p>One extra thought — should we use <code>SemanticsProperties.contentDescription</code> on the strength indicator so screen-reader users get textual feedback? Happy to add that while I'm in that area.</p>

  <p>David</p>
</div>
""".trimIndent()

private val BODY_INVOICE = """
<div style="font-family:'Helvetica Neue',Arial,sans-serif;max-width:560px;color:#1a1a1a;">

  <div style="text-align:center;padding:32px 0 24px;">
    <p style="margin:0;font-size:28px;font-weight:800;color:#5469d4;">CloudServices</p>
    <p style="margin:4px 0 0;font-size:13px;color:#6b7280;">cloud.services · support@cloud.services</p>
  </div>

  <div style="background:#f9fafb;border:1px solid #e5e7eb;border-radius:8px;padding:24px;margin-bottom:24px;">
    <div style="display:flex;justify-content:space-between;flex-wrap:wrap;gap:12px;">
      <div>
        <p style="margin:0;font-size:11px;color:#6b7280;text-transform:uppercase;letter-spacing:.5px;">Invoice</p>
        <p style="margin:4px 0 0;font-size:20px;font-weight:700;">#INV-2024-1047</p>
      </div>
      <div style="text-align:right;">
        <p style="margin:0;font-size:11px;color:#6b7280;text-transform:uppercase;letter-spacing:.5px;">Status</p>
        <span style="display:inline-block;margin-top:4px;background:#d1fae5;color:#065f46;font-size:12px;font-weight:600;padding:3px 10px;border-radius:12px;">PAID</span>
      </div>
    </div>
    <hr style="border:none;border-top:1px solid #e5e7eb;margin:16px 0;">
    <p style="margin:0;font-size:13px;"><strong>Bill to:</strong> Alex Sutugin · alex@mailstubdetails.com</p>
    <p style="margin:4px 0 0;font-size:13px;"><strong>Period:</strong> October 1–31, 2024</p>
    <p style="margin:4px 0 0;font-size:13px;"><strong>Issued:</strong> October 31, 2024 · <strong>Due:</strong> November 15, 2024</p>
  </div>

  <table style="width:100%;border-collapse:collapse;font-size:13px;margin-bottom:24px;">
    <thead>
      <tr style="background:#5469d4;color:#fff;">
        <th style="padding:10px 14px;text-align:left;">Service</th>
        <th style="padding:10px 14px;text-align:right;">Qty</th>
        <th style="padding:10px 14px;text-align:right;">Unit Price</th>
        <th style="padding:10px 14px;text-align:right;">Amount</th>
      </tr>
    </thead>
    <tbody>
      <tr style="border-bottom:1px solid #e5e7eb;">
        <td style="padding:10px 14px;">Compute (m5.large × 2)</td>
        <td style="padding:10px 14px;text-align:right;">744 hrs</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}0.096</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}71.42</td>
      </tr>
      <tr style="background:#f9fafb;border-bottom:1px solid #e5e7eb;">
        <td style="padding:10px 14px;">Object Storage (S3-compatible)</td>
        <td style="padding:10px 14px;text-align:right;">250 GB</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}0.023</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}5.75</td>
      </tr>
      <tr style="border-bottom:1px solid #e5e7eb;">
        <td style="padding:10px 14px;">Managed PostgreSQL (db.t3.medium)</td>
        <td style="padding:10px 14px;text-align:right;">744 hrs</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}0.068</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}50.59</td>
      </tr>
      <tr style="background:#f9fafb;border-bottom:1px solid #e5e7eb;">
        <td style="padding:10px 14px;">Outbound Data Transfer</td>
        <td style="padding:10px 14px;text-align:right;">120 GB</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}0.09</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}10.80</td>
      </tr>
      <tr style="border-bottom:1px solid #e5e7eb;">
        <td style="padding:10px 14px;">Support Plan (Business)</td>
        <td style="padding:10px 14px;text-align:right;">1</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}29.00</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}29.00</td>
      </tr>
    </tbody>
    <tfoot>
      <tr>
        <td colspan="3" style="padding:10px 14px;text-align:right;font-weight:600;">Subtotal</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}167.56</td>
      </tr>
      <tr>
        <td colspan="3" style="padding:10px 14px;text-align:right;font-weight:600;">Tax (0%)</td>
        <td style="padding:10px 14px;text-align:right;">${'$'}0.00</td>
      </tr>
      <tr style="background:#eff6ff;">
        <td colspan="3" style="padding:12px 14px;text-align:right;font-weight:800;font-size:15px;">Total</td>
        <td style="padding:12px 14px;text-align:right;font-weight:800;font-size:15px;color:#5469d4;">${'$'}167.56</td>
      </tr>
    </tfoot>
  </table>

  <div style="background:#ecfdf5;border:1px solid #6ee7b7;border-radius:6px;padding:14px 18px;margin-bottom:24px;">
    <p style="margin:0;font-size:13px;color:#065f46;"><strong>✓ Payment received</strong> — ${'$'}167.56 charged to Visa ending 4242 on Oct 31, 2024.</p>
  </div>

  <p style="font-size:13px;color:#6b7280;text-align:center;">Questions? Contact <a href="#" style="color:#5469d4;">support@cloud.services</a></p>
</div>
""".trimIndent()

// ─── Threads ────────────────────────────────────────────────────────────────

val MOCK_THREADS: List<EmailThread> = listOf(
    EmailThread(
        id = "thread1",
        subject = "Q4 Planning Meeting — Action Required",
        messages = listOf(
            EmailMessage(
                id = "msg1a",
                fromName = "Michael Chen",
                fromEmail = "m.chen@techcorp.com",
                toList = listOf("alex@mailstubdetails.com"),
                subject = "Q4 Planning Meeting — Action Required",
                date = "Oct 25, 9:42 AM",
                htmlBody = BODY_MEETING_INVITE,
                plainPreview = "Hi Alex, I hope this message finds you well. Following our preliminary discussions I'm formally inviting you to our Q4 strategy session.",
                isRead = true,
                hasAttachment = true
            ),
            EmailMessage(
                id = "msg1b",
                fromName = "Alex",
                fromEmail = "alex@mailstubdetails.com",
                toList = listOf("m.chen@techcorp.com"),
                subject = "Re: Q4 Planning Meeting — Action Required",
                date = "Oct 25, 11:17 AM",
                htmlBody = BODY_MEETING_REPLY,
                plainPreview = "Hi Michael, Thanks for the invite and the detailed agenda. I'll be there. One thing to flag...",
                isRead = true
            ),
            EmailMessage(
                id = "msg1c",
                fromName = "Michael Chen",
                fromEmail = "m.chen@techcorp.com",
                toList = listOf("alex@mailstubdetails.com"),
                subject = "Re: Q4 Planning Meeting — Action Required",
                date = "Oct 25, 2:03 PM",
                htmlBody = BODY_MEETING_FOLLOWUP,
                plainPreview = "Alex, Good catch on the budget numbers — please do share that comparison doc.",
                isRead = false
            )
        )
    ),
    EmailThread(
        id = "thread2",
        subject = "Weekly Tech Digest #47",
        messages = listOf(
            EmailMessage(
                id = "msg2a",
                fromName = "Tech Digest",
                fromEmail = "hello@techdigest.dev",
                toList = listOf("alex@mailstubdetails.com"),
                subject = "Weekly Tech Digest #47",
                date = "Oct 26, 8:00 AM",
                htmlBody = BODY_NEWSLETTER,
                plainPreview = "Jetpack Compose 1.7 ships with dramatically improved scroll performance. On-device LLMs: a practical guide for mobile engineers.",
                isRead = false
            )
        )
    ),
    EmailThread(
        id = "thread3",
        subject = "Project Artemis — Design Review Notes",
        messages = listOf(
            EmailMessage(
                id = "msg3a",
                fromName = "Sarah Kim",
                fromEmail = "s.kim@techcorp.com",
                toList = listOf("team@techcorp.com"),
                subject = "Project Artemis — Design Review Notes",
                date = "Oct 24, 5:30 PM",
                htmlBody = BODY_DESIGN_REVIEW,
                plainPreview = "Hi team, Here are my notes from today's Project Artemis design review session.",
                isRead = true
            ),
            EmailMessage(
                id = "msg3b",
                fromName = "David Park",
                fromEmail = "d.park@techcorp.com",
                toList = listOf("s.kim@techcorp.com", "team@techcorp.com"),
                subject = "Re: Project Artemis — Design Review Notes",
                date = "Oct 24, 6:45 PM",
                htmlBody = BODY_DESIGN_RESPONSE,
                plainPreview = "Sarah, thanks for the thorough notes! I've already addressed the button visibility issue.",
                isRead = true
            )
        )
    ),
    EmailThread(
        id = "thread4",
        subject = "Your invoice from CloudServices — October 2024",
        messages = listOf(
            EmailMessage(
                id = "msg4a",
                fromName = "CloudServices",
                fromEmail = "billing@cloud.services",
                toList = listOf("alex@mailstubdetails.com"),
                subject = "Your invoice from CloudServices — October 2024",
                date = "Oct 31, 6:00 AM",
                htmlBody = BODY_INVOICE,
                plainPreview = "Invoice #INV-2024-1047 · Total: \$167.56 · Status: PAID — Thank you for your business.",
                isRead = false,
                hasAttachment = true
            )
        )
    )
)

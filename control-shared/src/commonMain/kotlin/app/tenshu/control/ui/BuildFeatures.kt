package app.tenshu.control.ui

import app.tenshu.control.data.BillingRail

/**
 * Whether this build ships the **in-app "Subscribe to a paid tier" CTA** (the Stripe checkout shortcut
 * in the plan picker) **and the Services screen's "Manage subscription" button** (the Stripe billing
 * portal, where plans and payment methods change — external payment just the same). Purchase always happens on Stripe's hosted page in the system browser — the
 * in-app CTA is only a *steering shortcut* to it, so gating it is a distribution-channel concern, not a
 * change to how buying works.
 *
 * Defaulted per platform:
 *  - **Desktop = true** — no app store; "Get more storage" → browser → Stripe is the natural funnel.
 *  - **Android = false** — a Play-Store build must not steer users to external payment (Google Play
 *    Payments policy). The account still *reflects* whatever tier the entitlement grants (the Services
 *    screen reads the synced `StorageStatus` regardless), and users subscribe on the web; only the
 *    in-app purchase CTA is dropped.
 *
 * Paid storage is a cross-platform service, so this "buy on the web, reflect entitlement in-app" model
 * keeps the store builds policy-clean **without native IAP** (a separate track). A
 * sideloaded Android build (not distributed through Play) can flip its actual to `true` — Play's rules
 * don't apply off Play. Control has no iOS UI; the web app (drive.tenshu.app) is a separate TypeScript
 * project that gates this itself. See docs/proposals/commerce-accounts-and-sessions.md §9.4c.
 */
expect val inAppCheckoutEnabled: Boolean

/**
 * The rail this build sells paid plans through (`commerce-store-link-out.md` D1–D3): Play Billing in
 * a Play build, Stripe on desktop and in an Android APK distributed outside Play
 * (`-Ptenshu.android.billingRail=stripe`). [inAppCheckoutEnabled] is true exactly on the Stripe rail.
 */
expect val billingRail: BillingRail

/**
 * Whether this build can run the face pipeline, and so whether Settings offers **Group photos by
 * people** (`photos-people-and-pets.md` §12.12).
 *
 * ⚠⚠ A build fact, not a catalog guess: inferring it from the catalog conflates
 * "this platform cannot" with "the catalog is unreachable", and any fallback between the two gets
 * one of them wrong. The face pair's runtime (`FaceModel.RUNTIME`) needs MediaPipe, which only the
 * Android build links:
 *  - **Android = true**
 *  - **Desktop = false** while its detector is not ONNX — flip this together with the runtime it
 *    declares.
 */
expect val faceClusteringRunsHere: Boolean

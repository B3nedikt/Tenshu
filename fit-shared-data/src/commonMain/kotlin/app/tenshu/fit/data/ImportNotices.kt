package app.tenshu.fit.data

import app.tenshu.fit.model.ImportNotice

/**
 * The Google Fit app's package name. Named only to **detect** it — there is no Google Fit
 * integration to write and there cannot be one: developer signups for the Fit APIs closed on
 * 2024-05-01 and the APIs themselves are supported only to the end of 2026, so a Google Takeout
 * export is the sole route to that history (`fit-app.md` §16).
 */
internal const val GOOGLE_FIT_PACKAGE = "com.google.android.apps.fitness"

/**
 * Why an otherwise-successful import brought back none of the user's Google Fit history — and,
 * crucially, **whether they can do anything about it**.
 *
 * Platform-free on purpose: *observing* who wrote to a health store, which apps are installed, and
 * what those apps are permitted to do is platform work, but deciding what those facts mean is not,
 * and keeping the decision here is what makes it testable without a device.
 *
 * The three inputs, each load-bearing:
 *  - [writers] — **wrote none of what we read**, because the claim has to become false the moment
 *    it stops being true. If Fit ever does feed Health Connect it appears here and the notice
 *    retires itself; there is no constant anyone must remember to delete.
 *  - [googleFitInstalled] — telling someone who has never used Google Fit that Google Fit is not
 *    sharing is noise about an app they do not have.
 *  - [googleFitMayWrite] — ⚠⚠ **the difference between a fixable problem and a permanent one.**
 *    Google Fit *declares* Health Connect's `WRITE_*` permissions and simply ships with none of
 *    them granted, so the common case is a switch the user can flip. Collapsing both into one
 *    "Google Fit does not share" message would be false in that case and would talk them out of
 *    the only action that works.
 *
 * ⚠ Note what this deliberately does *not* look at: the number of rows imported. Workouts can come
 * back in quantity from *other* writers (a hiking app, a watch) while every Google Fit session
 * stays invisible — which is exactly the case that gets reported as a bug, and exactly the case a
 * count-based heuristic would miss.
 */
internal fun googleFitNotice(
    writers: Set<String>,
    googleFitInstalled: Boolean,
    googleFitMayWrite: Boolean,
): ImportNotice? = when {
    GOOGLE_FIT_PACKAGE in writers -> null
    !googleFitInstalled -> null
    !googleFitMayWrite -> ImportNotice.GoogleFitNotPermitted
    else -> ImportNotice.GoogleFitNotSharing
}

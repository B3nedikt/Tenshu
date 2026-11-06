package org.tenshu.inference

import org.tenshu.preference.BooleanAdapter
import org.tenshu.preference.Preference
import org.tenshu.preference.PreferenceScope

/**
 * One thing Tenshu can be asked to understand about a user's own content, and the two switches
 * that govern it (IN-1, `inference-service.md` IN-D3).
 *
 * ⚠⚠ **Never a blanket "AI" switch.** Each capability is separately consented, with its own copy
 * saying what it does, what it downloads and what it costs in battery. A single toggle would make
 * "let Drive find text in my screenshots" and "cluster the faces of everyone in my photo library"
 * the same decision, and they are not remotely the same decision.
 *
 * ⚠⚠ **Two switches, because they answer different questions.** [realmConsent] is *"may this realm
 * be indexed for X at all"* — a user decision that should follow them to a new device.
 * [deviceConsent] is *"may **this** device do the work"* — a phone on battery and a plugged-in hub
 * are legitimately different answers to the same question. Both must be yes; either one being no
 * is a complete answer.
 *
 * ⚠ Nothing here names a model, a runtime or a file. A capability is what the *user* is agreeing
 * to; which weights satisfy it is IN-2's business and must stay changeable without re-consenting.
 */
enum class InferenceCapability(
    /** Stable id — the preference key's suffix and, later, the `producedBy` stamp's subject. */
    val id: String,

    /**
     * Whether this may be offered in a first-run flow at all.
     *
     * ⚠⚠ `false` for [FACE_CLUSTERING] and that is the whole point of the field: many people do
     * not want it, and *an onboarding flow that asks is an onboarding flow that pressures*. A
     * feature that is fine when chosen becomes a different thing when it is the default — so this
     * is a property of the capability rather than a convention a future screen might forget.
     */
    val offerableInOnboarding: Boolean = true,
) {
    /** Text in images. No persons, high search value — the safest thing to offer. */
    TEXT_IN_IMAGES("textInImages"),

    /** "Photos of a cat" / "that receipt" — the IN-D8 embedding model's reason to exist. */
    SEMANTIC_IMAGE_SEARCH("semanticImageSearch"),

    /** The optional, domain-specific vocabulary of IN-D7. */
    CONTENT_TAGGING("contentTagging"),

    /**
     * Grouping the same person across a library.
     *
     * ⚠⚠ **Off, and Settings-only.** `camera-service.md` CD-D7 excludes face *recognition* as
     * policy and that stands: its subject is a camera classifying non-consenting third parties
     * continuously. Clustering faces in one's own photo library is a materially different
     * situation (IN-D3) — but only while it is genuinely chosen, which is what
     * [offerableInOnboarding] `= false` and a default of `false` together mean.
     */
    FACE_CLUSTERING("faceClustering", offerableInOnboarding = false),

    /**
     * Reading an utterance well enough to pick **which question it is** (MQ-5).
     *
     * ⚠⚠ **Not "AI in Mia" — Mia already answers questions with no model at all.** This capability
     * buys exactly one thing: when the deterministic grammar cannot place a sentence, a model may
     * choose among the intents Mia already has. Every one of those intents keeps working with this
     * off (MQ-D9), which is why it is safe to default off like everything else here.
     *
     * ⚠ The "content" being processed is the utterance itself — already device-local conversation
     * history under MQ-D8 — so this adds no new stored artifact. It does add prompts as an in-use
     * plaintext class (`inference-service.md` §4), which is why it is a *consented* capability and
     * not an implementation detail of the planner.
     */
    QUERY_PLANNING("queryPlanning"),

    /**
     * Speech to text.
     *
     * ⚠ AV-1 ships dictation **without** consulting this: the OS recogniser needs no model of ours
     * and no index, so the platform's own microphone permission is the whole consent. This entry
     * exists for the case that is not shipped — transcribing *stored* audio, which is indexing and
     * belongs under the same two switches as everything else here.
     */
    SPEECH_TO_TEXT("speechToText");

    /** "May this realm be indexed for X at all" — follows the user to a new device. */
    val realmConsent: Preference<Boolean> = consent(PreferenceScope.REALM, "realm", id)

    /** "May *this* device do the work" — a phone on battery and a hub are different answers. */
    val deviceConsent: Preference<Boolean> = consent(PreferenceScope.DEVICE, "device", id)

    companion object {
        /** The capabilities a first-run flow may mention at all (IN-D3). */
        val offerable: List<InferenceCapability> get() = entries.filter { it.offerableInOnboarding }
    }
}

/**
 * ⚠⚠ **Every capability is off by default, and this is not the same call as Mia's grants.** Mia
 * defaults its read grants *on* because reading was already the shipped behaviour and defaulting
 * off would break a working assistant. Nothing here has ever run: silence means "the user has not
 * asked for this", and inferring over their library on that basis would be helping oneself.
 *
 * ⚠ Top-level rather than in the companion — enum entries initialise **before** the companion
 * object, so a factory living there is uninitialised exactly when every entry needs it (the same
 * trap `MiaSource` documents).
 */
private fun consent(scope: PreferenceScope, kind: String, id: String) = Preference(
    id = "inference.$kind.$id",
    scope = scope,
    default = false,
    adapter = BooleanAdapter,
)

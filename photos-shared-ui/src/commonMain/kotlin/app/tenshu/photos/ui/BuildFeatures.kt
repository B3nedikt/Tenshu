package app.tenshu.photos.ui

/**
 * Whether the Control this Photos talks to can group faces at all, and so whether the People
 * section may explain itself when it is empty (`photos-people-and-pets.md` §6a).
 *
 * ⚠⚠ A build fact, mirroring Control's own `faceClusteringRunsHere`, for the same reason: the proxy
 * answers `NO_BACKEND` **before** it looks at consent, so on a platform that cannot run the face
 * pipeline that is the only reason it ever gives. From here it is indistinguishable from an Android
 * phone that has not downloaded the models yet, and only the second has a remedy. Without this flag
 * desktop Photos would tell its user to download a model desktop Control does not offer.
 *  - **Android = true**: Photos always talks to the Control on the same phone.
 *  - **Desktop = false** until the face detector runs off MediaPipe. Flip it together with Control's.
 */
expect val faceGroupingRunsHere: Boolean

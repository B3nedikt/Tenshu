package org.tenshu.mia.proxy

/**
 * Where search by description runs, **as Mia's refusals name it** (`ios-on-device-inference.md`
 * §1.8, §14.18.16 item 4). ⚠ Required of every platform's proxy module, as `MiaActionOffer` is:
 * a default would be the wrong sentence on one of them, silently.
 */
enum class MiaPhotoSearchHome {
    /** Android and desktop: Control holds the model, and this device does the work. */
    THIS_DEVICE,

    /**
     * iOS: Photos alone owns inference (PIO-D10). Mia runs in its own process, which binds no
     * backend, so it always answers *no model* there — and "this device" would be wrong: Photos
     * may hold a model and a full index. The sentences point at Photos.
     */
    PHOTOS_APP,
}

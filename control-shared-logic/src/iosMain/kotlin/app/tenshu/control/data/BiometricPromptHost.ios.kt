package app.tenshu.control.data

// iOS has no host-window requirement for LocalAuthentication — the OS presents the
// Face ID / Touch ID / passcode sheet itself — so the host is an inert marker.
//
// The real iOS `BiometricUnlockDelegate` (`IosBiometricUnlockDelegate`, in
// `:control-shared-proxy` iosMain) biometric-protects the wrap-key in a user-presence-gated
// Keychain item and ignores this host; it exists only to satisfy the cross-platform
// `onUnlockBiometric`/`enrollWrapKey` signatures (Android threads a `FragmentActivity` through it).
actual class BiometricPromptHost

package org.tenshu.inference.harness.ios

/**
 * Whether this binary was compiled for a simulator target.
 *
 * ⚠⚠ **A simulator must never produce a figure** (`ios-on-device-inference.md` §4.1, after
 * `inference-service.md` §15.1). It runs on the Mac's CPU with no Neural Engine, so a number taken
 * there is a Mac's number under an iPhone's name, and unlike a missing number a wrong one gets
 * quoted. A compile-time fact per target, so no environment variable can talk the harness into it.
 */
internal expect val builtForSimulator: Boolean

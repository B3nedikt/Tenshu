// Chrome refuses to start as root unless its sandbox is off ("Running as root without
// --no-sandbox is not supported"). Claude Code on the web runs sessions as root, so there
// `:timeline-projection:jsTest` — the run that proves the shared feed projection on JS — could
// never launch a browser. Only root gets the flag: a maintainer's machine keeps the sandboxed
// ChromeHeadless the Kotlin plugin picks. Same snippet as `tenshu-sdk-js/karma.config.d/`.
if (typeof process.getuid === 'function' && process.getuid() === 0) {
    config.set({
        browsers: ['ChromeHeadlessNoSandbox'],
        customLaunchers: {
            ChromeHeadlessNoSandbox: {
                base: 'ChromeHeadless',
                flags: ['--no-sandbox'],
            },
        },
    })
}

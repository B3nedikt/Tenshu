// Chrome refuses to start as root unless its sandbox is off ("Running as root without
// --no-sandbox is not supported"). Claude Code on the web runs sessions as root, so there no
// `jsTest` could launch a browser. Only root gets the flag: a maintainer's machine and GitHub's
// runners keep the sandboxed ChromeHeadless the Kotlin plugin picks. Applied to every module from
// the root build — see `mocha-timeout.js` beside this file.
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

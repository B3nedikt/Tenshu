// Emit an ES-module webpack bundle (instead of the default UMD wrapper) so
// Vite — which loads the bundle via `import bundle from '../../../.../tenshu-sdk-js.js'`
// inside web/sdk's `"type": "module"` package — gets a proper `default`
// export. UMD's `module.exports = …` assignment isn't visible to Vite's
// strict ESM resolver (Vite 8+); without this override the import errors
// out with "does not provide an export named 'default'".
//
// `useEsModules()` in tenshu-sdk-js/build.gradle.kts only affects per-module
// Kotlin output (the .mjs files under build/compileSync/...); the final
// webpack bundle is governed by webpack's own output config, which the
// Kotlin Gradle plugin doesn't override.
//
// Webpack 5 prefers `output.library = { type: ... }` over the older
// `output.libraryTarget` string. The Kotlin plugin sets *both* `library`
// (as a string) and `libraryTarget: "umd"`, so we need to delete the
// legacy property too — otherwise `libraryTarget` wins and the bundle
// stays UMD.
//
// `webpack.config.d/` files are also inlined into karma.conf.js for the
// `jsBrowserTest` task; in karma's context `config.output` is undefined
// at this point in the file, so guard before touching it. We only want
// the override to apply to the production webpack build that emits
// `tenshu-sdk-js.js`.
if (config.output) {
  delete config.output.libraryTarget
  config.output.library = { type: 'module' }
  config.experiments = Object.assign({}, config.experiments, { outputModule: true })
}

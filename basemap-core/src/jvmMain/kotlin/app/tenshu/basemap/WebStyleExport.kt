package app.tenshu.basemap

import java.io.File

/** `:basemap-core:generateWebBasemapStyles` — writes [webStyleBundleJson] to the path in `args[0]`. */
fun main(args: Array<String>) {
    val out = File(args.single())
    out.parentFile.mkdirs()
    out.writeText(webStyleBundleJson())
    println("wrote ${out.path}")
}

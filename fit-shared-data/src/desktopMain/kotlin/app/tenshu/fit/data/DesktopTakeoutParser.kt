package app.tenshu.fit.data

import app.tenshu.fit.data.xml.JvmXmlReader

/**
 * The desktop file-import parser: the JDK's XML for GPX/TCX plus the Garmin SDK for binary FIT.
 *
 * A factory rather than a constructor call in the app module, because [FitDecoder] is `internal` to
 * this module — the app should not have to widen a type's visibility just to assemble a parser.
 */
fun desktopTakeoutParser(): TakeoutParser =
    TakeoutParser(ActivityFileParser(xml = JvmXmlReader(), fit = FitDecoder))

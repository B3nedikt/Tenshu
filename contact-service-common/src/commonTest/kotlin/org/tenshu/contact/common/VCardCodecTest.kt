package org.tenshu.contact.common

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vCard codec (`contact-service-identity-model.md` §5) and, above all, the merge.
 *
 * The guarantee under test is Q6's: **editing a contact must never quietly discard the parts
 * of the record the editor does not understand.** Most of these tests are that one property
 * approached from different angles.
 */
class VCardCodecTest {

    private val appleish = """
        BEGIN:VCARD
        VERSION:3.0
        N:Okonkwo;Mira;;;
        FN:Mira Okonkwo
        ORG:Tenshu;Engineering
        item1.TEL;type=CELL;type=VOICE;type=pref:+44 7700 900123
        TEL;TYPE=HOME:+44 20 7946 0958
        EMAIL;TYPE=INTERNET;TYPE=WORK:mira@example.com
        ADR;TYPE=HOME:;;12 Cornfield Road;Bristol;;BS1 4ST;UK
        NOTE:Met at the conference.
        CATEGORIES:Friends,Climbing
        X-ABLabel:_${'$'}!<Anniversary>!${'$'}_
        X-SOCIALPROFILE;type=twitter:https://twitter.com/example
        UID:1a2b3c
        END:VCARD
    """.trimIndent()

    // ── parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `parses names params and groups`() {
        val properties = parseVCard(appleish)

        val cell = properties.single { it.name == "TEL" && it.group == "item1" }
        assertEquals("+44 7700 900123", cell.value)
        assertTrue(cell.types.any { it.equals("CELL", true) })

        // BEGIN/END/VERSION are structural and are re-emitted on write, never carried.
        assertFalse(properties.any { it.name.uppercase() in setOf("BEGIN", "END", "VERSION") })
    }

    @Test
    fun `unfolds continuation lines`() {
        val folded = "BEGIN:VCARD\r\nVERSION:3.0\r\nNOTE:one two\r\n  three\r\nEND:VCARD\r\n"
        assertEquals("one two three", decodeText(parseVCard(folded).single()))
    }

    @Test
    fun `decodes quoted-printable from a 2_1 export`() {
        // 2.1 exporters use QP for anything non-ASCII; a contact called Inés must not read
        // as "In=C3=A9s".
        val card = "BEGIN:VCARD\nVERSION:2.1\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:In=C3=A9s\nEND:VCARD"
        assertEquals("Inés", readContactCard(card).formattedName)
    }

    @Test
    fun `a quoted-printable soft break is joined even when a fold splits the encoding param`() {
        // The QP marker is found across a fold, and a soft break after it still joins the lines.
        val card = "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
            "NOTE;CHARSET=UTF-8;ENCODING=QUOTED-PRI\r\n NTABLE:caf=C3=A9 =\r\nau lait\r\nEND:VCARD\r\n"
        assertEquals("café au lait", decodeText(parseVCard(card).single()))
    }

    @Test
    fun `a trailing equals sign outside quoted-printable is kept and not taken for a soft break`() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nNOTE:a=\r\nUID:x\r\nEND:VCARD\r\n"
        val properties = parseVCard(card)
        assertEquals("a=", properties.single { it.name == "NOTE" }.value)
        assertEquals("x", properties.single { it.name == "UID" }.value)
    }

    /**
     * A big `PHOTO` is megabytes of base64 folded into tens of thousands of lines. Unfolding it used to
     * re-copy the whole logical line per continuation — quadratic, 15–30 s for one 2 MiB photo, which
     * timed out `ContactPhotoContentPlaneTest` under load.
     *
     * ⚠ Judged by how the time GROWS, not by a wall-clock bound: a debug Kotlin/Native build on a CI
     * simulator is slow enough to miss any fixed limit with the linear code. Four times the input
     * costs about four times as long when linear and about sixteen times when quadratic, so the
     * bound sits between the two. A big parse that is fast in absolute terms passes outright, so
     * millisecond noise on a quick machine cannot fail it.
     */
    @Test
    fun `unfolding a multi-megabyte photo is linear`() {
        fun cardOf(chars: Int): Pair<String, String> {
            val base64 = buildString { repeat(chars) { append('A' + it % 26) } } + "=="
            val card = buildString {
                append("BEGIN:VCARD\r\nVERSION:3.0\r\nPHOTO;ENCODING=b;TYPE=JPEG:")
                var index = 0
                while (index < base64.length) {
                    if (index > 0) append("\r\n ")
                    val end = minOf(index + 74, base64.length)
                    append(base64, index, end)
                    index = end
                }
                append("\r\nEND:VCARD\r\n")
            }
            return base64 to card
        }
        fun timeToUnfold(chars: Int): kotlin.time.Duration {
            val (base64, card) = cardOf(chars)
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            val photo = parseVCard(card).single()
            val elapsed = started.elapsedNow()
            assertEquals(base64, photo.value)
            return elapsed
        }

        timeToUnfold(100_000) // warm-up
        val small = timeToUnfold(750_000)
        val big = timeToUnfold(3_000_000)

        val fastOutright = big < kotlin.time.Duration.parse("1s")
        val ratio = big / small.coerceAtLeast(kotlin.time.Duration.parse("1ms"))
        assertTrue(fastOutright || ratio < 8.0, "4× the input took ${ratio}× as long ($small → $big): not linear")
    }

    @Test
    fun `reads the editable projection`() {
        val card = readContactCard(appleish)

        assertEquals("Mira Okonkwo", card.formattedName)
        assertEquals(listOf("+44 7700 900123", "+44 20 7946 0958"), card.phones.map { it.value })
        assertEquals("cell", card.phones.first().label, "PREF is an ordering hint, not a label")
        assertEquals(listOf("mira@example.com"), card.emails.map { it.value })
        assertEquals("work", card.emails.first().label, "INTERNET is what an email is, not its label")
        assertEquals("Tenshu, Engineering", card.organisation)
        assertEquals("Met at the conference.", card.note)
        assertContains(card.addresses.single().value, "Bristol")
    }

    @Test
    fun `kind types and comma lists never become the label`() {
        val card = readContactCard(
            "BEGIN:VCARD\nVERSION:3.0\nFN:A\nTEL;TYPE=VOICE;TYPE=HOME:+1 555 0100\n" +
                "TEL;TYPE=WORK,VOICE,PREF:+1 555 0101\nEMAIL;TYPE=INTERNET:a@example.com\nEND:VCARD",
        )
        assertEquals(listOf("home", "work"), card.phones.map { it.label })
        assertEquals("", card.emails.single().label, "an email typed only INTERNET has no label")
    }

    @Test
    fun `an edited label keeps the kind types beside it`() {
        val card = readContactCard(appleish)
        val merged = mergeIntoVCard(appleish, card.copy(emails = listOf(card.emails.single().copy(label = "home"))))
        val email = parseVCard(merged).single { it.name == "EMAIL" }
        assertEquals(listOf("INTERNET", "HOME"), email.types)
        assertEquals("home", readContactCard(merged).emails.single().label)
    }

    @Test
    fun `a save keeps the kind members of a comma list`() {
        val original = "BEGIN:VCARD\nVERSION:3.0\nFN:A\nTEL;TYPE=WORK,VOICE,PREF:+1 555 0101\nNOTE:x\nEND:VCARD"
        val card = readContactCard(original)
        val merged = mergeIntoVCard(original, card.copy(note = "y"))
        val tel = parseVCard(merged).single { it.name == "TEL" }
        assertEquals(listOf("VOICE", "PREF", "WORK"), tel.types)
        assertEquals("work", readContactCard(merged).phones.single().label)
    }

    @Test
    fun `a 2_1 bare type is written back as TYPE=`() {
        val original = "BEGIN:VCARD\nVERSION:2.1\nFN:A\nEMAIL;INTERNET;HOME:a@example.com\nEND:VCARD"
        val card = readContactCard(original)
        val merged = mergeIntoVCard(original, card.copy(note = "y"))
        val line = merged.lines().single { it.startsWith("EMAIL") }
        assertEquals("EMAIL;TYPE=INTERNET;TYPE=HOME:a@example.com", line.trimEnd('\r'))
    }

    @Test
    fun `splits a multi-card file`() {
        val two = appleish + "\n" + appleish
        assertEquals(2, splitVCards(two).size)
    }

    // ── the merge: what must survive ─────────────────────────────────────────

    @Test
    fun `an untouched record round-trips its unknown properties`() {
        // ⚠⚠ The core guarantee. Everything the editor does not model has to come back.
        val merged = mergeIntoVCard(appleish, readContactCard(appleish))

        assertContains(merged, "CATEGORIES:Friends,Climbing")
        assertContains(merged, "X-SOCIALPROFILE;type=twitter:https://twitter.com/example")
        assertContains(merged, "UID:1a2b3c")
        assertTrue(merged.contains("N:Okonkwo;Mira;;;"), "N is not editable and must survive verbatim")
        assertContains(merged, "X-ABLabel:")
    }

    @Test
    fun `editing one field leaves every other property alone`() {
        val edited = readContactCard(appleish).copy(note = "Moved to Lisbon.")
        val merged = mergeIntoVCard(appleish, edited)

        assertContains(merged, "NOTE:Moved to Lisbon.")
        assertContains(merged, "CATEGORIES:Friends,Climbing")
        assertContains(merged, "UID:1a2b3c")
        assertEquals("Mira Okonkwo", readContactCard(merged).formattedName)
    }

    @Test
    fun `a rewritten property keeps its group and non-type params`() {
        // Apple's `item1.` grouping binds a property to its custom X-ABLabel. Losing the
        // group on edit silently orphans the label.
        val card = readContactCard(appleish)
        val edited = card.copy(
            phones = listOf(card.phones[0].copy(value = "+44 7700 900999")) + card.phones.drop(1),
        )
        val merged = mergeIntoVCard(appleish, edited)

        assertContains(merged, "item1.TEL")
        assertContains(merged, "+44 7700 900999")
    }

    @Test
    fun `adding a value appends it beside its own kind`() {
        val card = readContactCard(appleish)
        val merged = mergeIntoVCard(
            appleish,
            card.copy(emails = card.emails + LabeledValue("home", "mira@personal.example")),
        )

        assertEquals(
            listOf("mira@example.com", "mira@personal.example"),
            readContactCard(merged).emails.map { it.value },
        )
        assertTrue(merged.contains("UID:1a2b3c"), "appending must not disturb the rest")
    }

    @Test
    fun `removing a value drops only that property`() {
        val card = readContactCard(appleish)
        val merged = mergeIntoVCard(appleish, card.copy(phones = card.phones.take(1)))

        assertEquals(listOf("+44 7700 900123"), readContactCard(merged).phones.map { it.value })
        assertContains(merged, "EMAIL")
        assertContains(merged, "CATEGORIES:Friends,Climbing")
    }

    @Test
    fun `a changed label replaces TYPE without touching other params`() {
        val card = readContactCard(appleish)
        val merged = mergeIntoVCard(
            appleish,
            card.copy(phones = listOf(card.phones[0].copy(label = "work")) + card.phones.drop(1)),
        )

        assertEquals("work", readContactCard(merged).phones.first().label)
    }

    @Test
    fun `user text with separators survives a round trip`() {
        // Unescaped, a `;` or `,` would silently split the value into components on re-read.
        val card = readContactCard(appleish).copy(note = "Bristol; then Lisbon, briefly\nnew line")
        val merged = mergeIntoVCard(appleish, card)

        assertEquals("Bristol; then Lisbon, briefly\nnew line", readContactCard(merged).note)
    }

    @Test
    fun `a quoted-printable value edited to plain text stops claiming QP`() {
        val card = "BEGIN:VCARD\nVERSION:2.1\nNOTE;ENCODING=QUOTED-PRINTABLE:caf=C3=A9\nEND:VCARD"
        val merged = mergeIntoVCard(card, readContactCard(card).copy(note = "café"))

        assertFalse(
            merged.contains("QUOTED-PRINTABLE"),
            "the bytes are plain 3.0 now, so the param would be a lie",
        )
        assertEquals("café", readContactCard(merged).note)
    }

    @Test
    fun `a fresh record carries only what was entered`() {
        val created = newVCard(
            ContactCard(
                formattedName = "Dad",
                phones = listOf(LabeledValue("mobile", "+44 7700 900321")),
            )
        )

        val back = readContactCard(created)
        assertEquals("Dad", back.formattedName)
        assertEquals(listOf("+44 7700 900321"), back.phones.map { it.value })
        assertContains(created, "VERSION:3.0")
    }

    @Test
    fun `long values fold and survive unfolding`() {
        val long = "x".repeat(400)
        val merged = mergeIntoVCard(appleish, readContactCard(appleish).copy(note = long))

        assertTrue(merged.lineSequence().all { it.length <= 76 }, "lines are folded")
        assertEquals(long, readContactCard(merged).note)
    }
}

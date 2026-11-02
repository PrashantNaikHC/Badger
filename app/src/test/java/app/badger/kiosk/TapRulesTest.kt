package app.badger.kiosk

import org.junit.Assert.*
import org.junit.Test

class TapRulesTest {
    private val ev = EventEntity(
        id = 1, title = "T", type = EventType.ENTRY_EXIT, createdAt = 0,
        msgIdle = "i", msgSuccess = "s", msgAlready = "a", msgExitSuccess = "x",
        msgAlreadyExited = "ax", msgNoEntry = "ne", msgNotRegistered = "nr"
    )
    private val emp = EmployeeEntity("U1", "Asha", 0)

    private fun show(o: TapOutcome) = o as TapOutcome.Show

    @Test fun unknownCardEntryNeedsRegistration() =
        assertEquals(TapOutcome.NeedsRegistration, TapRules.decide(ev, ScanMode.ENTRY, "U1", null, null, 1))

    @Test fun unknownCardExitIsNotRegistered() {
        val o = show(TapRules.decide(ev, ScanMode.EXIT, "U1", null, null, 1))
        assertFalse(o.result.ok); assertEquals("nr", o.result.title)
    }

    @Test fun knownEmployeeChecksInWithoutReRegistering() {
        val o = show(TapRules.decide(ev, ScanMode.ENTRY, "U1", emp, null, 5))
        assertTrue(o.result.ok); assertEquals(5L, o.write?.checkInAt)
    }

    @Test fun repeatEntryRejected() {
        val o = show(TapRules.decide(ev, ScanMode.ENTRY, "U1", emp, AttendanceEntity(1, "U1", 2), 5))
        assertFalse(o.result.ok); assertNull(o.write)
    }

    @Test fun exitWithoutEntryRejected() =
        assertEquals("ne", show(TapRules.decide(ev, ScanMode.EXIT, "U1", emp, null, 5)).result.title)

    @Test fun exitThenRepeatExit() {
        val a = AttendanceEntity(1, "U1", 2)
        val first = show(TapRules.decide(ev, ScanMode.EXIT, "U1", emp, a, 9))
        assertTrue(first.result.ok); assertEquals(9L, first.write?.checkOutAt)
        val second = show(TapRules.decide(ev, ScanMode.EXIT, "U1", emp, first.write, 10))
        assertEquals("ax", second.result.title)
    }

    @Test fun csvColumnsDependOnType() {
        val rows = listOf(AttendanceRow("U1", "=Bad", 0, null))
        assertTrue(TapRules.buildCsv(ev, rows).startsWith("employeeName,checkInAt,checkOutAt"))
        val one = TapRules.buildCsv(ev.copy(type = EventType.ONE_TIME), rows)
        assertTrue(one.startsWith("employeeName,checkInAt\n"))
        assertTrue(one.contains("\"'=Bad\""))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val files = mutableMapOf<String, String>()
        val zis = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes))
        var entry = zis.nextEntry
        while (entry != null) {
            files[entry.name] = zis.bufferedReader(java.nio.charset.StandardCharsets.UTF_8).readText()
            entry = zis.nextEntry
        }
        return files
    }

    @Test fun xlsxGeneratesValidOpenXmlZipStructure() {
        val rows = listOf(
            AttendanceRow("U1", "Alice & Bob <Dev>", 1700000000000L, 1700003600000L),
            AttendanceRow("U2", "Charlie \"VIP\"", 1700001000000L, null)
        )
        val bytes = TapRules.buildXlsx(ev, rows)
        assertTrue("XLSX output should not be empty", bytes.isNotEmpty())

        val files = unzip(bytes)
        assertTrue(files.containsKey("[Content_Types].xml"))
        assertTrue(files.containsKey("_rels/.rels"))
        assertTrue(files.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(files.containsKey("xl/workbook.xml"))
        assertTrue(files.containsKey("xl/styles.xml"))
        assertTrue(files.containsKey("xl/worksheets/sheet1.xml"))

        val sheet = files["xl/worksheets/sheet1.xml"]!!
        // Check Event Banner and title
        assertTrue(sheet.contains(ev.title))
        assertTrue(sheet.contains("Entry / Exit"))
        assertTrue(sheet.contains("TOTAL ATTENDEES"))
        assertTrue(sheet.contains("CURRENTLY INSIDE"))
        assertTrue(sheet.contains("COMPLETED / EXITED"))

        // Check XML escaping
        assertTrue(sheet.contains("Alice &amp; Bob &lt;Dev&gt;"))
        assertTrue(sheet.contains("Charlie &quot;VIP&quot;"))

        // Check Status and Duration
        assertTrue(sheet.contains("1h 0m"))
        assertTrue(sheet.contains("Exited"))
        assertTrue(sheet.contains("Inside"))

        // Check autoFilter on entry/exit
        assertTrue(sheet.contains("""<autoFilter ref="A7:E9"/>"""))
    }

    @Test fun xlsxOneTimeEventStructure() {
        val oneTimeEv = ev.copy(type = EventType.ONE_TIME, title = "Annual Meet")
        val rows = listOf(AttendanceRow("U1", "Dana", 1700000000000L, null))
        val bytes = TapRules.buildXlsx(oneTimeEv, rows)
        val files = unzip(bytes)
        val sheet = files["xl/worksheets/sheet1.xml"]!!

        assertTrue(sheet.contains("Annual Meet"))
        assertTrue(sheet.contains("One-Time"))
        assertTrue(sheet.contains("TOTAL ATTENDEES"))
        assertFalse(sheet.contains("CURRENTLY INSIDE"))
        assertTrue(sheet.contains("Employee Name"))
        assertTrue(sheet.contains("Check-in Time"))
        assertFalse(sheet.contains("Check-out Time"))
        assertTrue(sheet.contains("""<autoFilter ref="A7:B8"/>"""))
    }

    @Test fun nameExtractorUsesConfiguredAnchors() {
        // Multi-line: Name between "EMPLOYEE" and "ID"
        val lines1 = listOf(
            "ACME CORPORATION",
            "EMPLOYEE",
            "Govind",
            "Sharma",
            "ID 123456"
        )
        assertEquals("Govind Sharma", NameExtractor.extract(lines1, leftAnchor = "EMPLOYEE", rightAnchor = "ID"))

        // Right anchor only (name starts at the beginning/top)
        val lines2 = listOf(
            "Priya Patel",
            "DESIGNATION SOFTWARE ENGINEER",
            "CARD NO 9988"
        )
        assertEquals("Priya Patel", NameExtractor.extract(lines2, leftAnchor = null, rightAnchor = "DESIGNATION"))

        // Left anchor only (name goes to the end)
        val lines3 = listOf(
            "VISITOR PASS",
            "NAME",
            "Rahul",
            "Verma"
        )
        assertEquals("Rahul Verma", NameExtractor.extract(lines3, leftAnchor = "NAME", rightAnchor = null))

        // Same line anchors: e.g. "Name: Sarah Connor Date: 2026"
        val lines4 = listOf("Company XYZ", "Name: Sarah Connor Date: 2026")
        assertEquals("Sarah Connor", NameExtractor.extract(lines4, leftAnchor = "Name", rightAnchor = "Date"))

        // Neither anchor specified without fallback
        assertNull(NameExtractor.extract(lines1, null, null, allowFallback = false))

        // Neither anchor specified WITH fallback to smart heuristics
        assertEquals("Govind Sharma", NameExtractor.extract(lines1, null, null, allowFallback = true))
    }

    @Test fun smartNameDetectorExtractsRealWorldBadge() {
        // User's exact prompt scenario: "GOOGLE | Govinda | Rathod | G45098 | Bangalore"
        val lines = listOf(
            "GOOGLE",
            "Govinda",
            "Rathod",
            "G45098",
            "Bangalore"
        )
        val extracted = SmartNameDetector.extractNameFallback(lines)
        assertEquals("Govinda Rathod", extracted)
    }

    @Test fun nameExtractorFuzzyAnchorMatching() {
        // OCR character substitution: "G00GLE" instead of "GOOGLE"
        val linesWithOcrNoise = listOf(
            "G00GLE",
            "Govinda",
            "Rathod",
            "G45098",
            "Bangalore"
        )
        // With leftAnchor = "GOOGLE" (1 edit distance from G00GLE), fuzzy anchor matching succeeds
        val result = NameExtractor.extract(linesWithOcrNoise, leftAnchor = "GOOGLE", rightAnchor = "G45098")
        assertEquals("Govinda Rathod", result)
    }

    @Test fun nameExtractorSmartFallbackWhenAnchorMissing() {
        val lines = listOf(
            "Acme Technologies",
            "Sarah Connor",
            "EMP 99120",
            "California"
        )
        // Anchor "NON_EXISTENT" is missing, but allowFallback = true should salvage the name
        val result = NameExtractor.extract(lines, leftAnchor = "NON_EXISTENT", rightAnchor = null, allowFallback = true)
        assertEquals("Sarah Connor", result)
    }
}


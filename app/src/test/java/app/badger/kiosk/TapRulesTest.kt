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

        // Neither anchor specified
        assertNull(NameExtractor.extract(lines1, null, null))
    }
}

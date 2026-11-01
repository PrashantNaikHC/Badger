package app.badger.kiosk

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Generates rich OpenXML (.xlsx) spreadsheets without third-party dependencies.
 * Compatible with Microsoft Excel, Google Sheets, LibreOffice, and Apple Numbers.
 */
object XlsxExporter {

    private fun escapeXml(text: String): String {
        return buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun formatDuration(startMs: Long, endMs: Long?): String {
        if (endMs == null || endMs < startMs) return "—"
        val diffSec = (endMs - startMs) / 1000
        val hours = diffSec / 3600
        val minutes = (diffSec % 3600) / 60
        val seconds = diffSec % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun build(event: EventEntity, rows: List<AttendanceRow>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun writeEntry(name: String, content: String) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }

            writeEntry("[Content_Types].xml", contentTypesXml)
            writeEntry("_rels/.rels", rootRelsXml)
            writeEntry("xl/_rels/workbook.xml.rels", workbookRelsXml)
            writeEntry("xl/workbook.xml", workbookXml)
            writeEntry("xl/styles.xml", stylesXml)
            writeEntry("xl/worksheets/sheet1.xml", buildSheetXml(event, rows))
        }
        return out.toByteArray()
    }

    private val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""".trimIndent()

    private val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()

    private val workbookRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>""".trimIndent()

    private val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Attendance" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>""".trimIndent()

    private val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="8">
    <font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="16"/><color rgb="FF0F172A"/><name val="Calibri"/><family val="2"/></font>
    <font><sz val="10"/><color rgb="FF64748B"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="9"/><color rgb="FF475569"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="14"/><color rgb="FF0F172A"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><sz val="10"/><color rgb="FF065F46"/><name val="Calibri"/><family val="2"/></font>
    <font><sz val="10"/><color rgb="FF475569"/><name val="Calibri"/><family val="2"/></font>
  </fonts>
  <fills count="7">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF1F5F9"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1E293B"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD1FAE5"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFE2E8F0"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF8FAFC"/></patternFill></fill>
  </fills>
  <borders count="3">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFE2E8F0"/></left>
      <right style="thin"><color rgb="FFE2E8F0"/></right>
      <top style="thin"><color rgb="FFE2E8F0"/></top>
      <bottom style="thin"><color rgb="FFE2E8F0"/></bottom>
      <diagonal/>
    </border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="12">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1">
      <alignment vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1">
      <alignment vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="3" fillId="2" borderId="2" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="4" fillId="2" borderId="2" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="5" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1">
      <alignment horizontal="left" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="left" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="6" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
    <xf numFmtId="0" fontId="7" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
      <alignment horizontal="center" vertical="center"/>
    </xf>
  </cellXfs>
  <cellStyles count="1">
    <cellStyle name="Normal" xfId="0" builtinId="0"/>
  </cellStyles>
</styleSheet>""".trimIndent()

    private fun buildSheetXml(event: EventEntity, rows: List<AttendanceRow>): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val exportTime = dateFormat.format(Date())
        val hasExit = event.hasExit
        val insideCount = if (hasExit) rows.count { it.checkOutAt == null } else 0
        val exitedCount = if (hasExit) rows.count { it.checkOutAt != null } else 0

        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            append("""<sheetViews><sheetView tabSelected="1" workbookViewId="0" showGridLines="1"/></sheetViews>""")

            // Column widths
            append("<cols>")
            append("""<col min="1" max="1" width="30" customWidth="1"/>""")
            append("""<col min="2" max="2" width="22" customWidth="1"/>""")
            if (hasExit) {
                append("""<col min="3" max="3" width="22" customWidth="1"/>""")
                append("""<col min="4" max="4" width="18" customWidth="1"/>""")
                append("""<col min="5" max="5" width="16" customWidth="1"/>""")
            }
            append("</cols>")

            append("<sheetData>")

            // Row 1: Event Title
            append("""<row r="1" ht="28" customHeight="1">""")
            append("""<c r="A1" t="inlineStr" s="1"><is><t xml:space="preserve">${escapeXml(event.title)}</t></is></c>""")
            append("</row>")

            // Row 2: Event Subtitle / Metadata
            val typeStr = if (hasExit) "Entry / Exit" else "One-Time"
            val statusStr = if (event.status == EventStatus.ACTIVE) "Active" else "Ended"
            val subtitle = "Type: $typeStr   •   Status: $statusStr   •   Exported: $exportTime"
            append("""<row r="2" ht="20" customHeight="1">""")
            append("""<c r="A2" t="inlineStr" s="2"><is><t xml:space="preserve">${escapeXml(subtitle)}</t></is></c>""")
            append("</row>")

            // Row 3: Blank separator
            append("""<row r="3" ht="12" customHeight="1"/>""")

            // Row 4: KPI Labels
            append("""<row r="4" ht="18" customHeight="1">""")
            append("""<c r="A4" t="inlineStr" s="3"><is><t>TOTAL ATTENDEES</t></is></c>""")
            if (hasExit) {
                append("""<c r="B4" t="inlineStr" s="3"><is><t>CURRENTLY INSIDE</t></is></c>""")
                append("""<c r="C4" t="inlineStr" s="3"><is><t>COMPLETED / EXITED</t></is></c>""")
            }
            append("</row>")

            // Row 5: KPI Values
            append("""<row r="5" ht="26" customHeight="1">""")
            append("""<c r="A5" s="4"><v>${rows.size}</v></c>""")
            if (hasExit) {
                append("""<c r="B5" s="4"><v>$insideCount</v></c>""")
                append("""<c r="C5" s="4"><v>$exitedCount</v></c>""")
            }
            append("</row>")

            // Row 6: Blank separator
            append("""<row r="6" ht="14" customHeight="1"/>""")

            // Row 7: Table Headers
            append("""<row r="7" ht="24" customHeight="1">""")
            append("""<c r="A7" t="inlineStr" s="5"><is><t>Employee Name</t></is></c>""")
            append("""<c r="B7" t="inlineStr" s="5"><is><t>Check-in Time</t></is></c>""")
            if (hasExit) {
                append("""<c r="C7" t="inlineStr" s="5"><is><t>Check-out Time</t></is></c>""")
                append("""<c r="D7" t="inlineStr" s="5"><is><t>Duration</t></is></c>""")
                append("""<c r="E7" t="inlineStr" s="5"><is><t>Status</t></is></c>""")
            }
            append("</row>")

            // Rows 8+: Attendance Records
            rows.forEachIndexed { index, row ->
                val rowNum = 8 + index
                val isEven = index % 2 == 1
                val leftXf = if (isEven) 8 else 6
                val centerXf = if (isEven) 9 else 7
                val checkIn = dateFormat.format(Date(row.checkInAt))

                append("""<row r="$rowNum" ht="20" customHeight="1">""")
                append("""<c r="A$rowNum" t="inlineStr" s="$leftXf"><is><t xml:space="preserve">${escapeXml(row.name)}</t></is></c>""")
                append("""<c r="B$rowNum" t="inlineStr" s="$centerXf"><is><t>$checkIn</t></is></c>""")

                if (hasExit) {
                    val checkOut = row.checkOutAt?.let { dateFormat.format(Date(it)) } ?: "—"
                    val duration = formatDuration(row.checkInAt, row.checkOutAt)
                    val isExited = row.checkOutAt != null
                    val statusText = if (isExited) "Exited" else "Inside"
                    val statusXf = if (isExited) 11 else 10

                    append("""<c r="C$rowNum" t="inlineStr" s="$centerXf"><is><t>$checkOut</t></is></c>""")
                    append("""<c r="D$rowNum" t="inlineStr" s="$centerXf"><is><t>$duration</t></is></c>""")
                    append("""<c r="E$rowNum" t="inlineStr" s="$statusXf"><is><t>$statusText</t></is></c>""")
                }
                append("</row>")
            }

            append("</sheetData>")

            if (rows.isNotEmpty()) {
                val lastCol = if (hasExit) "E" else "B"
                val lastRow = 7 + rows.size
                append("""<autoFilter ref="A7:$lastCol$lastRow"/>""")
            }

            append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
            append("</worksheet>")
        }
    }
}

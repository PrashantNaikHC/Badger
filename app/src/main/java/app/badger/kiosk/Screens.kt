package app.badger.kiosk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────── Home: Active / Past / Data tabs ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    active: List<EventWithCounts>, past: List<EventWithCounts>,
    employees: List<EmployeeEntity>, adminCards: List<AdminCardEntity>,
    templates: List<IdCardTemplateEntity>,
    enrollingAdminCard: Boolean, nfcAvailable: Boolean,
    onOpen: (Long) -> Unit, onCreate: () -> Unit,
    onCreateTemplate: () -> Unit,
    onDeleteTemplate: (Long) -> Unit,
    onRemoveEmployee: (String) -> Unit,
    onToggleEmployeeAdmin: (String, Boolean) -> Unit,
    onStartEnrollAdminCard: () -> Unit,
    onCancelEnrollAdminCard: () -> Unit,
    onRemoveAdminCard: (String) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmRemoveEmployee by remember { mutableStateOf<EmployeeEntity?>(null) }
    var confirmRemoveAdminCard by remember { mutableStateOf<AdminCardEntity?>(null) }
    var confirmDeleteTemplate by remember { mutableStateOf<IdCardTemplateEntity?>(null) }

    Scaffold(
        containerColor = IdleBg,
        topBar = { TopAppBar(title = { Text(if (tab == 2) "Badger · Data" else "Badger", fontWeight = FontWeight.Bold) }) },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Active") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.DateRange, null) }, label = { Text("Past") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Person, null) }, label = { Text("Data") })
            }
        },
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(
                onClick = onCreate, containerColor = Ink, contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("New event") }
            )
        }
    ) { pad ->
        if (tab == 2) {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Device Data & Governance", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "All data is stored locally on this device only. Each record links an employee name and registration timestamp to a hardware card UID. Raw NFC UIDs are never displayed or exported, and camera frames during registration are processed in memory without saving photos.",
                                color = Muted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("ID Card Templates", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Configure your organization's ID badge layout by scanning an ID card with the back camera and picking start and end text anchors.",
                                color = Muted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onCreateTemplate,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("New ID Card Template")
                            }
                        }
                    }
                }
                if (templates.isNotEmpty()) {
                    item {
                        SectionLabel("Configured Templates (${templates.size})")
                    }
                    items(templates, key = { "tpl_${it.id}" }) { tpl ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(tpl.name, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    val left = tpl.leftAnchor?.let { "“$it”" } ?: "Top of badge"
                                    val right = tpl.rightAnchor?.let { "“$it”" } ?: "Bottom of badge"
                                    Text(
                                        "Between $left and $right",
                                        color = Muted,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton({ confirmDeleteTemplate = tpl }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete template", tint = ErrorRed)
                                }
                            }
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Scanner Admin Lock", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Exiting the public scanner screen requires either Device Screen Lock (PIN, pattern, or biometric) or tapping a designated Admin NFC card.",
                                color = Muted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = onStartEnrollAdminCard,
                                enabled = nfcAvailable,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (nfcAvailable) "Tap a card to add as Admin" else "Enable NFC to add Admin cards")
                            }
                        }
                    }
                }
                if (adminCards.isNotEmpty()) {
                    item {
                        SectionLabel("Standalone Admin Cards (${adminCards.size})")
                    }
                    items(adminCards, key = { "admin_${it.nfcUid}" }) { card ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(card.label, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Added ${TapRules.fmt(card.addedAt)} · Can unlock scanner",
                                        color = Muted,
                                        fontSize = 12.sp
                                    )
                                }
                                IconButton({ confirmRemoveAdminCard = card }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove admin card", tint = ErrorRed)
                                }
                            }
                        }
                    }
                }
                item {
                    SectionLabel("Registered employees (${employees.size})")
                }
                if (employees.isEmpty()) {
                    item {
                        Text(
                            "No registered employees stored on this device.",
                            color = Muted,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(employees, key = { it.nfcUid }) { emp ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(emp.name, fontWeight = FontWeight.SemiBold)
                                        if (emp.isAdmin) {
                                            Spacer(Modifier.width(8.dp))
                                            Surface(
                                                color = Color(0xFFE8EAF6),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    "ADMIN",
                                                    color = Ink,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "Hardware UID linked · Registered ${TapRules.fmt(emp.registeredAt)}",
                                        color = Muted,
                                        fontSize = 12.sp
                                    )
                                }
                                FilterChip(
                                    selected = emp.isAdmin,
                                    onClick = { onToggleEmployeeAdmin(emp.nfcUid, !emp.isAdmin) },
                                    label = { Text("Admin", fontSize = 12.sp) }
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton({ confirmRemoveEmployee = emp }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove employee", tint = ErrorRed)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val list = if (tab == 0) active else past
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                    Text(
                        if (tab == 0) "No active events.\nTap “New event” to start one." else "Ended events appear here.",
                        color = Muted, textAlign = TextAlign.Center
                    )
                }
            } else LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(list, key = { it.event.id }) { EventCard(it) { onOpen(it.event.id) } }
            }
        }
    }

    confirmRemoveEmployee?.let { emp ->
        AlertDialog(
            onDismissRequest = { confirmRemoveEmployee = null },
            title = { Text("Remove ${emp.name}?") },
            text = { Text("This deletes their registration and linked hardware UID from the device, along with their event attendance records.") },
            confirmButton = {
                TextButton({
                    onRemoveEmployee(emp.nfcUid)
                    confirmRemoveEmployee = null
                }) { Text("Remove", color = ErrorRed) }
            },
            dismissButton = { TextButton({ confirmRemoveEmployee = null }) { Text("Cancel") } }
        )
    }

    confirmRemoveAdminCard?.let { card ->
        AlertDialog(
            onDismissRequest = { confirmRemoveAdminCard = null },
            title = { Text("Remove ${card.label}?") },
            text = { Text("This card will no longer be able to unlock the scanner.") },
            confirmButton = {
                TextButton({
                    onRemoveAdminCard(card.nfcUid)
                    confirmRemoveAdminCard = null
                }) { Text("Remove", color = ErrorRed) }
            },
            dismissButton = { TextButton({ confirmRemoveAdminCard = null }) { Text("Cancel") } }
        )
    }

    confirmDeleteTemplate?.let { tpl ->
        AlertDialog(
            onDismissRequest = { confirmDeleteTemplate = null },
            title = { Text("Delete template \"${tpl.name}\"?") },
            text = { Text("Events linked to this template may require a template before running the scanner.") },
            confirmButton = {
                TextButton({
                    onDeleteTemplate(tpl.id)
                    confirmDeleteTemplate = null
                }) { Text("Delete", color = ErrorRed) }
            },
            dismissButton = { TextButton({ confirmDeleteTemplate = null }) { Text("Cancel") } }
        )
    }

    if (enrollingAdminCard) {
        AlertDialog(
            onDismissRequest = onCancelEnrollAdminCard,
            title = { Text("Tap Admin NFC Card") },
            text = { Text("Hold the NFC card against the back of the device to register it as an Admin card.") },
            confirmButton = {},
            dismissButton = { TextButton(onCancelEnrollAdminCard) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EventCard(row: EventWithCounts, onClick: () -> Unit) {
    val e = row.event
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(e.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(if (e.hasExit) "Entry & exit" else "One-time check-in", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            val counts = if (e.hasExit) "${row.checkedIn} checked in · ${row.exited} exited" else "${row.checkedIn} checked in"
            Text(counts, color = Ink, fontWeight = FontWeight.Medium)
            if (e.status == EventStatus.ENDED)
                Text("Ended ${TapRules.fmt(e.endedAt)}", color = Muted, fontSize = 12.sp)
        }
    }
}

// ───────────────────────── Create event ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateEventScreen(
    templates: List<IdCardTemplateEntity>,
    onBack: () -> Unit,
    onCreateTemplate: () -> Unit,
    onCreate: (EventEntity) -> Unit
) {
    var template by rememberSaveable { mutableStateOf(EventTemplate.GIFT) }
    var title by rememberSaveable { mutableStateOf(EventTemplate.GIFT.defaultTitle) }
    var selectedTemplateId by rememberSaveable { mutableStateOf(templates.firstOrNull()?.id) }
    var templateDropdownExpanded by remember { mutableStateOf(false) }
    var entryExit by rememberSaveable { mutableStateOf(EventTemplate.GIFT.entryExit) }
    var idle by rememberSaveable { mutableStateOf(EventTemplate.IDLE) }
    var success by rememberSaveable { mutableStateOf(EventTemplate.GIFT.success) }
    var already by rememberSaveable { mutableStateOf(EventTemplate.GIFT.already) }
    var exitSuccess by rememberSaveable { mutableStateOf(EventTemplate.EXIT_SUCCESS) }
    var alreadyExited by rememberSaveable { mutableStateOf(EventTemplate.ALREADY_EXITED) }
    var noEntry by rememberSaveable { mutableStateOf(EventTemplate.NO_ENTRY) }
    var notRegistered by rememberSaveable { mutableStateOf(EventTemplate.NOT_REGISTERED) }

    fun apply(t: EventTemplate) {
        template = t
        title = t.defaultTitle
        entryExit = t.entryExit
        success = t.success
        already = t.already
    }

    val valid = title.isNotBlank() && idle.isNotBlank() && success.isNotBlank() && already.isNotBlank() &&
        (!entryExit || (exitSuccess.isNotBlank() && alreadyExited.isNotBlank() && noEntry.isNotBlank() && notRegistered.isNotBlank()))

    Scaffold(
        containerColor = IdleBg,
        topBar = {
            TopAppBar(
                title = { Text("New event") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 4.dp) {
                Button(
                    onClick = {
                        onCreate(
                            EventEntity(
                                title = title.trim(),
                                type = if (entryExit) EventType.ENTRY_EXIT else EventType.ONE_TIME,
                                templateId = selectedTemplateId,
                                createdAt = System.currentTimeMillis(),
                                msgIdle = idle.trim(), msgSuccess = success.trim(), msgAlready = already.trim(),
                                msgExitSuccess = exitSuccess.trim(), msgAlreadyExited = alreadyExited.trim(),
                                msgNoEntry = noEntry.trim(), msgNotRegistered = notRegistered.trim()
                            )
                        )
                    },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(52.dp)
                ) { Text("Create event", fontSize = 16.sp) }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionLabel("Start from a template")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventTemplate.entries.forEach {
                    FilterChip(template == it, { apply(it) }, label = { Text(it.label) })
                }
            }
            SectionLabel("Event")
            Field("Event title", title) { title = it }

            SectionLabel("ID Card OCR Template")
            if (templates.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("No ID card templates created yet.", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "New employees registering badges at this event will need a template to extract their names from ID cards.",
                            color = Muted, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onCreateTemplate) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Create ID Card Template")
                        }
                    }
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = templateDropdownExpanded,
                    onExpandedChange = { templateDropdownExpanded = it }
                ) {
                    val currentTemplateName = templates.find { it.id == selectedTemplateId }?.name ?: "Select a template"
                    OutlinedTextField(
                        value = currentTemplateName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Badge Template") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateDropdownExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = templateDropdownExpanded,
                        onDismissRequest = { templateDropdownExpanded = false }
                    ) {
                        templates.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                onClick = {
                                    selectedTemplateId = t.id
                                    templateDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { entryExit = !entryExit },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(entryExit, { entryExit = it })
                Column {
                    Text("Has entry & exit", fontWeight = FontWeight.Medium)
                    Text(
                        if (entryExit) "People tap in and tap out" else "One-time check-in only",
                        color = Muted, fontSize = 12.sp
                    )
                }
            }
            SectionLabel("Messages shown on the scanner")
            Field("Idle prompt", idle) { idle = it }
            Field(if (entryExit) "Entry success" else "Success", success) { success = it }
            Field(if (entryExit) "Already checked in" else "Already done", already) { already = it }
            if (entryExit) {
                SectionLabel("Exit scanner messages")
                Field("Exit success", exitSuccess) { exitSuccess = it }
                Field("Already exited", alreadyExited) { alreadyExited = it }
                Field("Exit without entry", noEntry) { noEntry = it }
                Field("Card not registered", notRegistered) { notRegistered = it }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    )

// ───────────────────────── Event detail ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun EventDetailScreen(
    event: EventEntity?, rows: List<AttendanceRow>,
    onBack: () -> Unit, onScan: (ScanMode) -> Unit, onExport: () -> Unit,
    onEnd: () -> Unit, onReopen: () -> Unit, onRemove: (Set<String>) -> Unit
) {
    if (event == null) { Box(Modifier.fillMaxSize()) ; return }
    val active = event.status == EventStatus.ACTIVE
    var selected by remember { mutableStateOf(setOf<String>()) }
    var sheetFor by remember { mutableStateOf<AttendanceRow?>(null) }
    var confirmRemove by remember { mutableStateOf<Set<String>?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    BackHandler(selecting) { selected = emptySet() }

    Scaffold(
        containerColor = IdleBg,
        topBar = {
            if (selecting) TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                actions = { IconButton({ confirmRemove = selected }) { Icon(Icons.Default.Delete, "Remove selected") } }
            ) else TopAppBar(
                title = { Text(event.title, maxLines = 1) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (event.hasExit) "Entry & exit" else "One-time check-in", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Stat(rows.size, "Checked in")
                            if (event.hasExit) Stat(rows.count { it.checkOutAt != null }, "Exited")
                        }
                        if (active) {
                            Spacer(Modifier.height(16.dp))
                            if (event.hasExit) {
                                Button({ onScan(ScanMode.ENTRY) }, Modifier.fillMaxWidth().height(48.dp)) {
                                    Text("Start entry scanner")
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton({ onScan(ScanMode.EXIT) }, Modifier.weight(1f).height(48.dp)) {
                                        Text("Start exit scanner")
                                    }
                                    OutlinedButton(
                                        { confirmEnd = true },
                                        Modifier.height(48.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                                    ) {
                                        Text("End event")
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ onScan(ScanMode.ENTRY) }, Modifier.weight(1f).height(48.dp)) {
                                        Text("Start scanner")
                                    }
                                    OutlinedButton(
                                        { confirmEnd = true },
                                        Modifier.height(48.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                                    ) {
                                        Text("End event")
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Ended ${TapRules.fmt(event.endedAt)} · read-only",
                                    color = Muted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onReopen, Modifier.height(40.dp)) {
                                    Text("Reopen event")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Entries (${rows.size})")
                    Spacer(Modifier.weight(1f))
                    TextButton(onExport) { Text("Export CSV") }
                }
            }
            if (rows.isEmpty()) item {
                Text("No entries yet", color = Muted, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
            }
            items(rows, key = { it.nfcUid }) { r ->
                val isSel = r.nfcUid in selected
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isSel) Color(0xFFE8EAF6) else Color.White),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
                        onClick = { if (selecting) selected = selected.toggle(r.nfcUid) else sheetFor = r },
                        onLongClick = { if (active) selected = selected.toggle(r.nfcUid) }
                    )
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) {
                            Checkbox(isSel, null); Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "In ${TapRules.fmt(r.checkInAt)}" +
                                    if (event.hasExit) "  ·  Out ${r.checkOutAt?.let { TapRules.fmt(it) } ?: "—"}" else "",
                                color = Muted, fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { r ->
        ModalBottomSheet({ sheetFor = null }, containerColor = Color.White) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text(r.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                DetailLine("Checked in", TapRules.fmt(r.checkInAt))
                if (event.hasExit) DetailLine("Checked out", r.checkOutAt?.let { TapRules.fmt(it) } ?: "Not yet")
                Spacer(Modifier.height(16.dp))
                if (active) OutlinedButton(
                    { confirmRemove = setOf(r.nfcUid); sheetFor = null },
                    Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) { Text("Remove entry") }
                else Text("Ended events are read-only.", color = Muted, fontSize = 13.sp)
            }
        }
    }

    confirmRemove?.let { uids ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(if (uids.size == 1) "Remove entry?" else "Remove ${uids.size} entries?") },
            text = { Text("The person stays registered and can tap again for this event.") },
            confirmButton = {
                TextButton({ onRemove(uids); selected = emptySet(); confirmRemove = null }) { Text("Remove", color = ErrorRed) }
            },
            dismissButton = { TextButton({ confirmRemove = null }) { Text("Cancel") } }
        )
    }
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End this event?") },
        text = { Text("It moves to Past and becomes read-only. You can reopen it later.") },
        confirmButton = { TextButton({ confirmEnd = false; onEnd() }) { Text("End event") } },
        dismissButton = { TextButton({ confirmEnd = false }) { Text("Cancel") } }
    )
}

private fun Set<String>.toggle(v: String) = if (v in this) this - v else this + v

@Composable
private fun Stat(value: Int, label: String) = Column {
    Text("$value", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
    Text(label, color = Muted, fontSize = 12.sp)
}

@Composable
private fun DetailLine(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(label, color = Muted, modifier = Modifier.weight(1f))
    Text(value, fontWeight = FontWeight.Medium)
}

// ───────────────────────── Scanner (public-facing) ─────────────────────────

@Composable
fun ScannerScreen(
    event: EventEntity?, mode: ScanMode, state: ScanState, nfcAvailable: Boolean, hasCamera: Boolean,
    unlockPromptOpen: Boolean, unlockStatus: UnlockStatus,
    hasDeviceLock: Boolean, hasAdminCards: Boolean,
    leftAnchor: String?, rightAnchor: String?,
    onOcrCandidate: (String?) -> Unit, onCancelRegistration: () -> Unit,
    onRequestUnlock: () -> Unit, onCancelUnlock: () -> Unit, onTriggerDeviceUnlock: () -> Unit
) {
    if (event == null) { Box(Modifier.fillMaxSize()); return }
    BackHandler { onRequestUnlock() }

    Box(Modifier.fillMaxSize()) {
        when (state) {
            is ScanState.Idle ->
                if (nfcAvailable) IdleTapScreen(event.msgIdle)
                else ResultScreen(false, "NFC is off or unavailable", "Enable NFC in Settings")
            is ScanState.Result -> ResultScreen(state.result.ok, state.result.title, state.result.subtitle)
            is ScanState.Registering -> RegistrationScreen(hasCamera, leftAnchor, rightAnchor, onOcrCandidate, onCancelRegistration)
        }
        if (state is ScanState.Idle) {
            Row(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp)
                    .clip(CircleShape).background(Color.White).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (mode == ScanMode.ENTRY) "ENTRY" else "EXIT",
                    color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp
                )
                Text("  ·  ${event.title}", color = Muted, fontSize = 14.sp, maxLines = 1)
            }
        }
    }

    if (unlockPromptOpen) AlertDialog(
        onDismissRequest = onCancelUnlock,
        title = { Text("Admin Unlock Required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        hasAdminCards && hasDeviceLock -> "Tap an Admin NFC card on the back of the device, or unlock with Device Screen Lock to exit the scanner."
                        hasAdminCards -> "Tap an Admin NFC card on the back of the device to exit the scanner."
                        else -> "Unlock with Device Screen Lock (PIN, pattern, or biometric) to exit the scanner."
                    }
                )
                if (unlockStatus == UnlockStatus.REJECTED) {
                    Text(
                        "Card is not registered as an Admin card.",
                        color = ErrorRed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            if (hasDeviceLock) {
                Button(onClick = onTriggerDeviceUnlock) { Text("Use Device Lock") }
            }
        },
        dismissButton = { TextButton(onClick = onCancelUnlock) { Text("Stay") } }
    )
}

@Composable
fun ResultScreen(ok: Boolean, title: String, subtitle: String) {
    val bg = if (ok) Ink else ErrorRed
    Column(
        Modifier.fillMaxSize().background(bg).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { Text(if (ok) "✓" else "✕", color = Color.White, fontSize = 48.sp) }
        Spacer(Modifier.height(32.dp))
        Text(title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 42.sp)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, textAlign = TextAlign.Center)
        }
    }
}

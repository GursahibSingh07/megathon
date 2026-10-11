package com.example.qidk_hack2026.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.qidk_hack2026.R
import com.example.qidk_hack2026.job.Entry
import com.example.qidk_hack2026.job.Job
import com.example.qidk_hack2026.job.JobStore
import com.example.qidk_hack2026.job.Phase
import com.example.qidk_hack2026.job.Record
import com.example.qidk_hack2026.job.ReportPdf
import com.example.qidk_hack2026.job.Speaker
import com.example.qidk_hack2026.ui.components.CardShape
import com.example.qidk_hack2026.ui.components.ChipShape
import com.example.qidk_hack2026.ui.components.Divider
import com.example.qidk_hack2026.ui.components.GlassBackground
import com.example.qidk_hack2026.ui.components.GlassButton
import com.example.qidk_hack2026.ui.components.GlassCard
import com.example.qidk_hack2026.ui.components.GlassIconButton
import com.example.qidk_hack2026.ui.components.PrimaryButton
import com.example.qidk_hack2026.ui.components.SectionLabel
import com.example.qidk_hack2026.ui.components.StatusDot
import com.example.qidk_hack2026.ui.components.glass
import com.example.qidk_hack2026.ui.components.pressable
import com.example.qidk_hack2026.ui.theme.Glass
import com.example.qidk_hack2026.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class Tab(val title: String, val icon: Int) {
    JOB("Active job", R.drawable.ic_job),
    RECORDS("Records", R.drawable.ic_records),
}

@Composable
fun AppShell(
    job: Job,
    records: List<Record>,
    themeMode: ThemeMode,
    setupRemaining: Int,
    onTheme: () -> Unit,
    onSetup: () -> Unit,
) {
    val g = Glass.colors
    var tab by remember { mutableStateOf(Tab.JOB) }
    var open by remember { mutableStateOf<Long?>(null) }

    GlassBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(tab, themeMode, setupRemaining, onTheme, onSetup)
            AnimatedContent(
                tab,
                Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(260)) + slideInVertically(tween(320)) { dir * it / 24 }) togetherWith fadeOut(tween(160))
                },
                label = "tab",
            ) { t ->
                when (t) {
                    Tab.JOB -> ActiveJobScreen(job)
                    Tab.RECORDS -> RecordsScreen(records) { open = it }
                }
            }
        }
        TabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp))

        val rec = records.firstOrNull { it.id == open }
        AnimatedVisibility(
            rec != null,
            enter = fadeIn(tween(220)) + slideInVertically(tween(380)) { it / 3 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 3 },
        ) {
            // Keep the last record while the sheet animates out.
            var shown by remember { mutableStateOf(rec) }
            if (rec != null) shown = rec
            shown?.let { RecordSheet(it) { open = null } }
        }
    }
}

@Composable
private fun Header(tab: Tab, mode: ThemeMode, setupRemaining: Int, onTheme: () -> Unit, onSetup: () -> Unit) {
    val g = Glass.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Voice Fill")
            Spacer(Modifier.height(2.dp))
            AnimatedContent(tab.title, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "title") {
                Text(it, style = MaterialTheme.typography.displaySmall, color = g.text)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (setupRemaining > 0) GlassButton("Setup", onSetup, tint = g.warn)
            GlassButton(
                when (mode) { ThemeMode.AUTO -> "Auto"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" },
                onTheme, icon = R.drawable.ic_theme,
            )
        }
    }
}

@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val g = Glass.colors
    val itemW = 132.dp
    val x by animateDpAsState(itemW * tab.ordinal, spring(dampingRatio = 0.75f, stiffness = 380f), label = "pill")
    Box(modifier.glass(ChipShape, strong = true, elevation = 18.dp).padding(5.dp)) {
        Box(
            Modifier.offset(x = x).width(itemW).height(46.dp)
                .clip(ChipShape).background(Brush.linearGradient(listOf(g.accent.copy(alpha = 0.95f), g.accentEnd.copy(alpha = 0.95f)))),
        )
        Row {
            Tab.entries.forEach { t ->
                val on = t == tab
                Row(
                    Modifier.width(itemW).height(46.dp).clip(ChipShape)
                        .clickable(remember { MutableInteractionSource() }, null) { onTab(t) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(painterResource(t.icon), null, Modifier.size(20.dp), tint = if (on) g.onAccent else g.textMuted)
                    Text(t.title, style = MaterialTheme.typography.labelLarge, color = if (on) g.onAccent else g.textMuted)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- active job

@Composable
fun ActiveJobScreen(job: Job) {
    val context = LocalContext.current
    AnimatedContent(
        job.phase,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(160)) },
        label = "phase",
    ) { phase ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (phase) {
                Phase.IDLE -> Hero(
                    dot = null, label = "Idle", title = "Nothing in progress",
                    body = "Open a form, bring up the voice keyboard, switch to Fill form and tap the mic.",
                )
                Phase.PROCESSING, Phase.FILLING -> {
                    Hero(
                        dot = Glass.colors.busy, label = "Working", live = true,
                        title = if (phase == Phase.PROCESSING) "Understanding what you said" else "Filling the form",
                        body = "This stays on the device.", progress = true,
                    )
                    if (job.transcript.isNotBlank()) Quote(job.transcript)
                }
                Phase.READY -> {
                    Hero(
                        dot = Glass.colors.good, label = "Ready",
                        title = "Ready to fill",
                        body = "Go back to the form and tap the green bubble. The keyboard opens when you do.",
                    )
                    Quote(job.transcript)
                }
                Phase.FILLED, Phase.COMPLETE -> Result(job, onSpeak = {
                    Speaker.speak(context, spokenSummary(job))
                }, onFinish = { JobStore.finish() })
            }
        }
    }
}

private fun spokenSummary(job: Job) =
    if (job.missing.isEmpty()) "The form is complete."
    else "Still empty: " + job.missing.joinToString(", ") + "."

@Composable
private fun Hero(dot: Color?, label: String, title: String, body: String, live: Boolean = false, progress: Boolean = false) {
    val g = Glass.colors
    GlassCard(Modifier.fillMaxWidth(), strong = true, padding = PaddingValues(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dot != null) StatusDot(dot, live)
                SectionLabel(label)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, color = g.text)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = g.textMuted)
            if (progress) {
                Spacer(Modifier.height(6.dp))
                Shimmer()
            }
        }
    }
}

@Composable
private fun Shimmer() {
    val g = Glass.colors
    val t = rememberInfiniteTransition(label = "shimmer")
    val p by t.animateFloat(-0.4f, 1.0f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "p")
    BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(g.textMuted.copy(alpha = 0.16f))) {
        Box(
            Modifier.offset(x = maxWidth * p).width(maxWidth * 0.4f).fillMaxSize().clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(g.accent, g.accentEnd))),
        )
    }
}

@Composable
private fun Quote(text: String) {
    val g = Glass.colors
    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("You said")
        Text("“$text”", style = MaterialTheme.typography.bodyLarge, color = g.textMuted)
    }
}

@Composable
private fun Result(job: Job, onSpeak: () -> Unit, onFinish: () -> Unit) {
    val g = Glass.colors
    val complete = job.phase == Phase.COMPLETE
    Hero(
        dot = if (complete) g.good else g.warn,
        label = if (complete) "Complete" else "Needs you",
        title = job.title.ifBlank { "Form" },
        body = if (complete) "Every field has a value." else
            "${job.entries.size} filled, ${job.missing.size} still empty.",
    )

    if (job.missing.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Still empty")
            GlassCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) {
                Column {
                    job.missing.forEachIndexed { i, name ->
                        if (i > 0) Divider()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusDot(g.warn)
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (name == job.asking) {
                                Text(
                                    "Asking", style = MaterialTheme.typography.labelMedium, color = g.accent,
                                    modifier = Modifier.clip(ChipShape).background(g.accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (job.entries.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Filled")
            GlassCard(padding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)) { EntryList(job.entries) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (!complete) GlassButton("Read aloud", onSpeak, Modifier.weight(1f), icon = R.drawable.ic_speaker)
        if (complete) PrimaryButton("Start a new form", onFinish, Modifier.weight(1f))
        else GlassButton("Finish", onFinish, Modifier.weight(1f), icon = R.drawable.ic_check)
    }
}

@Composable
private fun EntryList(entries: List<Entry>) {
    val g = Glass.colors
    Column {
        entries.forEachIndexed { i, e ->
            if (i > 0) Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(e.label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = g.textMuted)
                Text(e.value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyLarge, color = g.text)
            }
        }
    }
}

// ---------------------------------------------------------------- records

@Composable
fun RecordsScreen(records: List<Record>, onOpen: (Long) -> Unit) {
    val g = Glass.colors
    if (records.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp)) {
            Hero(null, "Empty", "No records yet", "Each form you fill by voice is saved here with a PDF report you can listen to or share.")
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(records, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().glass(CardShape, elevation = 10.dp).pressable({ onOpen(r.id) }).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.title.ifBlank { "Form" }, style = MaterialTheme.typography.titleMedium, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.time)),
                        style = MaterialTheme.typography.bodySmall, color = g.textMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Pill("${r.entries.size} filled", g.good)
                        if (r.missing.isNotEmpty()) Pill("${r.missing.size} empty", g.warn)
                    }
                }
                Icon(painterResource(R.drawable.ic_chevron), null, Modifier.size(18.dp), tint = g.textMuted)
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Row(
        Modifier.clip(ChipShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color, size = 6.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun RecordSheet(record: Record, onClose: () -> Unit) {
    val g = Glass.colors
    val context = LocalContext.current
    var page by remember(record.id) { mutableStateOf<Bitmap?>(null) }
    var speaking by remember { mutableStateOf(false) }
    val pdf = remember(record) { ReportPdf.file(context, record) }

    LaunchedEffect(record) {
        page = withContext(Dispatchers.IO) {
            ReportPdf.create(context, record)
            ReportPdf.render(pdf, 900)
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (g.dark) 0.55f else 0.28f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 36.dp)) {
        Column(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(g.bgBottom.copy(alpha = 0.97f))
                .glass(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), strong = true, elevation = 24.dp)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(horizontal = 20.dp).navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(record.title.ifBlank { "Form" }, style = MaterialTheme.typography.titleLarge, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.time)),
                        style = MaterialTheme.typography.bodySmall, color = g.textMuted,
                    )
                }
                GlassIconButton(R.drawable.ic_close, "Close", { Speaker.stop(); onClose() })
            }

            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                AnimatedContent(page, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(100)) }, label = "page") { bmp ->
                    if (bmp == null) {
                        Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { StatusDot(g.accent, live = true, size = 14.dp) }
                    } else {
                        Image(
                            bmp.asImageBitmap(), "Report preview", contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth().shadow(14.dp, RoundedCornerShape(14.dp), ambientColor = g.shadow, spotColor = g.shadow)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    if (speaking) "Stop" else "Listen",
                    {
                        if (speaking) { Speaker.stop(); speaking = false } else {
                            speaking = true
                            Speaker.speak(context, record.spoken()) { speaking = false }
                        }
                    },
                    Modifier.weight(1f), icon = if (speaking) R.drawable.ic_close else R.drawable.ic_speaker,
                )
                PrimaryButton("Share PDF", { if (pdf.exists()) ReportPdf.share(context, pdf) }, Modifier.weight(1f), enabled = page != null)
                GlassIconButton(R.drawable.ic_trash, "Delete", { Speaker.stop(); JobStore.delete(record.id); onClose() }, tint = g.busy, size = 48.dp)
            }
        }
    }
}

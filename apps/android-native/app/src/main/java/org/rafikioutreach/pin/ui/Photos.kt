package org.rafikioutreach.pin.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.rafikioutreach.pin.AppModel
import org.rafikioutreach.pin.t
import java.io.File

/** A photo kept on the phone until sent. [label] is its English name in monday.com. */
data class Shot(val file: File, val label: String)

/** A suggested photo: English label (sent to monday.com), what it says on screen, and a hint. */
data class Slot(val label: String, val title: String, val hint: String)

/**
 * Photo boxes, one per suggested shot. Each box lets you take the photo with the camera or
 * choose one from the gallery. Photos are shrunk and kept on the phone until they have been sent.
 */
@Composable
fun PhotoSlots(m: AppModel, slots: List<Slot>, shots: List<Shot>, onChange: (List<Shot>) -> Unit, max: Int = 7) {
    val ctx = LocalContext.current
    val scope = rememberScope()
    var pending by remember { mutableStateOf<Pair<File, String>?>(null) }
    var gallFor by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    fun add(f: File, label: String) = onChange(shots.filter { it.label != label || label == "Other photo" } + Shot(f, label))
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val p = pending; pending = null
        if (ok && p != null) scope.launch { m.keepPhoto(p.first)?.let { add(it, p.second) } }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val label = gallFor; gallFor = null
        if (uri != null && label != null) scope.launch {
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "g${System.currentTimeMillis()}.jpg")
                    ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }; f
                }.getOrNull()
            }
            tmp?.let { m.keepPhoto(it) }?.let { add(it, label) }
        }
    }
    fun camera(label: String) {
        val f = File(File(ctx.cacheDir, "cam").apply { mkdirs() }, "c${System.currentTimeMillis()}.jpg")
        pending = f to label
        runCatching { cam.launch(FileProvider.getUriForFile(ctx, "org.rafikioutreach.pin.files", f)) }
    }
    fun pickGallery(label: String) { gallFor = label; runCatching { gallery.launch("image/*") } }

    @Composable
    fun Chooser(key: String, label: String) {
        DropdownMenu(menuFor == key, { menuFor = null }) {
            DropdownMenuItem({ Text(t("Take a photo", "Piga picha")) }, { menuFor = null; camera(label) }, leadingIcon = { Icon(Ic.get("camera"), null, tint = C.blue) })
            DropdownMenuItem({ Text(t("Choose from gallery", "Chagua kutoka kwenye picha")) }, { menuFor = null; pickGallery(label) }, leadingIcon = { Icon(Ic.get("upload"), null, tint = C.blue) })
        }
    }

    val all = slots.map { s -> Triple(s, s.hint, shots.lastOrNull { it.label == s.label }) } +
        shots.filter { s -> slots.none { it.label == s.label } }.map { Triple(Slot(it.label, t("Other photo", "Picha nyingine"), ""), "", it) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        all.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (slot, hint, shot) ->
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(18.dp)).background(C.card)
                                .border(if (shot != null) 2.dp else 1.5.dp, if (shot != null) C.ok else C.edge, RoundedCornerShape(18.dp))
                                .clickable(role = Role.Button) { menuFor = "slot:" + slot.label + shot?.file?.name }.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            if (shot != null) {
                                val bmp = remember(shot.file) { BitmapFactory.decodeFile(shot.file.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }
                                if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(12.dp)))
                                Text(slot.title, style = MaterialTheme.typography.labelSmall, color = C.ink, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                            } else {
                                IconTile("camera", C.blue, C.tint, 44.dp)
                                Text(slot.title, style = MaterialTheme.typography.labelMedium, color = C.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                                if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall, color = C.muted, textAlign = TextAlign.Center, maxLines = 2)
                            }
                        }
                        Chooser("slot:" + slot.label + shot?.file?.name, slot.label)
                        if (shot != null) Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).clip(CircleShape).background(C.ink)
                                .clickable { onChange(shots - shot) }.semantics { contentDescription = t("Remove photo", "Ondoa picha") },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Ic.get("x"), null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
        if (shots.size < max) Box {
            Btn(t("Add a photo", "Ongeza picha"), { menuFor = "add" }, light = true, small = true, icon = "camera")
            Chooser("add", "Other photo")
        }
    }
}

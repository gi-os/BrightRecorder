package com.gios.brightrecorder.ui

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gios.brightrecorder.hw.WheelScroll
import com.gios.brightrecorder.photo.Gallery
import com.gios.brightrecorder.photo.Stars
import com.gios.brightrecorder.ui.theme.Dim
import com.gios.brightrecorder.ui.theme.Faint
import java.io.File

/**
 * Choosing a photograph for a label.
 *
 * The system picker is not used, for the reason [Gallery] explains: it reads MediaStore, and nothing
 * on LightOS keeps MediaStore current, so a photograph taken minutes ago is not in it. This is a
 * grid over the filesystem instead.
 *
 * Single selection, and picking is the action — there is no confirm step. A label takes one
 * photograph, so a second tap would only ever mean "no, that one", and the way to say that is to
 * tap the one you meant.
 *
 * **Starred only** filters to what you starred in Roll. It appears only when Roll is installed and
 * has an answer; see [Stars] for why a star cannot come from anywhere else. On a phone whose camera
 * roll is a few hundred pictures deep, it is the difference between finding the photograph you
 * want and scrolling for it.
 */
@Composable
fun PhotoPickerScreen(onPick: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Gallery.permission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(granted) { if (!granted) ask.launch(Gallery.permission) }

    val photos by produceState(initialValue = emptyList<Gallery.Photo>(), granted) {
        value = if (granted) Gallery.scan() else emptyList()
    }
    // Null until asked, and null for ever if Roll is not installed — which is what hides the
    // filter rather than showing one that can only ever be empty.
    val starred by produceState(initialValue = null as Set<String>?, granted) {
        value = if (granted) Stars.names(context) else null
    }
    var starsOnly by remember { mutableStateOf(false) }

    val shown = remember(photos, starred, starsOnly) {
        if (starsOnly && starred != null) photos.filter { it.name in starred!! } else photos
    }

    val grid = rememberLazyGridState()
    WheelScroll(grid)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        ScreenTitle(if (starsOnly) "STARRED — ${shown.size}" else "PHOTOS — ${shown.size}")

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !granted -> EmptyState("BrightRecorder needs permission to read your photos.")
                shown.isEmpty() && starsOnly ->
                    EmptyState("Nothing starred yet. Star a photo in Roll and it will show up here.")
                shown.isEmpty() -> EmptyState("No photos on the phone.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = grid,
                    contentPadding = PaddingValues(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.key }) { photo -> Cell(photo) { onPick(photo.file) } }
                }
            }
        }

        Rule()
        Row(Modifier.fillMaxWidth()) {
            TransportKey(glyph = "BACK", modifier = Modifier.weight(1f), onClick = onClose)
            // Offered only when Roll has answered. A filter that can only ever be empty is worse
            // than no filter, because it reads as the app having lost your photographs.
            if (starred != null) {
                TransportKey(
                    glyph = "STARRED",
                    held = starsOnly,
                    modifier = Modifier.weight(1f),
                ) { starsOnly = !starsOnly }
            }
        }
    }
}

@Composable
private fun Cell(photo: Gallery.Photo, onPick: () -> Unit) {
    val thumb by produceState(initialValue = null as ImageBitmap?, photo.key) {
        value = Gallery.thumbnail(photo)
    }
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .background(Color(0xFF1A1A1A))
            .border(1.dp, Faint)
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: Text(
            "·",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = TextAlign.Center,
        )
    }
}

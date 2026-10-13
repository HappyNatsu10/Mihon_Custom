package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.icons.custommaterialsymbols.CustomMaterialSymbols
import mihon.icons.custommaterialsymbols.rounded.CropOff
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Crop
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // CUSTOM: Feature C - Translation button
    translateEnabled: Boolean = false,
    isTranslating: Boolean = false,
    isTranslated: Boolean = false,
    onClickTranslate: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (translateEnabled) {
            IconButton(
                onClick = onClickTranslate,
                enabled = !isTranslating,
            ) {
                if (isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_translate),
                        contentDescription = stringResource(
                            if (isTranslated) MR.strings.action_translate_original else MR.strings.action_translate,
                        ),
                        tint = if (isTranslated) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
        }

        IconButton(onClick = onClickReadingMode) {
            Icon(
                imageVector = readingMode.icon,
                contentDescription = stringResource(MR.strings.viewer),
            )
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                imageVector = if (cropEnabled) MaterialSymbols.Rounded.Crop else CustomMaterialSymbols.Rounded.CropOff,
                contentDescription = stringResource(MR.strings.pref_crop_borders),
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}

package com.runforvictory.tuition.core.localization

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary
import com.runforvictory.tuition.core.theme.BrandTealPrimary

/**
 * Reusable, accessible language selection button with a bilingual modal chooser.
 *
 * Meets Android accessibility guidelines:
 * - Minimum 48dp touch target
 * - High contrast colors
 * - Clear semantic labels for screen readers
 * - Text scales with system font size
 */
@Composable
fun LanguageSelectorButton(
    localeManager: LocaleManager,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.surface
) {
    var showDialog by remember { mutableStateOf(false) }
    val currentLang by localeManager.currentLanguage.collectAsState()
    val changeLanguageLabel = stringResource(id = R.string.action_change_language)

    IconButton(
        onClick = { showDialog = true },
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = changeLanguageLabel }
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = changeLanguageLabel,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }

    if (showDialog) {
        LanguageSelectionDialog(
            localeManager = localeManager,
            currentLanguageCode = currentLang,
            onDismiss = { showDialog = false }
        )
    }
}

/**
 * Modal dialog for selecting application language.
 */
@Composable
fun LanguageSelectionDialog(
    localeManager: LocaleManager,
    currentLanguageCode: String,
    onDismiss: () -> Unit
) {
    val languages = localeManager.getSupportedLanguages()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.language_selector_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = BrandNavyPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                languages.forEach { lang ->
                    val isSelected = lang.code == currentLanguageCode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable(role = Role.RadioButton) {
                                localeManager.setLanguage(lang.code)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                localeManager.setLanguage(lang.code)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = BrandTealPrimary,
                                unselectedColor = BrandNavyPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (lang.code == "te") "${lang.nativeName} (${lang.displayName})" else lang.displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) BrandTealPrimary else MaterialTheme.colorScheme.onSurface
                            )
                            if (lang.code == "te") {
                                Text(
                                    text = "రన్ ఫర్ విక్టరీ - రవి కుమార్ వారిచే",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BrandGoldPrimary
                                )
                            } else {
                                Text(
                                    text = "Run for Victory - By Ravi Kumar",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BrandGoldPrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(id = R.string.action_cancel),
                    fontWeight = FontWeight.SemiBold,
                    color = BrandNavyPrimary
                )
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

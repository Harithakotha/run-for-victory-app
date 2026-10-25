package com.runforvictory.tuition.core.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runforvictory.tuition.R
import com.runforvictory.tuition.core.localization.LanguageSelectorButton
import com.runforvictory.tuition.core.localization.LocaleManager
import com.runforvictory.tuition.core.theme.BrandGoldPrimary
import com.runforvictory.tuition.core.theme.BrandNavyPrimary

/**
 * Standard brand TopAppBar featuring:
 * - Education logo (Navy, Teal, Gold)
 * - App title: "Run for Victory" (or screen context)
 * - Subtitle: "By Ravi Kumar"
 * - Integrated, accessible Language Selector (English <-> Telugu)
 * - Custom screen action slots
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandTopAppBar(
    title: String? = null,
    subtitle: String? = null,
    localeManager: LocaleManager? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_run_for_victory_logo),
                        contentDescription = stringResource(id = R.string.app_name),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title ?: stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.surface
                    )
                    Text(
                        text = subtitle ?: stringResource(id = R.string.app_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandGoldPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = {
            if (localeManager != null) {
                LanguageSelectorButton(
                    localeManager = localeManager,
                    tint = MaterialTheme.colorScheme.surface
                )
            }
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BrandNavyPrimary,
            titleContentColor = MaterialTheme.colorScheme.surface,
            actionIconContentColor = MaterialTheme.colorScheme.surface
        )
    )
}

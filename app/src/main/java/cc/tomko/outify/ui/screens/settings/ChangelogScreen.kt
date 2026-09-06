package cc.tomko.outify.ui.screens.settings

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.R

/**
 * One release note. Everything is a resource id so the screen is fully localized.
 * [body] is optional: some releases only carry a bullet list.
 */
private data class ChangelogEntry(
    @StringRes val version: Int,
    @StringRes val date: Int,
    @StringRes val title: Int,
    @StringRes val body: Int? = null,
    @ArrayRes val bullets: Int,
)

/**
 * Newest release first
 */
private val changelogEntries = listOf(
    ChangelogEntry(
        version = R.string.changelog_1_7_1_version,
        date = R.string.changelog_1_7_1_date,
        title = R.string.changelog_1_7_1_title,
        bullets = R.array.changelog_1_7_1_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_7_0_version,
        date = R.string.changelog_1_7_0_date,
        title = R.string.changelog_1_7_0_title,
        bullets = R.array.changelog_1_7_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_6_0_version,
        date = R.string.changelog_1_6_0_date,
        title = R.string.changelog_1_6_0_title,
        bullets = R.array.changelog_1_6_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_5_1_version,
        date = R.string.changelog_1_5_1_date,
        title = R.string.changelog_1_5_1_title,
        bullets = R.array.changelog_1_5_1_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_5_0_version,
        date = R.string.changelog_1_5_0_date,
        title = R.string.changelog_1_5_0_title,
        bullets = R.array.changelog_1_5_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_4_0_version,
        date = R.string.changelog_1_4_0_date,
        title = R.string.changelog_1_4_0_title,
        bullets = R.array.changelog_1_4_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_3_2_version,
        date = R.string.changelog_1_3_2_date,
        title = R.string.changelog_1_3_2_title,
        bullets = R.array.changelog_1_3_2_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_3_1_version,
        date = R.string.changelog_1_3_1_date,
        title = R.string.changelog_1_3_1_title,
        bullets = R.array.changelog_1_3_1_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_3_0_version,
        date = R.string.changelog_1_3_0_date,
        title = R.string.changelog_1_3_0_title,
        bullets = R.array.changelog_1_3_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_2_0_version,
        date = R.string.changelog_1_2_0_date,
        title = R.string.changelog_1_2_0_title,
        bullets = R.array.changelog_1_2_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_1_3_version,
        date = R.string.changelog_1_1_3_date,
        title = R.string.changelog_1_1_3_title,
        bullets = R.array.changelog_1_1_3_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_1_2_version,
        date = R.string.changelog_1_1_2_date,
        title = R.string.changelog_1_1_2_title,
        bullets = R.array.changelog_1_1_2_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_1_0_version,
        date = R.string.changelog_1_1_0_date,
        title = R.string.changelog_1_1_0_title,
        bullets = R.array.changelog_1_1_0_bullets,
    ),
    ChangelogEntry(
        version = R.string.changelog_1_0_0_version,
        date = R.string.changelog_1_0_0_date,
        title = R.string.changelog_1_0_0_title,
        body = R.string.changelog_1_0_0_body,
        bullets = R.array.changelog_1_0_0_bullets,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.changelog_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPaddings ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPaddings.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(changelogEntries, key = { it.version }) { entry ->
                ChangelogCard(entry = entry)
            }
        }
    }
}

@Composable
private fun ChangelogCard(entry: ChangelogEntry) {
    val bullets = stringArrayResource(entry.bullets)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: version chip + date
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = stringResource(entry.version),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = stringResource(entry.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(entry.title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            entry.body?.let { body ->
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = bullet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

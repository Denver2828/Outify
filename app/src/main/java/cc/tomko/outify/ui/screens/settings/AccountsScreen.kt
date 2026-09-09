package cc.tomko.outify.ui.screens.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.tomko.outify.R
import cc.tomko.outify.ui.components.SpotyBrand
import cc.tomko.outify.ui.components.PreferenceHeader
import cc.tomko.outify.ui.components.SmartImage
import cc.tomko.outify.ui.viewmodel.settings.AccountsViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkAuthState()
    }

    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val isPartiallyLoggedIn by viewModel.isPartiallyLoggedIn.collectAsStateWithLifecycle()
    val scopes by viewModel.scopes.collectAsStateWithLifecycle()

    val isPremium by viewModel.isPremium.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val userImageUrl by viewModel.userImageUrl.collectAsStateWithLifecycle()
    val rateLimitSeconds by viewModel.rateLimitRemainingSeconds.collectAsStateWithLifecycle()
    val isRateLimited = rateLimitSeconds > 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_accounts_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                actions = { SpotyBrand(modifier = Modifier.padding(end = 16.dp)) }
            )
        },
        modifier = modifier
    ) { innerPaddings ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPaddings.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AnimatedVisibility(
                    visible = !isPremium || !isLoggedIn
                ) {

                    Surface(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "https://github.com/librespot-org/librespot#librespot".toUri()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onError,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_accounts_premium_required_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_accounts_premium_required_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = modifier.fillMaxWidth(),
                ) {
                    if (isLoggedIn) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (userImageUrl != null) {
                                    Surface(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        SmartImage(
                                            url = userImageUrl,
                                            contentDescription = stringResource(R.string.settings_accounts_profile_picture),
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = username ?: stringResource(R.string.settings_accounts_account_fallback),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isPremium) stringResource(R.string.settings_accounts_logged_in) else stringResource(R.string.settings_accounts_logged_in_free),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.settings_accounts_logged_in),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            if (!isPremium) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_accounts_premium_required_for_playback),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            if (isRateLimited) {
                                RateLimitNotice(
                                    seconds = rateLimitSeconds,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }

                            OutlinedButton(
                                onClick = { viewModel.logout() },
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
                                    .fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_accounts_logout))
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = if (isPartiallyLoggedIn) {
                                    stringResource(R.string.settings_accounts_reconnect_description)
                                } else {
                                    stringResource(R.string.settings_accounts_connect_description)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (isRateLimited) {
                                RateLimitNotice(seconds = rateLimitSeconds)
                            }

                            Button(
                                onClick = { viewModel.startAuth(context) },
                                enabled = !isRateLimited,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Login,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_accounts_connect))
                            }
                        }
                    }
                }
            }

            item {
                PreferenceHeader(stringResource(R.string.settings_accounts_feature_availability_header))
            }

            item {
                ElevatedCard(
                    modifier = modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_accounts_feature_availability_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(16.dp))

                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_stream),
                            isLoggedIn && isPremium
                        )
                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_sync_liked),
                            isLoggedIn && isPremium
                        )
                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_view_catalog),
                            isLoggedIn && isPremium
                        )

                        Spacer(Modifier.height(12.dp))

                        FeatureAvailability(stringResource(R.string.settings_accounts_availability_search), isLoggedIn)
                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_modify_playlists),
                            isLoggedIn && scopes.containsAll(
                                listOf(
                                    "playlist-modify-public",
                                    "playlist-modify-private"
                                )
                            )
                        )
                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_create_playlists),
                            isLoggedIn && scopes.containsAll(
                                listOf(
                                    "playlist-modify-public",
                                    "playlist-modify-private"
                                )
                            )
                        )
                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_like_items),
                            isLoggedIn && scopes.containsAll(
                                listOf(
                                    "user-library-modify",
                                    "user-follow-modify",
                                    "playlist-modify-public"
                                )
                            )
                        )

                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_sync_albums),
                            isLoggedIn && scopes.containsAll(
                                listOf(
                                    "user-library-read",
                                )
                            )
                        )

                        FeatureAvailability(
                            stringResource(R.string.settings_accounts_availability_episode_to_show),
                            isLoggedIn
                        )
                        FeatureAvailability(stringResource(R.string.settings_accounts_availability_user_profiles), isLoggedIn)

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.settings_accounts_available_scopes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = scopes.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureAvailability(text: String, available: Boolean, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Icon(
            imageVector = if (available) Icons.Default.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = if (available) stringResource(R.string.settings_available) else stringResource(R.string.settings_unavailable),
            tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error.copy(
                alpha = 0.7f
            ),
            modifier = Modifier.size(22.dp)
        )

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Inline warning shown while Spotify answers 429. [seconds] counts down from the ViewModel.
 */
@Composable
private fun RateLimitNotice(seconds: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(R.string.settings_accounts_rate_limited, seconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

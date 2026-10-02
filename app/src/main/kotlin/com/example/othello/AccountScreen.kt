package com.example.othello

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.ChanrivaColors
import com.example.othello.designsystem.ChanrivaNavigationRow
import com.example.othello.designsystem.ChanrivaScreenHeader
import com.example.othello.designsystem.ChanrivaSpacing
import com.example.othello.network.peopleplay.AvatarId
import com.example.othello.profile.PlayProfileLookup
import com.example.othello.profile.PlayProfileRepository
import kotlinx.coroutines.CancellationException

@Composable
internal fun AccountScreen(
    userId: String,
    playProfileRepository: PlayProfileRepository,
    logoutInProgress: Boolean,
    logoutError: String?,
    onBack: () -> Unit,
    onAccountDeletion: () -> Unit,
    onLogout: () -> Unit,
) {
    var displayName by remember(userId) { mutableStateOf<String?>(null) }
    var loadingName by remember(userId) { mutableStateOf(true) }
    val avatarRes = remember(userId) { derivePeoplePlayAvatar(userId).toPeoplePlayAvatarDrawable() }

    LaunchedEffect(userId, playProfileRepository) {
        loadingName = true
        displayName = try {
            when (val profile = playProfileRepository.findCurrentUserProfile()) {
                is PlayProfileLookup.Exists -> playProfileRepository
                    .getActiveDisplayNames()
                    .firstOrNull { it.id == profile.displayNameId }
                    ?.displayName
                PlayProfileLookup.Missing,
                is PlayProfileLookup.Failed -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            loadingName = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ChanrivaSpacing.page),
        verticalArrangement = Arrangement.spacedBy(ChanrivaSpacing.section),
    ) {
        ChanrivaScreenHeader(appString(R.string.account), onBack, backLabel = appString(R.string.back))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(ChanrivaColors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(avatarRes),
                    contentDescription = null,
                    modifier = Modifier.size(68.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = appString(R.string.account_chanriva_name),
                    color = ChanrivaColors.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = when {
                        loadingName -> appString(R.string.account_chanriva_name_loading)
                        displayName != null -> requireNotNull(displayName)
                        else -> appString(R.string.account_chanriva_name_unavailable)
                    },
                    color = ChanrivaColors.textPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        ChanrivaNavigationRow(
            title = appString(if (logoutInProgress) R.string.logout_in_progress else R.string.logout),
            onClick = if (logoutInProgress) null else onLogout,
        )
        ChanrivaNavigationRow(appString(R.string.account_deletion), onAccountDeletion)
        logoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

internal fun derivePeoplePlayAvatar(userId: String): AvatarId {
    val compact = userId.replace("-", "")
    val prefix = compact.take(8)
    val value = prefix.toLongOrNull(16) ?: 0L
    return AvatarId.entries[(value % AvatarId.entries.size).toInt()]
}

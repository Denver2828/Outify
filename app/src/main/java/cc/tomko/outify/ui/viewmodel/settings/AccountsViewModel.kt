package cc.tomko.outify.ui.viewmodel.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.tomko.outify.core.AuthCallbackServerManager
import cc.tomko.outify.core.AuthManager
import cc.tomko.outify.core.AuthStateEventBus
import cc.tomko.outify.core.RateLimitGate
import cc.tomko.outify.core.SpClient
import cc.tomko.outify.core.UserProfile
import cc.tomko.outify.core.model.CurrentUserProfile
import cc.tomko.outify.core.spirc.SpircController
import cc.tomko.outify.data.metadata.NativeErrorHandler
import cc.tomko.outify.data.repository.SettingsRepository
import cc.tomko.outify.services.OAuthService
import cc.tomko.outify.ui.GlobalPopupController
import cc.tomko.outify.ui.PopupSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    val spClient: SpClient,
    val userProfile: UserProfile,
    val authManager: AuthManager,
    private val spircController: SpircController,
    private val settingsRepository: SettingsRepository,
    private val serverManager: AuthCallbackServerManager,
    private val rateLimitGate: RateLimitGate,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Seconds until Spotify accepts Web API calls again, 0 when it is not rate limiting us.
     * Ticks once per second while someone is collecting it.
     */
    val rateLimitRemainingSeconds: StateFlow<Int> = rateLimitGate.remainingSecondsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), rateLimitGate.remainingSeconds())

    private val _isPlaybackLoggedIn = MutableStateFlow(false)
    private val _isAccountLoggedIn = MutableStateFlow(false)

    /**
     * A single Spotify login now backs both halves, so the UI only cares about the pair being
     * complete. A half-logged-in state can still happen when upgrading from an older version.
     */
    val isLoggedIn: StateFlow<Boolean> = combine(
        _isPlaybackLoggedIn,
        _isAccountLoggedIn,
    ) { playback, account ->
        playback && account
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Exactly one of the two halves is authenticated: the login has to be repeated to complete it.
     */
    val isPartiallyLoggedIn: StateFlow<Boolean> = combine(
        _isPlaybackLoggedIn,
        _isAccountLoggedIn,
    ) { playback, account ->
        playback != account
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _userImageUrl = MutableStateFlow<String?>(null)
    val userImageUrl: StateFlow<String?> = _userImageUrl.asStateFlow()

    private val _isPremium = MutableStateFlow(true)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _scopes = MutableStateFlow<List<String>>(emptyList())
    val scopes: StateFlow<List<String>> = _scopes.asStateFlow()

    init {
        checkAuthState()
        loadSavedUserProfile()
    }

    override fun onCleared() {
        super.onCleared()
        serverManager.stop()
    }

    fun checkAuthState() {
        _isAccountLoggedIn.value = spClient.isOAuthAuthenticated()
        _isPlaybackLoggedIn.value = authManager.hasCachedCredentials()

        _scopes.value = spClient.getOAuthScope()?.split(" ") ?: emptyList()
    }

    private fun loadSavedUserProfile() {
        viewModelScope.launch {
            _username.value = settingsRepository.username.first()
            _userImageUrl.value = settingsRepository.userImageUrl.first()
        }
    }

    /**
     * Single Spotify login. The librespot OAuth flow also stores the Web API token natively, so a
     * successful callback authenticates playback and the account at once.
     */
    fun startAuth(context: Context) {
        OAuthService.start(context)

        serverManager.start(onCodeReceived = { code, state ->
            OAuthService.stop(context)
            val result = authManager.handleOAuthCode(code, state)
            val isSuccess = result.contains("\"success\":true")
            val errorDetails = if (!isSuccess) parseErrorMessage(result) else null
            if (!isSuccess) {
                NativeErrorHandler.handleErrorJson(result, "spotify oauth")
            }
            GlobalPopupController.show(PopupSpec.AuthResult(isSuccess, errorDetails = errorDetails))
            if (isSuccess) {
                checkAuthState()
                AuthStateEventBus.tryEmitPlaybackLoggedIn()
                AuthStateEventBus.tryEmitAccountLoggedIn()
                spircController.restart()
                fetchProfile()
            }
        })

        val url = authManager.getAuthorizationURL()

        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri())
        )
    }

    fun logout() {
        authManager.logout()
        spClient.logout()
        _isPlaybackLoggedIn.value = false
        _isAccountLoggedIn.value = false
        _scopes.value = emptyList()
        // A fresh login should not inherit the previous session's 429 window.
        rateLimitGate.reset()

        viewModelScope.launch {
            try {
                settingsRepository.removeUserProfile()
            } catch (e: Exception) {
            }
        }
    }

    fun fetchProfile() {
        if (rateLimitGate.isLimited()) return
        viewModelScope.launch {
            try {
                // Blocking JNI call: keep it off the main thread.
                val profile = withContext(Dispatchers.IO) { spClient.getCurrentUserProfile() }
                if (profile == null) {
                    return@launch
                }
                // A 429 comes back as an error payload; NativeErrorHandler arms the gate and the
                // account stays "logged in" because the OAuth token is still valid.
                if (NativeErrorHandler.handleErrorJson(profile, "current user profile") != null) {
                    return@launch
                }
                val jsonObject = json.decodeFromString<CurrentUserProfile>(profile)

                val id = jsonObject.id
                val username = jsonObject.displayName
                val imageUrl = jsonObject.images.first().url

                _isPremium.value = jsonObject.product == "premium"

                _username.value = username
                _userImageUrl.value = imageUrl

                settingsRepository.saveUserProfile(id, username, imageUrl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun parseErrorMessage(json: String): String? {
        return try {
            val obj = org.json.JSONObject(json)
            if (obj.has("error")) {
                val err = obj.getJSONObject("error")
                err.optString("message", null)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
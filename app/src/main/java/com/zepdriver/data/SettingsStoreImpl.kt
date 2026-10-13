package com.zepdriver.data

import android.content.Context
import com.zepdriver.AppPrefs
import com.zepdriver.Favorite
import com.zepdriver.SettingsStore
import com.zepdriver.core.profile.VehicleProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** [SettingsStore] backed by SharedPreferences holding JSON documents. */
class SettingsStoreImpl(context: Context) : SettingsStore {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val favoritesSerializer = ListSerializer(Favorite.serializer())

    private val _profile = MutableStateFlow(read(KEY_PROFILE, VehicleProfile.serializer()) ?: VehicleProfile())
    override val profile: StateFlow<VehicleProfile> = _profile.asStateFlow()

    private val _prefs = MutableStateFlow(read(KEY_PREFS, AppPrefs.serializer()) ?: AppPrefs())
    override val prefs: StateFlow<AppPrefs> = _prefs.asStateFlow()

    private val _favorites = MutableStateFlow(read(KEY_FAVORITES, favoritesSerializer) ?: emptyList())
    override val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    override fun updateProfile(profile: VehicleProfile) {
        _profile.value = profile
        write(KEY_PROFILE, VehicleProfile.serializer(), profile)
    }

    override fun updatePrefs(prefs: AppPrefs) {
        _prefs.value = prefs
        write(KEY_PREFS, AppPrefs.serializer(), prefs)
    }

    override fun saveFavorite(favorite: Favorite) {
        _favorites.update { list ->
            val i = list.indexOfFirst { it.id == favorite.id }
            if (i >= 0) list.toMutableList().also { it[i] = favorite } else list + favorite
        }
        write(KEY_FAVORITES, favoritesSerializer, _favorites.value)
    }

    override fun removeFavorite(id: String) {
        _favorites.update { list -> list.filterNot { it.id == id } }
        write(KEY_FAVORITES, favoritesSerializer, _favorites.value)
    }

    private fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val text = sp.getString(key, null) ?: return null
        return runCatching { json.decodeFromString(serializer, text) }.getOrNull()
    }

    private fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        sp.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_PREFS = "prefs"
        const val KEY_FAVORITES = "favorites"
    }
}

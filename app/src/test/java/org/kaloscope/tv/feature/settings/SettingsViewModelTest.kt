package org.kaloscope.tv.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kaloscope.tv.core.common.AppError
import org.kaloscope.tv.core.common.AppResult
import org.kaloscope.tv.core.model.SavedServer
import org.kaloscope.tv.core.model.Session
import org.kaloscope.tv.core.model.SessionUser
import org.kaloscope.tv.core.model.TvSettings
import org.kaloscope.tv.core.player.PlaybackMode
import org.kaloscope.tv.core.player.TranscodeQuality
import org.kaloscope.tv.data.server.ServerConnectionInfo
import org.kaloscope.tv.data.server.ServerRepository
import org.kaloscope.tv.data.settings.SettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun `rapid updates persist the active write and latest settings only`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = PendingSettingsRepository()
        val viewModel = SettingsViewModel(repository, UnusedServerRepository())
        try {
            runCurrent()

            viewModel.setPlaybackMode(PlaybackMode.Direct)
            runCurrent()
            assertEquals(1, repository.saves.size)

            viewModel.setTranscodeQuality(TranscodeQuality.High)
            viewModel.setAutoplayNext(false)
            runCurrent()

            val optimistic = viewModel.uiState.value as SettingsUiState.Content
            assertEquals(PlaybackMode.Direct, optimistic.settings.playbackMode)
            assertEquals(TranscodeQuality.High, optimistic.settings.transcodeQuality)
            assertFalse(optimistic.settings.autoplayNext)
            assertTrue(optimistic.isSaving)
            assertEquals(1, repository.saves.size)

            repository.completeNext()
            runCurrent()

            assertEquals(2, repository.saves.size)
            val latest = repository.saves.last()
            assertEquals(PlaybackMode.Direct, latest.playbackMode)
            assertEquals(TranscodeQuality.High, latest.transcodeQuality)
            assertFalse(latest.autoplayNext)

            repository.completeNext()
            advanceUntilIdle()

            val saved = viewModel.uiState.value as SettingsUiState.Content
            assertEquals(latest, saved.settings)
            assertFalse(saved.isSaving)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed latest write rolls back to the last saved settings`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = PendingSettingsRepository()
        val viewModel = SettingsViewModel(repository, UnusedServerRepository())
        try {
            runCurrent()

            viewModel.setPlaybackMode(PlaybackMode.Direct)
            runCurrent()
            viewModel.setPlaybackMode(PlaybackMode.Transcode)
            runCurrent()

            repository.completeNext()
            runCurrent()
            repository.failNext()
            advanceUntilIdle()

            val state = viewModel.uiState.value as SettingsUiState.Content
            assertEquals(PlaybackMode.Direct, state.settings.playbackMode)
            assertEquals(AppError.InvalidData("settings_write"), state.saveError)
            assertFalse(state.isSaving)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connection reset cancels the old request and allows a new server test`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = PendingConnectionRepository()
        val viewModel = SettingsViewModel(PendingSettingsRepository(), repository)
        try {
            runCurrent()
            viewModel.testConnection(session())
            runCurrent()
            val oldRequest = repository.requests.single()

            viewModel.resetConnection()
            runCurrent()

            assertTrue(oldRequest.cancelled)
            assertEquals(
                SettingsConnection.Idle,
                (viewModel.uiState.value as SettingsUiState.Content).connection,
            )
            val nextSession = session().copy(
                server = SavedServer("server-2", "Other server", "https://other.example"),
            )
            viewModel.testConnection(nextSession)
            runCurrent()
            assertEquals(
                listOf(session().server.origin, nextSession.server.origin),
                repository.requests.map { it.origin },
            )
            repository.requests.last().result.complete(
                AppResult.Success(ServerConnectionInfo(nextSession.server.origin, "new")),
            )
            runCurrent()

            assertEquals(
                SettingsConnection.Success("new"),
                (viewModel.uiState.value as SettingsUiState.Content).connection,
            )
        } finally {
            viewModel.resetConnection()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connection reset preserves an in-flight settings save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settingsRepository = PendingSettingsRepository()
        val viewModel = SettingsViewModel(settingsRepository, PendingConnectionRepository())
        try {
            runCurrent()
            viewModel.setPlaybackMode(PlaybackMode.Direct)
            viewModel.testConnection(session())
            runCurrent()
            val saving = viewModel.uiState.value as SettingsUiState.Content
            assertTrue(saving.isSaving)
            assertEquals(SettingsConnection.Testing, saving.connection)

            viewModel.resetConnection()
            runCurrent()

            assertEquals(
                saving.copy(connection = SettingsConnection.Idle),
                viewModel.uiState.value,
            )
            settingsRepository.completeNext()
            runCurrent()

            val saved = viewModel.uiState.value as SettingsUiState.Content
            assertEquals(PlaybackMode.Direct, saved.settings.playbackMode)
            assertFalse(saved.isSaving)
            assertEquals(SettingsConnection.Idle, saved.connection)
            assertEquals(listOf(saved.settings), settingsRepository.saves)
        } finally {
            viewModel.resetConnection()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}

private class PendingSettingsRepository : SettingsRepository {
    private data class PendingSave(
        val settings: TvSettings,
        val result: CompletableDeferred<AppResult<TvSettings>>,
    )

    val saves = mutableListOf<TvSettings>()
    private val pending = ArrayDeque<PendingSave>()

    override suspend fun getSettings(): AppResult<TvSettings> =
        AppResult.Success(TvSettings())

    override suspend fun saveSettings(settings: TvSettings): AppResult<TvSettings> {
        saves += settings
        val save = PendingSave(
            settings = settings,
            result = CompletableDeferred(),
        )
        pending.addLast(save)
        return save.result.await()
    }

    fun completeNext() {
        val save = pending.removeFirst()
        save.result.complete(AppResult.Success(save.settings))
    }

    fun failNext() {
        pending.removeFirst().result.complete(
            AppResult.Failure(AppError.InvalidData("settings_write")),
        )
    }
}

private class UnusedServerRepository : ServerRepository {
    override suspend fun testConnection(origin: String) = error("Not used")

    override suspend fun saveServer(server: SavedServer) = error("Not used")

    override suspend fun deleteServer(serverId: String): List<SavedServer> =
        error("Not used")

    override suspend fun setActiveServer(serverId: String) = error("Not used")
}

private class PendingConnectionRepository : ServerRepository {
    class Request(val origin: String) {
        val result = CompletableDeferred<AppResult<ServerConnectionInfo>>()
        var cancelled = false
    }

    val requests = mutableListOf<Request>()

    override suspend fun testConnection(origin: String): AppResult<ServerConnectionInfo> {
        val request = Request(origin)
        requests += request
        return try {
            request.result.await()
        } catch (error: CancellationException) {
            request.cancelled = true
            throw error
        }
    }

    override suspend fun saveServer(server: SavedServer) = error("Not used")

    override suspend fun deleteServer(serverId: String): List<SavedServer> =
        error("Not used")

    override suspend fun setActiveServer(serverId: String) = error("Not used")
}

private fun session() = Session(
    server = SavedServer("server-1", "Test server", "https://server.example"),
    token = "fixture-token",
    user = SessionUser(1, "tv", "user"),
)

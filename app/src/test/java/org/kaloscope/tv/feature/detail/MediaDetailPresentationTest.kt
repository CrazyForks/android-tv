package org.kaloscope.tv.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kaloscope.tv.core.model.MediaDetail
import org.kaloscope.tv.core.model.MediaLibrary
import org.kaloscope.tv.core.model.MediaLibraryType
import org.kaloscope.tv.core.model.MediaSummary

class MediaDetailPresentationTest {
    @Test
    fun `series hero shows the selected episode plot`() {
        val parent = detail(MediaLibraryType.TvShow).copy(
            children = listOf(child(id = 301, title = "Episode 1")),
        )
        val episode = parent.copy(id = 301, plot = "Episode plot", children = emptyList())

        assertEquals("Episode plot", resolveDetailPlot(parent, episode))
    }

    @Test
    fun `series hero never substitutes series plot for missing episode plot`() {
        val parent = detail(MediaLibraryType.TvShow).copy(
            children = listOf(child(id = 301, title = "Episode 1")),
        )

        assertNull(resolveDetailPlot(parent, null))
        listOf(null, "", " \n ").forEach { plot ->
            val episode = parent.copy(id = 301, plot = plot, children = emptyList())
            assertNull(resolveDetailPlot(parent, episode))
        }
    }

    @Test
    fun `standalone movie and episode show their own plot`() {
        listOf(MediaLibraryType.Movie, MediaLibraryType.TvShow).forEach { type ->
            val parent = detail(type)

            assertEquals("Fixture plot", resolveDetailPlot(parent, null))
            assertNull(resolveDetailPlot(parent.copy(plot = " "), null))
        }
    }

    @Test
    fun `movie parts retain child plot with parent fallback`() {
        val parent = detail(MediaLibraryType.Movie).copy(
            children = listOf(child(id = 301, title = "Part 1")),
        )
        val part = parent.copy(id = 301, plot = "Part plot", children = emptyList())

        assertEquals("Part plot", resolveDetailPlot(parent, part))
        assertEquals("Fixture plot", resolveDetailPlot(parent, null))
        assertEquals("Fixture plot", resolveDetailPlot(parent, part.copy(plot = " ")))
    }

    @Test
    fun `focused child artwork follows WebUI fallback order`() {
        val parent = detail(
            type = MediaLibraryType.TvShow,
            poster = "parent-poster",
            backdrop = "parent-backdrop",
        )
        val focused = child(
            id = 301,
            title = "Episode 1",
            poster = "child-poster",
            backdrop = "child-backdrop",
            season = 1,
            episode = 1,
        )

        assertEquals("child-backdrop", resolveDetailBackdrop(parent, focused))
        assertEquals(
            "parent-backdrop",
            resolveDetailBackdrop(parent, focused.copy(backdropPath = null)),
        )
        assertEquals(
            "child-poster",
            resolveDetailBackdrop(
                parent.copy(backdropPath = null),
                focused.copy(backdropPath = null),
            ),
        )
        assertEquals(
            "parent-poster",
            resolveDetailBackdrop(
                parent.copy(backdropPath = null),
                focused.copy(backdropPath = null, posterPath = null),
            ),
        )
    }

    @Test
    fun `library type selects episode or part semantics`() {
        assertEquals(
            MediaChildSectionKind.Episodes,
            childSectionKind(detail(MediaLibraryType.TvShow)),
        )
        assertEquals(
            MediaChildSectionKind.Parts,
            childSectionKind(detail(MediaLibraryType.Movie)),
        )
        assertEquals(
            MediaChildSectionKind.Parts,
            childSectionKind(detail(MediaLibraryType.Unknown)),
        )
    }

    @Test
    fun `child title separates season episode prefix with a middle dot`() {
        assertEquals(
            "S2E3 · Pilot",
            mediaChildDisplayTitle(child(id = 301, title = "Pilot", season = 2, episode = 3)),
        )
    }

    @Test
    fun `child title needs both season and episode for a prefix`() {
        assertEquals(
            "Pilot",
            mediaChildDisplayTitle(child(id = 301, title = "Pilot", season = null, episode = 3)),
        )
        assertEquals(
            "Pilot",
            mediaChildDisplayTitle(child(id = 301, title = "Pilot", season = 2, episode = null)),
        )
    }

    @Test
    fun `child title canonicalizes duplicate episode prefixes`() {
        val titles = listOf(
            "S2E3 - Pilot",
            "s02e03 · Pilot",
            "E03 Pilot",
            "第 03 集：Pilot",
        )

        titles.forEach { title ->
            assertEquals(
                "S2E3 · Pilot",
                mediaChildDisplayTitle(child(id = 301, title = title, season = 2, episode = 3)),
            )
        }
        assertEquals(
            "S2E3",
            mediaChildDisplayTitle(child(id = 301, title = "第3集", season = 2, episode = 3)),
        )
    }

    @Test
    fun `child title preserves mismatched episode markers`() {
        assertEquals(
            "S2E3 · S2E4 - Other",
            mediaChildDisplayTitle(
                child(id = 301, title = "S2E4 - Other", season = 2, episode = 3),
            ),
        )
    }
}

private fun detail(
    type: MediaLibraryType,
    poster: String? = null,
    backdrop: String? = null,
) = MediaDetail(
    id = 201,
    library = MediaLibrary(21, "Fixture library", type),
    title = "Fixture title",
    path = "/media/fixture",
    posterPath = poster,
    backdropPath = backdrop,
    year = 2026,
    rating = 8.5,
    season = null,
    episode = null,
    aired = null,
    plot = "Fixture plot",
    genres = emptyList(),
    directors = emptyList(),
    writers = emptyList(),
    studios = emptyList(),
    actors = emptyList(),
    children = emptyList(),
)

private fun child(
    id: Long,
    title: String,
    poster: String? = null,
    backdrop: String? = null,
    season: Int? = 1,
    episode: Int? = 1,
) = MediaSummary(
    id = id,
    title = title,
    path = "/media/$id.mkv",
    posterPath = poster,
    backdropPath = backdrop,
    year = 2026,
    rating = null,
    season = season,
    episode = episode,
    aired = "2026-01-01",
)

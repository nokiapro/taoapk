/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package chromahub.rhythm.app

import android.app.Application
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.presentation.components.common.ExpressiveShapeProvider
import chromahub.rhythm.app.shared.presentation.components.common.toComposeShape as providerToComposeShape
import chromahub.rhythm.app.shared.presentation.theme.toComposeShape as themeToComposeShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Verifies the three targeted fixes:
 * 1) Zero per-frame Path/Matrix/Outline allocation in RoundedPolygon.toComposeShape() outline LRU cache.
 * 2) ExpressiveShapeTarget.ARTIST_ART honors user-selected expressive shapes for artists (not stuck on CircleShape).
 * 3) Restored Modifier.animateItem() without size caps + eliminated 100ms root progress ticker regressions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScrollMicroStutterAndShapeCacheTest {

    @Test
    fun polygonShapeOutlineCache_reusesExactOutlineAcross10000FramesAndMultipleSizes() {
        val density = Density(2.5f)
        val flowerShape = ExpressiveShapeProvider.getShapeById("FLOWER")
        val flowerShapeAgain = ExpressiveShapeProvider.getShapeById("FLOWER")
        assertSame("Shape instances for the same shapeId must be reused", flowerShape, flowerShapeAgain)

        val thumbSize = Size(160f, 160f)
        val cardSize = Size(320f, 320f)
        val heroSize = Size(640f, 640f)

        val firstThumbOutline = flowerShape.createOutline(thumbSize, LayoutDirection.Ltr, density)
        val firstCardOutline = flowerShape.createOutline(cardSize, LayoutDirection.Ltr, density)
        val firstHeroOutline = flowerShape.createOutline(heroSize, LayoutDirection.Ltr, density)

        assertTrue(firstThumbOutline is Outline.Generic)
        assertSame(firstThumbOutline, flowerShape.createOutline(thumbSize, LayoutDirection.Ltr, density))
        assertSame(firstCardOutline, flowerShape.createOutline(cardSize, LayoutDirection.Ltr, density))
        assertSame(firstHeroOutline, flowerShape.createOutline(heroSize, LayoutDirection.Ltr, density))

        // Verify MaterialShapesUtils.toComposeShape also delegates to cached outline implementation
        val themeShape = MaterialShapes.Cookie6Sided.themeToComposeShape()
        val themeFirst = themeShape.createOutline(thumbSize, LayoutDirection.Ltr, density)
        assertSame(themeFirst, themeShape.createOutline(thumbSize, LayoutDirection.Ltr, density))

        // Verify 10,000 repeated createOutline lookups hit cache with zero new Outline instances
        val ns = measureNanoTime {
            repeat(10_000) { idx ->
                val targetSize = when (idx % 3) {
                    0 -> thumbSize
                    1 -> cardSize
                    else -> heroSize
                }
                val outline = flowerShape.createOutline(targetSize, LayoutDirection.Ltr, density)
                when (idx % 3) {
                    0 -> assertSame(firstThumbOutline, outline)
                    1 -> assertSame(firstCardOutline, outline)
                    else -> assertSame(firstHeroOutline, outline)
                }
            }
        }
        val elapsedMs = ns / 1_000_000.0
        assertTrue("Expected 10,000 cached outline lookups under 250ms, took ${elapsedMs}ms", elapsedMs < 250.0)
    }

    @Test
    fun polygonShapeOutlineCache_evictsLeastRecentlyUsedOver32DistinctSizes() {
        val density = Density(2.0f)
        val shape = MaterialShapes.Heart.providerToComposeShape()
        val initialSize = Size(100f, 100f)
        val initialOutline = shape.createOutline(initialSize, LayoutDirection.Ltr, density)

        // Populate 33 distinct sizes so the oldest entry (100x100) is evicted from the 32-entry LRU cache
        for (i in 1..33) {
            shape.createOutline(Size(100f + i, 100f + i), LayoutDirection.Ltr, density)
        }

        val recomputed = shape.createOutline(initialSize, LayoutDirection.Ltr, density)
        assertNotSame("LRU cache must stay bounded at 32 entries", initialOutline, recomputed)
    }

    @Test
    fun artistExpressiveShape_updatesFromCircleWhenConfiguredInAppSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val appSettings = AppSettings.getInstance(app)

        appSettings.setExpressiveShapesEnabled(true)
        appSettings.setExpressiveShapeArtistArt("FLOWER")

        val configuredId = appSettings.expressiveShapeArtistArt.value
        assertEquals("FLOWER", configuredId)

        val resolvedShape = ExpressiveShapeProvider.getShapeById(configuredId, fallback = CircleShape)
        assertNotSame("Artist shape should not stay CircleShape when FLOWER is selected", CircleShape, resolvedShape)
        val outline = resolvedShape.createOutline(Size(180f, 180f), LayoutDirection.Ltr, Density(2f))
        assertTrue("Selected expressive artist shape must render Outline.Generic", outline is Outline.Generic)
    }

    @Test
    fun scrollHotPaths_preserveAnimateItemArtistShapesAndAvoidRootProgressTickers() {
        val homeSource = resolveMainSource("chromahub/rhythm/app/features/local/presentation/screens/HomeScreen.kt").readText()
        assertTrue(
            "HomeScreen must wire ExpressiveShapeTarget.ARTIST_ART into CuratedHomeArtistItem",
            homeSource.contains("artistArtShape = homeArtistArtShape")
        )
        assertTrue(
            "HomeScreen local ModernArtistsSection must hoist ExpressiveShapeTarget.ARTIST_ART",
            homeSource.contains("ExpressiveShapeTarget.ARTIST_ART") && homeSource.contains("artistArtShape = artistArtShape")
        )
        assertFalse(
            "HomeScreen must not collect 100ms progress StateFlow inside composables",
            homeSource.contains("musicViewModel.progress.collectAsState")
        )

        val librarySource = resolveMainSource("chromahub/rhythm/app/features/local/presentation/screens/LibraryScreen.kt").readText()
        assertFalse(
            "LibraryScreen must not suppress animateItem() via arbitrary <= 40 cap",
            librarySource.contains("preparedSongs.size <= 40")
        )
        assertTrue(
            "LibraryScreen must restore Modifier.animateItem()",
            librarySource.contains(".animateItem()")
        )

        val localNavSource = resolveMainSource("chromahub/rhythm/app/features/local/presentation/navigation/LocalNavigation.kt").readText()
        assertFalse(
            "LocalNavigation must not collect 100ms progress StateFlow at navigation root",
            localNavSource.contains("viewModel.progress.collectAsState")
        )

        val rootNavSource = resolveMainSource("chromahub/rhythm/app/shared/presentation/navigation/RhythmNavigation.kt").readText()
        assertFalse(
            "RhythmNavigation must not collect 100ms progress StateFlow at app root",
            rootNavSource.contains("musicViewModel.progress.collectAsState")
        )
    }

    private fun resolveMainSource(relativeFromJavaRoot: String): File {
        val candidates = listOf(
            File("src/main/java/$relativeFromJavaRoot"),
            File("app/src/main/java/$relativeFromJavaRoot")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativeFromJavaRoot from ${File(".").absolutePath}")
    }
}

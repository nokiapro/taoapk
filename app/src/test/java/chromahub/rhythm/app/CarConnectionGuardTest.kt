package chromahub.rhythm.app

import android.app.Application
import android.os.Build
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import chromahub.rhythm.app.infrastructure.service.MediaPlaybackService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CarConnectionGuardTest {

    @Test
    fun buildMediaLibrarySessionSafely_clearsCarObserverWhenProviderIsAbsent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val originalManufacturer = Build.MANUFACTURER
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")

        val fakePlayer = object : SimpleBasePlayer(Looper.getMainLooper()) {
            override fun getState(): State {
                return State.Builder()
                    .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                    .build()
            }

            override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
                return Futures.immediateVoidFuture()
            }

            override fun handleRelease(): ListenableFuture<*> {
                return Futures.immediateVoidFuture()
            }
        }

        val session: MediaLibraryService.MediaLibrarySession? = try {
            val builder = MediaLibraryService.MediaLibrarySession.Builder(
                app,
                fakePlayer,
                object : MediaLibraryService.MediaLibrarySession.Callback {}
            )
            MediaPlaybackService.buildMediaLibrarySessionSafely(app, builder)
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
        }

        try {
            val implField = MediaSession::class.java.getDeclaredField("impl").apply {
                isAccessible = true
            }
            val impl = implField.get(session)
            val sessionImplClass = Class.forName("androidx.media3.session.MediaSessionImpl")
            val stubField = sessionImplClass.getDeclaredField("sessionLegacyStub").apply {
                isAccessible = true
            }
            val stub = stubField.get(impl)
            val observerField = stub.javaClass.getDeclaredField("androidAutoObserver").apply {
                isAccessible = true
            }
            assertNull(
                "androidAutoObserver must be cleared when androidx.car.app.connection provider is absent",
                observerField.get(stub)
            )
            val releasedCleanly = AtomicBoolean(false)
            session!!.release()
            releasedCleanly.set(true)
            assertTrue(releasedCleanly.get())
        } finally {
            fakePlayer.release()
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

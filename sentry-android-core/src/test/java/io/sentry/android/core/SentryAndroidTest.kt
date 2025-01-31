package io.sentry.android.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.ILogger
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryLevel
import io.sentry.SentryLevel.DEBUG
import io.sentry.SentryLevel.FATAL
import io.sentry.SentryOptions
import io.sentry.SentryOptions.BeforeSendCallback
import io.sentry.Session
import io.sentry.android.core.cache.AndroidEnvelopeCache
import io.sentry.android.fragment.FragmentLifecycleIntegration
import io.sentry.android.timber.SentryTimberIntegration
import io.sentry.cache.IEnvelopeCache
import io.sentry.cache.PersistingOptionsObserver
import io.sentry.cache.PersistingOptionsObserver.ENVIRONMENT_FILENAME
import io.sentry.cache.PersistingOptionsObserver.OPTIONS_CACHE
import io.sentry.cache.PersistingOptionsObserver.RELEASE_FILENAME
import io.sentry.cache.PersistingScopeObserver
import io.sentry.cache.PersistingScopeObserver.BREADCRUMBS_FILENAME
import io.sentry.cache.PersistingScopeObserver.SCOPE_CACHE
import io.sentry.cache.PersistingScopeObserver.TRANSACTION_FILENAME
import io.sentry.transport.NoOpEnvelopeCache
import io.sentry.util.StringUtils
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.absolutePathString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SentryAndroidTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    class Fixture {
        lateinit var shadowActivityManager: ShadowActivityManager

        fun initSut(
            context: Context? = null,
            autoInit: Boolean = false,
            logger: ILogger? = null,
            options: Sentry.OptionsConfiguration<SentryAndroidOptions>? = null
        ) {
            val metadata = Bundle().apply {
                putString(ManifestMetadataReader.DSN, "https://key@sentry.io/123")
                putBoolean(ManifestMetadataReader.AUTO_INIT, autoInit)
            }
            val mockContext = context ?: ContextUtilsTest.mockMetaData(metaData = metadata)
            when {
                logger != null -> SentryAndroid.init(mockContext, logger)
                options != null -> SentryAndroid.init(mockContext, options)
                else -> SentryAndroid.init(mockContext)
            }
        }

        fun addAppExitInfo(
            reason: Int? = ApplicationExitInfo.REASON_ANR,
            timestamp: Long? = null,
            importance: Int? = null
        ) {
            val builder = ApplicationExitInfoBuilder.newBuilder()
            if (reason != null) {
                builder.setReason(reason)
            }
            if (timestamp != null) {
                builder.setTimestamp(timestamp)
            }
            if (importance != null) {
                builder.setImportance(importance)
            }
            shadowActivityManager.addApplicationExitInfo(builder.build())
        }
    }

    private val fixture = Fixture()
    private lateinit var context: Context

    @BeforeTest
    fun `set up`() {
        Sentry.close()
        AppStartState.getInstance().resetInstance()
        context = ApplicationProvider.getApplicationContext()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        fixture.shadowActivityManager = Shadow.extract(activityManager)
    }

    @Test
    fun `when auto-init is disabled and user calls init manually, SDK initializes`() {
        assertFalse(Sentry.isEnabled())

        fixture.initSut()

        assertTrue(Sentry.isEnabled())
    }

    @Test
    fun `when auto-init is disabled and user calls init manually with a logger, SDK initializes`() {
        assertFalse(Sentry.isEnabled())

        fixture.initSut(logger = mock())

        assertTrue(Sentry.isEnabled())
    }

    @Test
    fun `when auto-init is disabled and user calls init manually with configuration handler, options should be set`() {
        assertFalse(Sentry.isEnabled())

        var refOptions: SentryAndroidOptions? = null
        fixture.initSut {
            it.anrTimeoutIntervalMillis = 3000
            refOptions = it
        }

        assertEquals(3000, refOptions!!.anrTimeoutIntervalMillis)
        assertTrue(Sentry.isEnabled())
    }

    @Test
    fun `init won't throw exception`() {
        val logger = mock<ILogger>()

        fixture.initSut(autoInit = true, logger = logger)

        verify(logger, never()).log(eq(SentryLevel.FATAL), any<String>(), any())
    }

    @Test
    fun `set app start if provider is disabled`() {
        fixture.initSut(autoInit = true)

        // done by ActivityLifecycleIntegration so forcing it here
        AppStartState.getInstance().setAppStartEnd()
        AppStartState.getInstance().setColdStart(true)

        assertNotNull(AppStartState.getInstance().appStartInterval)
    }

    @Test
    fun `deduplicates fragment and timber integrations`() {
        var refOptions: SentryAndroidOptions? = null

        fixture.initSut(autoInit = true) {
            it.addIntegration(
                FragmentLifecycleIntegration(ApplicationProvider.getApplicationContext())
            )

            it.addIntegration(
                SentryTimberIntegration(minEventLevel = FATAL, minBreadcrumbLevel = DEBUG)
            )
            refOptions = it
        }

        assertEquals(refOptions!!.integrations.filterIsInstance<SentryTimberIntegration>().size, 1)
        val timberIntegration =
            refOptions!!.integrations.find { it is SentryTimberIntegration } as SentryTimberIntegration
        assertEquals(timberIntegration.minEventLevel, FATAL)
        assertEquals(timberIntegration.minBreadcrumbLevel, DEBUG)

        // fragment integration is not auto-installed in the test, since the context is not Application
        // but we just verify here that the single integration is preserved
        assertEquals(
            refOptions!!.integrations.filterIsInstance<FragmentLifecycleIntegration>().size,
            1
        )
    }

    @Test
    fun `AndroidEnvelopeCache is reset if the user disabled caching via cacheDirPath`() {
        var refOptions: SentryAndroidOptions? = null

        fixture.initSut {
            it.cacheDirPath = null

            refOptions = it
        }

        assertTrue { refOptions!!.envelopeDiskCache is NoOpEnvelopeCache }
    }

    @Test
    fun `envelopeCache remains unchanged if the user set their own IEnvelopCache impl`() {
        var refOptions: SentryAndroidOptions? = null

        fixture.initSut {
            it.cacheDirPath = null
            it.setEnvelopeDiskCache(CustomEnvelopCache())

            refOptions = it
        }

        assertTrue { refOptions!!.envelopeDiskCache is CustomEnvelopCache }
    }

    @Test
    fun `When initializing Sentry manually and changing both cache dir and dsn, the corresponding options should reflect that change`() {
        var options: SentryOptions? = null

        val mockContext = ContextUtilsTest.createMockContext(true)
        val cacheDirPath = Files.createTempDirectory("new_cache").absolutePathString()
        SentryAndroid.init(mockContext) {
            it.dsn = "https://key@sentry.io/123"
            it.cacheDirPath = cacheDirPath
            options = it
        }

        val dsnHash = StringUtils.calculateStringHash(options!!.dsn, options!!.logger)
        val expectedCacheDir = "$cacheDirPath/$dsnHash"
        assertEquals(expectedCacheDir, options!!.cacheDirPath)
        assertEquals(
            expectedCacheDir,
            (options!!.envelopeDiskCache as AndroidEnvelopeCache).directory.absolutePath
        )
    }

    @Test
    fun `init starts a session if auto session tracking is enabled and app is in foreground`() {
        initSentryWithForegroundImportance(true) { session: Session? ->
            assertNotNull(session)
        }
    }

    @Test
    fun `init does not start a session if auto session tracking is enabled but the app is in background`() {
        initSentryWithForegroundImportance(false) { session: Session? ->
            assertNull(session)
        }
    }

    private fun initSentryWithForegroundImportance(
        inForeground: Boolean,
        callback: (session: Session?) -> Unit
    ) {
        val context = ContextUtilsTest.createMockContext()

        Mockito.mockStatic(ContextUtils::class.java).use { mockedContextUtils ->
            mockedContextUtils.`when`<Any> { ContextUtils.isForegroundImportance(context) }
                .thenReturn(inForeground)
            SentryAndroid.init(context) { options ->
                options.release = "prod"
                options.dsn = "https://key@sentry.io/123"
                options.isEnableAutoSessionTracking = true
            }

            var session: Session? = null
            Sentry.getCurrentHub().configureScope { scope ->
                session = scope.session
            }
            callback(session)
        }
    }

    @Test
    fun `init does not start a session by if auto session tracking is disabled`() {
        fixture.initSut { options ->
            options.isEnableAutoSessionTracking = false
        }
        Sentry.getCurrentHub().withScope { scope ->
            assertNull(scope.session)
        }
    }

    @Test
    @Config(sdk = [30])
    fun `AnrV2 events get enriched with previously persisted scope and options data, the new data gets persisted after that`() {
        val cacheDir = tmpDir.newFolder().absolutePath
        fixture.addAppExitInfo(timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        val asserted = AtomicBoolean(false)
        lateinit var options: SentryOptions

        fixture.initSut(context) {
            it.dsn = "https://key@sentry.io/123"
            it.cacheDirPath = cacheDir
            // beforeSend is called after event processors are applied, so we can assert here
            // against the enriched ANR event
            it.beforeSend = BeforeSendCallback { event, hint ->
                assertEquals("MainActivity", event.transaction)
                assertEquals("Debug!", event.breadcrumbs!![0].message)
                assertEquals("staging", event.environment)
                assertEquals("io.sentry.sample@2.0.0", event.release)
                asserted.set(true)
                null
            }

            // have to do it after the cacheDir is set to options, because it adds a dsn hash after
            prefillOptionsCache(it.cacheDirPath!!)
            prefillScopeCache(it.cacheDirPath!!)

            it.release = "io.sentry.sample@1.1.0+220"
            it.environment = "debug"
            // this is necessary to delay the AnrV2Integration processing to execute the configure
            // scope block below (otherwise it won't be possible as hub is no-op before .init)
            it.executorService.submit {
                Thread.sleep(2000L)
                Sentry.configureScope { scope ->
                    // make sure the scope values changed to test that we're still using previously
                    // persisted values for the old ANR events
                    assertEquals("TestActivity", scope.transactionName)
                }
            }
            options = it
        }
        Sentry.configureScope {
            it.setTransaction("TestActivity")
            it.addBreadcrumb(Breadcrumb.error("Error!"))
        }
        await.withAlias("Failed because of BeforeSend callback above, but we swallow BeforeSend exceptions, hence the timeout")
            .untilTrue(asserted)

        // assert that persisted values have changed
        options.executorService.close(1000L) // finalizes all enqueued persisting tasks
        assertEquals(
            "TestActivity",
            PersistingScopeObserver.read(options, TRANSACTION_FILENAME, String::class.java)
        )
        assertEquals(
            "io.sentry.sample@1.1.0+220",
            PersistingOptionsObserver.read(options, RELEASE_FILENAME, String::class.java)
        )
    }

    private fun prefillScopeCache(cacheDir: String) {
        val scopeDir = File(cacheDir, SCOPE_CACHE).also { it.mkdirs() }
        File(scopeDir, BREADCRUMBS_FILENAME).writeText(
            """
            [{
              "timestamp": "2009-11-16T01:08:47.000Z",
              "message": "Debug!",
              "type": "debug",
              "level": "debug"
            }]
            """.trimIndent()
        )
        File(scopeDir, TRANSACTION_FILENAME).writeText("\"MainActivity\"")
    }

    private fun prefillOptionsCache(cacheDir: String) {
        val optionsDir = File(cacheDir, OPTIONS_CACHE).also { it.mkdirs() }
        File(optionsDir, RELEASE_FILENAME).writeText("\"io.sentry.sample@2.0.0\"")
        File(optionsDir, ENVIRONMENT_FILENAME).writeText("\"staging\"")
    }

    private class CustomEnvelopCache : IEnvelopeCache {
        override fun iterator(): MutableIterator<SentryEnvelope> = TODO()
        override fun store(envelope: SentryEnvelope, hint: Hint) = Unit
        override fun discard(envelope: SentryEnvelope) = Unit
    }
}

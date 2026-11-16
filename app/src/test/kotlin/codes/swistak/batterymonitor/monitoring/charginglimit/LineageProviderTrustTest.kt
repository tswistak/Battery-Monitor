package codes.swistak.batterymonitor.monitoring.charginglimit

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.MatrixCursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LineageProviderTrustTest {
    private val packageName = DeviceProfileSignals.LINEAGE_PROVIDER_PACKAGE
    private val owner = LineageProviderIdentity(1000, "/system_ext/provider.apk")
    private val directValue = ReadResult.Value("80", ReadSource.CONTENT_RESOLVER)
    private val privilegedValue = ReadResult.Value("80", ReadSource.PRIVILEGED_SHELL)
    private val absent = ReadResult.Absent(ReadSource.CONTENT_RESOLVER)
    private val rejected = ReadResult.Failed(
        ReadFailureReason.ACCESS_DENIED, ReadSource.CONTENT_RESOLVER
    )

    @Test
    fun `only the platform Lineage settings package is trusted`() {
        val system = ApplicationInfo.FLAG_SYSTEM
        assertEquals(owner, trustedLineageProvider(provider(packageName, system)) { true })
        assertEquals(
            owner, trustedLineageProvider(
                provider(
                    packageName, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                )
            ) { true })
        assertNull(trustedLineageProvider(provider(packageName, system)) { false })
        assertNull(trustedLineageProvider(provider("other.system", system)) { true })
        assertNull(trustedLineageProvider(provider(packageName, 0)) { true })
        assertNull(trustedLineageProvider(null) { true })
    }

    @Test
    fun `untrusted authority is never queried through either backend`() {
        val spoof = provider(packageName, 0)
        val result = readTrustedLineageSetting(
            { trustedLineageProvider(spoof) { false } },
            { error("Untrusted direct query") },
            { error("Untrusted privileged query") })
        assertEquals(absent, result)
    }

    @Test
    fun `trusted direct value and privileged fallback are preserved`() {
        assertEquals(directValue, readTrustedLineageSetting({ owner }, { directValue }) {
            error("No privileged query needed")
        })
        assertEquals(privilegedValue, readTrustedLineageSetting({ owner }, { absent }) {
            privilegedValue
        })
    }

    @Test
    fun `provider disappearance or replacement discards the result`() {
        var current: LineageProviderIdentity? = owner
        assertEquals(rejected, readTrustedLineageSetting({ current }, {
            current = null
            directValue
        }) { error("Provider no longer exists") })
        current = owner
        assertEquals(rejected, readTrustedLineageSetting({ current }, { absent }) {
            current = owner.copy(sourceDir = "/system_ext/replacement.apk")
            privilegedValue
        })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Android reader consults PackageManager before querying even a cached authority`() {
        val context = RuntimeEnvironment.getApplication()
        val packageManager = shadowOf(context.packageManager)
        val signature = Signature("1234")
        packageManager.installPackage(PackageInfo().apply {
            packageName = "android"
            signatures = arrayOf(signature)
        })
        val lineageProvider = provider(packageName, ApplicationInfo.FLAG_SYSTEM).apply {
            authority = DeviceProfileSignals.LINEAGE_PROVIDER
            name = "SyntheticSettings"
            exported = true
        }
        packageManager.installPackage(PackageInfo().apply {
            packageName = lineageProvider.packageName
            applicationInfo = lineageProvider.applicationInfo
            providers = arrayOf(lineageProvider)
            signatures = arrayOf(signature)
        })
        val settings = SyntheticSettings()
        settings.attachInfo(context, lineageProvider)
        ShadowContentResolver.registerProviderInternal(lineageProvider.authority, settings)
        val reader = AndroidSettingReader(context, { true }) { error("Unexpected shell command") }
        assertEquals(owner, DeviceProfileSignals.lineageProvider(context.packageManager))
        assertEquals(
            directValue,
            reader.read(SettingNamespace.LINEAGE_SYSTEM, "charging_control_charging_limit")
        )
        assertEquals(1, settings.queries)

        packageManager.removePackage(packageName)
        assertNull(DeviceProfileSignals.lineageProvider(context.packageManager))
        assertEquals(
            absent, reader.read(SettingNamespace.LINEAGE_SYSTEM, "charging_control_charging_limit")
        )
        assertEquals(1, settings.queries)
    }

    private class SyntheticSettings : ContentProvider() {
        var queries = 0
            private set

        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ) = MatrixCursor(arrayOf("value")).apply {
            queries++
            addRow(arrayOf("80"))
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
        ) = 0
    }

    private fun provider(name: String, appFlags: Int) = ProviderInfo().apply {
        packageName = name
        applicationInfo = ApplicationInfo().apply {
            packageName = name
            flags = appFlags
            uid = owner.uid
            sourceDir = owner.sourceDir
        }
    }
}

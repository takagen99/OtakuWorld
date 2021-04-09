package com.programmersbox.uiviews

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import androidx.navigation.fragment.findNavController
import androidx.preference.*
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseUser
import com.programmersbox.helpfulutils.requestPermissions
import com.programmersbox.helpfulutils.runOnUIThread
import com.programmersbox.loggingutils.Loged
import com.programmersbox.models.sourcePublish
import com.programmersbox.thirdpartyutils.into
import com.programmersbox.thirdpartyutils.openInCustomChromeBrowser
import com.programmersbox.uiviews.utils.*
import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class SettingsFragment : PreferenceFragmentCompat() {

    private val disposable: CompositeDisposable = CompositeDisposable()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        val genericInfo = BaseMainActivity.genericInfo

        accountPreferences()
        generalPreferences(genericInfo)
        aboutPreferences(genericInfo)

        val settingsDsl = SettingsDsl()

        genericInfo.customPreferences(settingsDsl)

        findPreference<PreferenceCategory>("generalCategory")?.let { settingsDsl.generalSettings(it) }
        findPreference<PreferenceCategory>("viewCategory")?.let { settingsDsl.viewSettings(it) }
    }

    private fun accountPreferences() {
        findPreference<Preference>("user_account")?.let { p ->

            fun accountChanges(user: FirebaseUser?) {
                Glide.with(this@SettingsFragment)
                    .load(user?.photoUrl)
                    .placeholder(OtakuApp.logo)
                    .error(OtakuApp.logo)
                    .fallback(OtakuApp.logo)
                    .circleCrop()
                    .into<Drawable> { resourceReady { image, _ -> p.icon = image } }
                p.title = user?.displayName ?: "User"
            }

            FirebaseAuthentication.auth.addAuthStateListener {
                accountChanges(it.currentUser)
                //findPreference<Preference>("upload_favorites")?.isEnabled = it.currentUser != null
                //findPreference<Preference>("upload_favorites")?.isVisible = it.currentUser != null
            }

            accountChanges(FirebaseAuthentication.currentUser)

            p.setOnPreferenceClickListener {
                FirebaseAuthentication.currentUser?.let {
                    MaterialAlertDialogBuilder(this@SettingsFragment.requireContext())
                        .setTitle("Log Out?")
                        .setMessage("Are you sure you want to log out?")
                        .setPositiveButton("Yes") { d, _ ->
                            FirebaseAuthentication.signOut()
                            d.dismiss()
                        }
                        .setNegativeButton("No") { d, _ -> d.dismiss() }
                        .show()
                } ?: FirebaseAuthentication.signIn(requireActivity())
                true
            }
        }
    }

    private fun generalPreferences(genericInfo: GenericInfo) {

        findPreference<PreferenceCategory>("aboutCategory")?.setIcon(OtakuApp.logo)

        findPreference<Preference>("current_source")?.let { p ->
            val list = genericInfo.sourceList().toTypedArray()
            p.setOnPreferenceClickListener {
                val service = requireContext().currentService
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Choose a source")
                    .setSingleChoiceItems(
                        list.map { it.serviceName }.toTypedArray(),
                        list.indexOfFirst { it.serviceName == service }
                    ) { d, i ->
                        sourcePublish.onNext(list[i])
                        requireContext().currentService = list[i].serviceName
                        d.dismiss()
                    }
                    .setPositiveButton("Done") { d, _ -> d.dismiss() }
                    .show()
                true
            }
            sourcePublish.subscribe { p.title = "Current Source: ${it.serviceName}" }
                .addTo(disposable)
        }

        findPreference<Preference>("view_source")?.let { p ->
            p.setOnPreferenceClickListener {
                requireContext().openInCustomChromeBrowser(sourcePublish.value!!.baseUrl) {
                    setStartAnimations(requireContext(), R.anim.fui_slide_in_right, R.anim.fui_slide_out_left)
                    setShareState(CustomTabsIntent.SHARE_STATE_ON)
                }
                true
            }
        }

        findPreference<Preference>("view_favorites")?.setOnPreferenceClickListener {
            findNavController().navigate(SettingsFragmentDirections.actionSettingsFragmentToFavoriteFragment())
            true
        }

        findPreference<ListPreference>("theme_setting")?.let { p ->
            p.setDefaultValue("system")
            p.setOnPreferenceChangeListener { _, newValue ->
                when (newValue) {
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> null
                }?.let(AppCompatDelegate::setDefaultNightMode)
                true
            }
        }

        findPreference<SeekBarPreference>("battery_alert")?.let { s ->
            s.showSeekBarValue = true
            s.setDefaultValue(requireContext().batteryAlertPercent)
            s.value = requireContext().batteryAlertPercent
            s.max = 100
            s.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Int) {
                    requireContext().batteryAlertPercent = newValue
                }
                true
            }
        }

        findPreference<Preference>("saved_notifications")?.let { p ->
            p.setOnPreferenceClickListener {
                SavedNotifications().viewNotificationsFromDb(requireContext())
                true
            }
        }

        findPreference<Preference>("delete_notifications")?.let { p ->
            p.setOnPreferenceClickListener {

                true
            }
        }

    }

    private fun aboutPreferences(genericInfo: GenericInfo) {
        val checker = AtomicBoolean(false)
        fun updateSetter() {
            if (!checker.get()) {
                GlobalScope.launch {
                    checker.set(true)
                    val request = Request.Builder()
                        .url("https://github.com/jakepurple13/OtakuWorld/releases/latest")
                        .get()
                        .build()
                    @Suppress("BlockingMethodInNonBlockingContext") val response = OkHttpClient().newCall(request).execute()
                    val f = response.request().url().path.split("/").lastOrNull()?.toDoubleOrNull()
                    runOnUIThread {
                        findPreference<Preference>("updateAvailable")?.let { p1 ->
                            p1.summary = "Version: $f"
                            p1.isVisible =
                                context?.packageManager?.getPackageInfo(
                                    requireContext().packageName,
                                    0
                                )?.versionName?.toDoubleOrNull() ?: 0.0 < f ?: 0.0
                        }
                    }
                    checker.set(false)
                }
            }
        }

        findPreference<Preference>("about_version")?.let { p ->
            p.title = "Version: ${context?.packageManager?.getPackageInfo(requireContext().packageName, 0)?.versionName}"
            p.summary = "Press to Check for Updates"
            p.setOnPreferenceClickListener {
                updateSetter()
                true
            }
        }

        findPreference<Preference>("updateAvailable")?.let { p ->
            p.isVisible = false
            updateSetter()
            p.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Update to ${p.summary}")
                    .setMessage("There's an update! Please update if you want to have the latest features!")
                    .setPositiveButton(R.string.update) { d, _ ->
                        activity?.requestPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE) {
                            if (it.isGranted) {
                                val isApkAlreadyThere =
                                    File(context?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!.absolutePath + "/", genericInfo.apkString)
                                if (isApkAlreadyThere.exists()) isApkAlreadyThere.delete()

                                DownloadApk(
                                    requireContext(),
                                    "https://github.com/jakepurple13/OtakuWorld/releases/latest/download/${genericInfo.apkString}",
                                    genericInfo.apkString
                                ).startDownloadingApk()
                            }
                        }
                        d.dismiss()
                    }
                    .setNeutralButton(R.string.gotoBrowser) { d, _ ->
                        context?.openInCustomChromeBrowser("https://github.com/jakepurple13/OtakuWorld/releases/latest")
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.notNow) { d, _ -> d.dismiss() }
                    .show()

                true
            }
        }

        findPreference<Preference>("sync_time")?.let { s ->
            requireContext().lastUpdateCheck
                ?.let { SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(it) }
                ?.let { s.summary = it }

            updateCheckPublish
                .map { SimpleDateFormat("MM/dd/yyyy hh:mm a", Locale.getDefault()).format(it) }
                .subscribe { s.summary = it }
                .addTo(disposable)

            s.setOnPreferenceClickListener {
                WorkManager.getInstance(this.requireContext())
                    .enqueueUniqueWork(
                        "oneTimeUpdate",
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<UpdateWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                    .setRequiresBatteryNotLow(false)
                                    .setRequiresCharging(false)
                                    .setRequiresDeviceIdle(false)
                                    .setRequiresStorageNotLow(false)
                                    .build()
                            )
                            .build()
                    )
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("sync")?.let { s ->
            s.setDefaultValue(requireContext().shouldCheck)
            s.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    requireContext().shouldCheck = newValue
                    OtakuApp.updateSetup(requireContext())
                }
                true
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.dispose()
    }
}

class SettingsDsl {
    internal var generalSettings: (PreferenceCategory) -> Unit = {}

    fun generalSettings(block: (PreferenceCategory) -> Unit) {
        generalSettings = block
    }

    internal var viewSettings: (PreferenceCategory) -> Unit = {}

    fun viewSettings(block: (PreferenceCategory) -> Unit) {
        viewSettings = block
    }
}

class DownloadApk(val context: Context, private val downloadUrl: String, private val outputName: String) : CoroutineScope {
    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job // to run code in Main(UI) Thread

    // call this method to cancel a coroutine when you don't need it anymore,
    // e.g. when user closes the screen
    fun cancel() {
        job.cancel()
    }

    fun startDownloadingApk() {
        if (URLUtil.isValidUrl(downloadUrl)) execute()
    }

    private lateinit var bar: AlertDialog

    private fun execute() = launch {
        onPreExecute()
        val result = doInBackground() // runs in background thread without blocking the Main Thread
        onPostExecute(result)
    }

    private suspend fun onProgressUpdate(vararg values: Int?) = withContext(Dispatchers.Main) {
        val progress = values[0]
        if (progress != null) {
            bar.setMessage(if (progress > 99) "Finishing... " else "Downloading... $progress%")
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun doInBackground(): Boolean = withContext(Dispatchers.IO) { // to run code in Background Thread
        // do async work
        var flag = false

        try {
            val url = URL(downloadUrl)
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connect()
            val path = Environment.getExternalStorageDirectory().toString() + "/Download/"
            val file = File(path)
            file.mkdirs()
            val outputFile = File(file, outputName)

            if (outputFile.exists()) outputFile.delete()

            val fos = FileOutputStream(outputFile)
            val inputStream = c.inputStream
            val totalSize = c.contentLength.toFloat() //size of apk

            val buffer = ByteArray(1024)
            var len1: Int
            var per: Float
            var downloaded = 0f
            while (inputStream.read(buffer).also { len1 = it } != -1) {
                fos.write(buffer, 0, len1)
                downloaded += len1
                per = (downloaded * 100 / totalSize)
                onProgressUpdate(per.toInt())
            }
            fos.close()
            inputStream.close()
            openNewVersion(path)
            flag = true
        } catch (e: MalformedURLException) {
            Loged.e("Update Error: " + e.message, "DownloadApk")
            flag = false
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return@withContext flag
    }

    // Runs on the Main(UI) Thread
    private fun onPreExecute() {
        // show progress
        bar = MaterialAlertDialogBuilder(context)
            .setTitle("Updating...")
            .setMessage("Downloading...")
            .setCancelable(false)
            .setIcon(OtakuApp.logo)
            .show()
    }

    // Runs on the Main(UI) Thread
    private fun onPostExecute(result: Boolean?) {
        // hide progress
        bar.dismiss()
        if (result != null && result) {
            Toast.makeText(context, "Update Done", Toast.LENGTH_SHORT)
                .show()
        } else {
            Toast.makeText(context, "Error: Try Again", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun openNewVersion(location: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(getUriFromFile(location), "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun getUriFromFile(location: String): Uri {
        return if (Build.VERSION.SDK_INT < 24) {
            Uri.fromFile(File(location + outputName))
        } else {
            FileProvider.getUriForFile(context, context.packageName + ".provider", File(location + outputName))
        }
    }
}
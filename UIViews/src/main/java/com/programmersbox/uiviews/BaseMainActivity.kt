package com.programmersbox.uiviews

import android.os.Bundle
import android.webkit.URLUtil
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.programmersbox.models.sourcePublish
import com.programmersbox.uiviews.utils.appUpdateCheck
import com.programmersbox.uiviews.utils.currentService
import com.programmersbox.uiviews.utils.setupWithNavController
import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

abstract class BaseMainActivity : AppCompatActivity() {

    protected val disposable = CompositeDisposable()

    private var currentNavController: LiveData<NavController>? = null

    protected val genericInfo: GenericInfo by inject()

    protected abstract fun onCreate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.base_main_activity)

        genericInfo.toSource(currentService.orEmpty())?.let { sourcePublish.onNext(it) }

        if (savedInstanceState == null) {
            setupBottomNavBar()
        }

        onCreate()

        intent.data?.let {
            if (URLUtil.isValidUrl(it.toString())) {
                currentService?.let { it1 ->
                    lifecycleScope.launch {
                        genericInfo.toSource(it1)?.getSourceByUrl(it.toString())?.let { it2 ->
                            runOnUiThread { currentNavController?.value?.navigate(RecentFragmentDirections.actionRecentFragment2ToDetailsFragment2(it2)) }
                        }
                    }
                }
            }
        }

    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        setupBottomNavBar()
    }

    private fun setupBottomNavBar() {
        val navGraphIds = listOf(R.navigation.recent_nav, R.navigation.all_nav, R.navigation.setting_nav)

        val controller = findViewById<BottomNavigationView>(R.id.navLayout2)
            .also { b ->
                appUpdateCheck
                    .filter { packageManager?.getPackageInfo(packageName, 0)?.versionName?.toDoubleOrNull() ?: 0.0 < it }
                    .subscribe { b.getOrCreateBadge(R.id.setting_nav).number = 1 }
                    .addTo(disposable)
            }
            .setupWithNavController(
                navGraphIds = navGraphIds,
                fragmentManager = supportFragmentManager,
                containerId = R.id.mainShows,
                intent = intent
            )

        currentNavController = controller

        lifecycleScope.launch {
            try {
                val request = Request.Builder()
                    .url("https://github.com/jakepurple13/OtakuWorld/releases/latest")
                    .get()
                    .build()
                @Suppress("BlockingMethodInNonBlockingContext") val response = OkHttpClient().newCall(request).execute()
                val f = response.request().url().path.split("/").lastOrNull()?.toDoubleOrNull()
                f?.let { it1 -> appUpdateCheck.onNext(it1) }
            } catch (e: Exception) {
            }
        }

        /*sourcePublish.onNext(currentSource)

        sourcePublish
            .subscribe { currentSource = it }
            .addTo(disposable)

        downloadOrStreamPublish
            .subscribe { downloadOrStream = it }
            .addTo(disposable)

        updateCheckPublish
            .subscribe { lastUpdateCheck = it }
            .addTo(disposable)*/
    }

    override fun onSupportNavigateUp(): Boolean = currentNavController?.value?.navigateUp() ?: false

    override fun onDestroy() {
        disposable.dispose()
        super.onDestroy()
    }

}
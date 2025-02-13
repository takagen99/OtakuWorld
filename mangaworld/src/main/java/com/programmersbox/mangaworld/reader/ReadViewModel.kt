package com.programmersbox.mangaworld.reader

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.util.fastMap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.google.android.gms.ads.AdRequest
import com.programmersbox.favoritesdatabase.ChapterWatched
import com.programmersbox.favoritesdatabase.ItemDatabase
import com.programmersbox.gsonutils.fromJson
import com.programmersbox.gsonutils.toJson
import com.programmersbox.mangaworld.ChapterList
import com.programmersbox.models.ChapterModel
import com.programmersbox.models.Storage
import com.programmersbox.sharedutils.FirebaseDb
import com.programmersbox.uiviews.GenericInfo
import com.programmersbox.uiviews.utils.BatteryInformation
import com.programmersbox.uiviews.utils.ChapterModelDeserializer
import com.programmersbox.uiviews.utils.ChapterModelSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReadViewModel(
    handle: SavedStateHandle,
    context: Context,
    val genericInfo: GenericInfo,
    val headers: MutableMap<String, String> = mutableMapOf(),
    model: Flow<List<String>>? = handle
        .get<String>("currentChapter")
        ?.fromJson<ChapterModel>(ChapterModel::class.java to ChapterModelDeserializer(genericInfo))
        ?.getChapterInfo()
        ?.map {
            headers.putAll(it.flatMap { h -> h.headers.toList() })
            it.mapNotNull(Storage::link)
        },
    /*?.subscribeOn(Schedulers.io())
    ?.observeOn(AndroidSchedulers.mainThread())*/
    //?.doOnError { Toast.makeText(context, it.localizedMessage, Toast.LENGTH_SHORT).show() },
    val isDownloaded: Boolean = handle.get<String>("downloaded")?.toBoolean() ?: false,
    filePath: File? = handle.get<String>("filePath")?.let { File(it) },
    modelPath: Flow<List<String>>? = if (isDownloaded && filePath != null) {
        /*Single.create<List<String>> {
            filePath
                .listFiles()
                ?.sortedBy { f -> f.name.split(".").first().toInt() }
                ?.fastMap(File::toUri)
                ?.fastMap(Uri::toString)
                ?.let(it::onSuccess) ?: it.onError(Throwable("Cannot find files"))
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())*/
        flow {
            filePath
                .listFiles()
                ?.sortedBy { f -> f.name.split(".").first().toInt() }
                ?.fastMap(File::toUri)
                ?.fastMap(Uri::toString)
                ?.let { emit(it) } ?: throw Exception("Cannot find files")
        }
            .catch { emit(emptyList()) }
            .flowOn(Dispatchers.IO)
    } else {
        model
    },
) : ViewModel() {

    companion object {
        const val MangaReaderRoute =
            "mangareader?currentChapter={currentChapter}&mangaTitle={mangaTitle}&mangaUrl={mangaUrl}&mangaInfoUrl={mangaInfoUrl}&downloaded={downloaded}&filePath={filePath}"

        fun navigateToMangaReader(
            navController: NavController,
            currentChapter: ChapterModel? = null,
            mangaTitle: String? = null,
            mangaUrl: String? = null,
            mangaInfoUrl: String? = null,
            downloaded: Boolean = false,
            filePath: String? = null
        ) {
            val current = Uri.encode(currentChapter?.toJson(ChapterModel::class.java to ChapterModelSerializer()))

            navController.navigate(
                "mangareader?currentChapter=$current&mangaTitle=${mangaTitle}&mangaUrl=${mangaUrl}&mangaInfoUrl=${mangaInfoUrl}&downloaded=$downloaded&filePath=$filePath"
            ) { launchSingleTop = true }
        }
    }

    val title by lazy { handle.get<String>("mangaTitle") ?: "" }

    val ad: AdRequest by lazy { AdRequest.Builder().build() }

    private val dao by lazy { ItemDatabase.getInstance(context).itemDao() }

    private val chapterList by lazy { ChapterList(context, genericInfo) }
    var list by mutableStateOf<List<ChapterModel>>(emptyList())

    private val mangaUrl by lazy { handle.get<String>("mangaInfoUrl") ?: "" }

    var currentChapter: Int by mutableStateOf(0)

    var batteryColor by mutableStateOf(Color.White)
    var batteryIcon by mutableStateOf(BatteryInformation.BatteryViewType.UNKNOWN)
    var batteryPercent by mutableStateOf(0f)

    val batteryInformation by lazy { BatteryInformation(context) }

    val pageList = mutableStateListOf<String>()
    var isLoadingPages by mutableStateOf(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            batteryInformation.composeSetupFlow(
                Color.White
            ) {
                batteryColor = it.first
                batteryIcon = it.second
            }
        }

        val url = handle.get<String>("mangaUrl") ?: ""
        list = chapterList.get().orEmpty()
        currentChapter = list.indexOfFirst { l -> l.url == url }

        loadPages(modelPath)
    }

    var showInfo by mutableStateOf(false)

    fun addChapterToWatched(newChapter: Int, chapter: () -> Unit) {
        currentChapter = newChapter
        list.getOrNull(newChapter)?.let { item ->
            ChapterWatched(item.url, item.name, mangaUrl)
                .let {
                    viewModelScope.launch {
                        dao.insertChapter(it)
                        FirebaseDb.insertEpisodeWatchedFlow(it).collect()
                        withContext(Dispatchers.Main) { chapter() }
                    }
                }

            item
                .getChapterInfo()
                .map { it.mapNotNull(Storage::link) }
                .let { loadPages(it) }
        }
    }

    private fun loadPages(modelPath: Flow<List<String>>?) {
        modelPath
            ?.onStart {
                isLoadingPages = true
                pageList.clear()
            }
            ?.onEach { pageList.addAll(it) }
            ?.onCompletion { isLoadingPages = false }
            ?.launchIn(viewModelScope)
    }

    fun refresh() {
        headers.clear()
        loadPages(
            list.getOrNull(currentChapter)
                ?.getChapterInfo()
                ?.map {
                    headers.putAll(it.flatMap { h -> h.headers.toList() })
                    it.mapNotNull(Storage::link)
                }
        )
    }

    override fun onCleared() {
        super.onCleared()
        chapterList.clear()
    }
}
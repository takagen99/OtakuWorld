package com.programmersbox.anime_sources.anime

import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import com.programmersbox.anime_sources.ShowApi
import com.programmersbox.anime_sources.Sources
import com.programmersbox.anime_sources.utilities.M3u8Helper
import com.programmersbox.anime_sources.utilities.fixUrl
import com.programmersbox.anime_sources.utilities.getQualityFromName
import com.programmersbox.gsonutils.fromJson
import com.programmersbox.helpfulutils.similarity
import com.programmersbox.models.ChapterModel
import com.programmersbox.models.InfoModel
import com.programmersbox.models.ItemModel
import com.programmersbox.models.Storage
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import khttp.responses.Response
import khttp.structures.authorization.Authorization
import khttp.structures.cookie.Cookie
import khttp.structures.cookie.CookieJar
import khttp.structures.files.FileLike
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * An HTTP session manager.
 *
 * This class simply keeps cookies across requests.
 *
 * @property sessionCookies A cookie jar.
 */
class HttpSession {
    companion object {
        const val DEFAULT_TIMEOUT = 30.0

        fun mergeCookies(cookie1: CookieJar, cookie2: Map<String, String>?): Map<String, String> {
            val a = cookie1
            if (!cookie2.isNullOrEmpty()) {
                a.putAll(cookie2)
            }
            return a
        }
    }

    val sessionCookies = CookieJar()

    fun get(
        url: String,
        headers: Map<String, String?> = mapOf(),
        params: Map<String, String> = mapOf(),
        data: Any? = null,
        json: Any? = null,
        auth: Authorization? = null,
        cookies: Map<String, String>? = null,
        timeout: Double = DEFAULT_TIMEOUT,
        allowRedirects: Boolean? = null,
        stream: Boolean = false,
        files: List<FileLike> = listOf(),
    ): Response {
        val res =
            khttp.get(
                url,
                headers,
                params,
                data,
                json,
                auth,
                mergeCookies(sessionCookies, cookies),
                timeout,
                allowRedirects,
                stream,
                files
            )
        sessionCookies.putAll(res.cookies)
        sessionCookies.putAll(
            CookieJar(
                *res.headers
                    .filter { it.key.toLowerCase() == "set-cookie" }
                    .map { Cookie(it.value) }
                    .toTypedArray()
            )
        )
        return res
    }

    fun post(
        url: String,
        headers: Map<String, String?> = mapOf(),
        params: Map<String, String> = mapOf(),
        data: Any? = null,
        json: Any? = null,
        auth: Authorization? = null,
        cookies: Map<String, String>? = null,
        timeout: Double = DEFAULT_TIMEOUT,
        allowRedirects: Boolean? = null,
        stream: Boolean = false,
        files: List<FileLike> = listOf()
    ): Response {
        val res =
            khttp.post(
                url,
                headers,
                params,
                data,
                json,
                auth,
                mergeCookies(sessionCookies, cookies),
                timeout,
                allowRedirects,
                stream,
                files
            )
        sessionCookies.putAll(res.cookies)
        sessionCookies.putAll(
            CookieJar(
                *res.headers
                    .filter { it.key.toLowerCase() == "set-cookie" }
                    .map { Cookie(it.value) }
                    .toTypedArray()
            )
        )
        return res
    }
}

private fun String.toAscii() = this.map { it.toInt() }.joinToString()

class CrunchyrollGeoBypasser {
    companion object {
        const val BYPASS_SERVER = "https://cr-unblocker.us.to/start_session"
        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "gzip, deflate",
            "Connection" to "keep-alive",
            "Referer" to "https://google.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.159 Safari/537.36".toAscii()
        )
        var sessionId: String? = null
        val session = HttpSession()
    }

    private fun getSessionId(): Boolean {
        return try {
            sessionId = khttp.get(BYPASS_SERVER, params = mapOf("version" to "1.1")).jsonObject.getJSONObject("data").getString("session_id")
            true
        } catch (e: Exception) {
            sessionId = null
            false
        }
    }

    private fun autoLoadSession(): Boolean {
        if (sessionId != null) return true
        getSessionId()
        return autoLoadSession()
    }

    fun geoBypassRequest(url: String): khttp.responses.Response {
        autoLoadSession()
        return session.get(url, headers = headers, cookies = mapOf("session_id" to sessionId!!))
    }
}

object CrunchyRoll : ShowApi(
    baseUrl = "http://www.crunchyroll.com",
    recentPath = "videos/anime/updated", allPath = ""
) {

    override val serviceName: String get() = "CRUNCHYROLL"
    override val canScroll: Boolean get() = false
    override val canDownload: Boolean get() = false

    private val crUnblock = CrunchyrollGeoBypasser()
    private val episodeNumRegex = Regex("""Episode (\d+)""")

    override fun getRecent(page: Int): Single<List<ItemModel>> = Single.create<Document> { emitter ->
        emitter.onSuccess(Jsoup.parse(crUnblock.geoBypassRequest("$baseUrl/videos/anime/popular/ajax_page?pg=1").text))
    }
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .flatMap { getRecent(it) }

    override fun getRecent(doc: Document): Single<List<ItemModel>> = Single.create { emitter ->
        doc
            .select("li.group-item")
            .fastMap {
                ItemModel(
                    title = it.select("span.series-title").text(),
                    description = "",
                    imageUrl = it.select("span.img-holder")
                        .select("img")
                        .attr("src"),
                    url = fixUrl(it.select("a").attr("href")),
                    source = Sources.CRUNCHYROLL
                )
            }
            .let(emitter::onSuccess)
    }

    override fun getList(page: Int): Single<List<ItemModel>> = Single.create<Document> { emitter ->
        emitter.onSuccess(Jsoup.parse(crUnblock.geoBypassRequest("$baseUrl/videos/anime/popular/ajax_page?pg=2").text))
    }
        .subscribeOn(Schedulers.io())
        .observeOn(Schedulers.io())
        .flatMap { getRecent(it) }

    override fun getList(doc: Document): Single<List<ItemModel>> = Single.create { emitter ->
        doc
            .select("li.group-item")
            .fastMap {
                ItemModel(
                    title = it.select("span.series-title").text(),
                    description = "",
                    imageUrl = it.select("span.img-holder")
                        .select("img")
                        .attr("src"),
                    url = fixUrl(it.select("a").attr("href")),
                    source = Sources.CRUNCHYROLL
                )
            }
            .let(emitter::onSuccess)
    }

    private data class CrunchyAnimeData(
        val name: String,
        val img: String,
        val link: String
    )

    private data class CrunchyJson(val data: List<CrunchyAnimeData>)

    override fun searchList(searchText: CharSequence, page: Int, list: List<ItemModel>): Single<List<ItemModel>> = Single.create<List<ItemModel>> {
        val json = crUnblock.geoBypassRequest("http://www.crunchyroll.com/ajax/?req=RpcApiSearch_GetSearchCandidates")
            .text
            .split("*/")[0].replace("\\/", "/")
            .split("\n").mapNotNull { if (!it.startsWith("/")) it else null }.joinToString("\n")
            .fromJson<CrunchyJson>()
            ?.data
            ?.filter { data -> data.name.similarity(searchText.toString()) >= .6 || data.name.contains(searchText, true) }
            ?.fastMap {
                ItemModel(
                    title = it.name,
                    description = "",
                    imageUrl = it.img.replace("small", "full"),
                    url = fixUrl(it.link),
                    source = Sources.CRUNCHYROLL
                )
            }
            .orEmpty()
        it.onSuccess(json)
    }
        .onErrorResumeNext(super.searchList(searchText, page, list))

    override fun getItemInfo(model: ItemModel): Single<InfoModel> = Single.create { emitter ->

        val source = model
        val doc = Jsoup.parse(crUnblock.geoBypassRequest(fixUrl(model.url)).text)

        val sub = ArrayList<ChapterModel>()
        val dub = ArrayList<ChapterModel>()

        doc
            .select(".season")
            .fastForEach {
                val seasonName = it.selectFirst("a.season-dropdown")?.text()?.trim()
                it.select(".episode").forEach { ep ->
                    val epTitle = ep.selectFirst(".short-desc")?.text()
                    val epNum = episodeNumRegex.find(ep.selectFirst("span.ellipsis")?.text().toString())?.destructured?.component1()

                    if (seasonName == null) {
                        val epi = ChapterModel(
                            "$epNum: $epTitle",
                            fixUrl(ep.attr("href")),
                            ep.select("div.episode-progress-bar").select("div.episode_progress").attr("media_id"),
                            source.url,
                            Sources.CRUNCHYROLL
                        )
                        sub.add(epi)
                    } else if (seasonName.contains("(HD)")) {
                        // do nothing (filters our premium eps from one piece)
                    } else if (seasonName.contains("Dub") || seasonName.contains("Russian")) {
                        val epi = ChapterModel(
                            "$epNum: $epTitle (Dub)",
                            fixUrl(ep.attr("href")),
                            ep.select("div.episode-progress-bar").select("div.episode_progress").attr("media_id"),
                            source.url,
                            Sources.CRUNCHYROLL
                        )
                        dub.add(epi)
                    } else {
                        val epi = ChapterModel(
                            "$epNum: $epTitle",
                            fixUrl(ep.attr("href")),
                            ep.select("div.episode-progress-bar").select("div.episode_progress").attr("media_id"),
                            source.url,
                            Sources.CRUNCHYROLL
                        )
                        sub.add(epi)
                    }
                }
            }

        val p = doc.selectFirst(".description")

        val description = if (
            p?.selectFirst(".more") != null &&
            !p.selectFirst(".more")?.text()?.trim().isNullOrEmpty()
        ) {
            p.selectFirst(".more")?.text()?.trim()
        } else {
            p?.selectFirst("span")?.text()?.trim()
        }
            .orEmpty()

        InfoModel(
            source = Sources.CRUNCHYROLL,
            title = source.title,
            url = source.url,
            alternativeNames = emptyList(),
            description = description,
            imageUrl = source.imageUrl,
            genres = doc.select(".large-margin-bottom > ul:nth-child(2) li:nth-child(2) a").map { it.text() },
            chapters = sub + dub
        )
            .let(emitter::onSuccess)
    }

    override fun getItemInfo(source: ItemModel, doc: Document): Single<InfoModel> = Single.never()

    data class Subtitles(
        val language: String,
        val url: String,
        val title: String?,
        val format: String?
    )

    data class Streams(
        val format: String?,
        val audio_lang: String?,
        val hardsub_lang: String?,
        val url: String,
        val resolution: String?,
        var title: String?
    )

    data class CrunchyrollVideo(
        val streams: List<Streams>,
        val subtitles: List<Subtitles>,
    )

    override fun getChapterInfo(chapterModel: ChapterModel): Single<List<Storage>> = Single.create<List<Storage>> { emitter ->

        //TODO: Look into using CloudStream's video player
        // https://github.com/LagradOst/CloudStream-3/blob/master/app/src/main/java/com/lagradost/cloudstream3/ui/player/PlayerFragment.kt
        // It looks to handle things and casting better

        //TODO: ALSO! Look into adding a bar above the nav bar for AnimeWorld only! This will ONLY show when casting

        val contentRegex = Regex("""vilos\.config\.media = (\{.+\})""")
        val response = crUnblock.geoBypassRequest(chapterModel.url)

        val hlsHelper = M3u8Helper()

        val dat = contentRegex.find(response.text)?.destructured?.component1()

        val f = if (!dat.isNullOrEmpty()) {
            val json = dat.fromJson<CrunchyrollVideo>()

            val streams = ArrayList<Streams>()

            for (stream in json?.streams.orEmpty()) {
                if (
                    listOf(
                        "adaptive_hls", "adaptive_dash",
                        "multitrack_adaptive_hls_v2",
                        "vo_adaptive_dash", "vo_adaptive_hls"
                    ).contains(stream.format)
                ) {
                    if (stream.audio_lang == "jaJP" && (listOf(null, "enUS").contains(stream.hardsub_lang)) && listOf(
                            "m3u",
                            "m3u8"
                        ).contains(hlsHelper.absoluteExtensionDetermination(stream.url))
                    ) {
                        stream.title = if (stream.hardsub_lang == "enUS") "Hardsub" else "Raw"
                        streams.add(stream)
                    }
                }
            }


            streams.flatMap { stream ->
                try {
                    hlsHelper.m3u8Generation(M3u8Helper.M3u8Stream(stream.url, null), false).fastMap {
                        Storage(
                            link = it.streamUrl,
                            source = chapterModel.url,
                            filename = "${chapterModel.name}.mp4",
                            quality = "${stream.title}: ${stream.resolution} - ${stream.format} - ${getQualityFromName(it.quality.toString()).name}",
                            sub = getQualityFromName(it.quality.toString()).name
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            }
        } else emptyList()

        emitter.onSuccess(f)
    }
        .timeout(15, TimeUnit.SECONDS)
        .onErrorReturnItem(emptyList())
}

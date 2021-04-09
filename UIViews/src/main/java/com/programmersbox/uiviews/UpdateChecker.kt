package com.programmersbox.uiviews

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.navigation.NavDeepLinkBuilder
import androidx.work.RxWorker
import androidx.work.WorkerParameters
import com.programmersbox.favoritesdatabase.*
import com.programmersbox.helpfulutils.GroupBehavior
import com.programmersbox.helpfulutils.NotificationDslBuilder
import com.programmersbox.helpfulutils.intersect
import com.programmersbox.helpfulutils.notificationManager
import com.programmersbox.loggingutils.Loged
import com.programmersbox.loggingutils.f
import com.programmersbox.models.ApiService
import com.programmersbox.models.InfoModel
import com.programmersbox.uiviews.utils.FirebaseDb
import com.programmersbox.uiviews.utils.lastUpdateCheck
import com.programmersbox.uiviews.utils.updateCheckPublish
import io.reactivex.Single
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference


class UpdateWorker(context: Context, workerParams: WorkerParameters) : RxWorker(context, workerParams) {

    private val update by lazy { UpdateNotification(this.applicationContext) }
    private val dao by lazy { ItemDatabase.getInstance(this@UpdateWorker.applicationContext).itemDao() }

    /*override fun startWork(): ListenableFuture<Result> {
        update.sendRunningNotification(100, 0, "Starting check")
        return super.startWork()
    }*/

    companion object {
        var sourcesList: List<ApiService> = emptyList()
        var sourceFromString: (String) -> ApiService? = { null }
    }

    override fun createWork(): Single<Result> {
        update.sendRunningNotification(100, 0, "Starting check")
        Loged.f("Starting check here")
        applicationContext.lastUpdateCheck = System.currentTimeMillis()
        applicationContext.lastUpdateCheck?.let { updateCheckPublish.onNext(it) }
        return Single.create<List<DbModel>> { emitter ->
            Loged.f("Start")
            val list = listOf(
                dao.getAllFavoritesSync(),
                FirebaseDb.getAllShows().requireNoNulls()
            ).flatten().groupBy(DbModel::url).map { it.value.maxByOrNull(DbModel::numChapters)!! }
            //applicationContext.dbAndFireMangaSync3(dao)
            /*val sourceList = Sources.getUpdateSearches()
                .filter { s -> list.any { m -> m.source == s } }
                .flatMap { m -> m.getManga() }*/

            val newList = list.intersect(
                sourcesList
                    .filter { s -> list.any { m -> m.source == s.serviceName } }
                    .mapNotNull { m ->
                        try {
                            m.getRecent().blockingGet()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                    }.flatten()
            ) { o, n -> o.url == n.url }
            emitter.onSuccess(newList.distinctBy { it.url })
        }
            .map { list ->
                Loged.f("Map1")
                val loadMarkersJob: AtomicReference<Job?> = AtomicReference(null)
                fun methodReturningJob() = GlobalScope.launch {
                    println("Before Delay")
                    delay(30000)
                    println("After Delay")
                    throw Exception("Finished")
                }
                list.mapIndexedNotNull { index, model ->
                    update.sendRunningNotification(list.size, index, model.title)
                    try {
                        loadMarkersJob.getAndSet(methodReturningJob())?.cancel()
                        val newData = sourceFromString(model.source)?.let { model.toItemModel(it).toInfoModel().blockingGet() }
                        println("Old: ${model.numChapters} New: ${newData?.chapters?.size}")
                        if (model.numChapters >= newData?.chapters?.size ?: -1) null
                        else Pair(newData, model)
                    } catch (e: Exception) {
                        //e.crashlyticsLog(model.title, "manga_load_error")
                        e.printStackTrace()
                        null
                    }
                }.also {
                    try {
                        loadMarkersJob.get()?.cancel()
                    } catch (ignored: Exception) {
                    }
                }
            }
            .map {
                update.updateManga(dao, it)
                update.mapDbModel(dao, it)
            }
            .map { update.onEnd(it).also { Loged.f("Finished!") } }
            .map {
                update.sendFinishedNotification()
                Result.success()
            }
            .onErrorReturn {
                println(it)
                update.sendFinishedNotification()
                Result.failure()
            }
    }

}

class UpdateNotification(private val context: Context) {

    private val icon = OtakuApp.notificationLogo

    fun updateManga(dao: ItemDao, triple: List<Pair<InfoModel?, DbModel>>) {
        triple.forEach {
            val item = it.second
            item.numChapters = it.first?.chapters?.size ?: item.numChapters
            dao.updateItem(item).subscribe()
            FirebaseDb.updateShow(item).subscribe()
        }
    }

    fun mapDbModel(dao: ItemDao, list: List<Pair<InfoModel?, DbModel>>) = list.mapIndexed { index, pair ->
        sendRunningNotification(list.size, index, pair.second.title)
        //index + 3 + (Math.random() * 50).toInt() //for a possible new notification value
        dao.insertNotification(
            NotificationItem(
                id = pair.second.hashCode(),
                url = pair.second.url,
                summaryText = context.getString(R.string.hadAnUpdate, pair.second.title, pair.first?.chapters?.firstOrNull()?.name ?: ""),
                notiTitle = pair.second.title,
                imageUrl = pair.second.imageUrl,
                source = pair.second.source,
                contentTitle = pair.second.title
            )
        ).subscribe()
        pair.second.hashCode() to NotificationDslBuilder.builder(
            context,
            "otakuChannel",
            icon
        ) {
            title = pair.second.title
            subText = pair.second.source
            getBitmapFromURL(pair.second.imageUrl)?.let {
                largeIconBitmap = it
                pictureStyle {
                    bigPicture = it
                    largeIcon = it
                    contentTitle = pair.first?.chapters?.firstOrNull()?.name ?: ""
                    summaryText = context.getString(
                        R.string.hadAnUpdate,
                        pair.second.title,
                        pair.first?.chapters?.firstOrNull()?.name ?: ""
                    )
                }
            } ?: bigTextStyle {
                contentTitle = pair.first?.chapters?.firstOrNull()?.name ?: ""
                bigText = context.getString(
                    R.string.hadAnUpdate,
                    pair.second.title,
                    pair.first?.chapters?.firstOrNull()?.name.orEmpty()
                )
            }
            showWhen = true
            groupId = "otakuGroup"
            deleteIntent { context ->
                val intent = Intent(context, DeleteNotificationReceiver::class.java)
                intent.action = "NOTIFICATION_DELETED_ACTION"
                intent.putExtra("url", pair.second.url)
                PendingIntent.getBroadcast(context, 0, intent, 0)
            }
            pendingIntent { context ->
                NavDeepLinkBuilder(context)
                    .setGraph(R.navigation.recent_nav)
                    .setDestination(R.id.detailsFragment2)
                    .setArguments(Bundle().apply { putSerializable("itemInfo", pair.second.toItemModel(pair.first!!.source)) })
                    .createPendingIntent()
            }
        }
    }

    fun onEnd(list: List<Pair<Int, Notification>>) {
        val n = context.notificationManager
        val currentNotificationSize = n.activeNotifications.filterNot { list.any { l -> l.first == it.id } }.size - 1
        list.forEach { pair -> n.notify(pair.first, pair.second) }
        if (list.isNotEmpty()) n.notify(
            42,
            NotificationDslBuilder.builder(context, "otakuChannel", icon) {
                title = context.getText(R.string.app_name)
                val size = list.size + currentNotificationSize
                subText = context.resources.getQuantityString(R.plurals.updateAmount, size, size)
                showWhen = true
                groupSummary = true
                groupAlertBehavior = GroupBehavior.ALL
                groupId = "otakuGroup"
            }
        )
    }

    private fun getBitmapFromURL(strURL: String?): Bitmap? = try {
        val url = URL(strURL)
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
        connection.doInput = true
        connection.connect()
        BitmapFactory.decodeStream(connection.inputStream)
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }

    fun sendRunningNotification(max: Int, progress: Int, contextText: CharSequence = "") {
        val notification = NotificationDslBuilder.builder(context, "updateCheckChannel", icon) {
            onlyAlertOnce = true
            ongoing = true
            progress {
                this.max = max
                this.progress = progress
                indeterminate = progress == 0
            }
            showWhen = true
            message = contextText
            subText = "Checking"
        }
        context.notificationManager.notify(13, notification)
        Loged.f("Checking for $contextText")
    }

    fun sendFinishedNotification() {
        val notification = NotificationDslBuilder.builder(context, "updateCheckChannel", icon) {
            onlyAlertOnce = true
            subText = "Finished"
            timeoutAfter = 750L
        }
        context.notificationManager.notify(13, notification)
    }
}

class DeleteNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val dao by lazy { context?.let { ItemDatabase.getInstance(it).itemDao() } }
        val url = intent?.getStringExtra("url")
        println(url)
        GlobalScope.launch {
            val noti = url?.let { dao?.getNotificationItem(it) }
            println(noti)
            noti?.let { dao?.deleteNotification(it)?.subscribe() }
        }
    }
}

class BootReceived : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Loged.d("BootReceived")
        context?.let { SavedNotifications().viewNotificationsFromDb(it) }
    }
}

class SavedNotifications {
    fun viewNotificationsFromDb(context: Context) {
        val dao by lazy { ItemDatabase.getInstance(context).itemDao() }
        val icon = OtakuApp.notificationLogo
        val update = UpdateNotification(context)
        GlobalScope.launch {
            dao.getAllNotifications()
                .blockingGet()
                .map { n ->
                    println(n)
                    n.id to NotificationDslBuilder.builder(
                        context,
                        "otakuChannel",
                        icon
                    ) {
                        title = n.notiTitle
                        subText = n.source
                        getBitmapFromURL(n.imageUrl)?.let {
                            largeIconBitmap = it
                            pictureStyle {
                                bigPicture = it
                                largeIcon = it
                                contentTitle = n.contentTitle
                                summaryText = n.summaryText
                            }
                        } ?: bigTextStyle {
                            contentTitle = n.contentTitle
                            bigText = n.summaryText
                        }
                        showWhen = true
                        groupId = "otakuGroup"
                        deleteIntent { context ->
                            val intent1 = Intent(context, DeleteNotificationReceiver::class.java)
                            intent1.action = "NOTIFICATION_DELETED_ACTION"
                            intent1.putExtra("url", n.url)
                            PendingIntent.getBroadcast(context, n.id, intent1, 0)
                        }
                        pendingIntent { context ->
                            val itemModel = UpdateWorker.sourceFromString(n.source)?.let { it1 ->
                                dao.getItemByUrl(n.url)
                                    .blockingGet()
                                    ?.toItemModel(it1)
                            }
                            NavDeepLinkBuilder(context)
                                .setGraph(R.navigation.all_nav)
                                .setDestination(R.id.detailsFragment3)
                                .setArguments(Bundle().apply { putSerializable("itemInfo", itemModel) })
                                .createPendingIntent()
                        }
                    }
                }
                .let { update.onEnd(it) }
        }
    }

    private fun getBitmapFromURL(strURL: String?): Bitmap? = try {
        val url = URL(strURL)
        val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
        connection.doInput = true
        connection.connect()
        BitmapFactory.decodeStream(connection.inputStream)
    } catch (e: IOException) {
        null
    }
}
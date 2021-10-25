package com.programmersbox.mangaworld

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.android.material.composethemeadapter.MdcTheme
import com.programmersbox.uiviews.BaseMainActivity
import com.programmersbox.uiviews.utils.BaseBottomSheetDialogFragment
import com.programmersbox.uiviews.utils.PermissionRequest
import com.programmersbox.uiviews.utils.animatedItems
import com.programmersbox.uiviews.utils.updateAnimatedItemsState
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.map
import java.io.File

class DownloadViewerFragment : BaseBottomSheetDialogFragment() {

    private val disposable = CompositeDisposable()

    private val defaultPathname get() = File(DOWNLOAD_FILE_PATH)

    @ExperimentalFoundationApi
    @ExperimentalPermissionsApi
    @ExperimentalAnimationApi
    @ExperimentalMaterialApi
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(viewLifecycleOwner))
        setContent {
            MdcTheme {
                PermissionRequest(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    LaunchedEffect(Unit) {
                        val c = ChaptersGet.getInstance(requireContext())
                        c?.loadChapters(lifecycleScope, defaultPathname.absolutePath)
                    }
                    DownloadViewer()
                }
            }
        }
    }

    @ExperimentalFoundationApi
    @ExperimentalAnimationApi
    @ExperimentalMaterialApi
    @Composable
    private fun DownloadViewer() {

        val c = remember { ChaptersGet.getInstance(requireContext()) }

        val fileList by c!!.chapters2
            .map { f ->
                f
                    .groupBy { it.folder }
                    .entries
                    .toList()
                    .fastMap { it.key to it.value.groupBy { c -> c.chapterFolder } }
                    .toMap()
            }
            .collectAsState(initial = emptyMap())

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.downloaded_chapters)) },
                    navigationIcon = {
                        IconButton(onClick = { findNavController().popBackStack() }) { Icon(Icons.Default.Close, null) }
                    }
                )
            }
        ) { p1 ->
            val f by updateAnimatedItemsState(newList = fileList.entries.toList())

            if (fileList.isEmpty()) EmptyState()
            else LazyColumn(
                contentPadding = p1,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)
            ) {
                animatedItems(
                    f,
                    enterTransition = fadeIn(),
                    exitTransition = fadeOut()
                ) { file -> ChapterItem(file) }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Box(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                elevation = 5.dp,
                shape = RoundedCornerShape(5.dp)
            ) {
                Column(modifier = Modifier) {

                    Text(
                        text = stringResource(id = R.string.get_started),
                        style = MaterialTheme.typography.h4,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Text(
                        text = stringResource(id = R.string.download_a_manga),
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Button(
                        onClick = {
                            findNavController().popBackStack()
                            (activity as? BaseMainActivity)?.goToScreen(BaseMainActivity.Screen.RECENT)
                        },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 5.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.go_download),
                            style = MaterialTheme.typography.button
                        )
                    }
                }
            }
        }
    }

    @ExperimentalMaterialApi
    @Composable
    private fun ChapterItem(file: Map.Entry<String, Map<String, List<ChaptersGet.Chapters>>>) {
        val context = LocalContext.current

        var expanded by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Card(
                interactionSource = MutableInteractionSource(),
                indication = rememberRipple(),
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                ListItem(
                    modifier = Modifier.padding(5.dp),
                    text = { Text(file.value.values.randomOrNull()?.randomOrNull()?.folderName.orEmpty()) },
                    secondaryText = { Text(stringResource(R.string.chapter_count, file.value.size)) },
                    trailing = {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            null,
                            modifier = Modifier.rotate(animateFloatAsState(if (expanded) 180f else 0f).value)
                        )
                    }
                )
            }

            if (expanded) {
                file.value.values.toList().fastForEach { chapter ->
                    val c = chapter.randomOrNull()

                    var showPopup by remember { mutableStateOf(false) }

                    if (showPopup) {
                        val onDismiss = { showPopup = false }

                        AlertDialog(
                            onDismissRequest = onDismiss,
                            title = { Text(stringResource(R.string.delete_title, c?.chapterName.orEmpty())) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        Single.create<Boolean> {
                                            try {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                    chapter.fastForEach { f ->
                                                        context.contentResolver.delete(
                                                            f.assetFileStringUri.toUri(),
                                                            "${MediaStore.Images.Media._ID} = ?",
                                                            arrayOf(f.id)
                                                        )
                                                    }
                                                } else {
                                                    File(c?.chapterFolder!!).delete()
                                                }
                                                it.onSuccess(true)
                                            } catch (e: Exception) {
                                                it.onSuccess(false)
                                            }
                                        }
                                            .subscribeOn(Schedulers.io())
                                            .observeOn(AndroidSchedulers.mainThread())
                                            .subscribeBy { Toast.makeText(context, R.string.finished_deleting, Toast.LENGTH_SHORT).show() }
                                            .addTo(disposable)
                                        onDismiss()
                                    }
                                ) { Text(stringResource(R.string.yes), style = MaterialTheme.typography.button) }
                            },
                            dismissButton = {
                                TextButton(onClick = onDismiss) {
                                    Text(
                                        stringResource(R.string.no),
                                        style = MaterialTheme.typography.button
                                    )
                                }
                            }
                        )
                    }

                    val dismissState = rememberDismissState(
                        confirmStateChange = {
                            if (it == DismissValue.DismissedToStart) {
                                //delete
                                showPopup = true
                            }
                            false
                        }
                    )

                    SwipeToDismiss(
                        modifier = Modifier.padding(start = 32.dp),
                        state = dismissState,
                        directions = setOf(DismissDirection.EndToStart),
                        dismissThresholds = { FractionalThreshold(0.5f) },
                        background = {
                            val color by animateColorAsState(
                                when (dismissState.targetValue) {
                                    DismissValue.Default -> Color.Transparent
                                    DismissValue.DismissedToEnd -> Color.Transparent
                                    DismissValue.DismissedToStart -> Color.Red
                                }
                            )
                            val alignment = Alignment.CenterEnd
                            val icon = Icons.Default.Delete
                            val scale by animateFloatAsState(if (dismissState.targetValue == DismissValue.Default) 0.75f else 1f)

                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(color)
                                    .padding(horizontal = 20.dp),
                                contentAlignment = alignment
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    modifier = Modifier.scale(scale)
                                )
                            }
                        }
                    ) {
                        Card(
                            interactionSource = MutableInteractionSource(),
                            indication = rememberRipple(),
                            onClick = {
                                activity?.startActivity(
                                    Intent(context, if (context.useNewReader) ReadActivityCompose::class.java else ReadActivity::class.java).apply {
                                        putExtra("downloaded", true)
                                        putExtra("filePath", c?.chapterFolder?.let { f -> File(f) })
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ListItem(
                                modifier = Modifier.padding(5.dp),
                                text = { Text(c?.chapterName.orEmpty()) },
                                secondaryText = { Text(stringResource(R.string.page_count, chapter.size)) },
                                trailing = { Icon(Icons.Default.ChevronRight, null) }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.dispose()
        ChaptersGet.getInstance(requireContext())?.unregister()
    }

}
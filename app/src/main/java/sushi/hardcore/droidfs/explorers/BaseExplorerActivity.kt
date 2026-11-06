package sushi.hardcore.droidfs.explorers

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import sushi.hardcore.droidfs.BaseActivity
import sushi.hardcore.droidfs.Constants
import sushi.hardcore.droidfs.EncryptedFileProvider
import sushi.hardcore.droidfs.FileShare
import sushi.hardcore.droidfs.FileTypes
import sushi.hardcore.droidfs.LoadingTask
import sushi.hardcore.droidfs.R
import sushi.hardcore.droidfs.VolumeManagerApp
import sushi.hardcore.droidfs.adapters.ExplorerElementAdapter
import sushi.hardcore.droidfs.adapters.OpenAsDialogAdapter
import sushi.hardcore.droidfs.content_providers.TemporaryFileProvider
import sushi.hardcore.droidfs.file_operations.FileOperationService
import sushi.hardcore.droidfs.widgets.ImportProgressDialog
import sushi.hardcore.droidfs.file_operations.OperationFile
import sushi.hardcore.droidfs.file_operations.TaskResult
import sushi.hardcore.droidfs.file_viewers.AudioPlayer
import sushi.hardcore.droidfs.file_viewers.ImageViewer
import sushi.hardcore.droidfs.file_viewers.PdfViewer
import sushi.hardcore.droidfs.file_viewers.TextEditor
import sushi.hardcore.droidfs.file_viewers.VideoPlayer
import sushi.hardcore.droidfs.filesystems.EncryptedVolume
import sushi.hardcore.droidfs.filesystems.Stat
import sushi.hardcore.droidfs.util.PathUtils
import sushi.hardcore.droidfs.util.finishOnClose
import sushi.hardcore.droidfs.widgets.EditTextDialog

open class BaseExplorerActivity : BaseActivity(), ExplorerElementAdapter.Listener {
    private lateinit var sortOrderEntries: Array<String>
    private lateinit var sortOrderValues: Array<String>
    private var foldersFirst = true
    private var mapFolders = true
    private var currentSortOrderIndex = 0
    protected var volumeId = -1
    protected lateinit var encryptedVolume: EncryptedVolume
    private lateinit var volumeName: String
    private lateinit var explorerViewModel: ExplorerViewModel
    /** 为 true 时, 把当前所在的卷和文件夹记下来, 下次启动可以回到这里 */
    protected open val rememberLocation = false
    protected var currentDirectoryPath: String = ""
        set(value) {
            field = value
            explorerViewModel.currentDirectoryPath = value
            currentPathText.text = getString(R.string.location, value)
            if (rememberLocation) {
                sharedPrefs.edit()
                    .putString(Constants.LAST_VOLUME_KEY, volumeName)
                    .putString(Constants.LAST_DIR_KEY, value)
                    .apply()
            }
        }
    protected lateinit var fileOperationService: FileOperationService
    protected val activityScope = MainScope()
    private var directoryLoadingTask: Job? = null
    protected lateinit var explorerElements: MutableList<ExplorerElement>
    // all the elements of the current directory, explorerElements is the (possibly filtered) displayed subset
    private var allExplorerElements: MutableList<ExplorerElement> = mutableListOf()
    private var searchQuery = ""
    // every element below the current directory (including its direct children), built lazily on the first search
    private var searchPool: List<ExplorerElement>? = null
    private var searchLoadingJob: Job? = null
    private var searchItem: MenuItem? = null
    // true between invalidateOptionsMenu() and the re-creation of the menu: the search view is then
    // collapsed by the system, which must not be mistaken for the user closing the search
    private var keepSearchOnMenuRebuild = false
    protected lateinit var explorerAdapter: ExplorerElementAdapter
    protected lateinit var app: VolumeManagerApp
    private var usf_open = false
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var isUsingListLayout = true
    private lateinit var layoutIcon: ImageButton
    private lateinit var recycler_view_explorer: RecyclerView
    private lateinit var refresher: SwipeRefreshLayout
    private lateinit var loader: ProgressBar
    private lateinit var textDirEmpty: TextView
    private lateinit var currentPathText: TextView
    private lateinit var numberOfFilesText: TextView
    private lateinit var numberOfFoldersText: TextView
    private lateinit var totalSizeText: TextView
    protected val fileShare by lazy { FileShare(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as VolumeManagerApp
        volumeName = intent.getStringExtra("volumeName") ?: ""
        volumeId = intent.getIntExtra("volumeId", -1)
        encryptedVolume = app.volumeManager.getVolume(volumeId)!!.also { finishOnClose(it) }
        sortOrderEntries = resources.getStringArray(R.array.sort_orders_entries)
        sortOrderValues = resources.getStringArray(R.array.sort_orders_values)
        foldersFirst = sharedPrefs.getBoolean("folders_first", true)
        mapFolders = sharedPrefs.getBoolean("map_folders", true)
        loadUnsafeFeatures()
        currentSortOrderIndex = resources.getStringArray(R.array.sort_orders_values).indexOf(sharedPrefs.getString(Constants.SORT_ORDER_KEY, "name"))
        init()
        recycler_view_explorer = findViewById(R.id.recycler_view_explorer)
        refresher = findViewById(R.id.refresher)
        loader = findViewById(R.id.loader)
        textDirEmpty = findViewById(R.id.text_dir_empty)
        currentPathText = findViewById(R.id.current_path_text)
        numberOfFilesText = findViewById(R.id.number_of_files_text)
        numberOfFoldersText = findViewById(R.id.number_of_folders_text)
        totalSizeText = findViewById(R.id.total_size_text)
        setSupportActionBar(findViewById(R.id.toolbar))
        title = ""
        setVolumeNameTitle()
        explorerAdapter = ExplorerElementAdapter(
            this,
            if (sharedPrefs.getBoolean("thumbnails", true)) {
                app.volumeManager.getImageLoader(volumeId)
            } else {
                null
            },
            this,
        )
        explorerViewModel = ViewModelProvider(this).get(ExplorerViewModel::class.java)
        currentDirectoryPath = explorerViewModel.currentDirectoryPath
        // 从"记住的位置"启动时, 跳到上次的文件夹(只在首次创建、且文件夹仍然存在时)
        if (savedInstanceState == null) {
            intent.getStringExtra("startPath")?.let { startPath ->
                if (encryptedVolume.getAttr(startPath)?.type == Stat.S_IFDIR) {
                    currentDirectoryPath = startPath
                }
            }
        }
        linearLayoutManager = LinearLayoutManager(this@BaseExplorerActivity)
        recycler_view_explorer.adapter = explorerAdapter
        isUsingListLayout = sharedPrefs.getBoolean("useListLayout", true)
        layoutIcon = findViewById(R.id.layout_icon)
        setRecyclerViewLayout()
        onBackPressedDispatcher.addCallback(this) {
            if (explorerAdapter.selectedItems.isEmpty()) {
                if (searchQuery.isNotEmpty()) {
                    searchItem?.collapseActionView()
                    return@addCallback
                }
                val parentPath = PathUtils.getParentPath(currentDirectoryPath)
                if (parentPath == currentDirectoryPath) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    changeCurrentDirectory(PathUtils.getParentPath(currentDirectoryPath))
                }
            } else {
                unselectAll()
            }
        }
        layoutIcon.setOnClickListener {
            isUsingListLayout = !isUsingListLayout
            setRecyclerViewLayout()
            recycler_view_explorer.recycledViewPool.clear()
            with (sharedPrefs.edit()) {
                putBoolean("useListLayout", isUsingListLayout)
                apply()
            }
        }
        refresher.setOnRefreshListener {
            val prefix = if (currentDirectoryPath == "/") "/" else "$currentDirectoryPath/"
            app.volumeManager.evictImageCache(volumeId) { it.startsWith(prefix) }
            refreshCurrentDirectory()
            refresher.isRefreshing = false
        }
        bindFileOperationService()
        findViewById<View>(R.id.info_bar)?.let {
            applyHorizonalDisplayCutoutInsets(it)
        }
    }

    class ExplorerViewModel: ViewModel() {
        var currentDirectoryPath = "/"
    }

    private fun setRecyclerViewLayout() {
        layoutIcon.setImageResource(if (isUsingListLayout) {
            recycler_view_explorer.layoutManager = linearLayoutManager
            explorerAdapter.isUsingListLayout = true
            R.drawable.icon_view_grid
        } else {
            val displayMetrics = resources.displayMetrics
            val columnsNumber = (displayMetrics.widthPixels / displayMetrics.density / 200 + 0.5).toInt()
            recycler_view_explorer.layoutManager = GridLayoutManager(this, columnsNumber)
            explorerAdapter.isUsingListLayout = false
            R.drawable.icon_view_list
        })
    }

    protected open fun loadUnsafeFeatures() {
        usf_open = sharedPrefs.getBoolean("usf_open", false)
    }

    protected open fun init() {
        setContentView(R.layout.activity_explorer)
    }

    protected open fun bindFileOperationService() {
        FileOperationService.bind(this) {
            fileOperationService = it
        }
    }

    private fun startFileViewer(cls: Class<*>, filePath: String) {
        val intent = Intent(this, cls).apply {
            putExtra("path", filePath)
            putExtra("volumeId", volumeId)
            putExtra("sortOrder", sortOrderValues[currentSortOrderIndex])
        }
        startActivity(intent)
    }

    protected fun onExportFailed(errorResId: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error)
            .setMessage(getString(R.string.tmp_export_failed, getString(errorResId)))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openWithExternalApp(path: String, size: Long) {
        app.isExporting = true
        val exportedFile = TemporaryFileProvider.instance.encryptedFileProvider.createFile(path, size)
        if (exportedFile == null) {
            onExportFailed(R.string.export_failed_create)
            return
        }
        val msg = when (exportedFile) {
            is EncryptedFileProvider.ExportedMemFile -> R.string.export_mem
            is EncryptedFileProvider.ExportedDiskFile -> R.string.export_disk
            else -> R.string.loading_msg_export
        }
        object : LoadingTask<Pair<Intent?, Int?>>(this, msg) {
            override suspend fun doTask(): Pair<Intent?, Int?> {
                return fileShare.openWith(exportedFile, size, volumeId)
            }
        }.startTask(lifecycleScope) { (intent, error) ->
            if (intent == null) {
                onExportFailed(error!!)
            } else {
                app.isStartingExternalApp = true
                startActivity(intent)
            }
            app.isExporting = false
        }
    }

    private fun showOpenAsDialog(explorerElement: ExplorerElement) {
        val path = explorerElement.fullPath
        val adapter = OpenAsDialogAdapter(this, usf_open)
        MaterialAlertDialogBuilder(this)
            .setSingleChoiceItems(adapter, -1) { dialog, which ->
                when (adapter.getItem(which)) {
                    "image" -> startFileViewer(ImageViewer::class.java, path)
                    "video" -> startFileViewer(VideoPlayer::class.java, path)
                    "audio" -> startFileViewer(AudioPlayer::class.java, path)
                    "pdf" -> startFileViewer(PdfViewer::class.java, path)
                    "text" -> startFileViewer(TextEditor::class.java, path)
                    "external" -> if (usf_open) {
                        openWithExternalApp(path, explorerElement.stat.size)
                    }
                }
                dialog.dismiss()
            }
            .setTitle(getString(R.string.open_as) + ':')
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    protected fun createNewFile(callback: (Long) -> Unit) {
        EditTextDialog(this, R.string.enter_file_name)
            .onSubmit {
                if (it.isEmpty()) {
                    Toast.makeText(this, R.string.error_filename_empty, Toast.LENGTH_SHORT).show()
                    createNewFile(callback)
                } else {
                    val filePath = PathUtils.pathJoin(currentDirectoryPath, it)
                    val handleID = encryptedVolume.openFileWriteMode(filePath)
                    if (handleID == -1L) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.error)
                            .setMessage(R.string.file_creation_failed)
                            .setPositiveButton(R.string.ok, null)
                            .show()
                    } else {
                        callback(handleID)
                    }
                }
            }.show()
    }

    private fun setVolumeNameTitle() {
        supportActionBar?.title = getString(R.string.volume, volumeName)
    }

    override fun onSelectionChanged(size: Int) {
        if (size == 0) {
            setVolumeNameTitle()
        } else {
            supportActionBar?.title = getString(R.string.elements_selected, size, explorerElements.count { !it.isParentFolder })
        }
    }

    override fun onExplorerElementClick(position: Int) {
        if (explorerAdapter.selectedItems.isEmpty()) {
            val fullPath = explorerElements[position].fullPath
            when {
                explorerElements[position].isDirectory -> {
                    changeCurrentDirectory(fullPath)
                }
                explorerElements[position].isParentFolder -> {
                    changeCurrentDirectory(PathUtils.getParentPath(currentDirectoryPath))
                }
                FileTypes.isImage(fullPath) -> {
                    startFileViewer(ImageViewer::class.java, fullPath)
                }
                FileTypes.isVideo(fullPath) -> {
                    startFileViewer(VideoPlayer::class.java, fullPath)
                }
                FileTypes.isText(fullPath) -> {
                    startFileViewer(TextEditor::class.java, fullPath)
                }
                FileTypes.isPDF(fullPath) -> {
                    startFileViewer(PdfViewer::class.java, fullPath)
                }
                FileTypes.isAudio(fullPath) -> {
                    startFileViewer(AudioPlayer::class.java, fullPath)
                }
                else -> showOpenAsDialog(explorerElements[position])
            }
        }
        invalidateOptionsMenu()
    }

    override fun onExplorerElementLongClick(position: Int) {
        invalidateOptionsMenu()
    }

    protected fun unselectAll(notifyChange: Boolean = true) {
        explorerAdapter.unSelectAll(notifyChange)
        invalidateOptionsMenu()
    }

    private fun invalidateSearchPool() {
        searchLoadingJob?.cancel()
        searchLoadingJob = null
        searchPool = null
    }

    private suspend fun collectDescendants(dir: ExplorerElement, out: MutableList<ExplorerElement>, prefix: String, updateSize: Boolean) {
        yield()
        for (child in encryptedVolume.readDir(dir.fullPath) ?: return) {
            child.searchLabel = prefix + child.name
            out.add(child)
            if (child.isDirectory) {
                collectDescendants(child, out, child.searchLabel + "/", mapFolders)
            }
            if (updateSize) {
                dir.stat.size += child.stat.size
            }
        }
    }

    private fun loadSearchPool() {
        recycler_view_explorer.isVisible = false
        loader.isVisible = true
        val children = allExplorerElements.filter { !it.isParentFolder }
        searchLoadingJob = lifecycleScope.launch {
            val pool = withContext(Dispatchers.IO) {
                val result = mutableListOf<ExplorerElement>()
                for (child in children) {
                    result.add(child)
                    if (child.isDirectory) {
                        // sizes of the direct children are already computed by refreshCurrentDirectory
                        collectDescendants(child, result, child.name + "/", false)
                    }
                }
                result
            }
            searchPool = pool
            searchLoadingJob = null
            displayExplorerElements()
        }
    }

    private fun displayExplorerElements() {
        if (searchQuery.isNotEmpty() && searchPool == null) {
            // the pool is being built, displayExplorerElements will be called again once it is ready
            if (searchLoadingJob?.isActive != true) {
                loadSearchPool()
            }
            return
        }
        explorerElements = if (searchQuery.isEmpty()) {
            ExplorerElement.sortBy(sortOrderValues[currentSortOrderIndex], foldersFirst, allExplorerElements)
            allExplorerElements
        } else {
            // names containing the query, those starting with it first (stable: keeps the sort order inside each group)
            val matches = searchPool!!.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }.toMutableList()
            ExplorerElement.sortBy(sortOrderValues[currentSortOrderIndex], foldersFirst, matches)
            val (prefixMatches, otherMatches) = matches.partition {
                it.name.startsWith(searchQuery, ignoreCase = true)
            }
            (prefixMatches + otherMatches).toMutableList()
        }
        // avoid re-creating the menu while the search view is open, unless the selection must be cleared
        if (explorerAdapter.selectedItems.isNotEmpty() || searchItem?.isActionViewExpanded != true) {
            unselectAll(false)
        }
        loader.isVisible = false
        recycler_view_explorer.isVisible = true
        explorerAdapter.explorerElements = explorerElements
    }

    private suspend fun recursiveSetSize(directory: ExplorerElement) {
        yield()
        for (child in encryptedVolume.readDir(directory.fullPath) ?: return) {
            if (child.isDirectory) {
                recursiveSetSize(child)
            }
            directory.stat.size += child.stat.size
        }
    }

    private fun displayNumberOfElements(textView: TextView, stringIdSingular: Int, stringIdPlural: Int, count: Int) {
        with(textView) {
            visibility = if (count == 0) {
                View.GONE
            } else {
                text = if (count == 1) {
                    getString(stringIdSingular)
                } else {
                    getString(stringIdPlural, count)
                }
                View.VISIBLE
            }
        }
    }

    private fun onSearchQueryChanged(query: String) {
        if (query == searchQuery) return
        if (keepSearchOnMenuRebuild && query.isEmpty()) return
        searchQuery = query
        if (query.isEmpty()) {
            // stop a pending indexation, the full directory is displayed again below
            searchLoadingJob?.cancel()
            searchLoadingJob = null
        }
        // displayExplorerElements must not be called if directoryLoadingTask is active,
        // it will be called with the new query once the loading is over
        if (::explorerElements.isInitialized && directoryLoadingTask?.isActive != true) {
            displayExplorerElements()
        }
    }

    protected fun changeCurrentDirectory(path: String) {
        searchItem?.collapseActionView()
        currentDirectoryPath = path
        refreshCurrentDirectory {
            recycler_view_explorer.scrollToPosition(0)
        }
    }

    protected fun refreshCurrentDirectory(onDisplayed: (() -> Unit)? = null) = lifecycleScope.launch {
        directoryLoadingTask?.cancelAndJoin()
        invalidateSearchPool()
        recycler_view_explorer.isVisible = false
        loader.isVisible = true
        explorerElements = encryptedVolume.readDir(currentDirectoryPath) ?: return@launch
        if (currentDirectoryPath != "/") {
            explorerElements.add(
                0,
                ExplorerElement("..", Stat.parentFolderStat(), parentPath = currentDirectoryPath)
            )
        }
        allExplorerElements = explorerElements
        invalidateSearchPool()
        textDirEmpty.visibility = if (explorerElements.size == 0) View.VISIBLE else View.GONE
        displayNumberOfElements(numberOfFilesText, R.string.one_file, R.string.multiple_files, explorerElements.count { it.isRegularFile })
        displayNumberOfElements(numberOfFoldersText, R.string.one_folder, R.string.multiple_folders, explorerElements.count { it.isDirectory })
        if (mapFolders) {
            var totalSize: Long = 0
            directoryLoadingTask = launch(Dispatchers.IO) {
                for (element in explorerElements) {
                    if (element.isDirectory) {
                        recursiveSetSize(element)
                    }
                    totalSize += element.stat.size
                }
            }
            directoryLoadingTask!!.join()
            displayExplorerElements()
            totalSizeText.text = getString(R.string.total_size, PathUtils.formatSize(totalSize))
            onDisplayed?.invoke()
        } else {
            displayExplorerElements()
            totalSizeText.text = getString(
                R.string.total_size,
                PathUtils.formatSize(explorerElements.filter { !it.isParentFolder }.sumOf { it.stat.size })
            )
            onDisplayed?.invoke()
        }
    }

    private fun askLockVolume() {
        MaterialAlertDialogBuilder(this)
                .setTitle(R.string.warning)
                .setMessage(R.string.ask_lock_volume)
                .setPositiveButton(R.string.ok) { _, _ ->
                    app.volumeManager.closeVolume(volumeId)
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
    }

    private fun createFolder(folderName: String){
        if (folderName.isEmpty()) {
            Toast.makeText(this, R.string.error_filename_empty, Toast.LENGTH_SHORT).show()
        } else {
            if (!encryptedVolume.mkdir(PathUtils.pathJoin(currentDirectoryPath, folderName))) {
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.error)
                        .setMessage(R.string.error_mkdir)
                        .setPositiveButton(R.string.ok, null)
                        .show()
            } else {
                refreshCurrentDirectory()
                invalidateOptionsMenu()
            }
        }
    }

    protected fun openDialogCreateFolder() {
        EditTextDialog(this, R.string.enter_folder_name)
            .onSubmit(::createFolder)
            .show()
    }

    protected fun checkPathOverwrite(items: List<OperationFile>, dstDirectoryPath: String, callback: (List<OperationFile>?) -> Unit) {
        val srcDirectoryPath = items[0].parentPath
        val dstPaths = HashSet<String>()
        for (i in items.indices) {
            if (items[i].dstPath == null) {
                items[i].dstPath = PathUtils.pathJoin(dstDirectoryPath, PathUtils.getRelativePath(srcDirectoryPath, items[i].srcPath))
            }
            val collision = items[i].dstPath in dstPaths
            if (items[i].overwriteConfirmed || !(collision || encryptedVolume.pathExists(items[i].dstPath!!))) {
                dstPaths.add(items[i].dstPath!!)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.warning)
                    .setMessage(getString(
                        if (collision) {
                            // No collision should happen on directories since we can't select multiple directories
                            R.string.file_collision_question
                        } else {
                            if (items[i].isDirectory) {
                                R.string.dir_overwrite_question
                            } else {
                                R.string.file_overwrite_question
                            }
                        }, items[i].dstPath
                    ))
                    .setPositiveButton(R.string.yes) {_, _ ->
                        items[i].overwriteConfirmed = true
                        checkPathOverwrite(items, dstDirectoryPath, callback)
                    }
                    .setNegativeButton(R.string.no) { _, _ ->
                        EditTextDialog(this, R.string.enter_new_name)
                            .setSelectedText(items[i].name)
                            .onSubmit {
                                items[i].dstPath = PathUtils.pathJoin(dstDirectoryPath, PathUtils.getRelativePath(srcDirectoryPath, items[i].parentPath), it)
                                if (items[i].isDirectory) {
                                    for (j in items.indices) {
                                        if (PathUtils.isChildOf(items[j].srcPath, items[i].srcPath)) {
                                            items[j].dstPath = PathUtils.pathJoin(items[i].dstPath!!, PathUtils.getRelativePath(items[i].srcPath, items[j].srcPath))
                                        }
                                    }
                                }
                                checkPathOverwrite(items, dstDirectoryPath, callback)
                            }
                            .setOnCancelListener {
                                callback(null)
                            }.show()
                    }
                    .setOnCancelListener{
                        callback(null)
                    }
                    .show()
                return
            }
        }
        callback(items)
    }

    protected fun onTaskResult(
        result: TaskResult<out String?>,
        failedErrorMessage: Int,
        successMessage: Int = -1,
        onSuccess: (() -> Unit)? = null,
    ) {
        when (result.state) {
            TaskResult.State.SUCCESS -> {
                if (onSuccess == null) {
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
                } else {
                    onSuccess()
                }
            }
            TaskResult.State.FAILED -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.error)
                    .setMessage(getString(failedErrorMessage, result.failedItem))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            }
            TaskResult.State.ERROR -> result.showErrorAlertDialog(this)
            TaskResult.State.CANCELLED -> {}
        }
    }

    /**
     * gocryptfs 加密后的文件名最长 255 个字符, 对应明文最多约 176 字节(UTF-8),
     * 超过的话创建文件会失败。导入时把过长的文件名截短, 保留扩展名。
     */
    private fun shortenFileName(name: String, maxBytes: Int = 176): String {
        if (name.toByteArray().size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 10) name.substring(dot) else ""
        var stem = name.substring(0, name.length - ext.length)
        val budget = maxBytes - ext.toByteArray().size
        while (stem.isNotEmpty() && stem.toByteArray().size > budget) {
            stem = stem.substring(0, stem.offsetByCodePoints(stem.length, -1))
        }
        return stem.trimEnd() + ext
    }

    protected fun importFilesFromUris(uris: List<Uri>, showProgress: Boolean = false, callback: () -> Unit) {
        val items = ArrayList<OperationFile>()
        for (uri in uris) {
            val fileName = PathUtils.getFilenameFromURI(this, uri)
            if (fileName == null) {
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.error)
                        .setMessage(getString(R.string.error_retrieving_filename, uri))
                        .setPositiveButton(R.string.ok, null)
                        .show()
                items.clear()
                break
            } else {
                items.add(OperationFile(PathUtils.pathJoin(currentDirectoryPath, shortenFileName(fileName)), Stat.S_IFREG))
            }
        }
        if (items.size > 0) {
            checkPathOverwrite(items, currentDirectoryPath) { checkedItems ->
                checkedItems?.let {
                    activityScope.launch {
                        val progress = if (showProgress) FileOperationService.ImportProgress() else null
                        val progressDialog = progress?.let {
                            ImportProgressDialog(this@BaseExplorerActivity, it) {
                                fileOperationService.cancelOperation(it.taskId)
                            }.also { d -> d.show() }
                        }
                        val result = try {
                            fileOperationService.importFilesFromUris(volumeId, checkedItems.map { it.dstPath!! }, uris, progress)
                        } finally {
                            progressDialog?.dismiss()
                        }
                        onTaskResult(result, R.string.import_failed, onSuccess = callback)
                        refreshCurrentDirectory()
                    }
                }
            }
        }
    }

    private fun showRenameDialog(element: ExplorerElement, preselectedText: String) {
        EditTextDialog(this, R.string.rename_title)
            .setSelectedText(preselectedText)
            .onSubmit { newName ->
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.error_filename_empty, Toast.LENGTH_SHORT).show()
                    showRenameDialog(element, element.name)
                    return@onSubmit
                }
                val dstPath = if (newName.startsWith("/")) {
                    newName
                } else {
                    PathUtils.pathJoin(element.parentPath.ifEmpty { "/" }, newName)
                }
                val lastComponent = newName.substringAfterLast('/')
                // if a directory is explicitly requested, rename into it with the original file name
                if (lastComponent.isEmpty() || lastComponent == "." || lastComponent == "..") {
                    checkRenameOverwrite(element, newName, PathUtils.pathJoin(dstPath, element.name))
                } else {
                    checkRenameOverwrite(element, newName, dstPath)
                }
            }
            .show()
    }

    private fun askRenameOverwrite(messageResId: Int, dstPath: String, element: ExplorerElement, newName: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.warning)
            .setMessage(getString(messageResId, dstPath, element.name))
            .setPositiveButton(R.string.yes) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.no) { _, _ -> showRenameDialog(element, newName) }
            .show()
    }

    private fun checkRenameOverwrite(element: ExplorerElement, newName: String, dstPath: String) {
        val attr = encryptedVolume.getAttr(dstPath)
        when {
            attr == null -> rename(element, dstPath)
            attr.type == Stat.S_IFDIR ->
                askRenameOverwrite(R.string.rename_into_dir_question, dstPath, element, newName) {
                    checkRenameOverwrite(element, newName, PathUtils.pathJoin(dstPath, element.name))
                }
            element.isDirectory -> {
                Toast.makeText(this, R.string.error_is_file, Toast.LENGTH_SHORT).show()
                showRenameDialog(element, newName)
            }
            else ->
                askRenameOverwrite(R.string.file_overwrite_question, dstPath, element, newName) {
                    rename(element, dstPath)
                }
        }
    }

    private fun rename(element: ExplorerElement, dstPath: String) {
        if (!encryptedVolume.rename(element.fullPath, dstPath)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.error)
                .setMessage(getString(R.string.rename_failed, element.name))
                .setPositiveButton(R.string.ok, null)
                .show()
        } else {
            val normalizedPath = PathUtils.normalizePath(dstPath)
            app.volumeManager.evictImageCache(volumeId) { it == normalizedPath }
            refreshCurrentDirectory() {
                invalidateOptionsMenu()
            }
        }
    }

    override fun invalidateOptionsMenu() {
        keepSearchOnMenuRebuild = searchQuery.isNotEmpty()
        super.invalidateOptionsMenu()
    }

    private fun setupSearchItem(item: MenuItem?, visible: Boolean) {
        searchItem = item
        if (item == null) {
            keepSearchOnMenuRebuild = false
            return
        }
        item.isVisible = visible
        val searchView = item.actionView as SearchView
        searchView.queryHint = getString(R.string.search)
        searchView.maxWidth = Int.MAX_VALUE
        // restore the state, as the menu is re-created by invalidateOptionsMenu()
        if (visible && searchQuery.isNotEmpty()) {
            item.expandActionView()
            searchView.setQuery(searchQuery, false)
            searchView.requestFocus()
        }
        keepSearchOnMenuRebuild = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                onSearchQueryChanged(newText)
                return true
            }
        })
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                onSearchQueryChanged("")
                return true
            }
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.rename).isVisible = false
        menu.findItem(R.id.open_as)?.isVisible = false
        if (usf_open){
            menu.findItem(R.id.external_open)?.isVisible = false
        }
        val noItemSelected = explorerAdapter.selectedItems.isEmpty()
        menu.findItem(R.id.sort).isVisible = noItemSelected
        setupSearchItem(menu.findItem(R.id.search), noItemSelected)
        menu.findItem(R.id.lock).isVisible = noItemSelected
        menu.findItem(R.id.close).isVisible = noItemSelected
        supportActionBar?.setDisplayHomeAsUpEnabled(!noItemSelected)
        if (!noItemSelected) {
            if (explorerAdapter.selectedItems.size == 1) {
                menu.findItem(R.id.rename).isVisible = true
                if (explorerElements[explorerAdapter.selectedItems.first()].isRegularFile) {
                    menu.findItem(R.id.open_as)?.isVisible = true
                    if (usf_open) {
                        menu.findItem(R.id.external_open)?.isVisible = true
                    }
                }
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                unselectAll()
                true
            }
            R.id.sort -> {
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.sort_order)
                        .setSingleChoiceItems(sortOrderEntries, currentSortOrderIndex) { dialog, which ->
                            currentSortOrderIndex = which
                            // displayExplorerElements must not be called if directoryLoadingTask is active
                            if (directoryLoadingTask?.isActive != true) {
                                displayExplorerElements()
                            }
                            val sharedPrefsEditor = sharedPrefs.edit()
                            sharedPrefsEditor.putString(Constants.SORT_ORDER_KEY, sortOrderValues[currentSortOrderIndex])
                            sharedPrefsEditor.apply()
                            dialog.dismiss()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                true
            }
            R.id.rename -> {
                val explorerElement = explorerElements[explorerAdapter.selectedItems.first()]
                showRenameDialog(explorerElement, explorerElement.name)
                true
            }
            R.id.open_as -> {
                showOpenAsDialog(explorerElements[explorerAdapter.selectedItems.first()])
                true
            }
            R.id.external_open -> {
                if (usf_open){
                    val explorerElement = explorerElements[explorerAdapter.selectedItems.first()]
                    openWithExternalApp(explorerElement.fullPath, explorerElement.stat.size)
                    unselectAll()
                }
                true
            }
            R.id.close -> {
                finish()
                true
            }
            R.id.lock -> {
                askLockVolume()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) { //activity won't be recreated
            activityScope.cancel()
        }
    }

    override fun onResume() {
        super.onResume()
        if (app.isStartingExternalApp) {
            TemporaryFileProvider.instance.wipe()
        }
        refreshCurrentDirectory()
    }
}

package com.andro6tube.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andro6tube.R
import com.andro6tube.data.Db
import com.andro6tube.data.Prefs
import com.andro6tube.data.Repo
import com.andro6tube.data.Video
import com.andro6tube.databinding.ActivityMainBinding
import com.andro6tube.player.Playback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

class MainActivity : BaseActivity() {

    private enum class Tab { SEARCH, HISTORY, LATER }

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: VideoAdapter
    private val db by lazy { Db.get(this) }

    private var tab = Tab.SEARCH
    private var query = ""
    private var searchResults: List<Video> = emptyList()
    private var nextPage: Page? = null
    private var loadJob: Job? = null
    private var loadingMore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = VideoAdapter(onClick = ::onItemClick, onLongClick = ::onItemMenu)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.setHasFixedSize(true)
        b.list.itemAnimator = null // анимации дорого обходятся слабому GPU
        b.list.setItemViewCacheSize(6)
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (tab == Tab.SEARCH && lm.findLastVisibleItemPosition() >= adapter.itemCount - 4) loadMore()
            }
        })

        b.query.setText(Prefs.lastQuery)
        b.query.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                doSearch(); true
            } else false
        }
        b.btnSearch.setOnClickListener { doSearch() }
        b.tabSearch.setOnClickListener { showTab(Tab.SEARCH) }
        b.tabHistory.setOnClickListener { showTab(Tab.HISTORY) }
        b.tabLater.setOnClickListener { showTab(Tab.LATER) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        b.mini.miniTitle.setOnClickListener { openPlayer() }
        b.mini.miniPrev.setOnClickListener { Playback.previous() }
        b.mini.miniPlay.setOnClickListener { Playback.togglePlay() }
        b.mini.miniNext.setOnClickListener { Playback.next() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Playback.state.collect { s ->
                    val cur = s.current
                    b.mini.root.visibility = if (cur != null) View.VISIBLE else View.GONE
                    if (cur != null) {
                        b.mini.miniTitle.text = when {
                            s.error != null -> s.error
                            s.loading -> "Загрузка… ${cur.title}"
                            else -> cur.title
                        }
                        b.mini.miniPlay.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)
                    }
                }
            }
        }

        showTab(Tab.SEARCH)
        if (!handleIntent(intent) && Prefs.lastQuery.isNotEmpty()) doSearch(focusResults = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (tab != Tab.SEARCH) showTab(tab) // обновить историю/позже после просмотра
    }

    /** Открытие ссылок youtube.com / youtu.be и «Поделиться». */
    private fun handleIntent(intent: Intent?): Boolean {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return false
        val id = Video.idFromUrl(text) ?: return false
        val v = Video(url = Video.canonicalUrl(id), title = "", uploader = "", durationSec = 0)
        Playback.play(this, listOf(v), 0)
        openPlayer()
        return true
    }

    private fun showTab(t: Tab) {
        tab = t
        b.tabSearch.isSelected = t == Tab.SEARCH
        b.tabHistory.isSelected = t == Tab.HISTORY
        b.tabLater.isSelected = t == Tab.LATER
        when (t) {
            Tab.SEARCH -> {
                adapter.submit(searchResults)
                setEmpty(if (searchResults.isEmpty()) getString(R.string.empty_search) else null)
            }
            Tab.HISTORY -> loadLocal { db.history() }
            Tab.LATER -> loadLocal { db.later() }
        }
    }

    private fun loadLocal(source: () -> List<Video>) {
        val forTab = tab
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { source() }
            if (tab != forTab) return@launch
            adapter.submit(list)
            setEmpty(
                if (list.isNotEmpty()) null
                else getString(if (forTab == Tab.HISTORY) R.string.empty_history else R.string.empty_later)
            )
        }
    }

    private fun setEmpty(text: String?) {
        b.empty.text = text
        b.empty.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun doSearch(focusResults: Boolean = true) {
        val q = b.query.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        query = q
        Prefs.lastQuery = q
        showTab(Tab.SEARCH)
        loadJob?.cancel()
        b.progress.visibility = View.VISIBLE
        setEmpty(null)
        loadJob = lifecycleScope.launch {
            try {
                val page = Repo.search(q)
                searchResults = page.videos
                nextPage = page.next
                if (tab == Tab.SEARCH) {
                    adapter.submit(searchResults)
                    b.list.scrollToPosition(0)
                    setEmpty(if (searchResults.isEmpty()) "Ничего не найдено" else null)
                    if (focusResults) b.list.post {
                        b.list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setEmpty("Ошибка поиска: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                b.progress.visibility = View.GONE
            }
        }
    }

    private fun loadMore() {
        val page = nextPage ?: return
        if (loadingMore || query.isEmpty()) return
        loadingMore = true
        val q = query
        lifecycleScope.launch {
            try {
                val more = Repo.searchMore(q, page)
                if (q != query) return@launch
                nextPage = more.next
                searchResults = searchResults + more.videos
                if (tab == Tab.SEARCH) adapter.append(more.videos)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                nextPage = null
            } finally {
                loadingMore = false
            }
        }
    }

    private fun onItemClick(position: Int) {
        Playback.play(this, adapter.items, position)
        openPlayer()
    }

    private fun onItemMenu(position: Int) {
        val v = adapter.items.getOrNull(position) ?: return
        lifecycleScope.launch {
            val inLater = withContext(Dispatchers.IO) { db.isLater(v.url) }
            val options = mutableListOf<Pair<String, () -> Unit>>()
            options += "▶ Воспроизвести только это" to {
                Playback.play(this@MainActivity, listOf(v), 0); openPlayer()
            }
            options += if (inLater) {
                "Убрать из «Смотреть позже»" to { io { db.removeLater(v.url) }; if (tab == Tab.LATER) showTab(tab) }
            } else {
                "Добавить в «Смотреть позже»" to { io { db.addLater(v) }; toast("Добавлено в «Позже»") }
            }
            if (tab == Tab.HISTORY) {
                options += "Удалить из истории" to { io { db.removeHistory(v.url) }; showTab(tab) }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(v.title)
                .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun io(block: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { block() }
    }

    private fun openPlayer() {
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.query.windowToken, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            b.query.requestFocus(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

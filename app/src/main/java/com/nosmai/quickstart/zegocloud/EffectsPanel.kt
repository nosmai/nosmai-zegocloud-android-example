package com.nosmai.quickstart.zegocloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.api.NosmaiBeauty
import com.nosmai.effect.api.NosmaiCloud
import java.net.URL
import java.util.concurrent.Executors

/**
 * Host-side controls for Nosmai: beauty sliders, cloud filters and cloud AR effects.
 * Everything applied here is rendered by Nosmai before the frame reaches ZEGO,
 * so viewers see exactly what the host sees.
 */
class EffectsPanel(private val panel: View, private val onStatus: (String) -> Unit) {

    private enum class Tab(val title: String, val cloudType: String?) {
        BEAUTY("Beauty", null),
        FILTERS("Filters", "filter"),   // category names understood by the Nosmai cloud API
        EFFECTS("AR Effects", "effects"),
    }

    private enum class LoadState { IDLE, LOADING, LOADED, FAILED }

    private val main = Handler(Looper.getMainLooper())
    /** Catalog requests run one at a time, off the main thread. */
    private val cloudExecutor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newFixedThreadPool(3)
    private val thumbnails = LruCache<String, Bitmap>(60)

    private val tabs: TabLayout = panel.findViewById(R.id.effectTabs)
    private val beautyList: LinearLayout = panel.findViewById(R.id.beautyList)
    private val effectList: RecyclerView = panel.findViewById(R.id.effectList)
    private val message: TextView = panel.findViewById(R.id.effectMessage)

    private var tab = Tab.BEAUTY
    private var ready = false
    private val items = mutableMapOf<Tab, List<NosmaiCloud.Item>>()
    private val loadState = mutableMapOf<Tab, LoadState>()
    private val loadError = mutableMapOf<Tab, String>()
    /** Selected cloud item id per tab; absent means "None". */
    private val selected = mutableMapOf<Tab, String>()
    private val downloading = mutableSetOf<String>()
    /** Local file of every item downloaded (or found already downloaded) this session. */
    private val localPaths = mutableMapOf<String, String>()
    private val adapter = EffectAdapter()

    init {
        Tab.entries.forEach { tabs.addTab(tabs.newTab().setText(it.title)) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(selectedTab: TabLayout.Tab) {
                tab = Tab.entries[selectedTab.position]
                showCurrentTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Beauty: every value is 0.0 (off) to 1.0 (maximum).
        addSlider("Smooth") { NosmaiBeauty.applySkinSmoothing(it) }
        addSlider("Whiten") { NosmaiBeauty.applySkinWhitening(it) }
        addSlider("Slim") { NosmaiBeauty.applyFaceSlimming(it) }
        addSlider("Big Eyes") { NosmaiBeauty.applyEyeEnlargement(it) }
        addSlider("Sharpen") { NosmaiBeauty.applySharpen(it) }

        effectList.layoutManager = LinearLayoutManager(panel.context, RecyclerView.HORIZONTAL, false)
        effectList.adapter = adapter
        showCurrentTab()
    }

    /** Call once Nosmai is initialised and the camera is running. */
    fun nosmaiReady() {
        ready = true
        showCurrentTab()
    }

    fun release() {
        cloudExecutor.shutdownNow()
        imageExecutor.shutdownNow()
    }

    // ---- UI ----

    private fun addSlider(title: String, apply: (Float) -> Unit) {
        val context = panel.context
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
        }, LinearLayout.LayoutParams((72 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(SeekBar(context).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && ready) apply(progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        beautyList.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (32 * density).toInt()))
    }

    private fun showCurrentTab() {
        beautyList.visibility = View.GONE
        effectList.visibility = View.GONE
        message.visibility = View.GONE

        fun show(text: String) {
            message.text = text
            message.visibility = View.VISIBLE
        }

        if (!ready) return show("Starting Nosmai…")
        if (tab == Tab.BEAUTY) {
            beautyList.visibility = View.VISIBLE
            return
        }
        when (loadState[tab] ?: LoadState.IDLE) {
            LoadState.IDLE -> {
                loadCloudItems(tab)
                show("Loading…")
            }
            LoadState.LOADING -> show("Loading…")
            LoadState.FAILED -> {
                show("${loadError[tab]}\nSwitch tabs to retry.")
                loadState[tab] = LoadState.IDLE
            }
            LoadState.LOADED ->
                if (items[tab].isNullOrEmpty()) {
                    show("No ${tab.title.lowercase()} available for this license.")
                } else {
                    effectList.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                }
        }
    }

    // ---- Cloud filters ----

    private fun loadCloudItems(target: Tab) {
        val cloudType = target.cloudType ?: return
        loadState[target] = LoadState.LOADING
        cloudExecutor.execute {
            // Network call: must stay off the main thread.
            var error: String? = null
            var result: List<NosmaiCloud.Item> = emptyList()
            try {
                if (!NosmaiCloud.isEnabled()) {
                    error = "Cloud filters are not enabled for this Nosmai license."
                } else {
                    val query = NosmaiCloud.FilterQuery().apply {
                        page = 1
                        limit = 50
                        fetchAllPages = true
                        filterType = cloudType
                    }
                    if (NosmaiCloud.fetch(query)) {
                        result = NosmaiCloud.list().toList()
                    } else {
                        error = NosmaiCloud.lastError()?.takeIf { it.isNotBlank() }
                            ?: "Could not load ${target.title.lowercase()}."
                    }
                }
            } catch (t: Throwable) {
                error = t.message ?: "Could not load ${target.title.lowercase()}."
            }
            main.post {
                if (error == null) {
                    items[target] = result
                    result.forEach { item ->
                        val path = item.localPath
                        if (item.isDownloaded && !path.isNullOrEmpty()) localPaths[item.id] = path
                    }
                    loadState[target] = LoadState.LOADED
                } else {
                    loadError[target] = error
                    loadState[target] = LoadState.FAILED
                }
                if (tab == target) showCurrentTab()
            }
        }
    }

    private fun clear(target: Tab) {
        selected.remove(target)
        if (target == Tab.FILTERS) NosmaiEffects.clearFilter() else NosmaiEffects.clearAREffect()
        adapter.notifyDataSetChanged()
    }

    private fun select(item: NosmaiCloud.Item, target: Tab) {
        if (item.id in downloading) return
        selected[target] = item.id
        adapter.notifyDataSetChanged()

        val localPath = localPaths[item.id]
        if (localPath != null) {
            apply(item, localPath, target)
            return
        }
        downloading += item.id
        adapter.notifyDataSetChanged()
        onStatus("Downloading ${item.name}…")
        NosmaiCloud.download(item.id) { _, success, path, error ->
            main.post {
                downloading -= item.id
                if (success && !path.isNullOrEmpty()) {
                    localPaths[item.id] = path
                    // Only apply if the user has not picked something else meanwhile.
                    if (selected[target] == item.id) apply(item, path, target)
                } else {
                    if (selected[target] == item.id) selected.remove(target)
                    onStatus("Download failed: ${error ?: item.name}")
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun apply(item: NosmaiCloud.Item, path: String, target: Tab) {
        NosmaiEffects.applyEffect(path, object : NosmaiEffects.EffectCallback {
            override fun onSuccess() {
                main.post { onStatus("${item.name} applied") }
            }

            override fun onError(error: String?) {
                main.post {
                    if (selected[target] == item.id) selected.remove(target)
                    adapter.notifyDataSetChanged()
                    onStatus("Could not apply ${item.name}: ${error ?: "unknown error"}")
                }
            }
        })
    }

    // ---- List ----

    private inner class EffectAdapter : RecyclerView.Adapter<EffectHolder>() {
        override fun getItemCount() = (items[tab]?.size ?: 0) + 1 // +1 for "None"

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EffectHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_effect, parent, false))

        override fun onBindViewHolder(holder: EffectHolder, position: Int) {
            val current = tab
            if (position == 0) {
                holder.bind("None", null, isSelected = selected[current] == null, isDownloading = false)
                holder.itemView.setOnClickListener { clear(current) }
                return
            }
            val item = items[current]?.getOrNull(position - 1) ?: return
            holder.bind(item.name ?: item.id, item.thumbnailUrl,
                isSelected = selected[current] == item.id, isDownloading = item.id in downloading)
            holder.itemView.setOnClickListener { select(item, current) }
        }
    }

    private inner class EffectHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val frame: View = view.findViewById(R.id.thumbFrame)
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val progress: ProgressBar = view.findViewById(R.id.progress)
        private val name: TextView = view.findViewById(R.id.name)

        fun bind(title: String, thumbnailUrl: String?, isSelected: Boolean, isDownloading: Boolean) {
            name.text = title
            frame.setBackgroundColor(if (isSelected) Color.parseColor("#FF2D55") else Color.TRANSPARENT)
            progress.visibility = if (isDownloading) View.VISIBLE else View.GONE
            loadThumbnail(thumbnailUrl)
        }

        private fun loadThumbnail(url: String?) {
            thumb.tag = url
            thumb.setImageBitmap(null)
            if (url.isNullOrEmpty()) return
            thumbnails.get(url)?.let { thumb.setImageBitmap(it); return }
            imageExecutor.execute {
                val bitmap = runCatching {
                    URL(url).openStream().use { stream ->
                        BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                }.getOrNull() ?: return@execute
                thumbnails.put(url, bitmap)
                // The holder may have been reused for another item while this loaded.
                main.post { if (thumb.tag == url) thumb.setImageBitmap(bitmap) }
            }
        }
    }
}

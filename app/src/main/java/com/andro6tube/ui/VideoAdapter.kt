package com.andro6tube.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.andro6tube.data.Video
import com.andro6tube.data.formatDuration
import com.andro6tube.data.formatViews
import com.andro6tube.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit = {},
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    var items: List<Video> = emptyList()
        private set

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<Video>) {
        items = list
        notifyDataSetChanged()
    }

    fun append(list: List<Video>) {
        val start = items.size
        items = items + list
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].url.hashCode().toLong() * 31 + position
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val h = Holder(b)
        b.root.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        b.root.setOnLongClickListener {
            h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongClick); true
        }
        b.root.setOnKeyListener { _, keyCode, event ->
            // Кнопка «Меню» на джойстике = долгое нажатие
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU && event.action == android.view.KeyEvent.ACTION_UP) {
                h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongClick); true
            } else false
        }
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val v = items[position]
        val b = h.b
        b.title.text = v.title.ifEmpty { v.url }
        b.sub.text = listOf(v.uploader, formatViews(v.views), v.uploaded.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" • ")
        val d = formatDuration(v.durationSec)
        b.duration.text = d
        b.duration.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
        if (v.positionMs > 0 && v.durationSec > 0) {
            b.watched.visibility = View.VISIBLE
            b.watched.progress = (v.positionMs / v.durationSec).toInt().coerceIn(0, 1000)
        } else {
            b.watched.visibility = View.GONE
        }
        b.thumb.load(v.thumbUrl) {
            size(320, 180)
            scale(Scale.FILL)
        }
    }

    class Holder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)
}

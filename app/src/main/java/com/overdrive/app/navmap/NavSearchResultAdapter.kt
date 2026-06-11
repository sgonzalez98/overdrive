package com.overdrive.app.navmap

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.R
import com.overdrive.app.navmap.nav.SearchResult

/**
 * Adapter for the Google-Maps-style autocomplete dropdown under the RoadSense
 * map search bar. Renders each [SearchResult] as a single
 * `item_nav_search_result` row: a leading pin glyph, a primary title, and an
 * optional muted subtitle.
 *
 * <p>The label produced by ForwardGeocoder is a comma-joined string
 * ("name, city, country"); we split it so the first segment is the title and
 * the remainder is the subtitle — a lightweight Gmap-style two-line treatment.
 * No network or disk work happens here (unlike RecordingAdapter's thumbnail
 * loader); rows are pure text, so binding is trivial.
 *
 * <p>Mirrors the app's house adapter pattern ([com.overdrive.app.ui.adapter.RecordingAdapter]):
 * [ListAdapter] + [DiffUtil] + an inner [RecyclerView.ViewHolder].
 */
class NavSearchResultAdapter(
    private val onResultTap: (SearchResult) -> Unit
) : ListAdapter<SearchResult, NavSearchResultAdapter.ResultViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nav_search_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvResultTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvResultSubtitle)

        fun bind(result: SearchResult) {
            // Split "name, rest…" so the first segment is the bold title and the
            // remainder is the muted secondary line. Falls back to a single line
            // when the label has no comma.
            val comma = result.label.indexOf(',')
            if (comma > 0 && comma < result.label.length - 1) {
                tvTitle.text = result.label.substring(0, comma).trim()
                tvSubtitle.text = result.label.substring(comma + 1).trim()
                tvSubtitle.visibility = View.VISIBLE
            } else {
                tvTitle.text = result.label
                tvSubtitle.visibility = View.GONE
            }
            itemView.setOnClickListener { onResultTap(result) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<SearchResult>() {
        override fun areItemsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem.lat == newItem.lat &&
                oldItem.lng == newItem.lng &&
                oldItem.label == newItem.label

        override fun areContentsTheSame(oldItem: SearchResult, newItem: SearchResult): Boolean =
            oldItem == newItem
    }
}

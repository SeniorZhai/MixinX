package one.mixin.android.ui.conversation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import one.mixin.android.R
import one.mixin.android.ui.conversation.holder.MentionHolder
import one.mixin.android.vo.App

class MentionAdapter constructor(private val onClickListener: OnUserClickListener) :
    RecyclerView.Adapter<MentionHolder>() {

    var list: List<App>? = null

    var filterList: List<App>? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var keyword: String? = null

    fun clear() {
        if (filterList != null) {
            filterList = null
        }
    }

    override fun onBindViewHolder(holder: MentionHolder, position: Int) {
        filterList?.let {
            holder.bind(it[position], keyword, onClickListener)
        }
    }

    override fun getItemCount(): Int = filterList?.size ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MentionHolder =
        MentionHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_mention, parent, false))

    interface OnUserClickListener {
        fun onUserClick(appNumber: String)
    }
}

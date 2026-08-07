package com.tv.coverscreen

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CardAdapter(
    private val onTap: (Card) -> Unit,
    /**
     * The icon above a card is its own target on One UI: it opens the per app
     * menu rather than switching to the app. The anchor comes back with it so
     * the sheet can be hung under the chip that was actually pressed.
     */
    private val onIconTap: (Card, View) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<CardAdapter.Holder>() {

    private val items = ArrayList<Card>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Card>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    fun at(position: Int): Card? = items.getOrNull(position)

    fun all(): List<Card> = ArrayList(items)

    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    /** Flip the kept open flag in place, without rebuilding the deck. */
    fun repin(pkg: String, pinned: Boolean) {
        val i = items.indexOfFirst { it.pkg == pkg }
        if (i < 0) return
        items[i] = items[i].copy(pinned = pinned)
        notifyItemChanged(i)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val shot: ImageView = view.findViewById(R.id.shot)
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val chip: FrameLayout = view.findViewById(R.id.chip)
        private val label: TextView = view.findViewById(R.id.label)
        private val veil: View = view.findViewById(R.id.veil)

        init {
            view.setOnClickListener {
                items.getOrNull(bindingAdapterPosition)?.let(onTap)
            }
            chip.setOnClickListener {
                items.getOrNull(bindingAdapterPosition)?.let { onIconTap(it, chip) }
            }
        }

        fun bind(card: Card) {
            label.text = card.label
            icon.setImageDrawable(card.icon)
            chip.setBackgroundResource(
                if (card.pinned) R.drawable.chip_pinned else R.drawable.chip,
            )
            if (card.shot != null) {
                shot.setImageBitmap(card.shot)
                shot.scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                // Never captured yet. Centre the icon so the card is not blank.
                shot.setImageDrawable(card.icon)
                shot.scaleType = ImageView.ScaleType.FIT_CENTER
            }
            // A recycled holder still carries the tilt of whatever card it used
            // to be, so hand it back flat and let TiltStack place it again on
            // the next frame.
            itemView.alpha = 1f
            itemView.scaleX = 1f
            itemView.scaleY = 1f
            itemView.rotationY = 0f
            itemView.translationX = 0f
            itemView.translationY = 0f
            itemView.translationZ = 0f
            veil.alpha = 0f
            chip.alpha = 1f
            label.alpha = 1f
        }
    }
}

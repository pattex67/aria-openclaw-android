package io.openclaw.aria

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.openclaw.aria.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val conversations: List<ConversationEntity>,
    private val onClick: (ConversationEntity) -> Unit,
    private val onLongClick: (ConversationEntity) -> Unit = {}
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    var activeId: String? = null

    class ViewHolder(val binding: ItemConversationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conv = conversations[position]
        holder.binding.txtConvTitle.text = conv.title
        holder.binding.txtConvDate.text =
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(conv.updatedAt))

        // Pin icon
        holder.binding.imgPin.visibility = if (conv.isPinned) View.VISIBLE else View.GONE

        // Folder label
        if (conv.folder != null) {
            holder.binding.txtConvFolder.text = conv.folder
            holder.binding.txtConvFolder.visibility = View.VISIBLE
        } else {
            holder.binding.txtConvFolder.visibility = View.GONE
        }

        val bgColor = if (conv.id == activeId)
            holder.itemView.context.getColor(R.color.dark_card)
        else Color.TRANSPARENT
        holder.itemView.setBackgroundColor(bgColor)

        holder.itemView.setOnClickListener { onClick(conv) }
        holder.itemView.setOnLongClickListener {
            onLongClick(conv)
            true
        }
    }

    override fun getItemCount() = conversations.size
}

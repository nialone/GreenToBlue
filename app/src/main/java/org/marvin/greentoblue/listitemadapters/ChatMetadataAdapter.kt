package org.marvin.greentoblue.listitemadapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.RecyclerView
import org.marvin.greentoblue.ChatExportActivity
import org.marvin.greentoblue.MainActivity
import org.marvin.greentoblue.R
import org.marvin.greentoblue.models.ChatMetadataModel
import org.marvin.greentoblue.models.ChatSources


class ChatMetadataAdapter(
    private val activity: MainActivity,
    private val chatMetadataSource: List<ChatMetadataModel>
) : RecyclerView.Adapter<ChatMetadataAdapter.ChatMetadataViewHolder>()
{

    companion object
    {
        private var INTENT_CHAT_METADATA = "chatmetadata"
    }

    private var selectionMode = false

    class ChatMetadataViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)
    {
        val txtChatName: TextView = itemView.findViewById(R.id.txtChatName)
        val txtChatDetails: TextView = itemView.findViewById(R.id.txtChatDetails)
    }

    @ColorInt
    fun Context.themeColor(@AttrRes attrRes: Int): Int = TypedValue().apply { theme.resolveAttribute (attrRes, this, true) }.data

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatMetadataViewHolder
    {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.listviewitem_chat_metadata, parent, false)
        return ChatMetadataViewHolder(itemView)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ChatMetadataViewHolder, position: Int)
    {
        val currentChat = chatMetadataSource[position]
        holder.txtChatName.text = currentChat.chatName

        val chatSource = when (currentChat.chatSource)
        {
            ChatSources.SOURCE_WHATSAPP -> "Whatsapp"
            ChatSources.SOURCE_FB -> "FB"
            ChatSources.SOURCE_MERGED -> "Merged"
            else -> "Unknown"
        }

        holder.txtChatDetails.text = String.format(activity.getString(R.string.chunk2, currentChat.chatMsgCount, currentChat.mediaCount, currentChat.mediaFound)) //+ "\nSource : $chatSource"

        if (currentChat.isSelected) holder.txtChatName.setTextColor(Color.GREEN)
        else if (currentChat.chatSource == ChatSources.SOURCE_MERGED) holder.txtChatName.setTextColor(Color.CYAN)
        else if (currentChat.isGroup()) holder.txtChatName.setTextColor(Color.MAGENTA)
        else holder.txtChatName.setTextColor(holder.txtChatDetails.currentTextColor)

        holder.itemView.setOnClickListener {

            if (activity.isScanning())
            {
                Toast.makeText(activity.applicationContext, "Scanning In Progress! Please Be Patient!", Toast.LENGTH_SHORT).show()
            }
            else
            {
                if (!selectionMode)
                {
                    val intent = Intent(activity, ChatExportActivity::class.java)
                    intent.putExtra(INTENT_CHAT_METADATA, currentChat)
                    activity.startActivityForResult(intent, 0)
                }
                else
                {
                    currentChat.isSelected = !currentChat.isSelected
                    notifyItemChanged(position)
                }
            }
        }

        holder.itemView.setOnLongClickListener {

            if (activity.isScanning())
            {
                Toast.makeText(activity.applicationContext, "Scanning In Progress! Please Be Patient!", Toast.LENGTH_SHORT).show()
            }
            else if (!selectionMode)
            {
                selectionMode = true
                currentChat.isSelected = selectionMode
                activity.onParticipantSelection(selectionMode)
                notifyItemChanged(position)
            }
            true
        }
    }

    fun updateChatMetadata(chatMetadata: ChatMetadataModel)
    {
        chatMetadataSource.find { chat -> chat.chatID == chatMetadata.chatID }?.let { chat ->
            val position = chatMetadataSource.indexOf(chat)
            chat.chatName = chatMetadata.chatName
            chat.chatParticipants = chatMetadata.chatParticipants
            notifyItemChanged(position)
        }

    }

    fun scrollToChat(chatID: String)
    {
        chatMetadataSource.find { chat -> chat.chatID == chatID }?.let { chat ->
            val position = chatMetadataSource.indexOf(chat)
            activity.findViewById<RecyclerView>(R.id.lstChatMetadata).smoothScrollToPosition(position)
        }

    }

    fun cancelSelection()
    {
        selectionMode = false

        chatMetadataSource.forEach {

            if (it.isSelected)
            {
                it.isSelected = false
                notifyItemChanged(chatMetadataSource.indexOf(it))
            }
        }
        activity.onParticipantSelection(selectionMode)
    }

    override fun getItemId(position: Int): Long
    {
        return position.toLong()
    }

    override fun getItemCount(): Int
    {
        return chatMetadataSource.size
    }

    fun getSelectedItems(): List<ChatMetadataModel>
    {
        val selectedItems = mutableListOf<ChatMetadataModel>()
        chatMetadataSource.forEach { if (it.isSelected) { selectedItems.add(it) } }
        return selectedItems
    }
}
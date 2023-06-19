package org.marvin.greentoblue.listitemadapters

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import org.marvin.greentoblue.ChatExportActivity
import org.marvin.greentoblue.ChatExportActivity.Companion.PREF_DISABLE_ALL_MEDIA
import org.marvin.greentoblue.GenericFileProvider
import org.marvin.greentoblue.R
import org.marvin.greentoblue.models.ChunkDataModel
import java.io.File


class ChunkAdapter (private val activity: ChatExportActivity, private val chunkModelSource : List<ChunkDataModel>) : RecyclerView.Adapter<ChunkAdapter.ChunkViewHolder>() {

    class ChunkViewHolder(itemView : View) : RecyclerView.ViewHolder(itemView)
    {
        val txtChunkName : TextView = itemView.findViewById(R.id.txtChunkName)
        val txtChunkNum : TextView = itemView.findViewById(R.id.txtChunkNum)
        val txtChunkDetails : TextView = itemView.findViewById(R.id.txtChunkDetails)
        val btnExport : ImageButton = itemView.findViewById(R.id.btnExport)
        val txtChunkSize: TextView =  itemView.findViewById(R.id.txtChunkSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChunkViewHolder
    {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.listviewitem_chunk, parent, false)
        return ChunkViewHolder(itemView)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ChunkViewHolder, position: Int)
    {
        val chunk = chunkModelSource[position]
        holder.txtChunkName.text = chunk.chatName
        holder.txtChunkNum.text = activity.getString(R.string.chunk_num, chunk.chunkID)
        holder.txtChunkDetails.text = String.format(activity.getString(R.string.chunk1), chunk.chatCount, chunk.mediaURI.size)
        holder.txtChunkSize.text = sizeToString(chunk.data.size+getMediaSize(chunk.mediaURI))

        holder.btnExport.setOnClickListener {

            val disableAllMedia = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_DISABLE_ALL_MEDIA, false)

        //  val txtFile = File(activity.filesDir, "WhatsAppOut.txt")
            val txtFile = File(activity.filesDir, String.format(activity.getString(R.string.wawith), chunk.chatName))
            txtFile.writeBytes(chunk.data)

            val txtFileUri = GenericFileProvider.getUriForFile(activity, activity.applicationContext.packageName, txtFile)

            val telegramIntent = Intent(Intent.ACTION_SEND_MULTIPLE)
            telegramIntent.type = "*/*"

            val finalUris: ArrayList<Uri> = arrayListOf() // empty
            finalUris.add(txtFileUri)
        //  finalUris.addAll(chunk.mediaURI)
            if (!disableAllMedia) chunk.mediaURI.forEach { if (it.path?.endsWith(".txt", true) == false) finalUris.add(it) }

            telegramIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, finalUris)
            activity.startActivity(telegramIntent)
        }
    }

    override fun getItemCount(): Int
    {
        return chunkModelSource.size
    }

    private fun sizeToString(value: Long): String
    {
        val size: Float = value.toFloat() / 1024 // byte to kilobyte
        return if (size > 1024) String.format("%.1f Mb", size / 1024)
        else String.format("%.0f Kb", size)
    }

    private fun getMediaSize(list: ArrayList<Uri>): Long
    {
        var size: Long = 0
        list.forEach{ uri -> size += getUriFileSize(uri) }
        return size
    }

    private fun getUriFileSize(fileUri: Uri): Long
    {
        var filesize: Long = 0
        activity.contentResolver.query(fileUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) filesize = it.getLong(0)
            it.close()
        }
        return filesize
    }

}
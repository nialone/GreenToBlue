package org.marvin.greentoblue

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.marvin.greentoblue.listitemadapters.ChunkAdapter
import org.marvin.greentoblue.listitemadapters.ParticipantAdapter
import org.marvin.greentoblue.models.ChatMetadataModel
import org.marvin.greentoblue.models.ChunkDataModel

class ChatExportActivity : AppCompatActivity()
{
    companion object
    {
        var PREF_MY_NAME = "myname"
        private var PREF_CHUNK_MEDIA_COUNT = "chunk_media_count"
        private var PREF_CHUNK_MEDIA_SIZE = "chunk_media_size"      // todo: to be or not to be?
        public val PREF_DISABLE_ALL_MEDIA = "disable_all_media"
        private val PREF_SKIP_EMPTY_MEDIA = "skip_empty_media"

        private const val CHAT_METADATA_RESULT_CODE = 100

        private var DEFAULT_CHUNK_MEDIA_COUNT = 200
        private var DEFAULT_MY_NAME = "Green To Blue"
        private var INTENT_CHAT_METADATA = "chatmetadata"
    }

    private var chunkMediaCount = 0
    private var chunks = mutableListOf<ChunkDataModel>()
    private var myName : String = ""
    private var disableAllMedia : Boolean = false
    private var skipEmptyMedia : Boolean = true
    private lateinit var adapter : ChunkAdapter
    private lateinit var chatDatabase: ChatDatabaseAdapter
    private lateinit var chatMetadata : ChatMetadataModel

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_export)

        chatMetadata = intent.getParcelableExtra(INTENT_CHAT_METADATA)!!
        chunkMediaCount = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREF_CHUNK_MEDIA_COUNT, DEFAULT_CHUNK_MEDIA_COUNT)
        myName = PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_MY_NAME, DEFAULT_MY_NAME)!!
        disableAllMedia = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_DISABLE_ALL_MEDIA, disableAllMedia)
        skipEmptyMedia = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_SKIP_EMPTY_MEDIA, skipEmptyMedia)

        initViews()
        populateFromDB()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun populateFromDB()
    {
        GlobalScope.launch {
            chatDatabase = ChatDatabaseAdapter.getInstance(applicationContext)
            chatDatabase.getChunks(chatMetadata.chatID, chunks)
            withContext(Dispatchers.Main){ adapter.notifyDataSetChanged() }
        }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun initViews()
    {
        findViewById<TextView>(R.id.txtChatHeader)?.let{ it.text = if(chatMetadata.isGroup()) getString(R.string.groupName) else getString(R.string.participantName) }
        findViewById<ConstraintLayout>(R.id.layoutParticipantBLock).let { it.visibility = if(chatMetadata.isGroup()) ConstraintLayout.VISIBLE else ConstraintLayout.GONE }

        findViewById<RecyclerView>(R.id.lstParticipants).let {
            it.adapter = ParticipantAdapter(chatMetadata.chatParticipants)
            it.layoutManager = LinearLayoutManager(this)
            it.setHasFixedSize(true)
        }

        findViewById<EditText>(R.id.editTxtChatName)?.setText(chatMetadata.chatName)
        findViewById<EditText>(R.id.editTxtMyName)?.setText(myName)
        findViewById<EditText>(R.id.editTxtChunkCountSize)?.setText(chunkMediaCount.toString())

        val cbSkipEmptyMedia = findViewById<CheckBox>(R.id.cbSkipEmptyMedia)
        val cbDisableAllMedia = findViewById<CheckBox>(R.id.cbDisableAllMedia)

        cbDisableAllMedia.isChecked = disableAllMedia
        cbDisableAllMedia.setOnCheckedChangeListener { _, isChecked ->
            disableAllMedia = isChecked
            cbSkipEmptyMedia.isEnabled = !isChecked
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_DISABLE_ALL_MEDIA, isChecked).apply()
        }

        cbSkipEmptyMedia.isEnabled = !disableAllMedia
        cbSkipEmptyMedia.isChecked = skipEmptyMedia
        cbSkipEmptyMedia.setOnCheckedChangeListener { _, isChecked -> skipEmptyMedia = isChecked  }

        findViewById<Button>(R.id.btnSaveParticipants)?.setOnClickListener {
            findViewById<TextView>(R.id.editTxtChatName)?.let { chatMetadata.chatName = it.text.toString()
            }

            if(chatMetadata.isGroup())
            {
                findViewById<RecyclerView>(R.id.lstParticipants).layoutManager?.let {
                    val itemCount = it.itemCount
                    for (i in 0 until itemCount)
                    {
                        it.getChildAt(i)?.let { listItem ->
                            val participantID = listItem.findViewById<TextView>(R.id.txtParticipantID).text.toString()
                            val participantName = listItem.findViewById<EditText>(R.id.editTxtParticipantName).text.toString()
                            chatMetadata.chatParticipants[participantID] = participantName
                        }
                    }
                }
            }
            chatDatabase.updateParticipant(chatMetadata)
        }

        findViewById<Button>(R.id.btnMakeChunks)?.setOnClickListener {
            chunkMediaCount = findViewById<EditText>(R.id.editTxtChunkCountSize)?.text.toString().toInt()
            GlobalScope.launch {
                withContext(Dispatchers.Main){
                    val btn = it as Button
                    btn.isEnabled = false
                    btn.text = getString(R.string.makingChunks)
                    chunks.clear()
                }

                makeChunks()

                withContext(Dispatchers.Main){
                    val btn = it as Button
                    btn.isEnabled = true
                    btn.text = getString(R.string.makeExportChunks)
                    adapter.notifyDataSetChanged()
                    Toast.makeText(applicationContext, getString(R.string.done), Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<RecyclerView>(R.id.lstChunks).let {
            adapter = ChunkAdapter(this, chunks)
            it.adapter = adapter
            it.layoutManager = LinearLayoutManager(this)
            it.setHasFixedSize(true)
        }
    }

    private fun makeChunks()
    {
        chatDatabase.makeChunks(chatMetadata, myName, chunkMediaCount, skipEmptyMedia, chunks)
        chatDatabase.clearChunks(chatMetadata.chatID)
        chatDatabase.writeChunks(chatMetadata, chunks)
    }

    override fun onBackPressed()
    {
        chunkMediaCount = findViewById<EditText>(R.id.editTxtChunkCountSize)?.text.toString().toInt()
        myName = findViewById<EditText>(R.id.editTxtMyName)?.text.toString()

        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(PREF_CHUNK_MEDIA_COUNT, chunkMediaCount).apply()
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_MY_NAME, myName).apply()
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_DISABLE_ALL_MEDIA, disableAllMedia).apply()
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PREF_SKIP_EMPTY_MEDIA, skipEmptyMedia).apply()

        val intent = Intent()
        intent.putExtra(INTENT_CHAT_METADATA, chatMetadata)
        setResult(CHAT_METADATA_RESULT_CODE, intent)

        super.onBackPressed()
    }

}
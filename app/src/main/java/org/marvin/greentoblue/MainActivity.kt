package org.marvin.greentoblue

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.*
import android.util.Base64
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.marvin.greentoblue.listitemadapters.ChatMetadataAdapter
import org.marvin.greentoblue.listitemadapters.SelectDirectoryAdapter
import org.marvin.greentoblue.models.ChatDataModel
import org.marvin.greentoblue.models.ChatMetadataModel
import org.marvin.greentoblue.models.ChatSources
import org.marvin.greentoblue.models.MediaModel
import java.io.File
import java.lang.String.format
import java.security.MessageDigest
import java.sql.Timestamp
import java.util.*


class MainActivity : AppCompatActivity()
{
    companion object
    {
        private const val MSGSTORE_LOCATION_REQUEST_CODE = 1
        private const val WA_LOCATION_REQUEST_CODE = 2
        private const val REQUEST_PERMISSION_REQUEST_CODE = 3

        private const val CHAT_METADATA_RESULT_CODE = 100

        private const val INTENT_CHAT_METADATA = "chatmetadata"

        private const val PREF_MEDIA_LOCATION = "medialocation"
        private const val PREF_FB_CHAT_LOCATION = "fbchatlocation"
        private val MEDIA_LOCATION_DEFAULT = Environment.getExternalStorageDirectory().absolutePath + "/Whatsapp/Media/"
        private val VCF_FOLDER_DEFAULT = "VCF/"

        private const val DATABASE_MSGSTORE = "msgstore.db"
        private const val DATABASE_WA = "wa.db"
    }

    class FBChatData(val chatMetadata: ChatMetadataModel, val chats: List<ChatDataModel>)

    //region Private Variables

    private lateinit var chatDatabase: ChatDatabaseAdapter
    private lateinit var adapter: ChatMetadataAdapter

    private var mediaFolderLocation = ""
    private var fbChatFolderLocation = ""
    private var vcfFolderLocation = ""

    private var mediaFiles = mutableListOf<MediaModel>()
    private var chatMetadataList = mutableListOf<ChatMetadataModel>()
    private var chatSelected = false
    private var scanning = false

    //endregion

    //region Initialization

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaFolderLocation = PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_MEDIA_LOCATION, MEDIA_LOCATION_DEFAULT)!!
        fbChatFolderLocation = PreferenceManager.getDefaultSharedPreferences(this).getString(PREF_FB_CHAT_LOCATION, Environment.getExternalStorageDirectory().absolutePath)!!
        vcfFolderLocation = mediaFolderLocation + VCF_FOLDER_DEFAULT

        addOnClickListeners()

        populateFromDB()

        findViewById<RecyclerView>(R.id.lstChatMetadata).let {
            adapter = ChatMetadataAdapter(this, chatMetadataList)
            it.adapter = adapter
            it.layoutManager = LinearLayoutManager(this)
            it.setHasFixedSize(true)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun populateFromDB()
    {
        GlobalScope.launch {
            chatDatabase = ChatDatabaseAdapter.getInstance(applicationContext)
            chatDatabase.getChatMetadata(chatMetadataList)
            chatDatabase.getMediaFiles(mediaFiles)
            withContext(Dispatchers.Main) { adapter.notifyDataSetChanged() }
        }
    }

    private fun addOnClickListeners()
    {
        addOnClickListenersWhatsapp()
        addOnClickListenersFB()
        addOnClickListenersEditChatMetadata()
    }

    private fun addOnClickListenersWhatsapp()
    {
        findViewById<Button>(R.id.btnMsgStore).setOnClickListener {
            val msgStoreIntent = Intent().setType("application/*").setAction(Intent.ACTION_GET_CONTENT)

            startActivityForResult(Intent.createChooser(msgStoreIntent, "Select msgstore.db"), MSGSTORE_LOCATION_REQUEST_CODE)
        }

        findViewById<Button>(R.id.btnWA).setOnClickListener {
            val waIntent = Intent().setType("application/*").setAction(Intent.ACTION_GET_CONTENT)
            startActivityForResult(Intent.createChooser(waIntent, "Select wa.db"), WA_LOCATION_REQUEST_CODE)
        }

        findViewById<Button>(R.id.btnMedia).setOnClickListener {

            val dialog = Dialog(this)
            dialog.setTitle("Select Media Directory")
            dialog.setCancelable(true)
            dialog.setContentView(R.layout.dialog_select_directory)

            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.90).toInt()

            dialog.window?.setLayout(width, height)

            val directoryAdapter = SelectDirectoryAdapter(mediaFolderLocation)

            val lstDirectory: RecyclerView = dialog.findViewById(R.id.lstDirectory)
            lstDirectory.adapter = directoryAdapter
            lstDirectory.layoutManager = LinearLayoutManager(this)
            lstDirectory.setHasFixedSize(true)

            val btnSelectDirectory: Button = dialog.findViewById(R.id.btnSelectDirectory)
            val btnCancelDirectorySelection: Button = dialog.findViewById(R.id.btnCancelDirectorySelection)

            btnCancelDirectorySelection.setOnClickListener { dialog.dismiss() }

            btnSelectDirectory.setOnClickListener {
                val selectedMediaDirectory = (lstDirectory.adapter as SelectDirectoryAdapter).currentDirectory
                mediaFolderLocation = selectedMediaDirectory
                vcfFolderLocation = mediaFolderLocation + VCF_FOLDER_DEFAULT
                PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_MEDIA_LOCATION, mediaFolderLocation).apply()
                dialog.dismiss()
            }

            dialog.show()
            directoryAdapter.listFolders()
        }

        findViewById<Button>(R.id.btnScanMedia).setOnClickListener {

            if (scanning)
            {
                Toast.makeText(applicationContext, "Scanning In Progress! Please Be Patient!", Toast.LENGTH_SHORT).show()
            }
            else
            {
                if (hasPermission()) scanMedia() else askPermissions()
            }
        }

        findViewById<Button>(R.id.btnFullClearBD).setOnClickListener {

            AlertDialog.Builder(this)
                .setTitle("Clear DB")
                .setMessage("Do you really want to delete all the data from the database (exclude media-scan)? You will need to scan the DBs again.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Yes") { _, _ ->

                        adapter.cancelSelection()
                        chatDatabase.clearChatAll()
                        chatMetadataList.clear()
                        adapter = ChatMetadataAdapter(this, chatMetadataList)

                }.setNegativeButton("No") { _, _ -> }.show()
        }

        findViewById<Button>(R.id.btnScanDB).setOnClickListener {

            if (scanning)
            {
                Toast.makeText(applicationContext, "Scanning In Progress! Please Be Patient!", Toast.LENGTH_SHORT).show()
            }
            else
            {
                if (mediaFiles.isEmpty())
                {
                    Toast.makeText(this, getString(R.string.scan_media_first), Toast.LENGTH_SHORT).show()
                }
                else if (databasesExist())
                {
                    scanWhatsappDatabase(true)
                }
            }
        }
    }

    private fun addOnClickListenersFB()
    {
        findViewById<Button>(R.id.btnFBChatBackup).setOnClickListener {

            val dialog = Dialog(this)
            dialog.setTitle("Select FB Chat Backup Directory")
            dialog.setCancelable(true)
            dialog.setContentView(R.layout.dialog_select_directory)

            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.90).toInt()

            dialog.window?.setLayout(width, height)

            val directoryAdapter = SelectDirectoryAdapter(fbChatFolderLocation)

            val lstDirectory: RecyclerView = dialog.findViewById(R.id.lstDirectory)
            lstDirectory.adapter = directoryAdapter
            lstDirectory.layoutManager = LinearLayoutManager(this)
            lstDirectory.setHasFixedSize(true)

            val btnSelectDirectory: Button = dialog.findViewById(R.id.btnSelectDirectory)
            val btnCancelDirectorySelection: Button = dialog.findViewById(R.id.btnCancelDirectorySelection)

            btnCancelDirectorySelection.setOnClickListener { dialog.dismiss() }

            btnSelectDirectory.setOnClickListener {
                val selectedFBDirectory = (lstDirectory.adapter as SelectDirectoryAdapter).currentDirectory
                fbChatFolderLocation = selectedFBDirectory

                if (fbChatFolderLocation.endsWith("messages"))
                {
                    fbChatFolderLocation = File(fbChatFolderLocation).parent!!
                }
                println(fbChatFolderLocation)
                PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF_FB_CHAT_LOCATION, fbChatFolderLocation).apply()
                dialog.dismiss()
            }

            dialog.show()

            directoryAdapter.listFolders()
        }

        findViewById<Button>(R.id.btnScanFB).setOnClickListener {
            if (scanning)
            {
                Toast.makeText(applicationContext, "Scanning In Progress! Please Be Patient!", Toast.LENGTH_SHORT).show()
            }
            else
            {
                val dialog = Dialog(this)
                dialog.setTitle("Select FB Chat Backup Directory")
                dialog.setCancelable(false)
                dialog.setContentView(R.layout.dialog_fb_my_name)

                val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
                val height = (resources.displayMetrics.heightPixels * 0.15).toInt()

                dialog.window?.setLayout(width, height)

                dialog.findViewById<Button>(R.id.btnOkMyNameFB).setOnClickListener {
                    val myName = dialog.findViewById<EditText>(R.id.editTxtMyNameFB).text.toString().trim()
                    if (myName.isNotEmpty())
                    {
                        dialog.dismiss()
                        scanFB(myName)
                    }
                    else
                    {
                        Toast.makeText(this, "Name Cannot Be Empty!", Toast.LENGTH_SHORT).show()
                    }
                }

                dialog.findViewById<Button>(R.id.btnCancelMyNameFB).setOnClickListener {
                    Toast.makeText(this, "Cannot Scan FB Chat Without Your Name!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }

                dialog.show()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun addOnClickListenersEditChatMetadata()
    {
        findViewById<Button>(R.id.btnCancelChatSelection).setOnClickListener { adapter.cancelSelection() }

        findViewById<Button>(R.id.btnDeleteChats).setOnClickListener {

            val selectedItems = adapter.getSelectedItems()

            if (selectedItems.isNotEmpty())
            {
                AlertDialog.Builder(this)
                    .setTitle("Delete Chats")
                    .setMessage("Do you want to delete ${selectedItems.size} chats?")
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setCancelable(false)
                    .setPositiveButton("Yes") { _, _ -> GlobalScope.launch {

                        chatDatabase.deleteChats(selectedItems)
                        chatDatabase.getChatMetadata(chatMetadataList)

                        withContext(Dispatchers.Main) {
                            adapter.cancelSelection()
                            adapter.notifyDataSetChanged()
                        }
                    }
                    }.setNegativeButton("No") { _, _ -> }.show()
            }
            else
            {
                Toast.makeText(this, "Please select chats to delete!", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnMergeChats).setOnClickListener {

            val selectedItems = adapter.getSelectedItems()

            if (selectedItems.size <= 1)
            {
                Toast.makeText(this, "Please select at least 2 chats to merge!", Toast.LENGTH_SHORT).show()
            }
            else
            {
                if (selectedItems.isNotEmpty())
                {
                    AlertDialog.Builder(this)
                        .setTitle("Merge Chats")
                        .setMessage("Do you want to merge ${selectedItems.size} chats?")
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setCancelable(false)
                        .setPositiveButton("Yes") { _, _ ->

                        GlobalScope.launch {

                            val idChat = selectedItems[0].chatID;
                            chatDatabase.mergeChats(selectedItems)
                            chatDatabase.getChatMetadata(chatMetadataList)

                            withContext(Dispatchers.Main) {
                                adapter.cancelSelection()
                                adapter.notifyDataSetChanged()
                                adapter.scrollToChat(idChat)
                            }
                        }
                    }.setNegativeButton("No") { _, _ -> }.show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
    {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK)
        {
            when (requestCode)
            {
                MSGSTORE_LOCATION_REQUEST_CODE ->
                {
                    data?.data?.let { selectedFile -> copyDBFile(selectedFile, DATABASE_MSGSTORE) }
                }
                WA_LOCATION_REQUEST_CODE ->
                {
                    data?.data?.let { selectedFile -> copyDBFile(selectedFile, DATABASE_WA) }
                }
                REQUEST_PERMISSION_REQUEST_CODE ->
                {
                    Log.d("PERMISSION", "GRANTED!")
                }
                else ->
                {
                    Log.d("REQUEST CODE", "Unknown Request Code : $requestCode")
                }
            }
        }
        else if (resultCode == CHAT_METADATA_RESULT_CODE)
        {
            data?.getParcelableExtra<ChatMetadataModel>(INTENT_CHAT_METADATA)?.let { adapter.updateChatMetadata(it) }
        }
    }

    //endregion
    //-----------------------------------------------------------------------------------------
    //region Scan Media Folder

    @SuppressLint("SetTextI18n")
    private fun scanMedia()
    {
        GlobalScope.launch(Dispatchers.IO) {

            withContext(Dispatchers.Main) {
                val btn = findViewById<Button>(R.id.btnScanMedia)
                btn.isEnabled = false
                btn.text = getString(R.string.scanning)
                scanning = true
            }

            mediaFiles.clear()
            scanMediaDir(true, File(mediaFolderLocation), mediaFiles)
            chatDatabase.clearMedia()
            chatDatabase.addMediaFiles(mediaFiles)

            withContext(Dispatchers.Main) {
                val btn = findViewById<Button>(R.id.btnScanMedia)
                btn.text = getString(R.string.scan_media) + " (${mediaFiles.size})"
                btn.isEnabled = true
                scanning = false
            }
        }
    }

    private fun scanMediaDir(gui: Boolean, mediaLoc: File, mediaFiles: MutableList<MediaModel>)
    {
        var running = true
        lateinit var dialog: AlertDialog

        if (gui) runOnUiThread {

            dialog = AlertDialog.Builder(this@MainActivity)
                .setCancelable(false)
                .setTitle(getString(R.string.scan_media))
                .setMessage(getString(R.string.scanning))
                .setNegativeButton("Отмена") { dialog, which -> running = false; dialog.dismiss() }
                .create()

            val handler = Handler(Looper.getMainLooper())
            var runnable = Runnable {}

            runnable = Runnable {
                if (!dialog.isShowing) return@Runnable
                dialog.setMessage(format(getString(R.string.scanned_media_files), mediaFiles.size))
                if (running) handler.postDelayed(runnable, 500)
            }

            dialog.show()
            handler.postDelayed(runnable, 50)

        }

        //-------------------------------------------------------------------

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
        {
            mediaLoc.walk().toList().filter { it.isFile }.map {
                if (running) mediaFiles.add(getMediaModel(it)) else { return@map }
            }
        }
        else
        {
            mediaLoc.walk().filter { it.isFile }.map {
                if (running) mediaFiles.add(getMediaModel(it)) else { return@map }
            }
        }

        running = false
        runOnUiThread {
            if (gui && dialog.isShowing) dialog.dismiss()
            Toast.makeText(applicationContext, format(getString(R.string.scanned_media_files), mediaFiles.size), Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMediaModel(file: File): MediaModel
    {
        val hash = hashFile(file)
        val uri = GenericFileProvider.getUriForFile(this, applicationContext.packageName, file)
        return MediaModel(file.name, file.absolutePath, uri, hash)
    }

    //endregion
    //-----------------------------------------------------------------------------------------
    //region Create ChatMetadata : Whatsapp

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun scanWhatsappDatabase(gui: Boolean)
    {
        var running = true
        lateinit var dialog: AlertDialog
        var outstr = "Wait..."
        var count = 0

        if (gui) runOnUiThread {

            dialog = AlertDialog.Builder(this@MainActivity)
                .setCancelable(false)
                .setTitle(getString(R.string.scan_db))
                .setMessage(outstr)
                .setNegativeButton("Отмена") { dialog, which -> running = false; dialog.dismiss() }
                .create()

            val handler = Handler(Looper.getMainLooper())
            var runnable = Runnable {}

            runnable = Runnable {
                if (!dialog.isShowing) return@Runnable
                dialog.setMessage(outstr)
                if (running) handler.postDelayed(runnable, 500)
            }

            dialog.show()
            handler.postDelayed(runnable, 50)

        }

        //-------------------------------------------------------------------

        GlobalScope.launch {
            withContext(Dispatchers.Main) {
                val btn = findViewById<Button>(R.id.btnScanDB)
                btn.isEnabled = false
                btn.text = getString(R.string.scanning)
                scanning = true
            }

            outstr = "Create Participants Map"
            val map = createParticipantsMap()
            createChatMetadataModelsWhatsapp(map)
            chatDatabase.clearChatWhatsapp()

            chatMetadataList.filter { it.chatSource == ChatSources.SOURCE_WHATSAPP }.forEach {
                count += 1;
                outstr = "Prepare $count chats"
                if (!running) return@forEach
                chatDatabase.addChatMetadata(it)
                val chatData = getChatData(it)
                runBlocking { chatDatabase.addChatData(chatData) }
            }

            withContext(Dispatchers.Main) {
                val btn = findViewById<Button>(R.id.btnScanDB)
                btn.text = getString(R.string.scan_db) + " ($count)"
                btn.isEnabled = true
                adapter.notifyDataSetChanged()
                if (gui && dialog.isShowing) dialog.dismiss()
                Toast.makeText(applicationContext, "Database Created! $count chats", Toast.LENGTH_SHORT).show()
                scanning = false
            }
        }

    }

    private fun getChatData(chatMetadata: ChatMetadataModel): List<ChatDataModel>
    {
        val chatID = chatMetadata.chatID
        val chats = mutableListOf<ChatDataModel>()
        var mediaFoundCount = 0

        val query = "SELECT " +

                "message._id, "+                        // for vcf file ...
                "message_type, " +
        //      "message_system.action_type, " +
                "timestamp, " +                         // time
                "jid.raw_string, " +                    // RAW user
                "from_me, " +
                "text_data, " +

                "message_vcard.vcard, " +               // vcf file

                "message_media.media_caption, " +       // OLD!!
                "message_media.media_name, " +
        //      "message_media.mime_type " +            // for only TEST
        //      "message_media.file_path " +
                "message_media.file_hash " +
        //      "message_media.file_size " +            // additional functionality in the future

        //      "message_location.latitude, " +
        //      "message_location.longitude, " +
        //      "message_location.place_name, " +
        //      "message_location.place_address, " +
        //      "message_location.url, " +

                "FROM message " +
        //      "LEFT JOIN message_system ON message._id = message_system.message_row_id " +
                "LEFT JOIN jid ON jid._id = message.sender_jid_row_id " +
                "LEFT JOIN message_vcard ON message._id = message_vcard.message_row_id " +
                "LEFT JOIN message_media ON message._id = message_media.message_row_id " +
                "WHERE message.chat_row_id='$chatID' AND message.message_type<>15 " + // SKIP EMPTY MSG TYPE (incorrect IMPORT)
                "ORDER BY timestamp ASC"

        SQLiteDatabase.openOrCreateDatabase(File(filesDir, DATABASE_MSGSTORE), null).use { db ->

            db.rawQuery(query, null).use { curr ->

                if (curr.moveToFirst())
                {
                    do
                    {
                        val msgid = curr.getInt(curr.getColumnIndex("_id"))
                        val chatype = curr.getInt(curr.getColumnIndex("message_type"))
                        val timestamp = curr.getLong(curr.getColumnIndex("timestamp"))
                        val participantSID = curr.getString(curr.getColumnIndex("raw_string"))
                        val keyFromMe = curr.getInt(curr.getColumnIndex("from_me"))
                        var chatData = curr.getString(curr.getColumnIndex("text_data"))
                        val chatVCARD = curr.getString(curr.getColumnIndex("vcard"))
                        val mediaCaption = curr.getString(curr.getColumnIndex("media_caption"))
                        var mediaName = curr.getString(curr.getColumnIndex("media_name"))
                        val mediaHash = curr.getString(curr.getColumnIndex("file_hash"))?.toString() ?: ""
                        var hasMedia = false
                        var mediaFound = false
                        var mediaURI = Uri.EMPTY

                        if (!chatVCARD.isNullOrEmpty())
                        {
                            val vcfname = if (!chatData.isNullOrEmpty()) "$chatData.vcf" else "contact_$msgid.vcf"

                            addVCARD(vcfFolderLocation, vcfname, chatVCARD).let {
                                getMediaModel(it).let {
                                    hasMedia = true
                                    mediaFound = true
                                    mediaFoundCount += 1
                                    mediaURI = it.fileUri
                                    mediaName = it.fileName
                                }
                            }

                            // chatData = "" // this file-name VCF
                        }

                        if (mediaHash.isNotEmpty())
                        {
                            hasMedia = true
                            mediaFiles.find { it.fileHash.trim() == mediaHash.trim() }?.let {
                                mediaFound = true
                                mediaFoundCount += 1
                                mediaURI = it.fileUri
                                mediaName = it.fileName
                            }
                        }

                        chats.add(ChatDataModel(chatID,
                            chatype,
                            Timestamp(timestamp),
                            chatData?.toString() ?: "",
                            keyFromMe != 0,
                            participantSID?.toString() ?: "",
                            hasMedia,
                            mediaName ?: "",
                            mediaCaption?.toString() ?: "",
                            mediaFound,
                            mediaURI,
                            ChatSources.SOURCE_WHATSAPP))
                    }
                    while (curr.moveToNext())
                }
            }
        }

        chatDatabase.updateMediaFoundCount(chatID, mediaFoundCount)
        chatMetadata.mediaFound = mediaFoundCount
        return chats
    }

    private fun createParticipantsMap(): MutableMap<String, String>
    {
        val participantsMap = mutableMapOf<String, String>()
        SQLiteDatabase.openOrCreateDatabase(File(filesDir, DATABASE_WA), null).use { db ->

            val query = "SELECT jid, display_name, wa_name FROM wa_contacts"
            db.rawQuery(query, null).use { if (it.moveToFirst())
            {
                do
                {
                    val jid = it.getString(0)
                    val senderDisplayName = it.getString(1)
                    val senderWhatsappName = it.getString(2)

                    participantsMap[jid] = if (!senderDisplayName.isNullOrEmpty()) senderDisplayName
                    else if (!senderWhatsappName.isNullOrEmpty()) senderWhatsappName
                    else getPhoneNumberOrID(jid)
                }
                while (it.moveToNext())
            }
            }
        }
        return participantsMap
    }

    private fun createChatMetadataModelsWhatsapp(participants: Map<String, String>)
    {
        val query = "SELECT _id, raw_string_jid, "+
                "(SELECT count(DISTINCT(remote_resource)) FROM receipts WHERE key_remote_jid=chat_view.raw_string_jid AND remote_resource != '') as userCount, "+
                "(SELECT count(*) FROM message WHERE chat_row_id=chat_view._id) as msgCount, "+
                "(SELECT count(*) FROM message_media WHERE chat_row_id=chat_view._id) as mediaCount, "+
                "(SELECT count(*) FROM message WHERE chat_row_id=chat_view._id AND message_type=4) as vcfCount "+
                "FROM chat_view "+
                "WHERE msgCount > 1 "+
                "ORDER BY msgCount DESC"

        chatMetadataList.filter { it.chatSource == ChatSources.SOURCE_WHATSAPP }.forEach { chatMetadataList.remove(it) }

        SQLiteDatabase.openOrCreateDatabase(File(filesDir, DATABASE_MSGSTORE), null).use { msgstoreDB ->

            msgstoreDB.rawQuery(query, null).use { if (it.moveToFirst())
            {
                do
                {
                    val chatID = it.getString(it.getColumnIndex("_id"))
                    val chatSID = it.getString(it.getColumnIndex("raw_string_jid"))
                    val chatName = participants[chatSID] ?: getPhoneNumberOrID(chatID)
                    val chatUserCount = it.getInt(it.getColumnIndex("userCount"))
                    val chatMsgCount = it.getInt(it.getColumnIndex("msgCount"))
                    val chatMediaCount = it.getInt(it.getColumnIndex("mediaCount"))
                    val chatVcfCount = it.getInt(it.getColumnIndex("vcfCount"))

                    val chat = ChatMetadataModel(chatID, chatName, chatUserCount, chatMsgCount, chatMediaCount + chatVcfCount, 0, ChatSources.SOURCE_WHATSAPP)

                    //----------------------------------

                    val query4 = "SELECT DISTINCT(remote_resource) FROM receipts WHERE key_remote_jid='$chatSID' AND remote_resource != ''"
                    msgstoreDB.rawQuery(query4, null).use { curr -> if (curr.moveToFirst())
                    {
                        do
                        {
                            val participantID = curr.getString(0)
                            val participantName = participants[participantID] ?: getPhoneNumberOrID(participantID)
                            chat.chatParticipants[participantID] = participantName
                        }
                        while (curr.moveToNext())
                    }}

                    //----------------------------------

                    chatMetadataList.add(chat)

                }
                while (it.moveToNext())
            }
            }
        }
    }

    //endregion

    //region Create ChatMetadata : FB

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun scanFB(myFBName: String)
    {
        var folderScanSuccess = false

        GlobalScope.launch {
            withContext(Dispatchers.Main) {
                val btn = findViewById<Button>(R.id.btnScanFB)
                btn.isEnabled = false
                btn.text = "Scanning..."
                scanning = true
            }

            val chatFolders = scanFBChatFolder()

            val chatList = mutableListOf<FBChatData>()

            if (chatFolders.isNotEmpty())
            {
                chatFolders.forEach { chatFolder ->
                    val chatFiles = scanFBChat(chatFolder)
                    val (chatMetadata, chats) = createChatMetadataFB(chatFiles, myFBName)
                    chatList.add(FBChatData(chatMetadata, chats))
                }

                chatMetadataList.filter { it.chatSource == ChatSources.SOURCE_FB }.forEach { chatMetadataList.remove(it) }
                chatList.forEach { chatMetadataList.add(it.chatMetadata) }
                chatMetadataList.sortByDescending { it.chatMsgCount }

                folderScanSuccess = true
            }

            withContext(Dispatchers.Main) {
                if (folderScanSuccess)
                {
                    adapter.notifyDataSetChanged()
                    Toast.makeText(applicationContext, "FB Data Scanned. Writing to Database!", Toast.LENGTH_SHORT).show()
                }
                else
                {
                    Toast.makeText(applicationContext, "Please Select FB Chat Folder!", Toast.LENGTH_SHORT).show()
                }
            }

            var chatAddedToDBSuccess = false

            if (folderScanSuccess)
            {

                chatDatabase.clearChatWhatsapp()

                chatList.forEach {
                    chatDatabase.addChatMetadata(it.chatMetadata)
                    chatDatabase.addChatData(it.chats)
                }

                chatAddedToDBSuccess = true
            }

            withContext(Dispatchers.Main) {

                val btn = findViewById<Button>(R.id.btnScanFB)
                btn.text = "Scan FB"
                btn.isEnabled = true

                if (chatAddedToDBSuccess)
                {
                    Toast.makeText(applicationContext, "FB Chat Scanning Done!", Toast.LENGTH_SHORT).show()
                }
                scanning = false
            }
        }
    }

    private fun scanFBChatFolder(): List<File>
    {
        val chatFolders = arrayListOf<File>()
        File(fbChatFolderLocation).listFiles()?.find { it.name == "messages" }?.listFiles()?.filter { it.name == "inbox" || it.name == "archived_threads" }?.forEach { file -> file.listFiles()?.also { chatFolders.addAll(it) } }
        return chatFolders
    }

    private fun scanFBChat(chatFolder: File): List<Map<String, Any?>>
    {
        val chats = mutableListOf<Map<String, Any?>>()
        chatFolder.listFiles()?.forEach { file ->
            if (file.extension == "json")
            {
                val fileContent = file.reader(Charsets.ISO_8859_1).readText()
                chats.add(JSONObject(fileContent).toMap())
            }
        }
        return chats
    }

    private fun createChatMetadataFB(chats: List<Map<String, Any?>>, myFBName: String): Pair<ChatMetadataModel, List<ChatDataModel>>
    {
        val chatList = mutableListOf<ChatDataModel>()

        val sampleChat = chats[0]
        val (chatID) = Regex(".*/(.*)$").find(sampleChat["thread_path"] as String)!!.destructured
        val chatName = sampleChat["title"] as String
        val chatParticipants = mutableMapOf<String, String>()
        (sampleChat["participants"] as List<Any?>).forEach { participant ->
            val participantName = (participant as Map<*, *>)["name"] as String
            if (participantName != myFBName)
            {
                val participantKey = participantName + "_" + chatID
                chatParticipants[participantKey] = participantName
            }
        }

        var chatCount = 0
        var chatUserCount = 0
        var mediaCount = 0
        var mediaFoundCount = 0

        chats.forEach { chatMap ->
            (chatMap["messages"] as List<*>).map { message -> message as Map<*, *> }.forEach { message ->

                val sender = message["sender_name"] as String
                val participantKey = "$sender@$chatID"
                chatParticipants[participantKey] = sender

                val chatContent = if ("content" in message.keys)
                {
                    (message["content"] as String).getDecodedContent()
                }
                else ""

                val timestamp = Timestamp(message["timestamp_ms"] as Long)

                val mediaKey = when
                {
                    "audio_files" in message.keys -> "audio_files"
                    "files" in message.keys -> "files"
                    "gifs" in message.keys -> "gifs"
                    "photos" in message.keys -> "photos"
                    "sticker" in message.keys -> "sticker"
                    "videos" in message.keys -> "videos"
                    else -> ""
                }
                val hasMedia = mediaKey.isNotEmpty()

                if (hasMedia)
                {
                    val mediaList = if (message[mediaKey] is List<*>) message[mediaKey] as List<*>
                    else listOf(message[mediaKey])
                    mediaList.map { it as Map<*, *> }.forEach { media ->
                        val mediaFile = File(fbChatFolderLocation, media["uri"] as String)

                        chatCount += 1
                        mediaCount += 1

                        if (mediaFile.exists())
                        {
                            val mediaFound = true
                            mediaFoundCount += 1

                            val mediaUri = GenericFileProvider.getUriForFile(this, applicationContext.packageName, mediaFile)
                            val mediaName = mediaFile.name

                            chatList.add(ChatDataModel(chatID, 0, timestamp,
                                "", myFBName == sender, participantKey, hasMedia, mediaName, chatContent, //same for each media file
                                mediaFound, mediaUri, ChatSources.SOURCE_FB))
                        }
                        else
                        {
                            chatList.add(ChatDataModel(chatID, 0, timestamp,
                                "", myFBName == sender, participantKey, hasMedia, "", chatContent,
                                false, Uri.EMPTY, ChatSources.SOURCE_FB))
                        }
                    }
                }
                else
                {
                    chatCount += 1
                    chatList.add(ChatDataModel(chatID, 0, timestamp, chatContent, myFBName == sender,
                        participantKey, false, "", "", false, Uri.EMPTY, ChatSources.SOURCE_FB))
                }
            }
        }

        val chatMetadata = ChatMetadataModel(chatID, chatName, chatUserCount, chatCount, mediaCount, mediaFoundCount, ChatSources.SOURCE_FB)
        if (chatParticipants.size > 1) chatMetadata.chatParticipants = chatParticipants

        return Pair(chatMetadata, chatList)
    }

    private fun String.getDecodedContent(): String
    {
        return String(this.toByteArray(Charsets.ISO_8859_1))
    }

    //endregion

    //region Edit ChatMetadata (Merge and Delete)

    fun onParticipantSelection(selectionMode: Boolean)
    {
        chatSelected = selectionMode
        toggleSelectionOperations()
    }

    fun isScanning(): Boolean
    {
        return scanning
    }

    private fun toggleSelectionOperations()
    {
        findViewById<LinearLayout>(R.id.layoutChatSelection).visibility = if (chatSelected) LinearLayout.VISIBLE else LinearLayout.GONE
    }

    override fun onBackPressed()
    {
        if (chatSelected)
        {
            adapter.cancelSelection()
            toggleSelectionOperations()
        }
        else
        {
            super.onBackPressed()
        }
    }

    //endregion

    //region Helper Methods

    private fun addVCARD(path: String, name: String, body: String): File
    {
        val renamed = name.replace(Regex("[\\\\/:\"*?<>|]+"), "")
        Log.d("AddVCF", path+renamed)
        File(path).mkdirs()
        val vcf = File(path + renamed)
        vcf.createNewFile()
        vcf.writeBytes(body.toByteArray())
        return vcf
    }

    private fun JSONObject.toMap(): Map<String, Any?>
    {
        val map = mutableMapOf<String, Any?>()
        this.keys().forEach { key ->
            var obj = this.get(key)
            if (obj is JSONArray) obj = obj.toList()
            else
                if (obj is JSONObject) obj = obj.toMap()
            map[key] = obj
        }
        return map
    }

    private fun JSONArray.toList(): List<Any?>
    {
        val list = mutableListOf<Any?>()
        for (i in 0 until this.length())
        {
            var obj = this.get(i)
            if (obj is JSONArray) obj = obj.toList()
            else if (obj is JSONObject) obj = obj.toMap()
            list.add(obj)
        }
        return list
    }

    private fun hashFile(file: File): String
    {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        file.inputStream().buffered(1024).use {
            while (true)
            {
                val bytesRead = it.read(buffer)
                if (bytesRead < 0) break
                md.update(buffer, 0, bytesRead)
            }
        }
        return Base64.encodeToString(md.digest(), Base64.DEFAULT)
    }

    private fun getPhoneNumberOrID(jid: String): String
    {
        val re = Regex("(.*)@(.*)")
        var sender = jid
        re.find(jid)?.let { result -> sender = result.groupValues[1] }
        return sender
    }

    private fun databasesExist(): Boolean
    {
        return if (File(filesDir, DATABASE_MSGSTORE).exists())
        {
            if (File(filesDir, DATABASE_WA).exists()) true
            else
            {
                Log.d("ERROR", "$DATABASE_WA Doesn't Exist")
                Toast.makeText(this, "Please locate $DATABASE_WA", Toast.LENGTH_SHORT).show()
                false
            }
        }
        else
        {
            Log.d("ERROR", "$DATABASE_MSGSTORE Doesn't Exist")
            Toast.makeText(this, "Please locate $DATABASE_MSGSTORE", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun hasPermission(): Boolean
    {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            PackageManager.PERMISSION_GRANTED == checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        else
        {
            true
        }
    }

    private fun askPermissions()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            val externalPerms = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
            requestPermissions(externalPerms, REQUEST_PERMISSION_REQUEST_CODE)
        }
    }

    private fun copyDBFile(selectedFile: Uri, fileName: String)
    {
        openFileOutput(fileName, Context.MODE_PRIVATE).use { outputStream ->
            application.contentResolver.openInputStream(selectedFile)?.let { inputStream ->
                val buff = ByteArray(2048)
                var read: Int
                while (inputStream.read(buff, 0, buff.size).also { read = it } > 0)
                {
                    outputStream.write(buff, 0, read)
                }
            }
        }
    }

    //endregion
}
package io.openclaw.aria

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(private val context: Context, private val dao: ChatDao) {

    suspend fun exportToJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        // Export conversations
        val convArray = JSONArray()
        val conversations = dao.getAllConversations()
        for (conv in conversations) {
            convArray.put(JSONObject().apply {
                put("id", conv.id)
                put("title", conv.title)
                put("createdAt", conv.createdAt)
                put("updatedAt", conv.updatedAt)
                put("isPinned", conv.isPinned)
                if (conv.folder != null) put("folder", conv.folder)
            })
        }
        root.put("conversations", convArray)

        // Export messages
        val msgArray = JSONArray()
        val messages = dao.getAllMessages()
        for (msg in messages) {
            msgArray.put(JSONObject().apply {
                put("id", msg.id)
                put("conversationId", msg.conversationId)
                put("role", msg.role)
                put("content", msg.content)
                if (msg.reaction != null) put("reaction", msg.reaction)
                put("timestamp", msg.timestamp)
                if (msg.replyToId != null) put("replyToId", msg.replyToId)
                if (msg.replyToContent != null) put("replyToContent", msg.replyToContent)
                // Skip media URIs/base64 — too large and device-specific
            })
        }
        root.put("messages", msgArray)

        // Export folders
        val folderArray = JSONArray()
        val folders = dao.getAllFolders()
        for (folder in folders) {
            folderArray.put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("position", folder.position)
            })
        }
        root.put("folders", folderArray)

        return root.toString(2)
    }

    suspend fun importFromJson(uri: Uri): ImportResult {
        val json = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: return ImportResult(false, "Unable to read file")

        return try {
            val root = JSONObject(json)
            var convCount = 0
            var msgCount = 0

            // Import folders
            if (root.has("folders")) {
                val folders = root.getJSONArray("folders")
                for (i in 0 until folders.length()) {
                    val f = folders.getJSONObject(i)
                    dao.insertFolder(
                        FolderEntity(
                            id = f.getString("id"),
                            name = f.getString("name"),
                            position = f.optInt("position", 0)
                        )
                    )
                }
            }

            // Import conversations
            if (root.has("conversations")) {
                val convs = root.getJSONArray("conversations")
                for (i in 0 until convs.length()) {
                    val c = convs.getJSONObject(i)
                    dao.insertConversation(
                        ConversationEntity(
                            id = c.getString("id"),
                            title = c.getString("title"),
                            createdAt = c.optLong("createdAt", System.currentTimeMillis()),
                            updatedAt = c.optLong("updatedAt", System.currentTimeMillis()),
                            isPinned = c.optBoolean("isPinned", false),
                            folder = if (c.has("folder") && !c.isNull("folder")) c.getString("folder") else null
                        )
                    )
                    convCount++
                }
            }

            // Import messages
            if (root.has("messages")) {
                val msgs = root.getJSONArray("messages")
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    dao.insertMessage(
                        ChatMessage(
                            conversationId = m.getString("conversationId"),
                            role = m.getString("role"),
                            content = m.getString("content"),
                            reaction = if (m.has("reaction") && !m.isNull("reaction")) m.getString("reaction") else null,
                            timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                            replyToId = if (m.has("replyToId") && !m.isNull("replyToId")) m.getLong("replyToId") else null,
                            replyToContent = if (m.has("replyToContent") && !m.isNull("replyToContent")) m.getString("replyToContent") else null
                        )
                    )
                    msgCount++
                }
            }

            ImportResult(true, "$convCount conversations, $msgCount messages imported")
        } catch (e: Exception) {
            ImportResult(false, "Error: ${e.message}")
        }
    }

    data class ImportResult(val success: Boolean, val message: String)
}

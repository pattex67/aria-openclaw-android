package io.openclaw.aria

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

data class MediaAttachment(
    val uri: String,
    val type: String,       // "image" or "video"
    val mimeType: String,   // "image/jpeg", "video/mp4", etc.
    val base64: String
)

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String = "",
    val role: String,
    val content: String,
    val mediaUri: String? = null,
    val mediaType: String? = null,
    val mediaMimeType: String? = null,
    val reaction: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val replyToId: Long? = null,
    val replyToContent: String? = null,
    @Ignore val media: MediaAttachment? = null
) {
    // Room constructor (without @Ignore fields)
    constructor(
        id: Long, conversationId: String, role: String, content: String,
        mediaUri: String?, mediaType: String?, mediaMimeType: String?,
        reaction: String?, timestamp: Long,
        replyToId: Long?, replyToContent: String?
    ) : this(id, conversationId, role, content, mediaUri, mediaType, mediaMimeType, reaction, timestamp, replyToId, replyToContent, null)
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val folder: String? = null
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int = 0
)

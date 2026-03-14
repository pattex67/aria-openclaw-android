package io.openclaw.aria

import androidx.room.*

@Dao
interface ChatDao {
    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Delete
    suspend fun deleteMessage(message: ChatMessage)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getMessagesForConversation(conversationId: String): List<ChatMessage>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: Long): ChatMessage?

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    // Conversations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("UPDATE conversations SET updatedAt = :updatedAt, title = :title WHERE id = :id")
    suspend fun updateConversation(id: String, title: String, updatedAt: Long)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND content LIKE '%' || :query || '%' ORDER BY timestamp ASC")
    suspend fun searchMessages(conversationId: String, query: String): List<ChatMessage>

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id")
    suspend fun setReaction(id: Long, reaction: String?)

    // Pin
    @Query("UPDATE conversations SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: String, isPinned: Boolean)

    // Folders
    @Query("UPDATE conversations SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: String, folder: String?)

    @Query("SELECT DISTINCT folder FROM conversations WHERE folder IS NOT NULL ORDER BY folder ASC")
    suspend fun getAllFolderNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Query("SELECT * FROM folders ORDER BY position ASC, name ASC")
    suspend fun getAllFolders(): List<FolderEntity>

    @Query("UPDATE conversations SET folder = NULL WHERE folder = :folderName")
    suspend fun clearFolder(folderName: String)

    // Backup helpers
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ChatMessage>
}

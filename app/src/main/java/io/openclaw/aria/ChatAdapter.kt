package io.openclaw.aria

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.decode.VideoFrameDecoder
import io.openclaw.aria.databinding.ItemMessageBinding
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    context: Context,
    private val messages: List<ChatMessage>,
    private val onLongClick: (ChatMessage, Int) -> Unit,
    private val onRegenerateClick: () -> Unit,
    private val onRetryClick: () -> Unit = {}
) : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    var streamingPosition: Int = -1
    var fontSize: Int = 15
    var lastAnimatedPosition: Int = -1
    var lastAssistantPosition: Int = -1
        private set

    private val typingHandler = Handler(Looper.getMainLooper())
    private val brightColor = context.getColor(R.color.purple_light)
    private val dimColor = context.getColor(R.color.purple_dim)

    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder
                    .codeBackgroundColor(context.getColor(R.color.code_bg))
                    .codeTextColor(context.getColor(R.color.code_text))
                    .linkColor(context.getColor(R.color.link_color))
            }
        })
        .build()

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    /** Recompute cached last assistant position (call after list changes) */
    fun refreshLastAssistantPosition() {
        lastAssistantPosition = messages.indexOfLast { it.role == "assistant" }
    }

    class MessageViewHolder(val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]
        val isUser = msg.role == "user"
        val ctx = holder.itemView.context

        // Stop any previous typing animation on this recycled view
        stopTypingAnimation(holder)

        // Content — Markwon for assistant, plain text for user
        if (isUser) {
            holder.binding.txtContent.text = msg.content
            holder.binding.txtContent.movementMethod = null
            holder.binding.txtContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
        } else if (msg.content == "\u2026") {
            // Animated typing indicator
            holder.binding.txtContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            holder.binding.txtContent.movementMethod = null
            startTypingAnimation(holder)
        } else {
            val displayText = if (position == streamingPosition)
                msg.content + " \u258C" else msg.content
            markwon.setMarkdown(holder.binding.txtContent, displayText)
            holder.binding.txtContent.movementMethod = LinkMovementMethod.getInstance()
            holder.binding.txtContent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSize.toFloat())
        }
        holder.binding.txtContent.isVisible = msg.content.isNotBlank()

        // Google Messages style: no sender label, bubble position only
        holder.binding.txtSender.isVisible = false
        if (isUser) {
            holder.binding.txtContent.setTextColor(ctx.getColor(R.color.text_primary))
            holder.binding.bubbleContainer.setBackgroundResource(R.drawable.bubble_user)
            (holder.binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams).gravity =
                Gravity.END
        } else {
            holder.binding.txtContent.setTextColor(ctx.getColor(R.color.text_primary))
            holder.binding.bubbleContainer.setBackgroundResource(R.drawable.bubble_aria)
            (holder.binding.bubbleContainer.layoutParams as FrameLayout.LayoutParams).gravity =
                Gravity.START
        }

        // Timestamp
        holder.binding.txtTimestamp.text = timeFmt.format(Date(msg.timestamp))

        // Regenerate button — only on last assistant message, not during streaming
        val isLastAssistant = !isUser && position == lastAssistantPosition
        val isError = msg.content.startsWith("\u274C")
        holder.binding.btnRegenerate.isVisible = isLastAssistant && streamingPosition < 0 && !isError
        holder.binding.btnRegenerate.setOnClickListener { onRegenerateClick() }

        // Retry button — only on error messages
        holder.binding.btnRetry.isVisible = isError
        holder.binding.btnRetry.setOnClickListener { onRetryClick() }

        // Reaction emoji
        if (msg.reaction != null) {
            holder.binding.txtReaction.text = msg.reaction
            holder.binding.txtReaction.isVisible = true
        } else {
            holder.binding.txtReaction.isVisible = false
        }

        // Reply quote
        if (msg.replyToContent != null) {
            holder.binding.replyContainer.isVisible = true
            holder.binding.txtReplyContent.text = msg.replyToContent
        } else {
            holder.binding.replyContainer.isVisible = false
        }

        // Long click
        holder.binding.bubbleContainer.setOnLongClickListener {
            onLongClick(msg, position)
            true
        }

        // Media — check runtime media first, then persisted fields
        val mediaUriStr = msg.media?.uri ?: msg.mediaUri
        val mediaTypeStr = msg.media?.type ?: msg.mediaType
        val mediaMimeStr = msg.media?.mimeType ?: msg.mediaMimeType

        if (mediaUriStr != null) {
            holder.binding.mediaContainer.isVisible = true
            val uri = Uri.parse(mediaUriStr)

            holder.binding.imgMedia.load(uri) {
                crossfade(true)
                if (mediaTypeStr == "video") {
                    decoderFactory { result, options, _ ->
                        VideoFrameDecoder(result.source, options)
                    }
                }
            }

            holder.binding.iconPlayOverlay.isVisible = mediaTypeStr == "video"

            holder.binding.imgMedia.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mediaMimeStr ?: "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(intent)
            }
        } else {
            holder.binding.mediaContainer.isVisible = false
        }

        // Bubble slide-in animation (only for new messages, not recycled)
        if (position > lastAnimatedPosition) {
            val animRes = if (isUser) R.anim.slide_in_right else R.anim.slide_in_left
            holder.itemView.startAnimation(AnimationUtils.loadAnimation(ctx, animRes))
            lastAnimatedPosition = position
        }
    }

    override fun onViewRecycled(holder: MessageViewHolder) {
        stopTypingAnimation(holder)
        super.onViewRecycled(holder)
    }

    private fun startTypingAnimation(holder: MessageViewHolder) {
        val dots = "\u25CF  \u25CF  \u25CF"
        // Dot char positions in the string: 0, 3, 6
        val dotPositions = intArrayOf(0, 3, 6)
        var frame = 0

        val runnable = object : Runnable {
            override fun run() {
                val spannable = SpannableString(dots)
                for (i in dotPositions.indices) {
                    val color = if (i == frame % 3) brightColor else dimColor
                    spannable.setSpan(
                        ForegroundColorSpan(color),
                        dotPositions[i], dotPositions[i] + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                holder.binding.txtContent.text = spannable
                frame++
                typingHandler.postDelayed(this, 400)
            }
        }

        holder.binding.txtContent.tag = runnable
        runnable.run()
    }

    private fun stopTypingAnimation(holder: MessageViewHolder) {
        val runnable = holder.binding.txtContent.tag as? Runnable
        if (runnable != null) {
            typingHandler.removeCallbacks(runnable)
            holder.binding.txtContent.tag = null
        }
    }

    override fun getItemCount() = messages.size
}

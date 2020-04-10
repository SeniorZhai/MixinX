package one.mixin.android.ui.media.pager

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.shizhefei.view.largeimage.LargeImageView
import com.shizhefei.view.largeimage.factory.FileBitmapDecoderFactory
import java.io.File
import one.mixin.android.R
import one.mixin.android.extension.getFilePath
import one.mixin.android.extension.screenWidth
import one.mixin.android.util.Session
import one.mixin.android.vo.MediaStatus
import one.mixin.android.vo.MessageItem
import one.mixin.android.widget.CircleProgress

class LargeImageHolder(itemView: View) : MediaPagerHolder(itemView) {
    fun bind(
        messageItem: MessageItem,
        needPostTransition: Boolean,
        mediaPagerAdapterListener: MediaPagerAdapterListener
    ) {
        val imageView = (itemView as ViewGroup).getChildAt(0) as LargeImageView
        val context = itemView.context
        val circleProgress = itemView.findViewById<CircleProgress>(R.id.circle_progress)
        if (messageItem.mediaStatus == MediaStatus.DONE.name || messageItem.mediaStatus == MediaStatus.READ.name) {
            circleProgress.isVisible = false
            circleProgress.setBindId(messageItem.messageId)
            messageItem.mediaUrl?.getFilePath()?.let { imageView.setImage(FileBitmapDecoderFactory(File(it))) }
        } else {
            val imageData = Base64.decode(messageItem.thumbImage, Base64.DEFAULT)
            imageView.setImage(BitmapFactory.decodeByteArray(imageData, 0, imageData.size))
            circleProgress.isVisible = true
            circleProgress.setBindId(messageItem.messageId)
            @Suppress("ControlFlowWithEmptyBody")
            if (messageItem.mediaStatus == MediaStatus.PENDING.name) {
                circleProgress.enableLoading()
            } else if (messageItem.mediaStatus == MediaStatus.CANCELED.name) {
                if (Session.getAccountId() == messageItem.userId) {
                    circleProgress.enableUpload()
                } else {
                    circleProgress.enableDownload()
                }
            } else {
                // TODO expired
            }
            circleProgress.setOnClickListener {
                mediaPagerAdapterListener.onCircleProgressClick(messageItem)
            }
        }
        if (messageItem.mediaWidth!! < context.screenWidth()) {
            imageView.scale = (context.screenWidth().toFloat() / messageItem.mediaWidth)
        }
        if (needPostTransition) {
            ViewCompat.setTransitionName(imageView, "transition")
            mediaPagerAdapterListener.onReadyPostTransition(imageView)
        }
        imageView.setOnClickListener {
            mediaPagerAdapterListener.onClick(messageItem)
        }
        imageView.setOnLongClickListener {
            mediaPagerAdapterListener.onLongClick(messageItem, itemView)
            return@setOnLongClickListener true
        }
    }
}

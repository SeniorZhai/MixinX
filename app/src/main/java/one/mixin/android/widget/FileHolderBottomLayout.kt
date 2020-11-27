package one.mixin.android.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.ViewAnimator
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import one.mixin.android.RxBus
import one.mixin.android.databinding.LayoutFileHolderBottomBinding
import one.mixin.android.event.ProgressEvent

class FileHolderBottomLayout constructor(context: Context, attrs: AttributeSet) : ViewAnimator(context, attrs) {

    private val binding = LayoutFileHolderBottomBinding.inflate(LayoutInflater.from(context), this)
    val fileSizeTv = binding.fileSizeTv
    val seekBar = binding.seekBar

    private var disposable: Disposable? = null
    var bindId: String? = null
        set(value) {
            if (field != value) {
                field = value
                binding.seekBar.progress = 0
            }
        }

    override fun onAttachedToWindow() {
        if (disposable == null) {
            disposable = RxBus.listen(ProgressEvent::class.java)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    if (it.id == bindId) {
                        if (it.status == CircleProgress.STATUS_PLAY &&
                            it.progress in 0f..binding.seekBar.max.toFloat()
                        ) {
                            binding.seekBar.progress = (it.progress * binding.seekBar.max).toInt()
                            if (displayedChild != POS_SEEK_BAR) {
                                showSeekBar()
                            }
                        } else if (it.status == CircleProgress.STATUS_PAUSE) {
                            if (displayedChild != POS_TEXT) {
                                showText()
                            }
                        }
                    } else {
                        if (it.status == CircleProgress.STATUS_PAUSE ||
                            it.status == CircleProgress.STATUS_PLAY ||
                            it.status == CircleProgress.STATUS_ERROR
                        ) {
                            binding.seekBar.progress = 0
                            if (displayedChild != POS_TEXT) {
                                showText()
                            }
                        }
                    }
                }
        }
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        disposable?.let {
            if (!it.isDisposed) {
                it.dispose()
            }
            disposable = null
        }
        super.onDetachedFromWindow()
    }

    fun showSeekBar() {
        displayedChild = POS_SEEK_BAR
    }

    fun showText() {
        displayedChild = POS_TEXT
    }

    companion object {
        const val POS_TEXT = 0
        const val POS_SEEK_BAR = 1
    }
}

package one.mixin.android.ui.setting

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.fragment_pin_bottom_sheet.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.mixin.android.R
import one.mixin.android.extension.updatePinCheck
import one.mixin.android.ui.common.PinBottomSheetDialogFragment
import one.mixin.android.util.ErrorHandler
import one.mixin.android.widget.BottomSheet
import one.mixin.android.widget.PinView

class PinEmergencyBottomSheetDialog : PinBottomSheetDialogFragment() {
    companion object {
        const val TAG = "PinEmergencyBottomSheetDialog"

        fun newInstance() = PinEmergencyBottomSheetDialog()
    }

    @SuppressLint("RestrictedApi")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        contentView = View.inflate(context, R.layout.fragment_pin_bottom_sheet, null)
        (dialog as BottomSheet).setCustomView(contentView)
    }

    override fun getTipTextRes() = R.string.setting_emergency

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        contentView.pin.setListener(object : PinView.OnPinListener {
            override fun onUpdate(index: Int) {
                if (index == contentView.pin.getCount()) {
                    verify(contentView.pin.code())
                }
            }
        })
    }

    private fun verify(pinCode: String) = lifecycleScope.launch {
        contentView.pin_va?.displayedChild = POS_PB
        val response = try {
            withContext(Dispatchers.IO) {
                bottomViewModel.verifyPin(pinCode)
            }
        } catch (t: Throwable) {
            contentView.pin_va?.displayedChild = POS_PIN
            contentView.pin.clear()
            ErrorHandler.handleError(t)
            return@launch
        }
        contentView.pin_va?.displayedChild = POS_PIN
        contentView.pin.clear()
        if (response.isSuccess) {
            context?.updatePinCheck()
            pinEmergencyCallback?.onSuccess(pinCode)
            dismiss()
        } else {
            ErrorHandler.handleMixinError(response.errorCode)
        }
    }

    var pinEmergencyCallback: PinEmergencyCallback? = null

    abstract class PinEmergencyCallback : Callback {
        abstract fun onSuccess(pinCode: String)

        override fun onSuccess() {
        }
    }
}
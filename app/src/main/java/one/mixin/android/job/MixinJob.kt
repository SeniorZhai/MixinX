package one.mixin.android.job

import android.os.SystemClock
import android.util.Log
import com.birbit.android.jobqueue.Params
import com.google.gson.Gson
import com.google.gson.JsonElement
import java.util.UUID
import one.mixin.android.Constants.SLEEP_MILLIS
import one.mixin.android.MixinApplication
import one.mixin.android.api.NetworkException
import one.mixin.android.api.SignalKey
import one.mixin.android.api.WebSocketException
import one.mixin.android.api.createPreKeyBundle
import one.mixin.android.api.request.ConversationRequest
import one.mixin.android.api.request.ParticipantRequest
import one.mixin.android.api.response.ParticipantSessionResponse
import one.mixin.android.crypto.Base64
import one.mixin.android.extension.fromJson
import one.mixin.android.extension.getDeviceId
import one.mixin.android.extension.networkConnected
import one.mixin.android.util.ErrorHandler.Companion.CONVERSATION_CHECKSUM_INVALID_ERROR
import one.mixin.android.util.ErrorHandler.Companion.FORBIDDEN
import one.mixin.android.util.Session
import one.mixin.android.vo.Conversation
import one.mixin.android.vo.ConversationStatus
import one.mixin.android.vo.LinkState
import one.mixin.android.vo.MessageCategory
import one.mixin.android.vo.MessageStatus
import one.mixin.android.vo.Participant
import one.mixin.android.vo.ParticipantSession
import one.mixin.android.vo.SenderKeyStatus
import one.mixin.android.vo.isGroup
import one.mixin.android.websocket.BlazeMessage
import one.mixin.android.websocket.BlazeMessageParam
import one.mixin.android.websocket.BlazeMessageParamSession
import one.mixin.android.websocket.BlazeSignalKeyMessage
import one.mixin.android.websocket.CREATE_MESSAGE
import one.mixin.android.websocket.PlainDataAction
import one.mixin.android.websocket.TransferPlainData
import one.mixin.android.websocket.createBlazeSignalKeyMessage
import one.mixin.android.websocket.createConsumeSessionSignalKeys
import one.mixin.android.websocket.createConsumeSignalKeysParam
import one.mixin.android.websocket.createSignalKeyMessage
import one.mixin.android.websocket.createSignalKeyMessageParam
import timber.log.Timber

abstract class MixinJob(params: Params, val jobId: String) : BaseJob(params) {

    protected var isCancel = false

    companion object {
        private const val serialVersionUID = 1L
        val TAG = MixinJob::class.java.simpleName
    }

    override fun onAdded() {
    }

    protected fun removeJob() {
        jobManager.removeJob(jobId)
    }

    override fun shouldRetry(throwable: Throwable): Boolean {
        return if (isCancel) {
            Timber.d("cancel")
            false
        } else {
            Timber.d("no cancel")
            super.shouldRetry(throwable)
        }
    }

    protected fun checkSessionSenderKey(conversationId: String) {
        val participants = participantSessionDao.getNotSendSessionParticipants(conversationId, Session.getSessionId()!!) ?: return
        if (participants.isEmpty()) return
        val requestSignalKeyUsers = arrayListOf<BlazeMessageParamSession>()
        val signalKeyMessages = arrayListOf<BlazeSignalKeyMessage>()
        for (p in participants) {
            if (!signalProtocol.containsSession(p.userId, p.sessionId.getDeviceId())) {
                requestSignalKeyUsers.add(BlazeMessageParamSession(p.userId, p.sessionId))
            } else {
                val (cipherText, err) = signalProtocol.encryptSenderKey(conversationId, p.userId, p.sessionId.getDeviceId())
                if (err) {
                    requestSignalKeyUsers.add(BlazeMessageParamSession(p.userId, p.sessionId))
                } else {
                    signalKeyMessages.add(createBlazeSignalKeyMessage(p.userId, cipherText!!, p.sessionId))
                }
            }
        }

        if (requestSignalKeyUsers.isNotEmpty()) {
            val blazeMessage = createConsumeSessionSignalKeys(createConsumeSignalKeysParam(requestSignalKeyUsers))
            val data = signalKeysChannel(blazeMessage)
            if (data != null) {
                val signalKeys = Gson().fromJson<ArrayList<SignalKey>>(data)
                val keys = arrayListOf<BlazeMessageParamSession>()
                if (signalKeys.isNotEmpty()) {
                    for (key in signalKeys) {
                        val preKeyBundle = createPreKeyBundle(key)
                        signalProtocol.processSession(key.userId!!, preKeyBundle)
                        val (cipherText, _) = signalProtocol.encryptSenderKey(conversationId, key.userId, preKeyBundle.deviceId)
                        signalKeyMessages.add(createBlazeSignalKeyMessage(key.userId, cipherText!!, key.sessionId))
                        keys.add(BlazeMessageParamSession(key.userId, key.sessionId))
                    }
                } else {
                    Log.e(TAG, "No any group signal key from server")
                }

                val noKeyList = requestSignalKeyUsers.filter { !keys.contains(it) }
                if (noKeyList.isNotEmpty()) {
                    val sentSenderKeys = noKeyList.map {
                        ParticipantSession(conversationId, it.user_id, it.session_id!!, SenderKeyStatus.UNKNOWN.ordinal)
                    }
                    participantSessionDao.updateList(sentSenderKeys)
                }
            }
        }
        if (signalKeyMessages.isEmpty()) {
            return
        }
        val bm = createSignalKeyMessage(createSignalKeyMessageParam(conversationId, signalKeyMessages))
        val result = deliverNoThrow(bm)
        if (result.retry) {
            return checkSessionSenderKey(conversationId)
        }
        if (result.success) {
            val sentSenderKeys = signalKeyMessages.map {
                ParticipantSession(conversationId, it.recipient_id, it.sessionId!!, SenderKeyStatus.SENT.ordinal)
            }
            participantSessionDao.updateList(sentSenderKeys)
        }
    }

    protected fun sendSenderKey(conversationId: String, recipientId: String, sessionId: String? = null, isForce: Boolean = false): Boolean {
        if (!signalProtocol.containsSession(recipientId, sessionId.getDeviceId()) || isForce) {
            val blazeMessage = createConsumeSessionSignalKeys(createConsumeSignalKeysParam(arrayListOf(BlazeMessageParamSession(recipientId, sessionId))))
            val data = signalKeysChannel(blazeMessage) ?: return false
            val keys = Gson().fromJson<ArrayList<SignalKey>>(data)
            if (keys.isNotEmpty() && keys.count() > 0) {
                val preKeyBundle = createPreKeyBundle(keys[0])
                signalProtocol.processSession(recipientId, preKeyBundle, sessionId.getDeviceId())
            } else {
                if (!sessionId.isNullOrBlank()) {
                    participantSessionDao.insert(ParticipantSession(conversationId, recipientId, sessionId, SenderKeyStatus.UNKNOWN.ordinal))
                }
                return false
            }
        }

        val (cipherText, err) = signalProtocol.encryptSenderKey(conversationId, recipientId, sessionId.getDeviceId())
        if (err) return false
        val signalKeyMessages = createBlazeSignalKeyMessage(recipientId, cipherText!!, sessionId)
        val bm = createSignalKeyMessage(createSignalKeyMessageParam(conversationId, arrayListOf(signalKeyMessages)))
        val result = deliverNoThrow(bm)
        if (result.retry) {
            return sendSenderKey(conversationId, recipientId, sessionId, isForce)
        }
        if (result.success) {
            if (!sessionId.isNullOrBlank()) {
                participantSessionDao.insert(ParticipantSession(conversationId, recipientId, sessionId, SenderKeyStatus.SENT.ordinal))
            }
        }
        return result.success
    }

    protected fun checkSignalSession(recipientId: String, sessionId: String? = null): Boolean {
        if (!signalProtocol.containsSession(recipientId, sessionId.getDeviceId())) {
            val blazeMessage = createConsumeSessionSignalKeys(
                createConsumeSignalKeysParam(arrayListOf(BlazeMessageParamSession(recipientId, sessionId)))
            )

            val data = signalKeysChannel(blazeMessage) ?: return false
            val keys = Gson().fromJson<ArrayList<SignalKey>>(data)
            if (keys.isNotEmpty() && keys.count() > 0) {
                val preKeyBundle = createPreKeyBundle(keys[0])
                signalProtocol.processSession(recipientId, preKeyBundle)
            } else {
                return false
            }
        }
        return true
    }

    protected tailrec fun deliverNoThrow(blazeMessage: BlazeMessage): MessageResult {
        val bm = chatWebSocket.sendMessage(blazeMessage)
        if (bm == null) {
            if (!MixinApplication.appContext.networkConnected() || !LinkState.isOnline(linkState.state)) {
                throw WebSocketException()
            }
            SystemClock.sleep(SLEEP_MILLIS)
            return deliverNoThrow(blazeMessage)
        } else if (bm.error != null) {
            return if (bm.error.code == CONVERSATION_CHECKSUM_INVALID_ERROR) {
                blazeMessage.params?.conversation_id?.let {
                    syncConversation(it)
                }
                MessageResult(false, retry = true)
            } else if (bm.error.code == FORBIDDEN) {
                MessageResult(true, retry = false)
            } else {
                SystemClock.sleep(SLEEP_MILLIS)
                // warning: may caused job leak if server return error data and come to this branch
                return deliverNoThrow(blazeMessage)
            }
        } else {
            return MessageResult(true, retry = false)
        }
    }

    protected fun deliver(blazeMessage: BlazeMessage): Boolean {
        val bm = chatWebSocket.sendMessage(blazeMessage)
        if (bm == null) {
            SystemClock.sleep(SLEEP_MILLIS)
            throw WebSocketException()
        } else if (bm.error != null) {
            if (bm.error.code == CONVERSATION_CHECKSUM_INVALID_ERROR) {
                blazeMessage.params?.conversation_id?.let {
                    syncConversation(it)
                }
                throw WebSocketException()
            } else if (bm.error.code == FORBIDDEN) {
                return true
            } else {
                SystemClock.sleep(SLEEP_MILLIS)
                throw NetworkException()
            }
        }
        return true
    }

    private tailrec fun signalKeysChannel(blazeMessage: BlazeMessage): JsonElement? {
        val bm = chatWebSocket.sendMessage(blazeMessage)
        if (bm == null) {
            SystemClock.sleep(SLEEP_MILLIS)
            return signalKeysChannel(blazeMessage)
        } else if (bm.error != null) {
            return if (bm.error.code == FORBIDDEN) {
                null
            } else {
                SystemClock.sleep(SLEEP_MILLIS)
                return signalKeysChannel(blazeMessage)
            }
        }
        return bm.data
    }

    protected fun makeMessageStatus(status: String, messageId: String) {
        val currentStatus = messageDao.findMessageStatusById(messageId)
        if (currentStatus == MessageStatus.SENDING.name) {
            messageDao.updateMessageStatus(status, messageId)
        } else if (currentStatus == MessageStatus.SENT.name && (status == MessageStatus.DELIVERED.name || status == MessageStatus.READ.name)) {
            messageDao.updateMessageStatus(status, messageId)
        } else if (currentStatus == MessageStatus.DELIVERED.name && status == MessageStatus.READ.name) {
            messageDao.updateMessageStatus(status, messageId)
        }
    }

    protected fun sendNoKeyMessage(conversationId: String, recipientId: String) {
        val plainText = Gson().toJson(TransferPlainData(PlainDataAction.NO_KEY.name))
        val encoded = Base64.encodeBytes(plainText.toByteArray())
        val params = BlazeMessageParam(
            conversationId, recipientId, UUID.randomUUID().toString(),
            MessageCategory.PLAIN_JSON.name, encoded, MessageStatus.SENDING.name
        )
        val bm = BlazeMessage(UUID.randomUUID().toString(), CREATE_MESSAGE, params)
        deliverNoThrow(bm)
    }

    protected fun checkConversation(conversationId: String) {
        val conversation = conversationDao.getConversation(conversationId) ?: return
        if (conversation.isGroup()) {
            syncConversation(conversation.conversationId)
        } else {
            checkConversationExist(conversation)
        }
    }

    protected fun checkConversationExist(conversation: Conversation) {
        if (conversation.status != ConversationStatus.SUCCESS.ordinal) {
            val request = ConversationRequest(conversationId = conversation.conversationId,
                category = conversation.category, participants = arrayListOf(ParticipantRequest(conversation.ownerId!!, ""))
            )
            val response = conversationApi.create(request).execute().body()
            if (response != null && response.isSuccess && response.data != null && !isCancel) {
                conversationDao.updateConversationStatusById(conversation.conversationId, ConversationStatus.SUCCESS.ordinal)

                val sessionParticipants = response.data!!.participantSessions?.let { resp ->
                    resp.map {
                        ParticipantSession(conversation.conversationId, it.userId, it.sessionId)
                    }
                }
                sessionParticipants?.let {
                    participantSessionDao.replaceAll(conversation.conversationId, it)
                }
            } else {
                throw Exception("Create Conversation Exception")
            }
        }
    }

    // TODO exception?
    protected fun syncConversation(conversationId: String) {
        val response = conversationApi.getConversation(conversationId).execute().body()
        if (response != null && response.isSuccess) {
            response.data?.let { data ->
                val remote = data.participants.map {
                    Participant(conversationId, it.userId, it.role, it.createdAt!!)
                }
                participantDao.replaceAll(conversationId, remote)

                data.participantSessions?.let {
                    syncParticipantSession(conversationId, it)
                }
            }
        }
    }

    protected fun syncParticipantSession(conversationId: String, data: List<ParticipantSessionResponse>) {
        val remote = data.map {
            ParticipantSession(conversationId, it.userId, it.sessionId)
        }
        if (remote.isEmpty()) {
            participantSessionDao.deleteByConversationId(conversationId)
            return
        }
        val local = participantSessionDao.getParticipantSessionsByConversationId(conversationId)
        if (local == null || local.isEmpty()) {
            participantSessionDao.insertList(remote)
            return
        }
        val common = remote.intersect(local)
        val remove = local.minus(common)
        val add = remote.minus(common)
        participantSessionDao.deleteList(remove)
        participantSessionDao.insertList(add)
    }

    internal abstract fun cancel()
}

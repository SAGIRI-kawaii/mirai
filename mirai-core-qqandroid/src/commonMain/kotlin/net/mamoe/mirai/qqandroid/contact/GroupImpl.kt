/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("INAPPLICABLE_JVM_NAME", "DEPRECATION_ERROR", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(MiraiInternalAPI::class, LowLevelAPI::class)

package net.mamoe.mirai.qqandroid.contact

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.core.Closeable
import net.mamoe.mirai.LowLevelAPI
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.data.GroupInfo
import net.mamoe.mirai.data.MemberInfo
import net.mamoe.mirai.event.broadcast
import net.mamoe.mirai.event.events.*
import net.mamoe.mirai.event.events.MessageSendEvent.GroupMessageSendEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.qqandroid.QQAndroidBot
import net.mamoe.mirai.qqandroid.message.MessageSourceToGroupImpl
import net.mamoe.mirai.qqandroid.message.ensureSequenceIdAvailable
import net.mamoe.mirai.qqandroid.message.firstIsInstanceOrNull
import net.mamoe.mirai.qqandroid.network.highway.HighwayHelper
import net.mamoe.mirai.qqandroid.network.protocol.packet.chat.TroopManagement
import net.mamoe.mirai.qqandroid.network.protocol.packet.chat.image.ImgStore
import net.mamoe.mirai.qqandroid.network.protocol.packet.chat.receive.MessageSvc
import net.mamoe.mirai.qqandroid.network.protocol.packet.list.ProfileService
import net.mamoe.mirai.qqandroid.utils.estimateLength
import net.mamoe.mirai.utils.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmSynthetic
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalContracts::class)
internal fun GroupImpl.Companion.checkIsInstance(instance: Group) {
    contract {
        returns() implies (instance is GroupImpl)
    }
    check(instance is GroupImpl) { "group is not an instanceof GroupImpl!! DO NOT interlace two or more protocol implementations!!" }
}

@OptIn(ExperimentalContracts::class)
internal fun Group.checkIsGroupImpl() {
    contract {
        returns() implies (this@checkIsGroupImpl is GroupImpl)
    }
    GroupImpl.checkIsInstance(this)
}

@OptIn(MiraiExperimentalAPI::class, LowLevelAPI::class)
@Suppress("PropertyName")
internal class GroupImpl(
    bot: QQAndroidBot,
    coroutineContext: CoroutineContext,
    override val id: Long,
    groupInfo: GroupInfo,
    members: Sequence<MemberInfo>
) : Group() {
    companion object;

    override val coroutineContext: CoroutineContext = coroutineContext + SupervisorJob(coroutineContext[Job])

    override val bot: QQAndroidBot by bot.unsafeWeakRef()

    val uin: Long = groupInfo.uin

    override lateinit var owner: Member

    override lateinit var botAsMember: Member

    override val botPermission: MemberPermission get() = botAsMember.permission

    // e.g. 600
    override val botMuteRemaining: Int get() = botAsMember.muteTimeRemaining

    override val members: ContactList<Member> = ContactList(members.mapNotNull {
        if (it.uin == bot.id) {
            botAsMember = newMember(it)
            if (it.permission == MemberPermission.OWNER) {
                owner = botAsMember
            }
            null
        } else newMember(it).also { member ->
            if (member.permission == MemberPermission.OWNER) {
                owner = member
            }
        }
    }.toLockFreeLinkedList())

    internal var _name: String = groupInfo.name
    private var _announcement: String = groupInfo.memo
    private var _allowMemberInvite: Boolean = groupInfo.allowMemberInvite
    internal var _confessTalk: Boolean = groupInfo.confessTalk
    internal var _muteAll: Boolean = groupInfo.muteAll
    private var _autoApprove: Boolean = groupInfo.autoApprove
    internal var _anonymousChat: Boolean = groupInfo.allowAnonymousChat

    override var name: String
        get() = _name
        set(newValue) {
            checkBotPermissionOperator()
            if (_name != newValue) {
                val oldValue = _name
                _name = newValue
                launch {
                    bot.network.run {
                        TroopManagement.GroupOperation.name(
                            client = bot.client,
                            groupCode = id,
                            newName = newValue
                        ).sendWithoutExpect()
                    }
                    GroupNameChangeEvent(oldValue, newValue, this@GroupImpl, null).broadcast()
                }
            }
        }

    override val settings: GroupSettings = object : GroupSettings {

        override var entranceAnnouncement: String
            get() = _announcement
            set(newValue) {
                checkBotPermissionOperator()
                if (_announcement != newValue) {
                    val oldValue = _announcement
                    _announcement = newValue
                    launch {
                        bot.network.run {
                            TroopManagement.GroupOperation.memo(
                                client = bot.client,
                                groupCode = id,
                                newMemo = newValue
                            ).sendWithoutExpect()
                        }
                        GroupEntranceAnnouncementChangeEvent(oldValue, newValue, this@GroupImpl, null).broadcast()
                    }
                }
            }


        override var isAllowMemberInvite: Boolean
            get() = _allowMemberInvite
            set(newValue) {
                checkBotPermissionOperator()
                if (_allowMemberInvite != newValue) {
                    val oldValue = _allowMemberInvite
                    _allowMemberInvite = newValue
                    launch {
                        bot.network.run {
                            TroopManagement.GroupOperation.allowMemberInvite(
                                client = bot.client,
                                groupCode = id,
                                switch = newValue
                            ).sendWithoutExpect()
                        }
                        GroupAllowMemberInviteEvent(oldValue, newValue, this@GroupImpl, null).broadcast()
                    }
                }
            }

        override var isAutoApproveEnabled: Boolean
            get() = _autoApprove
            @Suppress("UNUSED_PARAMETER")
            set(newValue) {
                TODO()
            }

        override var isAnonymousChatEnabled: Boolean
            get() = _anonymousChat
            @Suppress("UNUSED_PARAMETER")
            set(newValue) {
                TODO()
            }

        override var isConfessTalkEnabled: Boolean
            get() = _confessTalk
            set(newValue) {
                checkBotPermissionOperator()
                if (_confessTalk != newValue) {
                    val oldValue = _confessTalk
                    _confessTalk = newValue
                    launch {
                        bot.network.run {
                            TroopManagement.GroupOperation.confessTalk(
                                client = bot.client,
                                groupCode = id,
                                switch = newValue
                            ).sendWithoutExpect()
                        }
                        GroupAllowConfessTalkEvent(oldValue, newValue, this@GroupImpl, true).broadcast()
                    }
                }
            }


        override var isMuteAll: Boolean
            get() = _muteAll
            set(newValue) {
                checkBotPermissionOperator()
                if (_muteAll != newValue) {
                    val oldValue = _muteAll
                    _muteAll = newValue
                    launch {
                        bot.network.run {
                            TroopManagement.GroupOperation.muteAll(
                                client = bot.client,
                                groupCode = id,
                                switch = newValue
                            ).sendWithoutExpect()
                        }
                        GroupMuteAllEvent(oldValue, newValue, this@GroupImpl, null).broadcast()
                    }
                }
            }
    }

    override suspend fun quit(): Boolean {
        check(botPermission != MemberPermission.OWNER) { "An owner cannot quit from a owning group" }

        if (!bot.groups.delegate.remove(this)) {
            return false
        }
        bot.network.run {
            val response: ProfileService.GroupMngReq.GroupMngReqResponse = ProfileService.GroupMngReq(
                bot.client,
                this@GroupImpl.id
            ).sendAndExpect()
            check(response.errorCode == 0) {
                "Group.quit failed: $response".also {
                    bot.groups.delegate.addLast(this@GroupImpl)
                }
            }
        }
        BotLeaveEvent.Active(this).broadcast()
        return true
    }

    @OptIn(MiraiExperimentalAPI::class)
    override fun newMember(memberInfo: MemberInfo): Member {
        return MemberImpl(
            @OptIn(LowLevelAPI::class)
            bot._lowLevelNewFriend(memberInfo) as FriendImpl,
            this,
            this.coroutineContext,
            memberInfo
        )
    }

    internal fun newAnonymous(name: String): Member = newMember(
        object : MemberInfo {
            override val nameCard = name
            override val permission = MemberPermission.MEMBER
            override val specialTitle = "匿名"
            override val muteTimestamp = 0
            override val uin = 80000000L
            override val nick = name
        }
    )

    override operator fun get(id: Long): Member {
        if (id == bot.id) {
            return botAsMember
        }
        return members.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("member $id not found in group $uin")
    }

    override fun contains(id: Long): Boolean {
        return bot.id == id || members.firstOrNull { it.id == id } != null
    }

    override fun getOrNull(id: Long): Member? {
        if (id == bot.id) {
            return botAsMember
        }
        return members.firstOrNull { it.id == id }
    }

    @OptIn(MiraiExperimentalAPI::class, LowLevelAPI::class)
    @JvmSynthetic
    override suspend fun sendMessage(message: Message): MessageReceipt<Group> {
        require(message.isContentNotEmpty()) { "message is empty" }
        check(!isBotMuted) { throw BotIsBeingMutedException(this) }

        return sendMessageImpl(message, false).also {
            logMessageSent(message)
        }
    }

    @OptIn(MiraiExperimentalAPI::class)
    private suspend fun sendMessageImpl(message: Message, isForward: Boolean): MessageReceipt<Group> {
        if (message is MessageChain) {
            if (message.anyIsInstance<ForwardMessage>()) {
                return sendMessageImpl(message.singleOrNull() ?: error("ForwardMessage must be standalone"), true)
            }
        }
        if (message is ForwardMessage) {
            check(message.nodeList.size < 200) {
                throw MessageTooLargeException(
                    this, message, message,
                    "ForwardMessage allows up to 200 nodes, but found ${message.nodeList.size}")
            }

            return bot.lowLevelSendGroupLongOrForwardMessage(this.id, message.nodeList, false, message)
        }

        val msg: MessageChain

        if (message !is LongMessage && message !is ForwardMessageInternal) {
            val event = GroupMessageSendEvent(this, message.asMessageChain()).broadcast()
            if (event.isCancelled) {
                throw EventCancelledException("cancelled by GroupMessageSendEvent")
            }

            val length = event.message.estimateLength(703) // 阈值为700左右，限制到3的倍数
            var imageCnt = 0 // 通过下方逻辑短路延迟计算

            if (length > 5000 || event.message.count { it is Image }.apply { imageCnt = this } > 50) {
                throw MessageTooLargeException(
                    this,
                    message,
                    event.message,
                    "message(${event.message.joinToString(
                        "",
                        limit = 10
                    )}) is too large. Allow up to 50 images or 5000 chars"
                )
            }

            if (length > 702 || imageCnt > 2) {
                return bot.lowLevelSendGroupLongOrForwardMessage(this.id,
                    listOf(ForwardMessage.Node(
                        senderId = bot.id,
                        time = currentTimeSeconds.toInt(),
                        message = event.message,
                        senderName = bot.nick)
                    ),
                    true, null)
            }

            msg = event.message
        } else msg = message.asMessageChain()
        msg.firstIsInstanceOrNull<QuoteReply>()?.source?.ensureSequenceIdAvailable()

        lateinit var source: MessageSourceToGroupImpl
        bot.network.run {
            val response: MessageSvc.PbSendMsg.Response = MessageSvc.PbSendMsg.createToGroup(
                bot.client,
                this@GroupImpl,
                msg,
                isForward
            ) {
                source = it
            }.sendAndExpect()
            if (response is MessageSvc.PbSendMsg.Response.Failed) {
                when (response.resultType) {
                    120 -> throw BotIsBeingMutedException(this@GroupImpl)
                    34 -> {
                        kotlin.runCatching { // allow retry once
                            return bot.lowLevelSendGroupLongOrForwardMessage(
                                id, listOf(
                                    ForwardMessage.Node(
                                        senderId = bot.id,
                                        time = currentTimeSeconds.toInt(),
                                        message = msg,
                                        senderName = bot.nick
                                    )
                                ), true, null)
                        }.getOrElse {
                            throw IllegalStateException("internal error: send message failed(34)", it)
                        }
                    }
                    else -> error("send message failed: $response")
                }
            }
        }

        try {
            source.ensureSequenceIdAvailable()
        } catch (e: Exception) {
            bot.network.logger.warning {
                "Timeout awaiting sequenceId for group message(${message.contentToString()
                    .take(10)}). Some features may not work properly"
            }
            bot.network.logger.warning(e)
        }

        return MessageReceipt(source, this, botAsMember)
    }

    @Suppress("DEPRECATION")
    @OptIn(ExperimentalTime::class)
    @JvmSynthetic
    override suspend fun uploadImage(image: ExternalImage): OfflineGroupImage = try {
        if (BeforeImageUploadEvent(this, image).broadcast().isCancelled) {
            throw EventCancelledException("cancelled by BeforeImageUploadEvent.ToGroup")
        }
        bot.network.run {
            val response: ImgStore.GroupPicUp.Response = ImgStore.GroupPicUp(
                bot.client,
                uin = bot.id,
                groupCode = id,
                md5 = image.md5,
                size = image.inputSize
            ).sendAndExpect()

            @Suppress("UNCHECKED_CAST") // bug
            when (response) {
                is ImgStore.GroupPicUp.Response.Failed -> {
                    ImageUploadEvent.Failed(this@GroupImpl, image, response.resultCode, response.message).broadcast()
                    if (response.message == "over file size max") throw OverFileSizeMaxException()
                    error("upload group image failed with reason ${response.message}")
                }
                is ImgStore.GroupPicUp.Response.FileExists -> {
                    val resourceId = image.calculateImageResourceId()
                    return OfflineGroupImage(imageId = resourceId)
                        .also { ImageUploadEvent.Succeed(this@GroupImpl, image, it).broadcast() }
                }
                is ImgStore.GroupPicUp.Response.RequireUpload -> {
                    HighwayHelper.uploadImageToServers(
                        bot,
                        response.uploadIpList.zip(response.uploadPortList),
                        response.uKey,
                        image,
                        kind = "group image",
                        commandId = 2
                    )
                    val resourceId = image.calculateImageResourceId()
                    return OfflineGroupImage(imageId = resourceId)
                        .also { ImageUploadEvent.Succeed(this@GroupImpl, image, it).broadcast() }
                }
            }
        }
    } finally {
        (image.input as? Closeable)?.close()
    }

    override fun toString(): String = "Group($id)"
}

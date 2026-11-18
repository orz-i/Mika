package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState

/**
 * Mika 的任务状态 → 协议状态的唯一映射。
 *
 * 「要不要人」是这条映射的重点：`WaitingConfirmation`、`WaitingTakeover` 与 `reason.needsYou` 的 `Held`
 * 全部落到 `waiting_on_phone`，因为付款、确认、接管只能由人在手机上完成，远端回答不了。
 */
fun TaskState?.toRemoteState(): RemoteTaskState? = when (this) {
    null -> null
    TaskState.Idle -> RemoteTaskState.Starting
    is TaskState.Running -> RemoteTaskState.Running
    is TaskState.Held -> when {
        reason.needsYou -> RemoteTaskState.WaitingOnPhone
        reason.queued -> RemoteTaskState.Queued
        else -> RemoteTaskState.Paused
    }
    is TaskState.WaitingInput -> RemoteTaskState.WaitingForAnswer
    is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> RemoteTaskState.WaitingOnPhone
    is TaskState.Succeeded -> RemoteTaskState.Done
    is TaskState.Failed -> RemoteTaskState.Failed
    TaskState.Cancelled -> RemoteTaskState.Cancelled
}

/** 同一份状态的补充说明；AAU 只按字段输出，不做判断。 */
fun TaskState?.toRemoteDetail(): RemoteTaskDetail? = when (this) {
    is TaskState.Running -> RemoteTaskDetail(
        step = step,
        progress = (narration ?: reason ?: note).takeIf { it.isNotBlank() },
    )
    is TaskState.Held -> RemoteTaskDetail(
        waitingFor = reason.code,
        progress = reason.why.takeIf { it.isNotBlank() },
        note = if (reason.needsYou) ON_PHONE else null,
    )
    is TaskState.WaitingInput -> RemoteTaskDetail(
        question = prompt,
        options = options.orEmpty(),
        note = "Mika is asking a question. Answer this question remotely or on the phone.",
    )
    is TaskState.WaitingConfirmation -> RemoteTaskDetail(
        waitingFor = "confirmation",
        app = request.app,
        note = ON_PHONE,
    )
    is TaskState.WaitingTakeover -> RemoteTaskDetail(
        waitingFor = "hand_to_user",
        reason = reason,
        message = message,
        note = ON_PHONE,
    )
    is TaskState.Succeeded -> RemoteTaskDetail(
        answer = answer,
        verified = if (reviewed) verified else null,
        verificationNote = note,
        unconfirmed = unconfirmed,
    )
    is TaskState.Failed -> RemoteTaskDetail(failureReason = reason, failureKind = kind.name.lowercase())
    null, TaskState.Idle, TaskState.Cancelled -> null
}

/** 与迁出前 `RemoteStatus.ON_PHONE_NOTE` 一字不差，避免远端读到的提示语中途变样。 */
private const val ON_PHONE = "This step needs the user on the phone and cannot be answered remotely. " +
    "Mika has shown its usual notification there; keep waiting or cancel the task."

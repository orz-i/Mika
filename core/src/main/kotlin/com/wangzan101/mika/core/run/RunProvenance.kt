package com.wangzan101.mika.core.run

/** 谁启动这次运行；与单个动作的 Invoker 完全分开。 */
enum class RunOrigin { Ui, Channel, Trigger, Mcp, Child }

/** 用户能否在本次运行中及时处理提问或确认。 */
enum class Attendance { Present, Reachable, Unattended }

data class RunProvenance(
    val origin: RunOrigin = RunOrigin.Ui,
    val attendance: Attendance = Attendance.Present,
    /** 非只读子任务在交互式在场 UI 父任务下可向上冒泡审批确认（默认 false，不改变其它入口的拒绝基线）。 */
    val canEscalateApproval: Boolean = false,
)

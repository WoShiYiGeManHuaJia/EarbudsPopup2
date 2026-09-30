package com.woshiyigemanhuajia.btpopup.battery

/**
 * 耳机电池信息。未知值统一为 -1。
 */
data class BatteryInfo(
    val name: String,
    val address: String,
    val left: Int = UNKNOWN,
    val right: Int = UNKNOWN,
    val case: Int = UNKNOWN,
    val overall: Int = UNKNOWN,
    val charging: Boolean = false,
    val source: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 是否拿到了分体电量（左右耳 / 充电仓） */
    val hasSplit: Boolean get() = left >= 0 || right >= 0 || case >= 0

    val hasAny: Boolean get() = hasSplit || overall >= 0

    fun withSource(tag: String): BatteryInfo = copy(source = tag, updatedAt = System.currentTimeMillis())

    companion object {
        const val UNKNOWN = -1
        const val UNKNOWN_NAME = "蓝牙设备"
    }
}

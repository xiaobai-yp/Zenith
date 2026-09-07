package com.zenith.thermal

object Profile {
    val VALUES = intArrayOf(0, 10, 8, 9, 16, 13, 15, 11, 12, 14)
    val NAMES = arrayOf("Default", "Dynamic", "In-Calls", "Game", "Game 2", "Pubg", "AR & VR", "Class 0", "Camera", "YouTube")
    val MENU_NAMES = arrayOf("Default", "Dynamic (evaluation)", "In-Calls", "Game", "Game 2", "Pubg", "AR & VR", "Class 0", "Camera", "YouTube")
    val entries: List<Entry> get() = VALUES.indices.map { Entry(MENU_NAMES[it], VALUES[it]) }

    data class Entry(val name: String, val thermalId: Int)
    fun value(index: Int) = if (index in VALUES.indices) VALUES[index] else 0
    fun indexOf(thermalId: Int): Int = VALUES.indexOf(thermalId).let { if (it >= 0) it else 0 }
    fun name(thermalId: Int): String = NAMES[indexOf(thermalId)]
    fun nameForId(thermalId: Int): String = name(thermalId)
    fun count() = VALUES.size
}

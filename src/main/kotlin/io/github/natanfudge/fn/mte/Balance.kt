package io.github.natanfudge.fn.mte

import korlibs.time.milliseconds

object Balance {
    fun blockHardness(type: BlockType): Float = when (type) {
        BlockType.Dirt -> 2f
        BlockType.Gold -> 6f
        else -> error("")
    }

    val MineInterval = 500.milliseconds

    val BreakReach = 5

    val PickaxeStrength = 1f
}

val BlockType.yHeight : Int get() = when(this) {
    BlockType.Dirt -> 1
    BlockType.Gold -> 2

}

val BlockType.spawn : Int get() = when(this) {
    BlockType.Dirt -> 1
    BlockType.Gold -> 2

}

enum class BlockType {
    Dirt, Gold,
}
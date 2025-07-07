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

val BlockType.zHeight : Int get() = when(this) {
    BlockType.Dirt -> 1
    BlockType.Gold -> 2

}

val BlockType.spawnPrec : Int get() = when(this) {
    BlockType.Dirt -> 1
    BlockType.Gold -> 2

}

val BlockType.veinSize : IntRange get() = when(this) {
    BlockType.Dirt -> 1..4
    BlockType.Gold -> 2..3

}

enum class BlockType {
    Dirt, Gold,
}
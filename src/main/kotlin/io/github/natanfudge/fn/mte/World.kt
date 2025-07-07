package io.github.natanfudge.fn.mte

import io.github.natanfudge.fn.network.Fun
import io.github.natanfudge.fn.network.state.funList
import io.github.natanfudge.fn.network.state.funMap
import io.github.natanfudge.wgpu4k.matrix.Vec3f
import kotlin.random.Random

class World(val game: MineTheEarth) : Fun("World", game.context) {
    private val width = 21
    private val height = 21

    fun worldgen(mapWidth: Int, yLevelStart: Int, yLevelEnd: Int){
        val blocksList = BlockType.entries

        val validBlocks = blocksList.filter { it.yHeight in yLevelStart until yLevelEnd }
        val height = yLevelEnd - yLevelStart
        val matrix = Array(height) { Array(mapWidth) { "filler block" } }
        val weightedBlocksList = validBlocks.flatMap { block ->
            val weight = (block.spawnPrec * 10000).toInt().coerceAtLeast(1)
            List(weight) { block }
        }
        for (y in 0 until height){
            for (x in 0 until mapWidth){
                if ((0..99).random() < 90){
                    val block = weightedBlocksList.random()
                    val vienSize = block.veinSize.random()
                    var placed = mutableSetOf<Pair<Int, Int>>()
                    placed.add(Pair(x,y))
                    while (placed.size < veinSize) {
                        val vblock = placed.random()
                        var vx = vblock.first + (-1..1).random()
                        var vy = vblock.second + (-1..1).random()
                        if (vx in 0 until mapWidth && vy in 0 until height && Pair(vx, vy) !in placed) {
                            placed.add(Pair(vx, vy))
                            matrix[vy][vx] = block.name
                        }
                    }
                }
            }
        }
    }

    val blocks = funMap<BlockPos, Block>(
        "blocks",
        List(height * width) {
            val x = it % width
            val y = it / width
            val type = if (Random.nextInt(1, 11) == 10) BlockType.Gold else BlockType.Dirt
            Block(game, type, BlockPos(x - width / 2, y = 0, z = y - height / 2))
        }.associateBy { it.pos })

    val items = funList<WorldItem>("items")

    fun spawnItem(item: Item, pos: Vec3f) {
        items.add(WorldItem(game, item, pos))
    }

    init {
        repeat(10) {
            spawnItem(Item(ItemType.GoldOre, (it + 1) * 4), game.player.physics.position + Vec3f(2f + it, 0f, 0f))
        }
    }
}
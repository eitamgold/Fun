package io.github.natanfudge.fn.mte

import io.github.natanfudge.fn.network.Fun
import io.github.natanfudge.fn.network.state.funList
import io.github.natanfudge.fn.network.state.funMap
import io.github.natanfudge.wgpu4k.matrix.Vec3f
import kotlin.random.Random

class World(val game: MineTheEarth) : Fun("World", game.context) {
    private val width = 21
    private val height = 21

    private inline fun roll(chancePct: Float, crossinline callback: () -> Unit) {
        if ((0..99).random() < chancePct * 100) {
            callback()
        }
    }

    fun worldgen(mapWidth: Int, zLevelStart: Int, zLevelEnd: Int): List<List<Block>> {
        val blocksList = BlockType.entries

        val validBlocks = blocksList.filter { it.zHeight in zLevelStart until zLevelEnd }
        val height = zLevelEnd - zLevelStart
        var fillerBlock: BlockType
        if (zLevelStart == 0) {
            fillerBlock = BlockType.Dirt
        } else {
            TODO("")
        }

        val x = Block(1,2,3)

        val matrix = List(height) { z ->
            MutableList(mapWidth) { x ->
                Block(game, fillerBlock, BlockPos(x = x, y = 0.5f, z = z))
            }
        }
        val weightedBlocksList = validBlocks.flatMap { block ->
            val weight = (block.spawnPrec * 10000).coerceAtLeast(1)
            List(weight) { block }
        }

        // [0 - 100] - [0-100]
        for (z in 0 until height) {
            for (x in 0 until mapWidth) {
                roll(10.0f) {
                    val block = weightedBlocksList.random()
                    val veinSize = block.veinSize.random()
                    val placed = mutableSetOf<Pair<Int, Int>>()
                    placed.add(Pair(x, z))
                    while (placed.size < veinSize) {
                        val vblock = placed.random()
                        val vx = vblock.first + (-1..1).random()
                        val vy = vblock.second + (-1..1).random()
                        if (vx in 0 until mapWidth && vy in 0 until height && Pair(vx, vy) !in placed) {
                            placed.add(Pair(vx, vy))
                            matrix[vy][vx] = block
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
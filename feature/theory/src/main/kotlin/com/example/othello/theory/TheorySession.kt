package com.example.othello.theory

import com.example.othello.game.GameState
import com.example.othello.game.MoveOutcome
import com.example.othello.game.Position
import com.example.othello.game.TurnResolver
import kotlinx.serialization.Serializable

const val THEORY_SESSION_SCHEMA_VERSION = 1

@Serializable
data class TheoryMoveSnapshot(val row: Int, val column: Int)

@Serializable
data class TheoryNodeSnapshot(
    val id: Long,
    val parentId: Long?,
    val moveFromParent: TheoryMoveSnapshot?,
)

@Serializable
data class TheorySessionSnapshot(
    val schemaVersion: Int = THEORY_SESSION_SCHEMA_VERSION,
    val nodes: List<TheoryNodeSnapshot>,
    val currentNodeId: Long,
    val nextNodeId: Long,
    val selectedMetricId: String,
    val currentBoard: String,
    val currentPlayer: String,
    val currentConsecutivePasses: Int,
    val currentPly: Int,
)

data class TheoryContinuation(
    val nodeId: Long,
    val move: Position,
)

/** In-memory variation tree for one temporary theory-exploration session. */
class TheoryExplorationSession private constructor(
    private val nodes: LinkedHashMap<Long, Node>,
    private var currentId: Long,
    private var nextId: Long,
    selectedMetricId: String,
) {
    var selectedMetricId: String = selectedMetricId
        private set

    val current: GameState get() = requireNotNull(nodes[currentId]).state
    val currentNodeId: Long get() = currentId
    val nodeCount: Int get() = nodes.size
    val canGoBack: Boolean get() = nodes[currentId]?.parentId != null
    val continuations: List<TheoryContinuation>
        get() = requireNotNull(nodes[currentId]).children.map { childId ->
            val child = requireNotNull(nodes[childId])
            TheoryContinuation(child.id, requireNotNull(child.moveFromParent))
        }

    fun play(move: Position): Boolean {
        val parent = requireNotNull(nodes[currentId])
        parent.children.firstOrNull { childId -> nodes[childId]?.moveFromParent == move }?.let { existing ->
            currentId = existing
            return true
        }

        val played = parent.state.play(move) as? MoveOutcome.Played ?: return false
        val resolved = TurnResolver.resolveForcedPasses(played.state).state
        val child = Node(
            id = nextId++,
            parentId = parent.id,
            moveFromParent = move,
            state = resolved,
        )
        nodes[child.id] = child
        parent.children += child.id
        currentId = child.id
        return true
    }

    fun goBack(): Boolean {
        val parentId = requireNotNull(nodes[currentId]).parentId ?: return false
        currentId = parentId
        return true
    }

    fun goForward(): Boolean {
        val children = requireNotNull(nodes[currentId]).children
        if (children.size != 1) return false
        currentId = children.single()
        return true
    }

    fun selectContinuation(nodeId: Long): Boolean {
        if (nodeId !in requireNotNull(nodes[currentId]).children) return false
        currentId = nodeId
        return true
    }

    fun selectMetric(metricId: String): Boolean {
        if (TheoryMetricRegistry.find(metricId) == null || selectedMetricId == metricId) return false
        selectedMetricId = metricId
        return true
    }

    fun snapshot(): TheorySessionSnapshot {
        val state = current
        return TheorySessionSnapshot(
            nodes = nodes.values.map { node ->
                TheoryNodeSnapshot(
                    id = node.id,
                    parentId = node.parentId,
                    moveFromParent = node.moveFromParent?.let { TheoryMoveSnapshot(it.row, it.column) },
                )
            },
            currentNodeId = currentId,
            nextNodeId = nextId,
            selectedMetricId = selectedMetricId,
            currentBoard = state.board.toCompactString(),
            currentPlayer = state.currentPlayer.name,
            currentConsecutivePasses = state.consecutivePasses,
            currentPly = state.ply,
        )
    }

    private data class Node(
        val id: Long,
        val parentId: Long?,
        val moveFromParent: Position?,
        val state: GameState,
        val children: MutableList<Long> = mutableListOf(),
    )

    companion object {
        private const val ROOT_ID = 0L
        private const val MAX_RESTORED_NODES = 100_000

        fun fresh(): TheoryExplorationSession {
            val root = Node(ROOT_ID, null, null, GameState())
            return TheoryExplorationSession(
                nodes = linkedMapOf(ROOT_ID to root),
                currentId = ROOT_ID,
                nextId = ROOT_ID + 1,
                selectedMetricId = TheoryMetricRegistry.default.id,
            )
        }

        fun restore(snapshot: TheorySessionSnapshot): TheoryExplorationSession? = runCatching {
            require(snapshot.schemaVersion == THEORY_SESSION_SCHEMA_VERSION)
            require(snapshot.nodes.size in 1..MAX_RESTORED_NODES)
            require(snapshot.nodes.map { it.id }.toSet().size == snapshot.nodes.size)

            val snapshots = snapshot.nodes.associateBy { it.id }
            val rootSnapshot = requireNotNull(snapshots[ROOT_ID])
            require(rootSnapshot.parentId == null && rootSnapshot.moveFromParent == null)
            require(snapshot.nodes.count { it.parentId == null } == 1)

            val restored = linkedMapOf<Long, Node>()
            val visiting = mutableSetOf<Long>()

            fun restoreNode(id: Long): Node {
                restored[id]?.let { return it }
                require(visiting.add(id)) { "cycle in theory tree" }
                val source = requireNotNull(snapshots[id])
                val node = if (id == ROOT_ID) {
                    Node(ROOT_ID, null, null, GameState())
                } else {
                    val parentId = requireNotNull(source.parentId)
                    val moveSnapshot = requireNotNull(source.moveFromParent)
                    val move = Position(moveSnapshot.row, moveSnapshot.column)
                    val parent = restoreNode(parentId)
                    require(parent.children.none { childId -> snapshots[childId]?.moveFromParent == moveSnapshot }) {
                        "duplicate continuation"
                    }
                    val played = parent.state.play(move) as? MoveOutcome.Played
                        ?: error("illegal saved continuation")
                    val state = TurnResolver.resolveForcedPasses(played.state).state
                    Node(id, parentId, move, state).also { parent.children += id }
                }
                visiting.remove(id)
                restored[id] = node
                return node
            }

            snapshot.nodes.forEach { restoreNode(it.id) }
            require(restored.size == snapshots.size)
            val current = requireNotNull(restored[snapshot.currentNodeId]).state
            require(snapshot.nextNodeId > (restored.keys.maxOrNull() ?: ROOT_ID))
            require(current.board.toCompactString() == snapshot.currentBoard)
            require(current.currentPlayer.name == snapshot.currentPlayer)
            require(current.consecutivePasses == snapshot.currentConsecutivePasses)
            require(current.ply == snapshot.currentPly)

            TheoryExplorationSession(
                nodes = restored,
                currentId = snapshot.currentNodeId,
                nextId = snapshot.nextNodeId,
                selectedMetricId = TheoryMetricRegistry.find(snapshot.selectedMetricId)?.id
                    ?: TheoryMetricRegistry.default.id,
            )
        }.getOrNull()
    }
}

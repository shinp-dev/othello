package com.example.othello

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.othello.designsystem.OthelloTheme
import com.example.othello.game.Disc
import com.example.othello.game.GameStatus
import com.example.othello.game.Position
import com.example.othello.match.LocalMatchController
import com.example.othello.match.LocalMatchViewState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OthelloTheme { OthelloApp() } }
    }
}

@Composable
private fun OthelloApp() {
    var onMatch by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        if (onMatch) MatchScreen(onBack = { onMatch = false }) else HomeScreen(onStart = { onMatch = true })
    }
}

@Composable
private fun HomeScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OTHELLO", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text("オンライン対局 MVP", style = MaterialTheme.typography.titleMedium)
        Text("まずは端末内で遊べるローカル対局を提供しています。\nオンライン対局は接続設定後に有効化されます。")
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("対局する") }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("棋譜・プロフィール（準備中）") }
    }
}

@Composable
private fun MatchScreen(onBack: () -> Unit) {
    val controller = remember { LocalMatchController() }
    var viewState by remember { mutableStateOf<LocalMatchViewState>(controller.viewState) }
    DisposableEffect(controller) {
        val closeable = controller.observe { viewState = it }
        onDispose { closeable.close() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
            Spacer(Modifier.weight(1f))
            Text("ローカル対局", style = MaterialTheme.typography.titleLarge)
        }
        ScoreHeader(viewState)
        OthelloBoard(viewState, controller)
        Text(viewState.message, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        val gameOver = viewState.game.status is GameStatus.Finished
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::pass, modifier = Modifier.weight(1f), enabled = !gameOver) { Text("パス") }
            Button(onClick = controller::reset, modifier = Modifier.weight(1f)) { Text("新しい対局") }
        }
        Text("合法手をタップして着手します。対局中の通信はまだ開始していません。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScoreHeader(viewState: LocalMatchViewState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("● 黒 ${viewState.game.board.count(Disc.BLACK)}")
            Text("○ 白 ${viewState.game.board.count(Disc.WHITE)}")
            Text("手数 ${viewState.game.ply}")
        }
    }
}

@Composable
private fun OthelloBoard(viewState: LocalMatchViewState, controller: LocalMatchController) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF0D6B47)).padding(3.dp)) {
        repeat(8) { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                repeat(8) { column ->
                    val position = Position(row, column)
                    val disc = viewState.game.board[position]
                    val legal = position in viewState.game.legalMoves
                    Box(
                        modifier = Modifier.weight(1f).border(0.5.dp, Color(0xFF72AA8D)).clickable(enabled = legal) { controller.play(position) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (disc != Disc.EMPTY) {
                            Box(Modifier.size(34.dp).background(if (disc == Disc.BLACK) Color(0xFF111514) else Color(0xFFF5F4ED), CircleShape))
                        } else if (legal) {
                            Box(Modifier.size(10.dp).background(Color(0xFFB7E0C9), CircleShape))
                        }
                    }
                }
            }
        }
    }
}

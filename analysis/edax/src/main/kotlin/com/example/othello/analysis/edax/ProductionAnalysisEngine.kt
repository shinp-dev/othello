package com.example.othello.analysis.edax

import com.example.othello.analysis.api.AnalysisEngine
import com.example.othello.analysis.api.AnalysisResult
import com.example.othello.analysis.api.AnalysisSettings
import com.example.othello.analysis.api.ReviewPosition

/** JNI/NDK Edax adapter boundary. Until the native binary is bundled, analysis is unavailable. */
class ProductionAnalysisEngine : AnalysisEngine {
    override suspend fun analyze(position: ReviewPosition, settings: AnalysisSettings): AnalysisResult =
        AnalysisResult(emptyList(), available = false, message = "解析を利用できません")
}

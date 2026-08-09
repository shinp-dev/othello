# Edax adapter

`ProductionAnalysisEngine` deliberately returns `UNAVAILABLE` until a vetted Edax
source, version, license notice, and reproducible Android NDK build are approved.
The app must never substitute heuristic values for production analysis.

The JNI boundary is reserved in `src/main/cpp/edax_jni.cpp`; no external Edax source
or binary is committed. Before enabling it, document the upstream commit, license,
ABI outputs for every shipped architecture, and the OSS notices shown in-app.

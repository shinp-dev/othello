# Edax adapter概要

`:analysis:edax`は、固定したEdax 4.6ソースをAndroid NDKでビルドし、`analysis:api`の背後へJNIアダプターとして接続します。`ProductionAnalysisEngine`は検証済みの評価データがない場合に`UNAVAILABLE`を返し、本番解析値をヒューリスティック値で代用しません。

JNI境界は`src/main/cpp/edax_jni.cpp`です。固定したupstreamソースは`third_party/edax/upstream/src`、バージョン・ライセンス・Android向け変更は`third_party/edax/UPSTREAM.md`にあります。再現ビルド、対応ABI、16 KiB alignment、公開成果物に対応するソースの保持方法は[Edaxビルド](../../docs/05_解析エンジン/Edaxビルド.md)を参照してください。

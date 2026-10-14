# Verificación de correcciones Android

La línea base correctiva comienza en afa7dd1. Las pruebas se ejecutan con el wrapper del repositorio, JBR 21 y SDK local, sin credenciales de servicios reales. No modificar local.properties ni regenerar datos históricos como efecto de un build.

Comandos: `gradlew.bat --offline :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:lintDebug`. Para acreditar ejecución JVM, repetir `:app:testDebugUnitTest --rerun-tasks` cuando corresponda al cambio. Un resultado UP-TO-DATE no cuenta como ejecución nueva.

Ejecutar instrumentadas solo con dispositivo compatible, declarando resultados por separado. La corrección requiere pruebas Room reales; compilar SQL o instrumentadas no acredita migración, reinicio ni WorkManager.

La integración criptográfica debe verificar sobres completos producidos y consumidos en Kotlin/Python; no basta un roundtrip dentro del mismo lenguaje. Mantener bases y registros previos y excluir secretos de las trazas.

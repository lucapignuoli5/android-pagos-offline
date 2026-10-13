# Registro de cambios de Android

Este archivo acompaña los cambios del repositorio. Cada entrada identifica el motivo, la evidencia y el commit asociado sin incluir secretos ni datos personales.

## TFG-F0-AN-01 — Línea base auditada de Android

- **Fecha:** 2026-10-13
- **Fase:** 0 — Línea base segura
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** separar los cambios locales existentes de la implementación del contrato y la persistencia v2.
- **Comportamiento anterior:** la copia de trabajo ya contenía cambios en interfaz, contratos HTTP y eliminación de un administrador criptográfico anterior.
- **Comportamiento nuevo:** el estado previo queda identificado, los archivos auxiliares `._*` quedan excluidos y las credenciales de demostración se reciben desde configuración local.
- **Decisión:** conservar íntegramente los cambios de usuario y construir las siguientes fases sobre esta línea base.
- **Archivos:** `.gitignore`, `AGENTS.md`, `app/build.gradle.kts`, `gradle.properties`, cambios locales previos y `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** ninguno adicional en esta fase.
- **Pruebas:** `:app:testDebugUnitTest` y `:app:assembleDebug` completaron correctamente con JBR 21 y el SDK local; `:app:lintDebug` completó correctamente; `git diff --check` no detectó errores de formato.
- **Limitaciones:** esta línea base conserva los defectos funcionales diagnosticados para corregirlos en fases posteriores. Las pruebas existentes son mínimas y no validan NFC, persistencia ni interoperabilidad física.
- **Commit:** `chore(baseline): [TFG-F0-AN-01] registrar linea base auditada`
- **Impacto académico:** fija qué comportamiento estaba implementado antes del rediseño Android–POS.

## TFG-F1-AN-01 — Contrato criptográfico v2 compatible con Python

- **Fecha:** 2026-10-13
- **Fase:** 1 — Contrato criptográfico y protocolo NFC v2
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** Android debe validar y firmar exactamente los mismos bytes que el POS y el backend.
- **Comportamiento anterior:** HCE construía una cadena delimitada con datos parciales y generaba una operación al seleccionar el AID.
- **Comportamiento nuevo:** Android serializa, valida, firma y verifica el contrato v2 con las mismas reglas que Python, y codifica/decodifica los fragmentos APDU definidos.
- **Decisión:** portar la serialización canónica, validación estricta, ECDSA P-256 y framing APDU sin introducir una reestructuración general de Android.
- **Archivos:** `app/src/main/java/com/example/prototipopagosoffline/protocol/ProtocolV2.kt`, `app/src/test/java/com/example/prototipopagosoffline/protocol/ProtocolV2Test.kt`, `app/src/test/resources/protocol_v2_vectors.json`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** mensajes firmados y transporte APDU v2.
- **Pruebas:** pruebas JVM reproducen el vector canónico Python, verifican su firma, firman con P-256 en Kotlin, alteran individualmente todos los campos, rechazan importes inválidos y comprueban fragmentación APDU; `:app:testDebugUnitTest` correcto.
- **Limitaciones:** la máquina de estados HCE y la persistencia se integran en unidades posteriores; no se acredita hardware NFC.
- **Commit:** `feat(protocol): [TFG-F1-AN-01] implementar contrato v2 en Android`
- **Impacto académico:** aporta evidencia cruzada de que Kotlin interpreta el contrato definido en Python.

## TFG-F1-AN-02 — Sobre firmado interoperable

- **Fecha:** 2026-10-13
- **Fase:** 1 — Contrato criptográfico y protocolo NFC v2
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** HCE debe verificar los bytes recibidos y no una reconstrucción del payload.
- **Comportamiento anterior:** la biblioteca Kotlin solo aceptaba objetos JSON en memoria.
- **Comportamiento nuevo:** Android codifica y analiza el sobre canónico, conserva el payload byte a byte y verifica la firma directamente sobre esos bytes.
- **Decisión:** adoptar el sobre canónico `payload_b64`/`signature_b64` definido en Python y verificar directamente los bytes decodificados.
- **Archivos:** `app/src/main/java/com/example/prototipopagosoffline/protocol/ProtocolV2.kt`, `app/src/test/java/com/example/prototipopagosoffline/protocol/ProtocolV2Test.kt`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** representación de mensajes firmados en NFC y sincronización.
- **Pruebas:** 6 pruebas específicas de protocolo Android y una prueba heredada; sobre producido y analizado sin alterar el vector Python, firma válida y rechazo de campo de sobre alterado.
- **Limitaciones:** la resolución de claves registradas se conecta al flujo HCE en la siguiente unidad.
- **Commit:** `feat(protocol): [TFG-F1-AN-02] preservar payload firmado en Android`
- **Impacto académico:** demuestra que ambas plataformas conservan el mismo material firmado.

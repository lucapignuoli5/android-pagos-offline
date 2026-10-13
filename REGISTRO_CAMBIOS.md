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

## TFG-F1-AN-03 — Máquina de estados APDU v2

- **Fecha:** 2026-10-13
- **Fase:** 1 — Contrato criptográfico y protocolo NFC v2
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** `SELECT` no puede crear pagos y el intercambio fragmentado debe ser ordenado, verificable e idempotente.
- **Comportamiento anterior:** seleccionar el AID generaba, firmaba y persistía inmediatamente una operación basada en estado global volátil.
- **Comportamiento nuevo:** `SELECT` devuelve capacidades sin mutar datos; solicitudes y acuses se ensamblan en orden, verifican contra claves registradas y se delegan a almacenamiento durable; estado y comprobante se consultan por operación; un APDU idéntico repite la misma respuesta.
- **Decisión:** encapsular el protocolo en un motor suspendible con interfaces explícitas para almacenamiento durable y claves confiables.
- **Archivos:** `app/src/main/java/com/example/prototipopagosoffline/protocol/NfcProtocolEngine.kt`, `app/src/test/java/com/example/prototipopagosoffline/protocol/NfcProtocolEngineTest.kt`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** comandos APDU de solicitud, estado, comprobante y acuse.
- **Pruebas:** 4 pruebas específicas del motor cubren `SELECT` sin efecto, solicitud fragmentada, reintento exacto, clave desconocida, fragmento fuera de orden y firma alterada; suite Android completa con 11 pruebas correcta.
- **Limitaciones:** esta unidad prueba el motor con dobles en memoria; Room y `HostApduService` se conectan en la siguiente unidad.
- **Commit:** `feat(nfc): [TFG-F1-AN-03] implementar maquina APDU v2`
- **Impacto académico:** permite demostrar semántica de dos acercamientos e idempotencia sin atribuir resultados de hardware.

## TFG-F1-AN-04 — Adaptador HCE y persistencia de intercambios

- **Fecha:** 2026-10-13
- **Fase:** 1 — Contrato criptográfico y protocolo NFC v2
- **Estado:** PARCIAL
- **Hallazgo o requisito:** el motor probado debe sustituir el flujo HCE que cobraba durante `SELECT` y conservar solicitudes entre activaciones.
- **Comportamiento anterior:** el servicio dependía de saldo y monto globales, generaba el pago en `SELECT` y persistía de forma asíncrona después de responder.
- **Comportamiento nuevo:** HCE delega cada APDU al motor v2 fuera del hilo del servicio y responde después de validar/persistir; Room conserva solicitudes, comprobantes, acuses y claves confiables mediante migración 5→6 sin caída destructiva.
- **Decisión:** agregar tablas Room mediante migración 5→6, repositorios transaccionales y un adaptador `HostApduService` que responde solo después de persistir.
- **Archivos:** `database/AppDatabase.kt`, `database/NfcExchange.kt`, `database/NfcExchangeDao.kt`, `protocol/RoomNfcExchangeStore.kt`, `utils/PaymentHCEService.kt`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** base Room, registro local de claves confiables y ciclo HCE.
- **Pruebas:** `:app:testDebugUnitTest` y `:app:assembleDebug` correctos; esquema generado por Room contrastado con el SQL de migración.
- **Limitaciones:** estado PARCIAL hasta ejecutar una migración instrumentada desde una base v5. La confirmación humana, reserva y emisión del comprobante se completan con la persistencia de billetera; las claves POS deben aprovisionarse antes de operar.
- **Commit:** `feat(nfc): [TFG-F1-AN-04] conectar HCE v2 con Room`
- **Impacto académico:** cambia el estado demostrado de HCE desde cobro en selección a recepción durable de solicitudes verificadas.

## TFG-F1-AN-05 — Acuse limitado por vencimiento de solicitud

- **Fecha:** 2026-10-13
- **Fase:** 1 — Contrato criptográfico y protocolo NFC v2
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** el acuse debe acreditar aceptación local durante la ventana válida de la solicitud POS.
- **Comportamiento anterior:** Android exigía que `accepted_at` no precediera a la emisión, pero permitía que fuera posterior al vencimiento.
- **Comportamiento nuevo:** Android rechaza todo acuse cuyo `accepted_at` sea posterior al `expires_at` firmado en la solicitud.
- **Decisión:** aplicar en Kotlin la misma cota superior incorporada al validador Python.
- **Archivos:** `protocol/ProtocolV2.kt`, pruebas y `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** validación de `payment_ack`.
- **Pruebas:** suite JVM Android correcta con 12 pruebas, incluido acuse un segundo posterior al vencimiento.
- **Limitaciones:** no sustituye la comprobación física del instante de lectura.
- **Commit:** `fix(protocol): [TFG-F1-AN-05] rechazar acuses vencidos`
- **Impacto académico:** precisa que la aceptación local ocurre dentro de los dos minutos firmados por el POS.

## TFG-F3-AN-01 — Billeteras, autorizaciones y reservas persistentes

- **Fecha:** 2026-10-13
- **Fase:** 3 — Persistencia y reservas Android
- **Estado:** PARCIAL
- **Hallazgo o requisito:** el saldo global no sobrevive al proceso y una entrega incierta puede rehabilitar fondos si no existe una reserva durable.
- **Comportamiento anterior:** `OfflineWallet` guardaba un único saldo disponible; HCE y UI dependían de `PaymentState` y no representaban autorizaciones ni reservas.
- **Comportamiento nuevo:** Room 7 conserva saldo confirmado y reserva separada; las autorizaciones offline y metadatos de claves quedan persistidos; la confirmación valida la solicitud, consume autorización, reserva fondos y emite el comprobante en una transacción. La pantalla cliente espera una solicitud POS y requiere confirmación explícita.
- **Decisión:** migrar Room 6→7 conservando saldos, agregar saldo confirmado/reservado, autorizaciones y metadatos de claves por billetera, y emitir el comprobante dentro de una transacción de reserva.
- **Archivos:** `database/AppDatabase.kt`, `database/OfflineWallet.kt`, `database/WalletDao.kt`, `database/LocalOfflineAuthorization.kt`, `database/AuthorizationDao.kt`, `database/NfcExchange.kt`, `database/NfcExchangeDao.kt`, `utils/WalletKeyManager.kt`, `utils/OfflinePaymentCoordinator.kt`, `MainActivity.kt`, `app/schemas/.../7.json`, prueba de migración instrumentada y `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** Room, estado de operación, claves de billetera y emisión de comprobante.
- **Pruebas:** `:app:testDebugUnitTest`, `:app:assembleDebug` y `:app:compileDebugAndroidTestKotlin` correctos; la prueba instrumentada de migración quedó compilada. `:app:lintDebug` permanece ejecutándose en la verificación de cierre.
- **Limitaciones:** no hay dispositivo/emulador disponible para ejecutar la migración ni HCE; la descarga inicial de autorizaciones y registro remoto de claves queda para la unidad de integración con backend; no se verificó hardware ACR122U.
- **Commit:** `feat(wallet): [TFG-F3-AN-01] persistir reservas y autorizaciones`
- **Impacto académico:** materializa la diferencia entre saldo confirmado, saldo reservado y saldo gastable.

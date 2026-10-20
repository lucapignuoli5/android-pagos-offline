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

## TFG-F3-AN-02 — Conservar la billetera recibida en el login

- **Fecha:** 2026-10-13
- **Fase:** 3 — Persistencia y reservas Android
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** el cliente debe usar el mismo identificador de billetera que el backend autoriza.
- **Comportamiento anterior:** `LoginResponse` ignoraba la billetera asociada.
- **Comportamiento nuevo:** el DTO conserva `wallet_id`, `expires_in` y token para aprovisionar reservas en unidades posteriores.
- **Archivos:** `network/SyncItem.kt`, `REGISTRO_CAMBIOS.md`.
- **Pruebas:** compilación Android y pruebas JVM verifican que el contrato actualizado se deserializa sin romper el cliente.
- **Limitaciones:** no se agregan todavía llamadas de aprovisionamiento.
- **Commit:** `feat(auth): [TFG-F3-AN-02] conservar billetera del login`
- **Impacto académico:** documenta la asociación explícita entre sesión y billetera local.

## TFG-F3-AN-03 — Aprovisionar clave, saldo y autorización offline

- **Fecha:** 2026-10-13
- **Fase:** 3 — Persistencia y reservas Android
- **Estado:** PARCIAL
- **Hallazgo o requisito:** la reserva durable no puede operar hasta que Android registre una clave con prueba de posesión y guarde una autorización emitida por el backend.
- **Comportamiento anterior:** el login guardaba solo el JWT y la base local carecía de datos autorizados para confirmar solicitudes POS.
- **Comportamiento nuevo:** después del login se registra una clave P-256 de Android Keystore, se valida la respuesta, se consulta el saldo y se persiste una autorización offline; una autorización local vigente evita solicitar cupo adicional al reconectar.
- **Decisión:** mantener el aprovisionamiento fuera del hilo de interfaz y persistir saldo/autorización en una transacción Room, con límites de $1.000 y diez pagos.
- **Archivos:** `network/ApiService.kt`, `network/SyncItem.kt`, `database/AuthorizationDao.kt`, `utils/TokenManager.kt`, `utils/OfflineProvisioner.kt`, `utils/OfflinePaymentCoordinator.kt`, `MainActivity.kt`, pruebas y `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** login v2, registro de clave, autorización offline, saldo Room y sesión cifrada local.
- **Pruebas:** `:app:testDebugUnitTest`, `:app:assembleDebug` y `:app:compileDebugAndroidTestKotlin` correctos; la prueba JVM comprueba que la prueba de posesión vincula usuario, billetera, clave, huella e instante exactos.
- **Limitaciones:** la ejecución contra un backend real y Android Keystore requiere dispositivo/emulador; no se contactan servicios de pago.
- **Commit:** `feat(wallet): [TFG-F3-AN-03] aprovisionar billetera offline`
- **Impacto académico:** conecta autenticación y autorización online previa con el pago local posterior sin Internet.

## TFG-F3-AN-04 — Impedir la restauración de una sesión incompleta

- **Fecha:** 2026-10-13
- **Fase:** 3 — Persistencia y reservas Android
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** la revisión de fase detectó que un fallo después del login podía dejar un JWT persistido y habilitar el próximo inicio sin autorización local.
- **Comportamiento anterior:** la sesión completa se guardaba antes de registrar la clave y persistir saldo/autorización.
- **Comportamiento nuevo:** el token se guarda provisionalmente para autenticar el aprovisionamiento, se elimina ante cualquier fallo y la sesión completa se confirma solo al terminar; el arranque exige además una billetera Room existente.
- **Decisión:** tratar login y aprovisionamiento como una transición de sesión explícita sin borrar reservas ni pendientes al cerrar sesión.
- **Archivos:** `MainActivity.kt`, `utils/TokenManager.kt`, `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** restauración de sesión y almacenamiento cifrado local.
- **Pruebas:** revisión completa de fase con pruebas JVM, ensamblado, compilación instrumentada y lint correctos sobre el árbol estable.
- **Limitaciones:** no se pudo simular cierre forzado en un dispositivo; el control se verificó por flujo y compilación.
- **Commit:** `fix(session): [TFG-F3-AN-04] rechazar sesiones incompletas`
- **Impacto académico:** diferencia una autenticación obtenida de una billetera offline efectivamente aprovisionada.

## TFG-F4-AN-01 — Aprovisionar claves públicas del POS

- **Fecha:** 2026-10-13
- **Fase:** 4 — POS de Windows
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** HCE rechaza correctamente solicitudes de claves desconocidas, por lo que Android necesita una copia durable de las claves POS registradas antes de operar sin red.
- **Comportamiento anterior:** `trusted_public_keys` solo podía poblarse manualmente o en pruebas.
- **Comportamiento nuevo:** el aprovisionamiento autenticado descarga claves POS activas y las actualiza en Room antes de emitir autorizaciones offline.
- **Archivos:** `network/ApiService.kt`, `network/SyncItem.kt`, `database/NfcExchangeDao.kt`, `utils/OfflineProvisioner.kt`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** registro local de claves confiables y ruta de claves del backend.
- **Pruebas:** pruebas JVM y ensamblado Android correctos; el contrato de lista coincide con la respuesta Pydantic y Room actualiza por ID sin duplicar el registro.
- **Limitaciones:** requiere iniciar sesión con conectividad después de registrar un POS nuevo; la revocación se refleja en la próxima actualización online.
- **Commit:** `feat(keys): [TFG-F4-AN-01] aprovisionar claves POS confiables`
- **Impacto académico:** completa la precondición criptográfica para que Android valide una solicitud de un POS registrado.

## TFG-F4-AN-02 — Revisión de compatibilidad con el POS simulado

- **Fecha:** 2026-10-13
- **Fase:** 4 — POS de Windows
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** el cierre de fase exige comprobar que el nuevo consumidor PC/SC conserva AID, APDU, fragmentación y campos exactos de Android.
- **Comportamiento anterior:** las instrucciones seguían presentando el POS como pendiente de implementación.
- **Comportamiento nuevo:** se registra que el POS simulado usa el mismo contrato y que la validación física continúa pendiente.
- **Archivos:** `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- **Pruebas:** JVM, ensamblado, compilación de instrumentadas y lint Android correctos durante la revisión conjunta; 28 pruebas Python verifican backend/POS.
- **Limitaciones:** no hay dispositivo Android conectado ni ACR122U.
- **Commit:** `docs(review): [TFG-F4-AN-02] registrar compatibilidad simulada`
- **Impacto académico:** separa compatibilidad contractual automatizada de interoperabilidad NFC física.

## TFG-F5-AN-01 — Conciliación v2 única y por resultado

- **Fecha:** 2026-10-14
- **Fase:** 5 — Conciliación independiente
- **Estado:** PARCIAL
- **Hallazgo o requisito:** WorkManager y la sincronización manual usaban el contrato heredado y marcaban todo el lote como sincronizado ante cualquier HTTP exitoso.
- **Comportamiento anterior:** no enviaban sobres v2 ni distinguían `ACCEPTED`, `REJECTED`, `CONFLICT`, `PENDING_EVIDENCE` y errores transitorios.
- **Comportamiento nuevo:** la cola durable envía solicitud, comprobante y acuse v2; valida un resultado conocido por operación; consulta el estado de evidencia pendiente; concilia aceptación o rechazo en transacciones Room y conserva la reserva ante conflicto o error transitorio. La sincronización manual, el inicio de sesión, el arranque y HCE programan el mismo worker.
- **Decisión:** usar `nfc_exchanges` como cola durable, limitar cada ejecución a la billetera autenticada, aplicar cada resultado en una transacción Room y reintentar solo estados transitorios con espera exponencial.
- **Archivos:** `MainActivity.kt`, `database/AppDatabase.kt`, `database/AuthorizationDao.kt`, `database/NfcExchange.kt`, `database/NfcExchangeDao.kt`, `database/WalletDao.kt`, `network/ApiService.kt`, `network/SyncItem.kt`, `sync/SyncManager.kt`, `sync/SyncWorker.kt`, `sync/V2SyncRepository.kt`, `utils/PaymentHCEService.kt`, esquema Room 8, pruebas y `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** endpoints v2 de sincronización/estado/saldo, estados `SYNCED`, `REJECTED` y `CONFLICT`, reserva y autorización local, Room 7→8.
- **Pruebas:** 16 pruebas JVM correctas; APK debug, compilación de pruebas instrumentadas y lint correctos con JBR 21 y SDK 36.1. La revisión corrigió el alcance por billetera, la validación del ID consultado y la coherencia del valor predeterminado de la migración.
- **Limitaciones:** la prueba de migración instrumentada compiló pero no pudo ejecutarse por falta de dispositivo/emulador; operaciones QR/TCP heredadas se adaptan en la Fase 6.
- **Commit:** `feat(sync): [TFG-F5-AN-01] conciliar operaciones v2 por resultado`
- **Impacto académico:** implementa la conciliación independiente y conserva reservas hasta un resultado verificable.

## TFG-F5-AN-02 — Revisión de cierre de conciliación Android

- **Fecha:** 2026-10-14
- **Fase:** 5 — Conciliación independiente
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** la fase debe revisarse completa y sin regresiones antes de adaptar QR/TCP y el lector móvil.
- **Comportamiento anterior:** la cola v2 estaba implementada, pero las instrucciones seguían describiendo Room 7 y no registraban su alcance por billetera ni las limitaciones de ejecución.
- **Comportamiento nuevo:** las instrucciones reflejan Room 8, cola por billetera, resultados individuales y límites de evidencia; el cierre conjunto confirma compatibilidad sin atribuir ejecución física.
- **Decisión:** contrastar contratos, esquema Room, estados, reservas, reintentos y ambos órdenes de sincronización; no acreditar pruebas físicas inexistentes.
- **Archivos:** `AGENTS.md`, `REGISTRO_CAMBIOS.md`; registro consolidado e instrucciones generales fuera del repositorio.
- **Pruebas:** puerta final correcta: 16 pruebas JVM, APK debug, compilación de pruebas instrumentadas y lint; esquema Room 8 contrastado y estado Git revisado.
- **Limitaciones:** sin dispositivo/emulador ni ACR122U.
- **Commit:** `docs(review): [TFG-F5-AN-02] cerrar revision de conciliacion`
- **Impacto académico:** diferencia la conciliación implementada y automatizada de la validación física pendiente.

## TFG-F6-AN-01 — Aprovisionar identidad del comercio móvil

- **Fecha:** 2026-10-14
- **Fase:** 6 — Flujos móviles secundarios e integraciones
- **Estado:** PARCIAL
- **Hallazgo o requisito:** QR/TCP y el lector NFC móvil no pueden emitir solicitudes v2 verificables sin sesión de comercio, terminal y clave POS registrada.
- **Comportamiento anterior:** el rol comercio se abría sin autenticación y usaba contratos heredados con claves incluidas por el emisor.
- **Comportamiento nuevo:** el acceso comercio usa credenciales externas, registra una clave P-256 de terminal con prueba de posesión, descarga claves de billetera y guarda sesión/terminal en Room 9. El arranque restaura cada rol solo si existe su aprovisionamiento local; las búsquedas de claves confiables también exigen el propósito esperado.
- **Decisión:** mantener los flujos secundarios, pero exigir aprovisionamiento online previo y persistir identidad/clave del terminal en Room sin incorporar credenciales al repositorio.
- **Archivos:** `app/build.gradle.kts`, `gradle.properties`, `MainActivity.kt`, `database/AppDatabase.kt`, `database/MobileTerminal.kt`, `database/MobileTerminalDao.kt`, `database/NfcExchangeDao.kt`, `protocol/RoomNfcExchangeStore.kt`, `utils/TokenManager.kt`, `utils/TerminalKeyManager.kt`, `utils/MerchantProvisioner.kt`, esquema Room 9, pruebas, `AGENTS.md` y `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** sesión local por rol, registro de clave POS, directorio de claves de billetera y migración Room 8→9.
- **Pruebas:** 17 pruebas JVM, APK debug, compilación de pruebas instrumentadas y lint correctos; el esquema Room 9 coincide con la migración y la prueba canónica vincula usuario, terminal, clave, huella, nonce e instante.
- **Limitaciones:** la ejecución con Android Keystore requiere dispositivo/emulador.
- **Commit:** `feat(merchant): [TFG-F6-AN-01] aprovisionar terminal movil`
- **Impacto académico:** permite presentar QR/TCP y NFC móvil como transportes del contrato v2, no como comprobantes autocontenidos inseguros.

## TFG-F6-AN-02 — Unificar validación y persistencia del comercio móvil

- **Fecha:** 2026-10-14
- **Fase:** 6 — Flujos móviles secundarios e integraciones
- **Estado:** PARCIAL
- **Hallazgo o requisito:** HCE validaba solicitudes y acuses dentro del motor APDU, mientras los flujos secundarios reconstruían campos y guardaban transacciones heredadas.
- **Comportamiento anterior:** cada transporte tenía validación y persistencia incompatibles, y el comercio móvil no tenía una cola v2 propia.
- **Comportamiento nuevo:** HCE delega solicitud y acuse en un receptor común independiente del canal. El comercio móvil crea solicitudes v2 firmadas, verifica comprobantes con claves de billetera, genera acuses y persiste solicitud/comprobante/acuse atómicamente en una cola Room 10 separada e idempotente.
- **Decisión:** extraer el receptor de billetera independiente del transporte y crear operaciones POS móviles durables con solicitud, comprobante, acuse y estados de conciliación.
- **Archivos:** `protocol/ClientPaymentExchangeService.kt`, `protocol/NfcProtocolEngine.kt`, `protocol/ProtocolV2.kt`, `database/AppDatabase.kt`, `database/MobilePosOperation.kt`, `database/MobilePosOperationDao.kt`, `utils/MobilePosCoordinator.kt`, esquema Room 10, migración y pruebas, `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** validación común de solicitud/comprobante/acuse, estados del comercio móvil y migración Room 9→10.
- **Pruebas:** 18 pruebas JVM, APK debug, compilación instrumentada y lint correctos; las pruebas APDU ejercen el receptor extraído y una prueba adicional rechaza un comprobante cuyo importe no coincide con la solicitud.
- **Limitaciones:** las pantallas y transportes se conectan en las unidades siguientes.
- **Commit:** `feat(protocol): [TFG-F6-AN-02] compartir validacion de pagos locales`
- **Impacto académico:** atribuye la seguridad al contrato y a la persistencia, no al canal NFC o TCP.

## TFG-F6-AN-03 — Sustituir QR/TCP heredado por protocolo v2

- **Fecha:** 2026-10-14
- **Fase:** 6 — Flujos móviles secundarios e integraciones
- **Estado:** PARCIAL
- **Hallazgo o requisito:** el servidor aceptaba JSON incompleto con valores inventados, cambiaba el signo del importe, leía líneas sin límite, no esperaba acuse y mostraba éxito antes de persistir.
- **Comportamiento anterior:** QR solo anunciaba IP/puerto y TCP enviaba una transacción heredada con clave pública autocontenida.
- **Comportamiento nuevo:** el QR contiene una solicitud v2 firmada y parámetros TCP estrictos; el cliente valida comercio, terminal, importe y firma antes de confirmar, reserva y persiste antes de transmitir, y solo informa éxito después de verificar y guardar el acuse. El comercio valida y persiste el comprobante, genera el acuse y usa una cola durable independiente para conciliación.
- **Decisión:** usar QR como descubrimiento y transporte de la solicitud v2 firmada; usar TCP acotado para comprobante y acuse exactos, con la misma persistencia/validación que HCE.
- **Archivos:** `protocol/QrTcpProtocol.kt`, `protocol/QrTcpProtocolTest.kt`, `ui/screens/CobroQrScreen.kt`, `ui/screens/PagarQrScreen.kt`, `database/MobilePosOperationDao.kt`, `sync/MobilePosSyncRepository.kt`, `sync/SyncWorker.kt`, `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** descriptor QR v2 canónico, tramas TCP `payment_receipt`/`payment_ack`, límite de 3.000 bytes para QR, línea TCP de 8 KiB, tiempos de espera de 5 segundos y conciliación por rol sobre colas Room separadas.
- **Pruebas:** 22 pruebas JVM correctas, incluidas validación exacta de centavos, campos y tipos obligatorios, límites y terminación de línea, y recorrido completo del descriptor firmado; APK debug, compilación de pruebas instrumentadas y lint correctos.
- **Limitaciones:** requiere dos dispositivos y red local para evidencia de ejecución real.
- **Commit:** `feat(qr): [TFG-F6-AN-03] transportar pagos v2 por tcp local`
- **Impacto académico:** conserva QR/TCP como alternativa móvil sin equipararla al flujo principal ACR122U.

## TFG-F6-AN-04 — Adaptar el lector NFC móvil al protocolo v2

- **Fecha:** 2026-10-14
- **Fase:** 6 — Flujos móviles secundarios e integraciones
- **Estado:** PARCIAL
- **Hallazgo o requisito:** el lector móvil ejecutaba un único `SELECT`, confiaba en una clave autocontenida, reconstruía campos no transmitidos y persistía una operación heredada sin solicitud ni acuse.
- **Comportamiento anterior:** acercar un teléfono intentaba convertir una respuesta delimitada por barras en un cobro y podía mostrar éxito sin el protocolo de dos acercamientos.
- **Comportamiento nuevo:** el comercio crea una solicitud v2 firmada con importe fijo; el primer acercamiento selecciona la aplicación HCE, valida capacidades, transmite la solicitud fragmentada y espera la confirmación del cliente. El segundo acercamiento consulta estado, recupera el comprobante por fragmentos, lo valida y persiste mediante el coordinador común, transmite el acuse y recién entonces informa aceptación local.
- **Decisión:** reutilizar solicitud, comprobante, acuse, validadores y persistencia v2; encapsular APDU en un lector comprobable sin hardware.
- **Archivos:** `PosScreen.kt`, `protocol/MobileNfcReader.kt`, `protocol/MobileNfcReaderTest.kt`, eliminación de `utils/SecurityUtils.kt`, `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** APDU v2 `SELECT`, `PUT_REQUEST`, `GET_STATUS`, `GET_RECEIPT` y `PUT_ACK`; fragmentos de 160 bytes, máximo lógico de 4 KiB, tiempo de espera ISO-DEP de 5 segundos y la misma cola durable del comercio móvil.
- **Pruebas:** 24 pruebas JVM correctas, incluidas espera tras el primer acercamiento y recepción/transmisión multiframe en el segundo; APK debug, compilación de pruebas instrumentadas y lint correctos.
- **Limitaciones:** la interoperabilidad física exige dos dispositivos NFC compatibles y no fue ejecutada en este entorno; este flujo secundario no acredita el ACR122U principal.
- **Commit:** `feat(nfc): [TFG-F6-AN-04] adaptar lector movil al protocolo v2`
- **Impacto académico:** conserva el lector móvil como flujo secundario y evita presentarlo como sustituto del POS de Windows.

## TFG-F6-RV-01 — Revisar integraciones y flujos secundarios

- **Fecha:** 2026-10-14
- **Fase:** 6 — Flujos móviles secundarios e integraciones
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** la revisión de fase detectó registro HTTP de cuerpos sensibles, precedencia incorrecta de configuración externa y una referencia literal en el acceso móvil a Mercado Pago.
- **Comportamiento anterior:** OkHttp registraba cuerpos completos; propiedades vacías rastreadas anulaban variables de entorno; la UI podía intentar una integración externa sin una habilitación Android explícita.
- **Comportamiento nuevo:** OkHttp no instala un interceptor de registro ni conserva esa dependencia; las variables de entorno prevalecen sobre propiedades locales, la URL HTTPS del API es externa y usa un dominio inerte por defecto, y Mercado Pago permanece deshabilitado salvo habilitación explícita sin referencias de usuario inventadas.
- **Decisión:** eliminar el interceptor de registro, dar prioridad al entorno, externalizar la URL del API y mantener la integración externa deshabilitada por defecto también en Android.
- **Archivos:** `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `MainActivity.kt`, `network/RetrofitClient.kt`, `utils/NetworkUtils.kt`, `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- **Contratos o datos afectados:** configuración de URL y credenciales Android, habilitación explícita de recargas externas y política de registro HTTP.
- **Pruebas:** 24 pruebas JVM, APK debug, compilación de pruebas instrumentadas y lint Android correctos; 33 pruebas backend correctas en bases temporales. Búsquedas estáticas sin interceptor `BODY`, trazas de pila, referencia de recarga literal ni claves privadas rastreadas.
- **Limitaciones:** la revisión automatizada no sustituye pruebas físicas entre dispositivos ni con ACR122U; las recargas externas reales no se ejecutaron y continúan fuera de la demostración.
- **Commit:** `fix(review): [TFG-F6-RV-01] cerrar integraciones moviles seguras`
- **Impacto académico:** permite describir los canales secundarios como implementación comprobada en código y simuladores, separada de la evidencia física pendiente.

## TFG-F7-AN-01 — Consolidar evidencia final Android

- **Fecha:** 2026-10-14
- **Fase:** 7 — Evidencia final y documentación académica
- **Estado:** COMPROBADO
- **Hallazgo o requisito:** repetir compilación, pruebas y lint con el entorno identificado, y ejecutar instrumentadas y NFC físico solo cuando existan dispositivos compatibles.
- **Comportamiento anterior:** cada fase tenía evidencia propia, pero faltaba un cierre final con inventario explícito del entorno disponible.
- **Comportamiento nuevo:** el cierre identifica JBR 21.0.10, repite suite JVM, APK, compilación instrumentada y lint, y registra por consulta directa que ADB no tiene dispositivos y Windows no presenta lectores de tarjetas inteligentes.
- **Decisión:** ejecutar la suite JVM, APK, compilación instrumentada y lint con JBR 21/SDK local; consultar ADB y hardware antes de atribuir pruebas físicas.
- **Archivos:** `AGENTS.md`, `REGISTRO_CAMBIOS.md`; no cambia código Android en esta unidad.
- **Contratos o datos afectados:** ninguno.
- **Pruebas:** 24 pruebas JVM, APK debug, compilación de pruebas instrumentadas y lint correctos con JBR 21.0.10 y SDK 36.1 configurado mediante `ANDROID_HOME`; ADB informó cero dispositivos y Windows cero lectores presentes.
- **Limitaciones:** la ausencia comprobada de dispositivo, emulador o ACR122U impide ejecutar pruebas instrumentadas y de interoperabilidad; `local.properties` conserva una ruta SDK inexistente, compensada en las verificaciones mediante `ANDROID_HOME`.
- **Commit:** `test(evidence): [TFG-F7-AN-01] consolidar verificacion android`
- **Impacto académico:** diferencia evidencia de compilación/simulación de la validación física pendiente.


## TFG-C0-AN-01 — Preparar correcciones de auditoría

- **Fecha:** 2026-10-14
- **Fase:** C0
- **Estado:** COMPROBADO
- **Hallazgos:** R31, R32; matriz de seguimiento R01–R34.
- **Anterior:** cierre previo con pruebas parciales y defectos de integración diagnosticados.
- **Nuevo previsto:** línea base explícita, verificaciones aisladas y contrato v3 planificado.
- **Decisión:** conservar datos y evidencia v2; commits locales, sin push ni pagos reales.
- **Base Git:** `afa7dd1`, árbol limpio antes de la unidad.
- **Archivos:** AGENTS.md, REGISTRO_CAMBIOS.md y guía de pruebas aisladas.
- **Contratos/datos:** ninguna migración ejecutada; v3 todavía no implementado.
- **Pruebas:** revisión independiente C0 aprobada; estados Git y diff-check correctos; hashes de ambas bases y ambos Word conservados. Backend completa 43 pruebas con CPython 3.13.13 en base temporal y reloj histórico explícito; pyscard 2.3.1 importado sin acceder al lector. Android solo cambia guías/instrucciones y no requiere repetir el build de código.
- **Limitaciones:** ACR122U y Android físico no disponibles.
- **Commit:** `chore(audit): [TFG-C0-AN-01] preparar correcciones trazables`
- **Impacto académico:** separar auditoría, implementación y evidencia; no actualizar Entrega 4.


## TFG-C1-AN-01 — Contrato firmado v3 interoperable

- **Fecha:** 2026-10-14
- **Fase:** C1
- **Estado:** COMPROBADO
- **Hallazgos:** R01, R02, R09, R16, R20, R33, R34.
- **Anterior:** sobres v2 incompatibles por escape `\u003d` de Gson y ausencia de autorización verificable.
- **Nuevo previsto:** contrato v3 estricto y autorización/cierre/resolución firmados; compatibilidad v2 sin re-firmar; pruebas completas cruzadas.
- **Decisión:** bytes originales, digests de payload, mensajes independientes hasta 4096 bytes, UTC segundos enteros y ECDSA P-256/DER canónico.
- **Archivos:** `app/src/main/java/com/example/prototipopagosoffline/protocol/ProtocolV3.kt`, `app/src/test/java/com/example/prototipopagosoffline/protocol/ProtocolV3Test.kt`, `app/src/test/resources/protocol_v3_vectors.json`, `app/src/main/java/com/example/prototipopagosoffline/protocol/ProtocolV2.kt`, `app/src/test/java/com/example/prototipopagosoffline/protocol/ProtocolV2Test.kt`, `REGISTRO_CAMBIOS.md`.
- **Contratos/datos:** v3 nuevo; compatibilidad exterior en v2 preservando bytes firmados; ningún blob v2 ni base existente modificada.
- **Pruebas:** 38 pruebas unitarias JVM aprobadas (`:app:testDebugUnitTest`), incluyendo 13 en `ProtocolV3Test` y 9 en `ProtocolV2Test`. Se verificaron los 7 sobres canónicos Python originales sin reserializar ni firmar; alteración exhaustiva de campos; validación controlada de curvas DER no soportadas; formato canónico sin escape HTML; capacidades SELECT con versión y límites estrictos; vinculación de autorizaciones, comprobantes y acuses; y generación del archivo de vectores `app/build/cross_language/v3_kotlin_vectors.json`, verificado íntegramente por la herramienta cruzada de Python (`CROSS_LANGUAGE_VERIFIED=7`).
- **Limitaciones:** conexión a persistencia Room y transportes HCE/NFC/TCP se completa en C3 y C5; hardware físico ACR122U e instrumentadas permanecen pendientes.
- **Commit:** `feat(protocol): [TFG-C1-AN-01] definir mensajes v3 verificables`
- **Impacto académico:** demuestra equivalencia e interoperabilidad cruzada Kotlin/Python sin depender de reconstrucción de payloads ni mutación de bytes firmados.
## TFG-C3-AN-01 — Migración Room 10→11 y política de identidad local

- **Fecha:** 2026-10-14
- **Fase:** C3
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Requisito:** conservar pendientes y separar historial por cuenta sin backup o transferencia automática de identidad y pagos.
- **Cambio:** Room pasa a versión 11 con migración aditiva para expiración/cancelación de solicitudes e identificación de billetera en historial; se mantiene la compatibilidad de Room 5→10. Android desactiva backup y transferencia de base, preferencias y archivos de pagos.
- **Archivos:** `AppDatabase.kt`, `NfcExchange.kt`, `TransactionHistory.kt`, `AndroidManifest.xml`, `data_extraction_rules.xml`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** la migración debe validarse posteriormente con `MigrationTestHelper` sobre una fixture Room v5; no se acredita ejecución instrumentada.
- **Commit:** `feat(android-db): [TFG-C3-AN-01] migrar Room 10 a 11 y bloquear backup de pagos`
## TFG-C3-AN-02 — Recuperación de identidad y solicitudes previas a emisión

- **Fecha:** 2026-10-14
- **Fase:** C3
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Cambio:** una clave ya registrada que desapareció del Keystore se retira y fuerza recuperación en vez de regenerarse con el mismo ID; las solicitudes guardan vencimiento y solo pueden expirar o cancelarse antes de emitir comprobante; el historial ofrece consultas por billetera y separa registros heredados sin propietario.
- **Archivos:** DAO de autorización, NFC e historial; `WalletKeyManager.kt`, `OfflinePaymentCoordinator.kt`, `RoomNfcExchangeStore.kt`, `NfcProtocolEngine.kt`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** schema Room 11 y ejecución de migración quedan para la etapa de validación agrupada.
- **Commit:** `feat(android-state): [TFG-C3-AN-02] completar recuperación y estados previos`
## TFG-C5-AN-01 — Activaciones HCE, límites TCP y colas por identidad

- **Fecha:** 2026-10-14
- **Fase:** C5
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Cambio:** HCE serializa APDU por activación y descarta respuestas tardías; TCP aplica conexión e inactividad de 5 segundos y plazo total de 15 segundos cerrando recursos por alcance; WorkManager fija rol y billetera/terminal en sus datos y usa una cola única por identidad, evitando que un cambio de sesión redirija trabajo pendiente.
- **Archivos:** `PaymentHCEService.kt`, `QrTcpProtocol.kt`, `SyncManager.kt`, `SyncWorker.kt`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** intercambio físico entre teléfonos, ACR122U/HCE y cancelación bajo red real quedan pendientes.
- **Commit:** `fix(transports): [TFG-C5-AN-01] aislar activaciones y colas por identidad`
## TFG-C7-DOC-01 — Estado Android consolidado

- **Fecha:** 2026-10-14
- **Fase:** C7
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Cambio:** instrucciones Android actualizadas para distinguir contrato implementado, ejecución pendiente y evidencia física inexistente; el informe académico independiente concentra el detalle sin reescribir Entrega 4.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Commit:** `docs(plan): [TFG-C7-DOC-01] consolidar estado Android pendiente de validar`
## TFG-C5-AN-02 — Flujo operativo v3 y cierre durable

- **Fecha:** 2026-10-14
- **Fase:** C5
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Cambio:** Android aprovisiona y verifica autorizaciones v3 con emisor fijado externamente, conserva el sobre, emite índices permanentes, entrega la autorización mediante APDU 0x50, produce comprobantes v3 y sincroniza con resultados separados. Room 11→13 añade evidencia y cierre durable; WorkManager congela y reintenta cierres firmados al expirar.
- **Archivos:** configuración Gradle, Room/DAO, DTO/API, protocolo HCE, coordinador, aprovisionador, sincronización y `OfflineAuthorizationCloser.kt`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** schemas Room 11–13, compilación, ejecución Android, Keystore y hardware quedan pendientes.
- **Commit:** `feat(v3): [TFG-C5-AN-02] integrar autorizacion y cierre v3`

## TFG-C3-AN-03 — Aislar saldo, confianza e historial por identidad

- **Fecha:** 2026-10-14
- **Fase:** C3
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Hallazgos:** R06, R09, R10, R20 y R21.
- **Anterior:** el refresco reemplazaba la fila completa de billetera, el inventario local no conservaba el alcance de terminal/billetera, un acuse tardío no podía adjuntarse a un resultado final y la pantalla leía únicamente el historial heredado.
- **Nuevo:** el saldo confirmado se actualiza sin copiar ni reemplazar reservas; Room 13→14 agrega el alcance de las claves confiables, revoca el inventario anterior antes de aplicar el nuevo y v3 exige que la clave POS corresponda a la terminal firmante. Los acuses tardíos se anexan sin cambiar estados financieros finales y el historial de cliente incorpora operaciones v3 de su billetera, manteniendo separados los registros heredados.
- **Decisión:** conservar estados y reservas mediante actualizaciones SQL condicionadas y tratar el inventario descargado como snapshot de confianza por propósito y alcance.
- **Archivos:** `MainActivity.kt`, `database/AppDatabase.kt`, `WalletDao.kt`, `NfcExchange.kt`, `NfcExchangeDao.kt`, `network/SyncItem.kt`, `protocol/ClientPaymentExchangeService.kt`, `NfcProtocolEngine.kt`, `RoomNfcExchangeStore.kt`, `utils/OfflineProvisioner.kt`, `MerchantProvisioner.kt` y adaptación de dobles de prueba a la nueva interfaz.
- **Contratos/datos:** migración aditiva Room 13→14; `request_id` idempotente al solicitar autorización; respuesta de saldo expone revisión contable.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** schema Room 14, compilación, migración real, Keystore y dispositivos quedan para validación agrupada.
- **Commit:** `fix(android-state): [TFG-C3-AN-03] aislar confianza saldo e historial`
- **Impacto académico:** completa la programación de C3 sin presentar la migración ni el recorrido móvil como comprobados.

## TFG-C5-AN-03 — Exigir resolución final firmada

- **Fecha:** 2026-10-14
- **Fase:** C5
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Hallazgos:** R03 y R10; liberación o liquidación local basada solamente en estados HTTP.
- **Anterior:** la cola v3 normalizaba `ACCEPTED` o `REJECTED` sin validar una resolución del emisor, y podía consultar el endpoint v2 durante una entrega v3 pendiente.
- **Nuevo:** el resultado v3 transporta la resolución final firmada; Android comprueba emisor fijado, firma, participante, importe, autorización y digests de solicitud/comprobante antes de liquidar o liberar. Una resolución ausente o inválida se conserva como reintentable, y una operación v3 pendiente nunca cae al sondeo v2.
- **Archivos:** `network/SyncItem.kt`, `sync/V2SyncRepository.kt` y `REGISTRO_CAMBIOS.md`.
- **Contratos/datos:** extensión compatible de respuesta v3 con `resolution_envelope_b64`; sin migración Room adicional.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** compilación y recorridos de pérdida/reintento quedan para validación agrupada.
- **Commit:** `fix(sync): [TFG-C5-AN-03] exigir resolucion final firmada`
- **Impacto académico:** la reserva solo cambia por evidencia final verificable en el flujo v3 programado.

## TFG-C5-AN-04 — Migrar QR/TCP y lector móvil al contrato v3

- **Fecha:** 2026-10-14
- **Fase:** C5/C6
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Hallazgos:** R09, R20, R23, R25 y R27; los canales móviles secundarios todavía generaban pagos nuevos con v2.
- **Anterior:** QR/TCP y el lector NFC móvil enviaban solicitud, comprobante y acuse v2; el comercio no recibía ni persistía la autorización firmada separada.
- **Nuevo:** el comercio móvil genera solicitudes v3; QR/TCP transmite primero la autorización y luego el comprobante dentro del mismo plazo total, y NFC recupera la autorización mediante APDU `0x50` antes del comprobante. El coordinador valida emisor, alcance de billetera, vínculos y firmas, persiste autorización/comprobante/acuse y su cola sincroniza exclusivamente por `/api/v3`, aceptando estados finales solo con resolución firmada.
- **Decisión:** conservar las interfaces y pantallas existentes, agregando el sobre de autorización como frame/APDU separado y migración Room 14→15.
- **Archivos:** Room y DAO de operaciones POS móviles, `MobilePosCoordinator.kt`, `QrTcpProtocol.kt`, `MobileNfcReader.kt`, `PagarQrScreen.kt`, `MobilePosSyncRepository.kt` y adaptación de dobles de prueba.
- **Contratos/datos:** Room 15 agrega `authorization_envelope` a operaciones POS móviles; descriptor y frames secundarios usan versión 3.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** suites, migración, dos teléfonos, red local y NFC físico quedan para validación agrupada.
- **Commit:** `feat(transports): [TFG-C5-AN-04] migrar canales moviles a v3`
- **Impacto académico:** los canales secundarios dejan de crear evidencia v2 nueva; v2 queda para recuperación histórica controlada.

## TFG-C7-DOC-02 — Cerrar programación Android sin atribuir ejecución

- **Fecha:** 2026-10-14
- **Fase:** C7
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Cambio:** se actualizan instrucciones y registros para reflejar Room 15, confianza por alcance, resoluciones firmadas y transportes v3, conservando por separado toda evidencia anterior.
- **Archivos:** `AGENTS.md`, registros consolidados y `REGISTRO_CAMBIOS.md`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** no se afirma compilación, migración, ejecución en teléfonos ni interoperabilidad física.
- **Commit:** `docs(corrections): [TFG-C7-DOC-02] cerrar programacion android pendiente`
- **Impacto académico:** ofrece una fuente vigente sin modificar la Entrega 4 presentada.

## TFG-C3-AN-04 — Registrar inventario histórico v2

- **Fecha:** 2026-10-14
- **Fase:** C3
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Requisito:** inventariar comprobantes v2 conservados antes de conciliarlos mediante compatibilidad histórica.
- **Anterior:** los comprobantes locales v2 no tenían constancia durable de haber sido registrados en el inventario del servidor.
- **Nuevo:** Room 16 identifica cada comprobante histórico pendiente; durante el aprovisionamiento autenticado envía lotes acotados con UUID, autorización y digest, y marca localmente solo los lotes aceptados por completo.
- **Archivos:** `database/AppDatabase.kt`, `NfcExchange.kt`, `NfcExchangeDao.kt`, `network/ApiService.kt`, `SyncItem.kt`, `utils/OfflineProvisioner.kt` y `REGISTRO_CAMBIOS.md`.
- **Contratos/datos:** migración aditiva Room 15→16 con `legacy_inventory_registered`; consumo de `/api/v3/legacy-inventory`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** requiere que el operador abra temporalmente la ventana de inventario del backend; compilación, migración y datos históricos reales quedan pendientes.
- **Commit:** `feat(migration): [TFG-C3-AN-04] registrar inventario v2`
- **Impacto académico:** conserva bytes y firmas históricas sin convertir pagos v2 en operaciones nuevas.

## TFG-C7-DOC-03 — Registrar Room 16 y compatibilidad histórica

- **Fecha:** 2026-10-14
- **Fase:** C7
- **Estado:** IMPLEMENTADA / VALIDACIÓN PENDIENTE
- **Cambio:** instrucciones y registro consolidado reflejan Room 16 y el inventario histórico previo a la conciliación v2.
- **Archivos:** `AGENTS.md`, registros académicos y `REGISTRO_CAMBIOS.md`.
- **Pruebas:** no ejecutadas por decisión del usuario.
- **Limitaciones:** la migración, compilación y conciliación histórica permanecen pendientes.
- **Commit:** `docs(corrections): [TFG-C7-DOC-03] registrar compatibilidad historica`
- **Impacto académico:** evita presentar la compatibilidad programada como evidencia ejecutada.
## TFG-C8-AN-01 — Preparación para prueba manual

- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Requisito: corregir bloqueos de compilación, APDU v3, aprovisionamiento y recuperación.
- Validación autorizada: solo compilación Android y sintaxis Python; sin pruebas ni arranque.
- Datos existentes: conservar; configuración y demostración nuevas fuera de Git.
- Anterior: hasKey fuera de clase impedía compilar; APDU 0x50 rechazado; Room/UI leían solicitudes v3 como v2; botón de recarga dependía de integración externa.
- Nuevo: claves compilan; NFC transmite autorización; solicitudes vinculadas a cuenta y parseadas por versión; recarga simulada autenticada de $1.000 con UUID durable; trust histórico fijado; reservas e índices no se rehabilitan por rechazo; sockets se cierran por plazo/cancelación.
- Archivos: `MainActivity.kt`, entidades/DAO afectados, DTO/API, `ProtocolV2.kt`, `ClientPaymentExchangeService.kt`, `RoomNfcExchangeStore.kt`, `IssuerTrust.kt`, `QrTcpProtocol.kt`, repositorios sync, coordinadores, provisioner, TokenManager, WalletKeyManager, PaymentHCEService, SimulatedTopupService, Gradle y schema Room 16.
- Datos: conserva Room 16 y migraciones 5→16; autorización existente se inserta con IGNORE para no restablecer consumos; solicitud de autorización y recarga mantienen UUID por cuenta ante respuesta perdida.
- Entorno: SDK local corregido en archivo ignorado; JBR 21. `assembleDebug` y `assembleDebugAndroidTest` correctos tras cambios finales; schema Room 16 exportado. Avisos de deprecación y condiciones redundantes no impiden compilar.
- Pruebas: ninguna ejecutada; instrumentadas solo se compilan.
- Limitaciones: compilación con configuración inerte mientras el usuario proporciona URL; Keystore, Room, dos teléfonos, contabilidad y hardware pendientes.
- Commit: `fix(manual): [TFG-C8-AN-01] preparar ejecucion controlada`.
- Impacto académico: habilita preparación manual sin convertir compilación en evidencia de pagos.
## TFG-C8-AN-02 — APK configurado para demostración manual

- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Requisito: compilar con dominio fijo, credenciales demo externas e identidad emisora correspondiente.
- Verificación: solo compilación; no pagos ni pruebas.
- Commit: `feat(demo): [TFG-C8-AN-02] aislar apk de demostracion`.
- Decisión: build debug opcional aislado `.manual` activado únicamente por launcher demo; mismo código y contrato, datos separados de la instalación anterior.
- Archivos: app/build.gradle.kts, AndroidManifest.xml, registro e instrucciones; credenciales y claves de preparación permanecen fuera de Git.
- MainActivity: Actualizar también encola conciliación de cliente; mensaje de sincronización sin versión técnica.
- Configuración real: dominio fijo y cuentas/identidad de la base externa nueva. Valores sensibles nunca se incluyen en registros.
- Verificación: assembleDebug y assembleDebugAndroidTest correctos; valores de configuración comprobados por presencia sin imprimir credenciales. Instrumentadas no ejecutadas.
- Datos: misma versión Room 16, sin alteración de bytes históricos ni bases originales; APK demo en carpeta externa.
## TFG-C8-AN-03 — Limpieza recuperable de temporales Android

- Fecha: 2026-10-14. Estado: COMPLETADA / RECUPERABLE.
- Requisito: retirar intermediarios generados y logs de fallo antiguos.
- Protección: conservar fuentes, schemas, Gradle/SDK/IDE vigente y APKs finales; no compilar ni ejecutar pruebas por limpieza.
- Método: mover a archivo externo recuperable, con rutas verificadas y manifiesto; no cerrar procesos ni borrar datos.
- Resultado: .kotlin (incluido su log rastreado errors-1780496860256.log), build raíz y app/build intermediates/tmp/kotlin/kspCaches/generated archivados; hs_err_pid10752.log retirado del checkout con respaldo y patrón Git para futuros logs JVM.
- Archivo externo: `C:\Users\anita\TFG-Archivo-Limpieza\2026-10-14-limpieza-01`; registro general y manifiesto. APKs finales, fuentes, schemas, .idea y .gradle vigentes conservados.
- Verificación: clasificación y límites de rutas, estado Git y diff; no pruebas ni compilación posterior. Las cachés se regeneran al compilar.
- Commit: `chore(cleanup): [TFG-C8-AN-03] registrar limpieza de temporales`.

## TFG-C8-AN-04 — Identidad comercial por instalación

- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Fallo observado: login 200 seguido de registro de clave 409 en el segundo teléfono; terminal y key_id derivados solo del usuario colisionan entre Keystores independientes.
- Corrección prevista: conservar perfil local previo; terminal UUID para aprovisionamientos nuevos, persistido antes de enviar. No reemplazar claves registradas ni datos del servidor.
- Verificación autorizada: compilación e instalación, sin pruebas ni pagos.
- Commit: fix(android): [TFG-C8-AN-04] separar terminales comerciales por instalación.
- Archivos: MerchantProvisioner.kt, TerminalKeyManager.kt, AGENTS.md y registros. Sin migración Room ni cambios del backend.
- Verificación: assembleDebug y assembleDebugAndroidTest correctos (BUILD SUCCESSFUL); sin ejecutar tests ni pagos. APK demo externo actualizado.
- Instalación: adb install -r devolvió Success en Samsung SM-G935F; sin borrar datos. Login corregido pendiente del reintento del usuario.

## TFG-C8-AN-05 — Acceso de demostración con un toque
- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Requisito: reemplazar dependencia de credenciales al compilar por identidad aleatoria persistida cifrada por instalación/rol. No borrar identidades ni pendientes previos.
- Configuración Android Studio: URL y emisor público externos; sin contraseñas embebidas. Sin pruebas, solo compilación.
- Commit: feat(android): [TFG-C8-AN-05] ingresar en demo sin contraseña manual.
- Archivos: MainActivity.kt, ApiService.kt, TokenManager.kt, app/build.gradle.kts, .gitignore, AGENTS y registros.
- Implementación: secreto/UUID cifrados por rol se conservan tras logout; login demo sustituye credenciales embebidas. Configuración pública externa compartida con Android Studio, debug aislado .manual, Room sin cambios.
- Verificación: assembleDebug y assembleDebugAndroidTest correctos tanto por launcher como compilación directa sin variables demo. Primer intento Gradle falló por resolución de java.util.Properties; corregido con import explícito. No tests ni pagos.
- Instalación: APK demo actualizado en SM-S721B mediante adb install -r (Success), conservando datos. Otro teléfono pendiente de actualización con el mismo APK.

## TFG-C8-AN-06 — URL efectiva de Android Studio
- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Evidencia: BuildConfig generado por Android Studio contiene example.invalid pese a demo.local.properties con dominio correcto; gradle.properties tenía API_BASE_URL de ejemplo con mayor prioridad.
- Corrección: retirar override de ejemplo y bloquear dirección inerte cuando existe configuración local demo. Conservar bases, identidades y claves.
- Verificación: compilación y revisión de URL generada, sin peticiones ni pagos.
- Commit: fix(android): [TFG-C8-AN-06] usar dominio demo en Android Studio.
- Resultado: assembleDebug y assembleDebugAndroidTest correctos (BUILD SUCCESSFUL). URL generada contrastada exactamente con dominio ngrok y applicationId .manual; compilación directa sin variables demo.
- Archivos: gradle.properties, app/build.gradle.kts, AGENTS.md y registros. Sin pruebas, arranque, pagos ni instalación; el usuario instalará desde Android Studio.

## TFG-C8-AN-07 — Saldo confirmado del comercio
- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Evidencia: usuario observa ACCEPTED definitivo en backend y caja cero. MainActivity muestra PaymentState.merchantBalance volátil; sincronización comercial no refresca saldo.
- Corrección: consultar saldo confirmado por cuenta comercial, persistir Room y observarlo en pantalla al ingresar y después de conciliar. No modificar contabilidad ni reenviar pagos manualmente.
- Verificación autorizada: compilación, sin pruebas ni instalación; usuario usa Android Studio.
- Commit: fix(android): [TFG-C8-AN-07] refrescar saldo comercial conciliado.
- Archivos: MainActivity.kt, SyncWorker.kt, MerchantBalanceRepository.kt, AGENTS y registros. Room sin migración; insert IGNORE y UPDATE de confirmado conservan reservas.
- Diagnóstico: lectura SQLite mode=ro sobre demo externa mostró 200000 centavos confirmados para usuario comercial 2. No se alteró su saldo ni se ejecutaron pagos.
- Verificación: assembleDebug y assembleDebugAndroidTest correctos; diff revisado. Sin tests ni instalación. Actualización del comercio debe conservar su sesión usuario 2 para ver ese saldo.

## TFG-C8-AN-08 — Recuperación automática de conciliación
- Fecha: 2026-10-14. Estado: COMPILA / FUNCIONAMIENTO PENDIENTE DE PRUEBA MANUAL.
- Requisito: conciliar al recuperar Internet sin botón, en ambos roles y transportes.
- Fallo estático: cadena única con tareas demoradas/backoff podía bloquear nuevos avisos; sin observador de Internet validado ni recuperación periódica. Cancelaciones de red absorbidas por runCatching.
- Diseño: cola inmediata durable por cuenta, despertador separado para reintentos, recuperación periódica y callback validado de red; mutex por cuenta y API ligada al token capturado. Sesión inválida conserva evidencia y requiere reingreso.
- Verificación: solo compilación, sin pruebas ni instalación. Commit: feat(sync): [TFG-C8-AN-08] conciliar al recuperar Internet.
- Archivos: PaymentsApplication.kt, AndroidManifest.xml, AutoSyncMonitor.kt, SyncManager.kt, SyncRecoveryWorker.kt, SyncWorker.kt, SyncHttp.kt, RetrofitClient.kt, TokenManager.kt, MainActivity.kt, PagarQrScreen.kt, repositorios de conciliación/cierre/saldo comercial, guía y registros.
- Resultado: compilaciones assembleDebug y assembleDebugAndroidTest correctas tras cambios finales; sin test, lint, simuladores, pagos, instalación ni cambios de base.
- Revisión estática: condiciones de Internet, identidades de trabajos, token capturado, exclusión mutua, callbacks, cancelación, fronteras de reintento, reserva y actualización de confirmado. Sin migraciones Room, cambios de bytes firmados ni backend.
- Errores HTTP definitivos de contrato/configuración requieren atención, 401/403 reingreso; pausas asociadas a digest de token. Logs solo códigos, rol y contadores.
- Límites: Android puede diferir ejecución; recovery cada 15 min no es SLA, force-stop exige reapertura; serialización se limita al proceso. Sesión activa requerida y cuentas históricas no se remapean.
- Entrega: APK externo actualizado; usuario instalará desde Android Studio. Commit local, sin push. Intercambio QR/NFC informado por usuario antes de esta unidad no acredita recuperación automática posterior.

## TFG-C8-AN-09 — Instrucciones Android actualizadas
- Fecha: 2026-10-15. Estado: COMPROBADO DOCUMENTAL / SIN NUEVA VALIDACIÓN FUNCIONAL.
- Requisito: instrucciones breves y coherentes con Room 16, v3, acceso individual demo, configuración Studio y sincronización automática. Separar compilaciones verificadas y pruebas manuales informadas de suites/hardware pendientes.
- Verificación: lectura de fuentes y registros, estado Git y diff. Sin pruebas, compilación ni instalación.
- Commit: docs(agents): [TFG-C8-AN-09] actualizar contexto Android y evidencia.
- Resultado: AGENTS del área consolidado, sin instrucciones contradictorias de versiones ni garantías heredadas; evidencia manual y pendientes separados. Se ajustó contexto compartido general y registro consolidado.
- Archivos del commit: AGENTS.md y REGISTRO_CAMBIOS.md. No código, configuración, datos ni entregas históricas. Revisados fuentes seleccionadas y diff; ninguna prueba ni compilación ejecutada.

## TFG-C8-AN-10 — Publicación autorizada en GitHub

- Fecha: 2026-10-19. Estado: COMPROBADO LOCAL / PUBLICACIÓN AUTORIZADA.
- Requisito: subir commits locales con descripciones específicas en español. La autorización actual reemplaza la restricción previa Sin push solo para esta publicación.
- Estado inicial: main limpio; origin/main actualizado mediante fetch diverge en dos commits remotos de julio. No modificar datos, código funcional ni historial publicado.
- Integración prevista: conservar árbol funcional local mediante merge ours sin reescritura; preservar commits remotos como ancestros y archivar sus documentos como antecedentes no vigentes. No importar código heredado incompatible, ._* ni herramientas .agents ajenas al runtime.
- Verificación: inspección de cambios y secretos sin reproducir valores, diff y comprobación de referencias Git. Sin pruebas, compilación, instalaciones, arranques ni pagos.
- Asunto exacto: `docs(publicacion): [TFG-C8-AN-10] publicar pagos locales y sincronización automática`.
- Corte preservado: b555c8c; remoto integrado: 1b70c7c. Revisión independiente de compatibilidad e inspección heurística de secretos sin valores. No se detectaron secretos nuevos por los patrones revisados; no equivale a garantía absoluta.
- Archivos: PUBLICACION_GITHUB_2026-10-19.md, antecedentes/github_2026-07-06/*.md, AGENTS.md y este registro. Todos los cambios del árbol de esta unidad son Markdown; código funcional y datos conservados.
- Resultado: merge con historia completa preparado, referencias remotas verificables y mensajes de commit detallados en español. Confirmación de subida a origin/main se comunicará tras git push y contraste de hash remoto.
- Antecedentes copiados byte a byte: conservan espacios finales históricos señalados por diff --check. Se verifica limpieza del resto del diff; no se reescriben originales para eliminar esos avisos.

## TFG-C8-AN-11 — Inventario de limpieza y retirada de APK

- Fecha: 2026-10-19. Estado: PARCIAL: INVENTARIO COMPLETO / BORRADO APK BLOQUEADO.
- Solicitud: inventariar ambas carpetas, clasificar eliminar/archivar/conservar/investigar y borrar APK porque el usuario instala desde Android Studio.
- Alcance: medir archivos y referencias estáticas; borrar únicamente APK generados identificados, incluido APK externo preparado en TFG-Demo-Manual. No borrar código, datos, claves, entornos, migraciones, pruebas ni cachés en esta unidad.
- Preservación: registrar estados Git y rutas/tamaños antes de borrar; sin tests, compilación, instalación, servicios, migraciones o pagos. La publicación anterior no autoriza push de estos cambios nuevos.
- Resultado: inventario por área y general con tamaños, candidatos y consumidores. Código y scripts solo clasificados. Revisión automática bloqueó borrado de los cuatro APK en dos comandos; todos siguen presentes, sin espacio liberado ni eliminación alternativa.
- Archivos del commit: INVENTARIO_LIMPIEZA_2026-10-19.md, AGENTS.md y REGISTRO_CAMBIOS.md; informes/registro general y académico fuera de estos repos. Solo documentación; sin pruebas, compilación ni instalación.
- Asunto exacto: docs(limpieza): [TFG-C8-AN-11] inventariar Android y documentar bloqueo de APK.

## TFG-C10-AN-01 — Acceso separado de la preparación de cupo
- Fecha: 2026-10-20.
- Fase: C10 — Recuperación de sesión y preparación explícita.
- Estado: IMPLEMENTADA / VALIDACIÓN PENDIENTE.
- Hallazgos: AUD-01, AUD-04, AUD-06, AUD-12.
- Requisito: conservar sesión válida aunque no haya cupo nuevo; permitir que la renovación se recupere sin duplicar autorizaciones ni perder reservas.
- Cambio: bootstrap automático solo sin historial; sesión guardada tras login; acción manual Cupo para renovar, recuperar evidencias vencidas y cerrar el manifiesto durable antes de solicitar el saldo libre. El request_id queda persistido para reintentos idénticos. La migración 16→17 agrega reanudación de emisión, payload de cierre, persistencia de evidencia del servidor y resolución firmada. Las resoluciones finales se guardan en la misma transacción que el estado financiero; pagos v3 exigen resolución firmada. V2 mantiene su ruta de recuperación heredada.
- Archivos: `MainActivity.kt`, `AppDatabase.kt`, `AuthorizationDao.kt`, `LocalOfflineAuthorization.kt`, `NfcExchange.kt`, `NfcExchangeDao.kt`, `SyncItem.kt`, `OfflineAuthorizationCloser.kt`, `SyncWorker.kt`, `V2SyncRepository.kt`, `AccountOperationLocks.kt`, `OfflineProvisioner.kt`, `SimulatedTopupService.kt`, schema 17, `AGENTS.md`, `REGISTRO_CAMBIOS.md`.
- Verificación: `:app:assembleDebug` y `:app:assembleDebugAndroidTest` terminaron correctamente con JBR 21. La instrumentada solo se compiló, no se ejecutó. No se realizaron tests, lint, instalación, migraciones sobre datos ni pagos.
- Límites: compilación no acredita migración 16→17, cierre criptográfico, renovación, conciliación física ni preservación de datos en dispositivo. Queda validación manual/agrupada. Warnings Kotlin preexistentes visibles en MainActivity y OfflinePaymentCoordinator.
- Commit: `fix(android): [TFG-C10-AN-01] separar sesión de renovación offline`.
- Impacto académico: autenticación no depende de obtener cupo; el usuario puede renovar usando fondos libres y conserva respaldados los pagos emitidos pendientes.

## TFG-C12-AN-03 — Recuperar acuses tardíos
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo: una solicitud conocida quedaba rechazada por vencimiento al reenviar el acuse.
- Alcance: reanudar solo comprobante idéntico persistido de la cuenta activa.
- Commit: `fix(nfc): [TFG-C12-AN-03] reanudar acuses de solicitudes conocidas`.
- Antes: vencimiento bloqueaba reanudación aunque ya hubiera comprobante. Ahora: solo solicitud idéntica firmada de cuenta activa y comprobante guardado atraviesa esa barrera; se devuelve estado persistido. Confirmar consulta la cuenta antes de devolver comprobante existente.
- Archivos: `protocol/ClientPaymentExchangeService.kt`, `protocol/RoomNfcExchangeStore.kt`, `utils/OfflinePaymentCoordinator.kt`, este registro. NFC y QR/TCP usan el mismo servicio; no se alteran APDU ni firma. Verificación: compilación; pérdida física de acuse pendiente.

## TFG-C12-AN-02 — Cerrar cupos agotados
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo: diez pagos pequeños podían bloquear fondos sin emitir hasta el vencimiento de 24 horas.
- Alcance: cierre explícito anticipado, sin liberar comprobantes pendientes.
- Commit: `fix(cupo): [TFG-C12-AN-02] cerrar autorizaciones agotadas`.
- Antes: solo se cerraba al vencer. Ahora: la acción explícita «Cupo» incluye autorizaciones sin índices o importe disponible, sincroniza comprobantes y reutiliza el manifiesto congelado; WorkManager sigue cerrando solo por vencimiento.
- Archivos: `database/AuthorizationDao.kt`, `sync/OfflineAuthorizationCloser.kt`, `utils/OfflineProvisioner.kt`, este registro. Sin cambio de base ni sobre firmado. Verificación: compilación; pendiente diez pagos pequeños y reserva sin acuse.

## TFG-C12-AN-01 — Recuperar solicitud de autorización anterior
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo: al migrar a Room 17 se olvidaba el ID pendiente de preferencias cifradas.
- Alcance: conservar ID histórico, recuperarlo explícitamente y persistir el grant antes de limpiarlo.
- Commit: `fix(cupo): [TFG-C12-AN-01] recuperar solicitudes previas a Room 17`.
- Antes: se generaba otro UUID al migrar. Ahora: «Cupo» lee el ID previo sin crearlo, lo prioriza frente a otro intento Room y limpia preferencias solo tras guardar un grant firmado; si está vencido, intenta cerrarlo y emitir uno nuevo. Login con historial no emite grants.
- Archivos: `utils/TokenManager.kt`, `utils/OfflineProvisioner.kt`, este registro. Contrato: mismo POST v3 y parámetros históricos 100000 centavos/10 pagos. Verificación: compilación; recuperación sobre una instalación previa pendiente.

## TFG-C12-CO-01 — Respetar resoluciones financieras finales
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo: los sincronizadores priorizaban el conflicto de envío aun con resolución financiera final válida.
- Alcance: verificar resolución vinculada antes de clasificar el envío.
- Commit: `fix(conciliacion): [TFG-C12-CO-01] respetar resoluciones finales firmadas`.
- Antes: CONFLICT de envío tapaba ACCEPTED/REJECTED. Ahora: la resolución vinculada y firmada decide el estado financiero; su código firmado prevalece. No se altera el contrato HTTP.
- Archivos: `sync/V2SyncRepository.kt`, `sync/MobilePosSyncRepository.kt`, este registro. Verificación: compilación APK e instrumentadas; no se ejecutaron pruebas ni pagos. Falta comprobar con un reenvío contradictorio real.

## TFG-C12-CO-02 — Conservar y recuperar resoluciones finales
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo: comercio móvil perdía la prueba firmada; pagos históricos finales podían carecer de ella.
- Alcance: migración aditiva Room 17→18, persistencia atómica de resolución y consulta de evidencia histórica sin repetir movimientos.
- Commit: `fix(evidencia): [TFG-C12-CO-02] recuperar resoluciones finales sin mover saldos`.
- Antes: comercio móvil no guardaba prueba final y finales antiguos no la recuperaban. Ahora: Room 17→18 añade resolución y estado PENDING/COMPLETE/ATTENTION_REQUIRED; comercio la guarda junto al final. Cliente/comercio consultan GET v3 para adjuntar solo evidencia verificada a finales sin blob, hasta 100 por cuenta/terminal. 404 o prueba inválida requieren atención individual; errores transitorios reintentan. «Actualizar/Sincronizar» permite reintento explícito.
- Archivos: `database/AppDatabase.kt`, entidades/DAO de intercambios y comercio, schema `18.json`, `network/ApiService.kt`, `sync/V2SyncRepository.kt`, `sync/MobilePosSyncRepository.kt`, `sync/SyncManager.kt`, `sync/SyncWorker.kt`, `MainActivity.kt`, `AGENTS.md`, este registro.
- Verificación: `assembleDebug` y `assembleDebugAndroidTest` completaron; instrumentadas no ejecutadas. Room 17→18 en datos existentes y recuperación histórica siguen pendientes. No desinstalar ni degradar APK como método de recuperación.

## TFG-C13-AN-01 — Estado visible del pago NFC
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo manual: cliente queda en «Pago reservado» después del acuse recibido por NFC; QR sí muestra aceptación local.
- Alcance: observar la operación persistida de la cuenta activa y presentar espera, aceptación local o resolución financiera según su estado. Sin modificar reservas, firmas ni transporte.
- Antes/ahora: se observaban únicamente solicitudes REQUEST_RECEIVED y quedaba un mensaje volátil; ahora la operación seleccionada se observa en Room por ID/billetera tras reservar. Muestra espera, acuse verificado o resolución final, priorizando rechazo/revisión. El ID de selección NFC por cuenta permite volver del menú sin elegir por error un pago QR; limpiar selección no altera evidencia.
- Archivos: `MainActivity.kt`, `database/NfcExchangeDao.kt`, `utils/TokenManager.kt`, este registro. Sin cambio de esquema, APDU, firmas o contabilidad.
- Verificación: revisión estática de la ruta del acuse y filtros; `:app:assembleDebug` y `:app:assembleDebugAndroidTest` completaron con JBR 21. Instrumentadas solo compiladas; sin ejecutar pruebas ni pagos.
- Límite: confirmar visualmente segundo acercamiento, regreso desde menú y acuse tardío en teléfonos. No se declara interoperabilidad ACR122U.
- Impacto académico: separa entrega incierta, aceptación local y conciliación demostrada por resolución; corrección de presentación sin mover fondos.
- Commit: `fix(nfc): [TFG-C13-AN-01] mostrar el resultado persistido al cliente`.

## TFG-C13-AN-02 — Preparación offline automática
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Solicitud: retirar «Cupo» y «Actualizar»; preparar/renovar autorizaciones automáticamente con Internet. Esta decisión reemplaza la renovación explícita acordada en C10–C12.
- Alcance: integrar recuperación idempotente, conciliación y cierre conservador en el trabajo por cuenta; preservar respaldos de pagos emitidos y no renovar si el cierre requiere revisión.
- Antes/ahora: renovación explícita desde Cupo y refrescos manuales en menú; ahora login/trabajo cliente y recarga activan mantenimiento por cuenta: conciliación, cierre de vencidas/agotadas y emisión sobre saldo libre. Se retiran Cupo/Actualizar/Sincronizar del menú. Presupuesto cero es espera normal; revisión/acceso requieren atención. Reintentos recuperan el mismo ID y cuerpo.
- Decisión: mantener autorizaciones y límites firmados internos con preparación automática; no hacer que el saldo visual sustituya evidencia. Cierre con rechazo definitivo requiere resolución vinculada y contrapartida contable en TFG-C13-BE-01; nunca rehabilita índice ni libera pagos incompletos.
- Archivos: `utils/OfflineProvisioner.kt`, `sync/SyncWorker.kt`, `utils/SimulatedTopupService.kt`, `sync/OfflineAuthorizationCloser.kt`, `MainActivity.kt`, `SINCRONIZACION_AUTOMATICA.md`, `AGENTS.md`, este registro. Sin cambio de esquema/rutas/payload firmado.
- Verificación: revisión estática independiente del flujo de cierre/emisión, compilación APK e instrumentadas correctas; instrumentadas no ejecutadas. Sin tests, servicios, migraciones ni pagos.
- Límites: requiere sesión e Internet/servidor; callback, trabajo durable y recuperación periódica están sujetos a demoras Android. Vencimientos y diez pagos conservan límites; no se promete renovación instantánea ni prevención total de doble gasto.
- Impacto académico: sustituye la interacción explícita C10–C12 por mantenimiento transparente; evidencia funcional de esta versión queda pendiente.
- Commit: `feat(sync): [TFG-C13-AN-02] renovar autorizaciones sin intervención manual`.

## TFG-C13-AN-03 — Actividad reciente persistida por cuenta
- Fecha: 2026-10-20. Estado: IMPLEMENTADA / VALIDACIÓN MANUAL PENDIENTE.
- Hallazgo manual: actividad no accesible ni visible en cliente/comercio; menú aún consulta estado volátil heredado.
- Alcance: conectar actividad a operaciones persistidas de la cuenta/terminal y permitir su consulta; separar aceptación local y conciliación.
- Antes/ahora: lista global cargada una vez, sin cobros mobile_pos_operations y menú sin scroll. Ahora Flow por billetera o comercio/terminal verificado actualiza vista previa y pantalla Actividad con hasta 100 pagos/cobros locales, fecha, importe, estado y detalle. Se deduplica por operación con prioridad moderna; históricos sin dueño no se muestran como personales. Hay reintento contextual de prueba final que requiere atención.
- Archivos: `MainActivity.kt`, `ui/screens/PaymentHistoryScreen.kt`, `database/NfcExchangeDao.kt`, `database/MobilePosOperationDao.kt`, `database/TransactionDao.kt`, este registro. Solo consultas/representación; Room sigue en 18 sin migración nueva. No calcula saldo sumando cobros pendientes.
- Verificación: revisión estática de filtros/cuenta y compilación APK e instrumentadas correctas; sin ejecución de pruebas ni instalación.
- Límites: pagos guardados en este teléfono; no descarga historial de otros dispositivos ni atribuye registros sin dueño. Pantallas pequeñas, detalle, cambio de cuenta y actualización en vivo pendientes de validación manual.
- Impacto académico: visibilidad de estados persistidos y separación aceptación/conciliación, sin alterar datos históricos ni Entrega 4.
- Commit: `fix(historial): [TFG-C13-AN-03] consultar movimientos persistidos por cuenta`.

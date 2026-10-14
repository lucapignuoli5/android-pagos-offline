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

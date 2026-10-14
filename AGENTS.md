Leer `../../AGENTS.md` antes de actuar si el contexto general no está cargado; las rutas siguientes parten de esta carpeta.

# Aplicación Android

## Organización existente

- Kotlin, Jetpack Compose, Room, Retrofit y WorkManager; consultar versiones en `app/build.gradle.kts` y `gradle/libs.versions.toml`.
- El paquete principal está en `app/src/main/java/com/example/prototipopagosoffline/`; las rutas de código siguientes son relativas a él.
- `MainActivity.kt` reúne pantallas y lógica; `PaymentState.kt` comparte estado y `PosScreen.kt` implementa el lector NFC del comercio.
- `utils/PaymentHCEService.kt` responde mediante HCE; `utils/SecurityUtils.kt` maneja claves y firmas.
- `ui/screens/CobroQrScreen.kt` y `ui/screens/PagarQrScreen.kt` implementan QR y sockets TCP locales.
- `network/` define DTO y acceso HTTP; `database/` contiene Room, entidades y DAO; `sync/` programa y ejecuta sincronización.
- No hay una separación MVVM demostrada: mantener el diseño observado sin inventar ViewModels, Hilt ni capas adicionales.
- El QR intercambia parámetros de conexión TCP; no asumir que conecta automáticamente a Wi-Fi Direct o hotspot.
- Leer las decisiones vigentes de la raíz: Android aportará la billetera HCE al POS de PC con ACR122U elegido; conservar los flujos móviles existentes salvo cambios solicitados.
- Al integrar el POS, contrastar HCE con el consumidor PC/SC: AID, APDU, tamaños, segmentación, campos y bytes firmados requieren compatibilidad comprobada.
- La billetera debe persistir la reserva y el comprobante y poder sincronizar aunque la PC siga offline; no exigir conectividad simultánea de ambos equipos.
- La aceptación local permanece pendiente de conciliación; verificar en el código los controles previstos en la Entrega 4 antes de declararlos implementados.

## Estado implementado verificado al 14/10/2026

- El contrato v2, la máquina APDU HCE, la persistencia Room 8 de solicitudes, reservas, autorizaciones, claves y reintentos, y el aprovisionamiento autenticado compilan y tienen pruebas JVM.
- La pantalla cliente recibe del POS el comercio y el importe, exige confirmación y descuenta reservas del saldo gastable; `SELECT` no genera pagos.
- La sesión solo se restaura si conserva token, identificador y billetera local aprovisionada; cerrar sesión no elimina operaciones ni libera reservas.
- La conciliación v2 usa una única cola WorkManager durable por billetera autenticada, procesa resultados individuales y conserva reservas ante evidencia pendiente, conflicto o error transitorio.
- La migración 5→8 y las pruebas instrumentadas compilan, pero no se ejecutaron porque no hay dispositivo/emulador conectado. HCE y Android Keystore tampoco cuentan aún con validación física.
- El POS de escritorio está implementado en el repositorio backend y el flujo simulado pasa; Android descarga sus claves públicas registradas. La interoperabilidad ACR122U/HCE continúa pendiente de hardware.

## Criterios de revisión del código

- Para contratos HTTP, contrastar `network/ApiService.kt` y `network/SyncItem.kt` con `../Prototipo Pagos Offline/schemas.py` y `../Prototipo Pagos Offline/main.py`.
- Revisar rutas, autenticación, campos opcionales, tipos y conversiones de importes; la sincronización transmite centavos.
- Al cambiar NFC, contrastar HCE, lector y `app/src/main/res/xml/apduservice.xml`: AID, APDU, identificador y orden de campos.
- Preservar exactamente los bytes firmados: codificación UTF-8, formato del importe, timestamp y representación de firma y clave pública.
- No asumir que una respuesta HTTP exitosa acepta todo el lote: comprobar los resultados individuales del backend antes de considerar una operación conciliada.
- Revisar `sync/SyncWorker.kt` y los flujos de sincronización de `MainActivity.kt` cuando cambien estados, reintentos o respuestas.
- Para Room, revisar `database/AppDatabase.kt`, entidades y DAO; evaluar cómo conservar pendientes en cambios de versión y migraciones.
- Para operaciones locales, revisar persistencia antes de informar aceptación, duplicados, rechazo y recuperación después de interrupciones.
- Mantener operaciones de red y base de datos fuera del hilo de UI; seguir el uso local de coroutines y WorkManager.
- Estas comprobaciones son criterios de revisión, no garantías ya demostradas por el prototipo.

## Comandos y requisitos

- Ejecutar desde esta carpeta mediante el wrapper; no sustituirlo por un Gradle global.
- Revisar JDK, SDK y `gradle/gradle-daemon-jvm.properties`: el daemon declara Java 21 y el código usa compatibilidad Java 11.
- Compilación: `.\gradlew.bat :app:assembleDebug`.
- Pruebas unitarias: `.\gradlew.bat :app:testDebugUnitTest`; análisis Android: `.\gradlew.bat :app:lintDebug`.
- Pruebas instrumentadas: `.\gradlew.bat :app:connectedDebugAndroidTest`, con dispositivo o emulador compatible.
- Las verificaciones vigentes usan el JBR 21 de Android Studio y el SDK 36.1 local mediante `JAVA_HOME` y `ANDROID_HOME`; `local.properties` aún señala una ruta SDK inexistente.
- La suite JVM vigente contiene 16 pruebas y las pruebas instrumentadas de migración compilan; su ejecución requiere dispositivo o emulador.

## Antes de terminar

- Para cambios de pagos, probar según corresponda rechazo, duplicación, fallo de conexión, reintento y conservación de pendientes.
- Para NFC/HCE, comprobar en hardware compatible ambos roles; el emulador o un build exitoso no valida el intercambio físico.
- Informar qué flujo se verificó y qué queda pendiente; el POS de PC y la conciliación independiente están implementados, pero la interoperabilidad ACR122U/HCE requiere hardware y pruebas físicas.

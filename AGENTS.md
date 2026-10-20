Leer `../../AGENTS.md` y este archivo antes de trabajar. Esta carpeta es un repositorio Git independiente del backend.

# Aplicación Android — contexto vigente al 15/10/2026

## Mapa y alcance

- Kotlin, Jetpack Compose, Room, Retrofit y WorkManager. Versiones en `app/build.gradle.kts` y `gradle/libs.versions.toml`.
- Fuentes en `app/src/main/java/com/example/prototipopagosoffline/`. `MainActivity.kt` reúne pantallas y lógica; no imponer Hilt, MVVM ni una reestructuración general.
- `protocol/` contiene contratos, validadores, APDU, lector NFC móvil y adaptadores Room; `utils/PaymentHCEService.kt` implementa HCE. `ui/screens/CobroQrScreen.kt` y `PagarQrScreen.kt` usan QR/TCP local.
- Flujo principal académico: Android cliente HCE contra POS Windows/ACR122U por PC/SC. El lector aún no está disponible; no confundir NFC entre teléfonos con validación del ACR122U.
- QR/TCP requiere conectividad local entre teléfonos; el código no configura automáticamente hotspot o Wi-Fi Direct. NFC no requiere esa red local.
- `PaymentState.kt` conserva estado heredado: no tomar sus valores volátiles como fuente contable. El comercio muestra saldo confirmado persistido por cuenta, no ventas del día ni cobros pendientes.

## Contrato y datos actuales

- Pagos nuevos: v3. V2 se conserva exclusivamente para recuperación/conciliación histórica controlada; no volver a firmar evidencia antigua.
- Room 18; migraciones declaradas 5→18 y schemas exportados. Su presencia/compilación no acredita migración ejecutada sobre todas las bases históricas.
- Reserva, autorización, índice, comprobante y estado deben persistirse antes del transporte. Conservar reserva ante entrega incierta, conflictos o evidencia incompleta; no liberar por vencimiento o error de red.
- NFC conserva AID, capacidades SELECT y GET_AUTHORIZATION 0x50. SELECT no debe producir pagos. Contrastar con `pos_desktop/nfc.py` y productores/consumidores antes de cambiar APDU o campos firmados.
- Preservar payloads originales, unidades en centavos, firmas y digests. No reconstruir evidencia histórica para hacerla compatible.
- Claves por cuenta en Keystore; falta de alias existente exige recuperación, no sustitución silenciosa. Perfiles comerciales previos conservan ID y clave; nuevos terminales usan UUID persistido antes del registro HTTP.
- Sesión, identidad y bases de pagos están excluidas de backup/transferencia. Logout no elimina evidencia, claves ni reservas. No borrar almacenamiento ni desinstalar como solución rutinaria.

## Acceso y configuración de demostración

- Cliente/Comercio usan `/api/demo/device-login`: UUID y secreto aleatorios por instalación/rol, persistidos cifrados. Reingresar reutiliza la cuenta y no reinicia saldo. Primer acceso requiere Internet.
- El endpoint solo funciona con modo demo explícito en backend. No convertirlo en acceso anónimo de producción ni compartir identidad entre teléfonos.
- Cuentas históricas se conservan; una cuenta demo nueva no hereda su saldo/historial. No remapear pendientes automáticamente al cambiar usuario.
- `demo.local.properties` está ignorado por Git y contiene solo URL, emisor público y opción de instalación aislada. Lo genera `tools.run_demo android-config` del backend para Android Studio.
- Prioridad: entorno, propiedad Gradle y configuración pública local. No restablecer el override `API_BASE_URL=https://example.invalid/`: causó APKs que compilaban y no llegaban al servidor. Verificar la URL efectiva generada sin imprimir secretos.
- Demo actual: `https://unfailing-bless-thrift.ngrok-free.dev/`; aplicación `.manual`, etiqueta Pagos Offline Demo. Conservar la app anterior y los datos de ambas.
- Confianza emisora aprovisionada externamente; no aprender una clave por HTTP sin fijación confiable. APK sin contraseñas compartidas; Mercado Pago deshabilitado por defecto y recarga móvil v3 simulada con ID durable.

## Sincronización automática C8-AN-08

- `PaymentsApplication` inicia `AutoSyncMonitor`: callback de red predeterminada con INTERNET y VALIDATED. WorkManager conserva trabajos; recuperación periódica de 15 minutos como respaldo.
- `SyncManager` separa cola inmediata por cuenta de despertadores diferidos. No volver a bloquear avisos nuevos detrás de una espera/backoff larga.
- `SyncWorker` serializa por cuenta dentro del proceso, verifica la sesión activa y usa una API ligada al token capturado. Cambiar sesión no debe cambiar el destinatario de una operación.
- `SyncHttp` propaga cancelación. Red transitoria/servidor reintentan; 401/403 pausa para reingreso y errores definitivos de contrato/configuración requieren atención. La pausa está ligada al digest del token; no modifica reservas ni resultados financieros.
- Ambos saldos confirmados se refrescan preservando reservas. `COMPLETED` en Logcat describe una ejecución, no aceptación financiera de todo el lote. Logs `PaymentSync` sin cuerpos, JWT, claves ni datos personales.
- Android puede demorar background; callback solo mientras vive el proceso. Force-stop requiere reapertura. Solo se concilia automáticamente bajo la sesión activa; no prometer ejecución instantánea o sincronización de otras cuentas.
- Guía y límites en `SINCRONIZACION_AUTOMATICA.md`.

## Evidencia y pendientes

- C8-AN-01 a C8-AN-08: se compilaron APK e instrumentadas con JBR 21; compilar instrumentadas no es ejecutarlas. Último corte de programación previo a esta edición: `070bded`.
- Usuario informó en esta conversación pagos QR y NFC entre dos teléfonos y actualización automática de ambos al recuperar Internet. Registrar como evidencia manual informada, sin protocolo experimental ni generalización a todos los fallos.
- Se diagnosticó diferencia de reloj que impedía reservar; el usuario informó que sincronizar horas permitió pagar. No resolverlo eliminando validaciones temporales.
- Suites antiguas (incluido el antecedente de 24 JVM), simulaciones y benchmarks son históricos; no certifican el código posterior a la auditoría R01–R34.
- Pendientes: regresión agrupada, migraciones ejecutadas, interrupciones, duplicados, reintentos, cambios de cuenta, restricciones background, batería y ACR122U/PC/SC. No afirmar aprobación integral de seguridad o contabilidad.

## Forma de trabajar y cierre

- Registrar Git antes/después; preservar cambios previos. Cada unidad tiene ID, entrada EN PROGRESO en `REGISTRO_CAMBIOS.md`, verificación real y commit local conjunto. Sin push.
- Revisar ambos extremos con `network/ApiService.kt`, `SyncItem.kt` y backend `api_v3.py`, `sync_v3.py`, `schemas.py`; autenticación/registro/saldo compartidos pueden conservar rutas sin prefijo v3.
- Última autorización: no ejecutar tests, lint, instrumentadas, simuladores, benchmarks ni pagos. Solo compilación cuando corresponda. No instalar APK: el usuario lo hace desde Android Studio, salvo nueva autorización explícita.
- Wrapper local, JBR 21 de Android Studio y SDK 36.1. `local.properties` fue corregido al SDK local. Compilaciones permitidas: `:app:assembleDebug` y `:app:assembleDebugAndroidTest`.
- No recompilar por una edición exclusivamente documental. Nuevas verificaciones de ejecución requieren autorización y entorno seguro; no bases originales ni servicios de pagos reales.
- Actualizar registro consolidado y decisiones/instrucciones cuando corresponda. Antecedentes y detalles históricos permanecen en registros; no reescribir Entrega 4.
- Diferenciar implementación, compilación, prueba ejecutada, informe manual e hipótesis. No aceptar como ciertos hallazgos o garantías de conversaciones anteriores sin comprobarlos.

## Publicación autorizada — 19/10/2026

El usuario autorizó explícitamente subir los commits actuales a origin/main con
explicaciones específicas en español. Esta autorización prevalece sobre la regla
anterior Sin push para esta publicación; no implica permiso general para publicar
cambios futuros. No reescribir historia ni usar force-push. Ver
PUBLICACION_GITHUB_2026-10-19.md; documentos remotos antiguos se preservan como
antecedentes y no revierten el contrato v3 ni habilitan integraciones reales.

## Inventario de limpieza — 19/10/2026

Ver INVENTARIO_LIMPIEZA_2026-10-19.md del área y el inventario general en la raíz.
Esta unidad clasifica candidatos; no retira código, scripts, cachés, bases o claves.
El usuario autorizó borrar APK generados, pero la revisión automática rechazó
ambos comandos antes de ejecutar. Los cuatro APK Android/demo siguen presentes;
no afirmar que se borraron ni que se liberó espacio. No ejecutar las propuestas
de código de forma incidental. Sin pruebas, compilación, instalación ni push.

## Renovación offline C10 — 20/10/2026
- Room actual pasa a versión 17 con migración aditiva 16→17; conserva autorizaciones, reservas, recibos y claves. La migración agrega reanudación idempotente de solicitudes, cierre congelado y resolución financiera firmada.
- El inicio de sesión queda disponible si el servidor no emite cupo nuevo. Solo una cuenta sin historial recibe el aprovisionamiento inicial automático; renovaciones posteriores se solicitan desde la acción «Cupo».
- El cierre primero persiste manifiesto y payload firmable; un reinicio reutiliza el mismo cierre. Los pagos pendientes siguen respaldados y sus índices no se rehabilitan.
- Estados financieros v3 requieren y persisten una resolución firmada. V2 conserva recuperación histórica.
- Compilación APK e instrumentadas aprobada; instrumentadas no ejecutadas. Migraciones, pruebas y comportamiento en móviles continúan pendientes.

## Recuperación C12 — 20/10/2026

- Room 18 añade evidencia de resolución final para comercio móvil y estado de recuperación para ambos roles. La consulta histórica por GET v3 adjunta bytes firmados a finales existentes sin repetir efectos contables.
- La renovación explícita puede cerrar una autorización vencida o agotada; el trabajo automático conserva el criterio de vencimiento. Un ID de solicitud guardado antes de Room 17 se recupera sin generar otro al iniciar sesión con historial.
- Solicitudes nuevas vencidas siguen rechazadas; una solicitud exacta de la cuenta activa con comprobante ya persistido puede reanudar la entrega del acuse sin consumir otro índice.
- No desinstalar, borrar datos ni instalar un APK anterior para resolver errores de migración. Room 17→18 es aditiva, pero no fue ejecutada sobre bases de usuario durante esta implementación.

## Automatización y actividad C13 — 20/10/2026

- Por solicitud del usuario, C13 reemplaza la renovación explícita de C10–C12: login, trabajo por cuenta y recarga preparan autorizaciones con Internet y saldo libre, reutilizando solicitudes durables. Se retiran «Cupo», «Actualizar» y «Sincronizar» del menú. Los límites firmados se conservan; Android puede demorar background y sin servidor no renueva.
- Conciliar precede al cierre de vencidas/agotadas y a nuevas emisiones. El cierre conserva pagos pendientes; REJECTED entra en el manifiesto solo con resolución firmada vinculada y liberación contable demostrada en backend. No se reutilizan índices ni se libera por timeout.
- La pantalla NFC observa la operación por billetera y conserva su selección por cuenta para reanudar; acuse persistido permite aceptación local, resolución final permite conciliación. Rechazo/revisión prevalecen sobre el acuse. La selección de pantalla no es estado contable.
- Actividad consulta Room por billetera o comercio/terminal comprobado, muestra estados y detalles, y permite desplazarse. Filas heredadas sin propietario siguen excluidas de la actividad personal. No usa PaymentState como fuente ni suma pendientes al saldo.
- No se ejecutan pruebas, servicios, pagos ni migraciones en esta unidad. Registrar compilación y revisión estática por separado; la nueva automatización e interfaz requieren validación manual.

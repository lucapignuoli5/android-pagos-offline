# Inventario de limpieza Android — 19/10/2026

Unidad: TFG-C8-AN-11. Corte inicial `df465a5da1b77c6818fc419c9df003b5c0651136`,
Git limpio. Inventario estático y de tamaños; no es una nueva auditoría funcional.
No se ejecutaron compilaciones, tests, lint, ADB, instalaciones ni pagos.

## Resultado y alcance

Se clasificaron archivos como **eliminar, archivar, conservar o investigar**.
Las propuestas no se ejecutaron: no se retiró código, configuración, cachés,
bases ni claves. La única eliminación solicitada fue la de APK generados, pero
ambos comandos de borrado fueron rechazados por la revisión automática antes de
ejecutarse, incluso con rutas literales. Los cuatro APK siguen presentes.
El bloqueo solo indicó `blocked by policy`, sin motivo adicional detallado.

## Tamaños observados

Antes de crear este informe: 1.908 archivos y 153.505.567 bytes, excluyendo `.git`.
La instantánea incluye generados y configuración local; no representa el tamaño
del código fuente ni espacio físico recuperable en disco.

| Grupo | Archivos | Bytes | Tratamiento |
|---|---:|---:|---|
| `app/build/` | 1.740 | 143.097.233 | Generados; APK autorizados, resto solo inventariado |
| `.gradle/` | 25 | 9.566.191 | Caché; eliminación posterior opcional |
| `.kotlin/` | 1 | 22.292 | Caché; eliminación posterior opcional |
| `.idea/` | 20 | 149.752 | Conservar configuración Studio |
| `app/src/` | 94 | 421.357 | Conservar; candidatos puntuales abajo |
| `app/schemas/` | 5 | 88.236 | Conservar evidencia de esquema |
| `gradle/` | 4 | 50.032 | Conservar wrapper y configuración |
| `antecedentes/` | 3 | 9.802 | Conservar procedencia histórica |

El resto corresponde a archivos de raíz y configuración del módulo. No se
inspeccionaron valores privados ni contenido de almacenes de identidad.

## Clasificación de candidatos

Todas las rutas siguientes son relativas a este repositorio. **Eliminar propuesto**
no significa eliminado. Las ausencias de referencias son resultados de búsqueda
estática: Android, XML, bibliotecas y reflexión pueden introducir consumidores.

| Decisión | Ruta y evidencia | Recomendación / límite |
|---|---|---|
| ELIMINAR AUTORIZADO, BLOQUEADO | `app/build/intermediates/apk/debug/app-debug.apk`; `app/build/outputs/apk/debug/app-debug.apk`; `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | Productos regenerables; borrado rechazado. Studio puede generarlos nuevamente al ejecutar. No afecta las apps ya instaladas. |
| ELIMINAR PROPUESTO | `.kotlin/`, `.gradle/`, `app/build/`; raíz `build/` si vuelve a existir | Cachés/generados, no fuentes. No retirados en esta unidad. Pueden regenerarse con Studio abierto; eliminarlos implica reconstrucción o posibles descargas. |
| INVESTIGAR | `utils/ReceiptUtils.kt:18–79` | Sin consumidores externos encontrados de `shareReceipt`/`generateReceiptBitmap`; decidir si compartir recibos sigue en alcance. No confundir con evidencia criptográfica del pago. |
| INVESTIGAR CONJUNTAMENTE | `AndroidManifest.xml:61–69`, `res/xml/file_paths.xml` | FileProvider tiene consumidor Kotlin en `ReceiptUtils.kt:71`; cualquier retirada debe coordinarse con ese helper y el manifest. |
| INVESTIGAR | `PaymentState.kt:10–11,15,17–39` | Saldos volátiles y mutadores sin consumidores externos encontrados. **No borrar el archivo:** `recentTransactions` sí se consume en `MainActivity.kt:265–266,562–568`. |
| INVESTIGAR | `MainActivity.kt:761`, `CustomNumberPad` | Solo definición encontrada; candidato a retirar en unidad de código separada. Otros botones de UI sí están usados. |
| INVESTIGAR | `network/SyncItem.kt:27–28`, `RecargaRequest`/`RecargaResponse` | DTO sin consumidores encontrados; recarga actual usa v3. El archivo contiene muchos DTO activos, no debe eliminarse completo. |
| INVESTIGAR | `network/ApiService.kt:18–19,30–33,72–73` | Sin llamadas encontradas a `syncPayments`, `createOfflineAuthorization` y `consultarSaldo`; contrastar compatibilidad, pruebas y backend antes de retirarlos. |
| CONSERVAR / DECIDIR ALCANCE | `network/ApiService.kt:21–22,75–76` | Login común y creación de link MP sin llamadas actuales, pero relacionados con recuperación o integración opcional prevista. Desuso actual no equivale a autorización de retirada. |
| INVESTIGAR | Imports `MainActivity.kt:37–39,74` | `Visibility`, `VisibilityOff`, `Wallet`, `Gson` sin uso encontrado fuera de importaciones. Mejora de legibilidad, no ahorro significativo. |
| INVESTIGAR PARCIALMENTE | `res/values/colors.xml:3–9` | Colores heredados sin referencias locales encontradas; preservar los overrides nominales ZXing de líneas 10–11 que puede consumir la dependencia. |
| INVESTIGAR | `ExampleUnitTest.kt:12`, `ExampleInstrumentedTest.kt:17` | Pruebas de plantilla, no validación del pago; packageName instrumentado no contempla `.manual`. Retirar/reformular en unidad de pruebas posterior. |
| ARCHIVAR / CONSERVAR YA ARCHIVADO | `antecedentes/github_2026-07-06/` | Ya separado como antecedente con procedencia. No duplicar ni borrar por contradecir el estado actual. |
| CONSERVAR | `ProtocolV2.kt`, `V2SyncRepository.kt`, fixtures v2 | Tienen consumidores actuales: constantes/APDU y recuperación histórica; `SyncWorker.kt:51` usa el repositorio. Nombre v2 no significa basura. |
| CONSERVAR | Migraciones, `app/schemas/`, pruebas y vectores | Room 16 y migraciones 5→16 declaradas; necesarios para validar compatibilidad. Compilar o estar guardados no acredita ejecutarlos. |
| CONSERVAR | `PaymentsApplication`, monitores/workers, actividades y HCE del manifest | Consumidos por framework o configuración aunque no exista llamada Kotlin directa. |
| CONSERVAR | Wrapper, Gradle, `local.properties`, `demo.local.properties`, `.idea/` | Necesarios para Studio, SDK o configuración de demostración. No leer secretos ni retirar configuración por parecer temporal. |

No se identificó una dependencia cuya retirada sea suficientemente justificada
en esta revisión. No modificar arquitectura ni contratos como parte de limpieza.

## APK externos y continuación

También se incluyó por su procedencia la copia generada
`C:\Users\anita\TFG-Demo-Manual\Pagos-Offline-Demo.apk`; su borrado fue bloqueado.
Los cuatro suman 69.263.398 bytes (aproximadamente 66,1 MiB) y ninguno está
rastreado por Git. El detalle con rutas exactas está en el inventario general.

La siguiente unidad debería elegir candidatos de código, comprobar todos sus
consumidores y retirar cambios pequeños con su registro propio. Esta unidad no
autoriza a ejecutar todas las propuestas ni modifica las verificaciones pendientes.

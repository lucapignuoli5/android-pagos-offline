# Publicación de Android — 19/10/2026

Esta publicación reúne el desarrollo local conservando sus commits y registros.
El usuario informó pagos QR y NFC entre dos teléfonos, conciliación automática al
recuperar Internet y actualización de saldos. Es evidencia manual informada;
no certifica seguridad integral, todos los escenarios de fallo ni el ACR122U.

## Cambios incluidos

- Contrato v3: payload original, firmas ECDSA P-256/SHA-256, autorización separada,
  comprobante y acuse. V2 permanece para recuperación histórica controlada.
- Room 16 y migraciones declaradas desde v5; persistencia por cuenta de claves,
  autorizaciones, índices, reservas, comprobantes, resultados y colas.
- NFC/HCE/APDU con intercambio de dos acercamientos, capacidades SELECT y
  GET_AUTHORIZATION 0x50; lector NFC móvil y transporte QR/TCP compartiendo contrato.
- Acceso demo con un toque e identidad cifrada individual por instalación/rol;
  terminal UUID propio, preservación de perfiles previos y control de clave ausente.
- Configuración pública local para Android Studio, dominio HTTPS y APK `.manual`
  separado. Sin contraseñas compartidas embebidas ni transferencia de identidad.
- Recargas simuladas v3 con identificador durable; Mercado Pago deshabilitado.
- Saldo confirmado del comercio obtenido del servidor, persistido en Room y
  observado en pantalla; sin acreditar cobros pendientes como saldo confirmado.
- Conciliación automática: detección de Internet validado, cola inmediata y
  despertadores diferidos separados, recuperación periódica y reintentos progresivos.
- Trabajos ligados a cuenta y token capturados, exclusión mutua por cuenta,
  cancelación propagada y pausas visibles ante falta de acceso o evidencia inválida.
- Exclusión de backup de datos sensibles, registro Markdown por unidad y retirada
  recuperable de temporales y logs del árbol de trabajo.

## Integración con GitHub

Antes de publicar, main tenía 42 commits locales y origin/main dos commits remotos
de julio. Se conservan todos como historial mediante un merge con estrategia ours.
El árbol funcional local se mantiene: los cambios remotos de contratos heredados
están superados y su propuesta SQLCipher no incluye una migración compatible con
las bases actuales. No se introduce migración destructiva ni se reescribe historial.

Los documentos remotos se conservan íntegros en `antecedentes/github_2026-07-06/`;
su README explica que no describen el estado actual. No se incorporan auxiliares
`._*`. Los commits existentes mantienen sus IDs; este commit agrega el resumen
detallado en español y la constancia de integración.

## Verificación y límites

- Antecedente C8: assembleDebug y assembleDebugAndroidTest correctos. Las
  instrumentadas se compilaron; no se ejecutaron durante estas correcciones.
- En esta publicación: revisión Git, comparación remota, inspección heurística
  de secretos y revisión de diff. No se ejecutaron pruebas, compilaciones,
  instalaciones, servicios ni pagos; no se modificaron datos o claves.
- Los archivos privados de demostración y APK no se incluyen en Git. Configurar
  el entorno según AGENTS.md y las guías antes de compilar en otra computadora.
- Siguen pendientes regresión agrupada, migraciones ejecutadas, fallos,
  duplicados, concurrencia, restricciones background y POS/ACR122U físico.

Detalles de cada unidad: `REGISTRO_CAMBIOS.md`. Operación automática y límites:
`SINCRONIZACION_AUTOMATICA.md`.

# Sincronización y preparación automática — C8/C13

Implementada; compilación verificada por separado. Funcionamiento pendiente de
prueba manual. No se ejecutaron pagos ni pruebas automatizadas en esta unidad.

- Abrir la versión actualizada en ambos teléfonos al menos una vez y conservar
  la sesión de cada cuenta. Mantener backend y ngrok funcionando.
- Cada reserva/pago programa trabajo durable. Internet validado y regreso a la
  app generan avisos adicionales; no se necesita el botón para programarlos.
- C13 retira «Cupo», «Actualizar» y «Sincronizar» del menú. Con la sesión cliente
  activa, el trabajo concilia, cierra autorizaciones vencidas/agotadas y prepara
  nuevas según el saldo libre. Recargas simuladas también despiertan esa cola.
  Los identificadores de solicitudes se guardan para reintentar sin duplicarlas.
- Cerrar conserva los comprobantes emitidos y libera únicamente presupuesto no
  emitido. Un rechazo definitivo requiere resolución firmada y contabilidad
  coherente; sus índices no vuelven a usarse. Si el manifiesto no puede probarse,
  conserva el compromiso. Tener saldo no elimina los límites firmados ni permite
  renovar sin acceso al servidor.
- La recuperación periódica de 15 minutos es respaldo, no un plazo garantizado.
  Android puede demorar trabajos en segundo plano. Tras forzar detención hay que
  abrir la app de nuevo. Un proceso vivo detecta la recuperación de Internet.
- Trabajo inmediato y despertadores diferidos usan nombres separados por cuenta.
  Se conserva el reintento por operación en Room. Un mutex serializa conciliación
  en el proceso; no se declara coordinación entre varios procesos Android.
- Las peticiones usan el token capturado y la cuenta indicada en el trabajo.
  Otra sesión no cambia el destinatario de sus operaciones.
- Un error de red, 429 o servidor conserva pendientes y programa reintento.
  401/403 pausa con aviso de acceso; errores definitivos de contrato/configuración
  pausan con aviso de revisión. No se liberan reservas por esos errores.
- Al resolver el problema, recuperar la sesión correcta. Las cuentas históricas
  no se convierten automáticamente en nuevas identidades demo.
- Logcat, etiqueta `PaymentSync`: `INTERNET_RESTORED_SYNC_QUEUED`, `STARTED`,
  `COMPLETED`, `RETRY_SCHEDULED`, `REAUTH_REQUIRED` o `ATTENTION_REQUIRED`.
  No se imprimen JWT, claves, cuerpos, datos personales ni comprobantes.
  `COMPLETED` describe una ejecución; cada operación conserva su estado propio.

## Comprobación manual pendiente

Pagar sin Internet por QR o NFC, recuperar Internet en cada teléfono de forma
independiente y esperar la cola automática. Revisar estados y saldo en ambos.
Repetir con la app en segundo plano y después de cerrarla y volver a abrirla.
Si el servidor sigue apagado, deben conservarse reserva y pendientes. Repetir
sincronización no debe duplicar movimientos. Estas comprobaciones no se ejecutaron.
También comprobar renovación tras vencimiento/diez pagos y recarga, presupuesto
cero, respuesta perdida y cierre con pendientes. El historial ofrece reintento
de verificación solo cuando una prueba final requiere atención; no reemplaza
la revisión de una configuración inválida ni el reingreso por sesión vencida.

Referencias de Android: [conectividad](https://developer.android.com/develop/connectivity/network-ops/reading-network-state),
[trabajo persistente](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).

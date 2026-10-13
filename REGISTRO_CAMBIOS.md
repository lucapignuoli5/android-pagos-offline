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

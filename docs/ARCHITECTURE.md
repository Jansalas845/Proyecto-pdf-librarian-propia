# Arquitectura (100 % local)
- Datos: Room (`LibDb`: carpetas y documentos). PDFs en `filesDir/pdfs/{id}.pdf` (copia exacta, sin modificar),
  portadas en `filesDir/covers/{id}_{versión}.jpg`.
- UI: Compose Material 3 + MVVM (`LibVm`). Ancho >= 720 dp = dos paneles (tablet).
- Visor: `PdfRenderer` + LazyColumn, zoom con dos dedos, progreso guardado con debounce.
- Sin Internet, sin cuentas, sin permisos.
- Limitación: los datos viven solo en el teléfono; desinstalar la app o borrar sus datos los elimina.

# PDF Manager Android — versión 0.4.0

Aplicación Android nativa para seleccionar una carpeta y explorar sus PDF, incluidas subcarpetas, sin depender de Termux. Esta versión es un gestor/escáner local de documentos; no publica archivos ni consulta sitios de terceros.

## Funciones
- Selector de carpeta mediante Storage Access Framework y permiso persistente de lectura.
- Exploración recursiva de archivos `.pdf`, sin distinguir mayúsculas/minúsculas.
- Cada activación vuelve a recorrer todos los archivos; no se usa historial para omitirlos.
- Recorrido iterativo en dos pasadas: primero cuenta y suma tamaños; después revisa y muestra progreso. Evita conservar todos los objetos de archivo en memoria.
- Notificación persistente con progreso determinado y actualizaciones limitadas para no actualizar la notificación en cada archivo.
- Acción Detener desde la notificación y desde la aplicación.
- Resumen de la última exploración: revisados, total encontrado, tamaño acumulado y fecha.
- Errores de permisos comunicados para volver a seleccionar la carpeta.
- Manejo del callback de tiempo límite de servicios `dataSync` en Android 15+.
- No solicita permiso general de almacenamiento.

## Compilar con GitHub Actions
1. Sube el contenido de esta carpeta a un repositorio GitHub propio.
2. Abre **Actions**.
3. Ejecuta **Android debug APK** con **Run workflow** (o haz push a `main`/`master`).
4. Descarga el artefacto `PDFManager-debug`.
5. Extrae `app-debug.apk` e instálalo en Android.

Requisitos locales: JDK 17 y Gradle 8.9; ejecutar `gradle --no-daemon :app:assembleDebug`.

## Limitaciones conocidas
- La app enumera PDF y consulta sus metadatos básicos (nombre y tamaño); no abre, modifica ni envía documentos.
- No integra Telegram ni Hitomi.la.
- Android puede restringir la ejecución en segundo plano según versión y ajustes de batería. El servicio en primer plano reduce esas restricciones, pero no garantiza ejecución ilimitada.
- Android 15 limita servicios `dataSync` a seis horas acumuladas por periodo de 24 horas; el servicio interrumpe la exploración si el sistema notifica ese límite.
- La primera pasada cuenta y calcula tamaño; la segunda revisa y actualiza la barra de progreso. Cambios en la carpeta entre ambas pasadas pueden hacer que los totales difieran.
- Esta entrega es código fuente; no se declara un APK compilado o probado hasta completar una compilación real y una prueba en dispositivo.

Paquete: `com.example.pdfmanager`  
Versión: `0.4.0`

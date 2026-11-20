# Plan de integración del bot original en Android

## Recuperación verificada

El archivo original `pdfbot/bot.py` se recuperó desde `pdfbot.zip` mediante un artefacto temporal de GitHub Actions. El archivo tiene 7.513 bytes. La extracción se limitó exclusivamente a ese archivo; no se copiaron `config.txt`, `estado.json`, `token.txt`, registros ni bytecode. El análisis de patrones de credenciales no encontró coincidencias habituales y el fuente no se publicó en el repositorio.

## Comportamiento que debe conservarse

El bot original es un bot de Telegram que:
- Se conecta con un token de bot.
- Solo acepta comandos del administrador configurado.
- Publica en un canal configurado.
- Busca archivos PDF en una carpeta.
- Para cada PDF, publica primero una miniatura de la primera página y después el PDF.
- Omite archivos de más de 50 MiB.
- Guarda el estado por ruta, tamaño y fecha de modificación para no volver a publicar archivos ya completados.
- Permite `/start`, `/iniciar`, `/procesar`, `/estado` y `/detener`.
- Informa al administrador del inicio, progreso, errores y finalización.

## Dependencias y obstáculos de la versión Termux

- `requests`: debe sustituirse por llamadas HTTP compatibles con Android.
- `pdftoppm`: no debe suponerse disponible en Android. La alternativa nativa es `android.graphics.pdf.PdfRenderer`.
- `~/pdfbot`, `token.txt` y la ruta absoluta de Termux: no son válidos como rutas universales en Android moderno. La app debe usar el selector SAF y permisos persistentes.
- El token y la configuración deben introducirse en la app y mantenerse en almacenamiento privado; nunca deben incluirse en el APK ni en el repositorio.

## Diseño de la adaptación Android

1. Mantener la interfaz Android nativa y el selector SAF de carpeta.
2. Añadir configuración local para token, identificador de canal e identificador numérico del administrador.
3. Implementar un servicio en primer plano separado del escáner de metadatos.
4. Implementar el sondeo de Telegram y los cinco comandos originales.
5. Enumerar PDF desde el árbol SAF y generar la portada con PdfRenderer.
6. Enviar la miniatura y el documento mediante multipart/form-data.
7. Persistir el progreso por identificador de documento, tamaño y fecha de modificación.
8. Añadir notificación persistente con progreso real, acción Detener y recuperación de errores.
9. Compilar y validar el APK en CI; una compilación correcta no sustituye la prueba real de conexión con Telegram.

## Criterios de aceptación

- La app no contiene credenciales.
- Sin token/canal/admin configurados, no inicia el bot y explica qué falta.
- `/start` y `/estado` responden solo al administrador.
- `/procesar` publica miniatura y PDF en el canal, en ese orden.
- Los PDF mayores de 50 MiB se omiten.
- Los ya publicados no se duplican al reanudar.
- `/detener` detiene el lote de forma controlada.
- La notificación refleja el archivo actual y el avance del lote.
- Los errores de Telegram, permisos SAF y PDF inválidos se muestran de forma comprensible.

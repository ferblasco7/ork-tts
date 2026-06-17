# Ork TTS

App Android simple para leer EPUBs en voz alta usando el motor TTS del propio sistema.

## Funciona así

- Abres un EPUB con el selector de archivos del sistema.
- Se extrae el texto capítulo a capítulo (y la portada, si el EPUB la incluye).
- Se lee frase a frase con `TextToSpeech`.
- Se recuerda automáticamente la frase exacta por la que vas, aunque cierres la app.
- Mientras lee, hay una notificación fija con la portada y tres botones: retroceder, play/pausa y avanzar (saltan una frase, ya que el TTS no tiene una línea de tiempo de audio real).

## Estructura

- `Epub.kt` — parseo del EPUB (zip + OPF) a texto plano por capítulos.
- `TtsManager.kt` — cola de lectura con `android.speech.tts.TextToSpeech`.
- `ReaderService.kt` — servicio en foreground, notificación y controles.
- `ReadingPosition.kt` — persistencia de la posición de lectura (DataStore).
- `MainActivity.kt` — UI mínima en Compose.

## Compilar

Abre la carpeta en Android Studio y ejecuta. Requiere Android 8.0 (API 26) o superior.

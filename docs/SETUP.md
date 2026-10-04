# Cómo obtener el APK (sin cuentas ni registros)

## Opción A: sin instalar nada (GitHub compila por ti)
1. Crea cuenta en github.com y un repositorio **privado**.
2. Sube todo el contenido de la carpeta `PdfLibrary` (incluida la carpeta oculta `.github`).
3. Pestaña **Actions** → "Build APK" → espera ~5 min → descarga **PdfLibrary-apk** (zip con `app-debug.apk`).
4. Pasa el APK al Samsung, ábrelo y acepta "instalar apps desconocidas".

## Opción B: Android Studio
Abrir la carpeta → esperar Gradle Sync → Build → Build APK(s) → `app/build/outputs/apk/debug/app-debug.apk`.

## Nuevas versiones
Sube `versionCode`/`versionName` en `app/build.gradle.kts`, compila y reinstala encima (se conservan los datos).
Usa siempre `keystore/personal.jks` (alias `personal`, contraseña `pdflibrary`) o Android no permitirá actualizar.
**No desinstales la app para actualizar: borraría tu biblioteca.**

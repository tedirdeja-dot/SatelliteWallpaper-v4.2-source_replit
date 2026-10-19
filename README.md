# Satélite Zoom Earth 4.2

App Android de fondo satelital a partir de Zoom Earth.

## Actualización de la imagen

- Al iniciar la app aparece el selector de intervalo mínimo: 15, 30 o 60 minutos. La selección se guarda; el valor inicial es 30 minutos.
- La descarga solo se inicia al desbloquear el teléfono. Abrir la app, encender la pantalla, mostrar el fondo o reiniciar el dispositivo no inicia una descarga.
- El intervalo se cuenta desde el último intento, incluso si ese intento falló.
- La captura nueva se guarda primero en la caché temporal privada de la app. Solo después de validar la imagen se sustituye atómicamente la imagen anterior.
- La resolución de captura se limita según la memoria disponible del dispositivo. Si una captura falla, la imagen vigente se conserva.

## Compilar en GitHub

Coloca el contenido de esta carpeta en la raíz del repositorio de GitHub. El workflow de `.github/workflows/build.yml` se ejecuta al hacer push a `main` o `master`, o desde **Actions → Build APK → Run workflow**. El APK debug se publica como artifact `SatelliteWallpaper-apk`.

El workflow no incluye una clave privada de firma. Para instalar una compilación sobre una versión que use otra firma, configura una clave de firma estable mediante GitHub Secrets; no subas archivos `.keystore` ni contraseñas al repositorio. Sin esa configuración, puede ser necesario desinstalar la versión anterior antes de instalar el APK nuevo.

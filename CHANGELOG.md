# Historial de versiones

Cada modificación del proyecto sube `versionName` y `versionCode` en `app/build.gradle.kts`.
La versión se ve en la app (arriba y en "Últimos intentos"), en el nombre del APK y del zip.

## 4.2 (build 21)
- Al abrir la app se elige el intervalo mínimo entre intentos de descarga: 15, 30 o 60 minutos (30 por defecto); los intentos fallidos también respetan ese intervalo.
- La captura solo se inicia al desbloquear el teléfono. Abrir la app, encender la pantalla, mostrar el fondo o reiniciar el teléfono no descargan imágenes.
- La imagen nueva se prepara en una carpeta temporal privada y sustituye la caché anterior con un cambio atómico solo tras validar el JPEG; los fallos conservan la imagen anterior.
- Resolución y densidad de captura adaptadas a la memoria del dispositivo; las imágenes antiguas se decodifican con muestreo en equipos con menos memoria.
- Espera posterior a la carga de Zoom Earth reducida de 9 a 5 segundos y JPEG guardado con calidad 90.
- El registro de intentos muestra cuánto tardó cada captura.
- El workflow de GitHub Actions compila el APK debug sin guardar el keystore de firma dentro del repositorio.

## 4.1 (build 20)
- Si no se puede descargar una imagen nueva, se conserva la actual hasta que una descarga termine bien (fondo animado y pantalla de bloqueo no se tocan).
- Antes: sin conexión, el WebView mostraba su página de error y esa captura se aceptaba como imagen válida, sobrescribiendo la buena. Ahora se descarta si la página principal falla (error de red o HTTP >= 400) o si fallan 5 o más recursos por conectividad.
- Comprobación de red previa (`isOnline`): sin conexión no se inicia la captura.
- Si la red se pierde durante la captura, la imagen se descarta.
- El archivo temporal se borra antes y después de cada intento.
- `loadCached` ya no borra la caché por un fallo de decodificación transitorio (p. ej. memoria); solo si el archivo está corrupto.
- Los fallos de red no repiten la cascada de estrategias de captura.
- Si la descarga manual falla y hay imagen anterior, se muestra esa en vez de un error.

## 4.0 (build 19)
- Lo que eliges es lo que ves: el recuadro de encuadre tiene la misma proporción que la pantalla del teléfono y usa el mismo cálculo que el fondo animado.
- La captura manual usa el mismo método que el fondo en segundo plano: imagen del tamaño y proporción de la pantalla (antes se capturaba del tamaño del recuadro de la app, apaisado, y al llevarla al fondo se ampliaba y recortaba). Si falla, se reintenta con el WebView visible.
- Nueva pantalla de confirmación: "Usar como fondo" muestra la imagen a pantalla completa, tal como quedará, con botones Aceptar / Cancelar. El fondo solo se aplica al aceptar.
- El fondo ya no se desplaza al cambiar de página del launcher (paralaje desactivado; `USE_LAUNCHER_PARALLAX` en `SatelliteWallpaperService`) para que coincida con la vista previa.
- El fondo de bloqueo estático usa el tamaño real de la pantalla.
- El registro de intentos indica el modo de captura usado al terminar bien.

## 3.9 (build 18)
- Corrige la captura con la app abierta: `PixelCopy` fallaba con "Window doesn't have a backing surface!" (ventana de la Activity sin superficie, p. ej. al volver del selector de vista).
- Espera a que la ventana de la app tenga foco antes de capturar (hasta 15 s) y reintenta `PixelCopy` hasta 6 veces.
- Si aun así falla, reintenta en el mismo intento con la captura en segundo plano (pantalla virtual, overlay, draw), que no depende de la Activity.
- Vigilante de bloqueo ampliado a 300 s para cubrir el reintento.

## 3.8 (build 17)
- Actualización al volver a verse el fondo igual que la APK original: sin el límite de 10 min (ahora solo 30 s contra repeticiones inmediatas).
- Captura en segundo plano en cascada: pantalla virtual, ventana overlay (si hay permiso) y WebView.draw(); si una falla se prueba la siguiente en el mismo intento.
- Cada intento queda registrado con el modo usado y el motivo del fallo, también los disparados por la visibilidad del fondo.
- El bloqueo de "captura en curso" ya no puede quedar atascado (capturas que lanzan excepciones o no terminan).
- Se repinta el fondo animado antes de aplicar el fondo estático de la pantalla de bloqueo.

## 3.7 (build 16)
- Versión visible en la app, nombre del zip con versión y este historial.

## 3.6 (build 15)
- Nuevo disparador: pantalla encendida (`SCREEN_ON`) además del desbloqueo, para empezar la descarga antes.
- Registro de intentos ampliado a 10 líneas, con el origen de cada evento.

## 3.5 (build 14)
- Captura en segundo plano con pantalla virtual (VirtualDisplay + Presentation + ImageReader): sin permisos ni ventana visible.
- Botón "Probar descarga en segundo plano".

## 3.4 (build 13)
- Servicio en primer plano (`UnlockService`) para que funcione con la app cerrada.
- Permisos de notificaciones y de batería sin restricciones.

## 3.3 (build 12)
- Captura en segundo plano con ventana overlay (Dialog) y PixelCopy; respaldo con WebView.draw().

## 3.2 (build 11)
- Registro de intentos de descarga visible en la app (diagnóstico).

## 3.1 (build 10)
- Selección de vista de Zoom Earth al iniciar (se recuerda).
- Descarga al desbloquear, fondo de bloqueo estático y fecha/hora en la imagen.

## 3.0 (build 9)
- Versión recibida.

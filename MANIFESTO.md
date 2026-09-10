# Manifiesto de Spoty

Registro de decisiones del proyecto. Cada entrada lleva fecha, la decisión tomada, el contexto que la motivó y las alternativas descartadas. Se agrega al final, nunca se reescribe la historia.

## Qué es Spoty

Cliente Android de Spotify, construido con Jetpack Compose y un núcleo de reproducción en Rust (librespot) enlazado por JNI. Interfaz en español rioplatense por defecto, con inglés como idioma alternativo.

- Repositorio de trabajo: fork `Denver2828/Outify`, rama `spoty`.
- Licencia heredada: GPL v3. Para uso personal no impone obligaciones. Si el APK se distribuye a terceros, la licencia exige ofrecer el código fuente correspondiente.
- Mantenedor: Darius.

## Plan por etapas

| Etapa | Alcance | Estado |
| --- | --- | --- |
| 1 | Rebranding total: nombre Spoty, ícono nuevo, pantalla About sin enlaces a GitHub, "Made by Darius", versión 1.0.0 | hecha |
| 2 | Internacionalización: extracción de cadenas hardcodeadas a resources, español rioplatense como default, inglés como alternativa | hecha |
| 3 | Ajuste de tema Sistema / Claro / Oscuro dentro de la app | hecha |
| 4 | Ajuste de adelanto de letras en milisegundos | hecha |
| 5 | Ajuste de tamaño de fuente de letras | hecha |
| 6 | Pantalla "Registro de cambios" en ajustes con el historial de versiones | hecha |
| 7 | Este manifiesto, mantenido en cada etapa | en curso |

Las siete etapas se publican juntas como Spoty 1.1.0 (código de versión 20100).

## Decisiones

### 2026-09-04 — Nace Spoty a partir de un fork

**Decisión:** el proyecto se rebrandea como Spoty y se desarrolla en la rama `spoty`, creada desde `fix/spirc-package-casing` (commit `936a01c`).

**Contexto:** la rama base incluye la corrección del casing del paquete `core.spirc`, necesaria para compilar en Windows. La rama `master` no la tiene todavía, así que arrancar desde ahí rompería el build local.

**Descartado:** partir desde `master` y aplicar el fix después.

### 2026-09-04 — Se conserva el identificador interno `cc.tomko.outify`

**Decisión:** el `applicationId` y el `namespace` no cambian. Solo cambian el nombre visible, el ícono y los textos.

**Contexto:** la instalación de Spoty reemplaza a la app anterior y conserva sesión, ajustes y caché. Renombrar el paquete implicaba refactorizar unos 150 archivos Kotlin/Java más el puente JNI en Rust, con riesgo de romper la carga de la librería nativa, y obligaba a volver a iniciar sesión.

**Descartado:** renombrar el paquete completo.

### 2026-09-04 — Internacionalización bilingüe con español como default

**Decisión:** todas las cadenas de la interfaz se extraen a `res/values/strings.xml` (español) y `res/values-en/strings.xml` (inglés). El default de Android pasa a ser el español.

**Contexto:** el código tiene unas 900 cadenas hardcodeadas en Kotlin y solo 20 en resources. Traducir sin extraer habría dejado la app monolingüe y sin posibilidad de sumar idiomas después.

**Descartado:** reemplazar los textos en su lugar, solo en español.

**Pendiente:** decidir si se conserva la traducción al checo existente (`values-cs`) o se elimina.

### 2026-09-04 — Ícono generado, reemplazable

**Decisión:** se genera un ícono vectorial simple con la letra S y los colores del tema. Queda documentado como provisorio.

**Contexto:** no hay diseño propio todavía. Un ícono vectorial adaptativo se reemplaza sin tocar código.

### 2026-09-04 — La numeración de versiones arranca en 1.0.0

**Decisión:** Spoty 1.0.0 corresponde al nacimiento del proyecto. La tanda de cambios de las etapas 1 a 7 se publica como 1.1.0.

**Contexto:** el registro de cambios dentro de la app empieza con "Nace Spoty". Continuar la numeración anterior habría dejado un hueco inexplicable en ese historial.

**Descartado:** continuar desde 1.9.1.

**Ajuste posterior (mismo día):** el `versionCode` lleva un desplazamiento de 10000. Sin él, 1.0.0 daba código 10000, menor al 10901 de la última build anterior, y Android rechaza instalar un código menor sobre una app existente. Con el desplazamiento, 1.0.0 es 20000 y la actualización en el lugar funciona. El nombre visible sigue siendo 1.0.0.

### 2026-09-04 — Ajustes de letras: adelanto y tamaño de fuente

**Decisión:** el adelanto de letras se guarda como un entero con signo en milisegundos y se aplica a la posición de reproducción antes de calcular la línea activa. El tamaño de fuente se guarda como escala y se aplica al texto de cada línea. Ambos controles se muestran como sliders en la sección Letras de los ajustes de reproducción.

**Contexto:** el usuario quiere leer la línea antes de que el artista la cante. Un valor con signo permite también retrasar si alguna pista viene adelantada.

**Pendiente:** definir rango y paso de cada slider al implementar la etapa 4 y 5.

### 2026-09-04 — Etapa 1 cerrada: alcance exacto del rebranding

**Decisión:** cambian el nombre visible, el ícono, la versión, la pantalla About, el README y el título de fastlane. No cambian los identificadores técnicos que el usuario nunca ve.

**Se conservan a propósito:**

- El esquema de deep link `outify://`, el tipo MIME `application/x-outify-backup` y la extensión `.outify` de los respaldos. Cambiarlos rompería los respaldos ya exportados y los enlaces guardados. El nombre de archivo sugerido al exportar sí pasa a `spoty-backup.outify`.
- Los nombres de clases, paquetes y tags de log. Son internos.
- El nombre del dispositivo Spotify Connect: el valor por defecto pasa a "Spoty", pero si el usuario ya tenía uno guardado, se respeta.
- Los mipmaps `.webp` heredados del ícono anterior. Con `minSdk 26` Android siempre usa el ícono adaptativo vectorial, así que nunca se muestran. Se eliminarán cuando llegue el ícono definitivo.
- El archivo `LICENSE` y los avisos de copyright, por obligación de la GPL v3.

**Pendiente de esta etapa:** `docs/`, `.github/ISSUE_TEMPLATE` y `docs/CONTRIBUTING.md` siguen nombrando al proyecto anterior y apuntan a su repositorio. Se revisan o eliminan en una etapa posterior.

**Idioma de los documentos:** el README queda en inglés, siguiendo el idioma del proyecto original. Este manifiesto se escribe en español porque es un documento interno del mantenedor.

### 2026-09-04 — Etapas 3 a 6: cómo quedaron los ajustes nuevos

**Tema.** Nueva preferencia `dark_mode` con valores Sistema, Claro y Oscuro, como fila segmentada al principio de Apariencia. Se resuelve a un booleano antes de entrar al tema, así los tres modos de color existentes (estático, sistema, portada del álbum) no cambian. La opción de negro AMOLED se muestra deshabilitada cuando el tema efectivo es claro.

**Adelanto de letras.** Un switch "Mostrar letras antes" habilita un slider de -3000 a +3000 ms en pasos de 100, con 500 ms por defecto. El valor se suma a la posición de reproducción solo para decidir la línea activa y el auto-scroll. El reloj y la barra de progreso siguen mostrando la posición real. Las letras estáticas del detalle de canción no lo usan porque no tienen marcas de tiempo.

**Tamaño de letras.** Escala de 0,7 a 1,6 sobre los 22 sp base, con vista previa en vivo en el ajuste. La medición que usa el auto-scroll para centrar la línea también se escala, si no el centrado se desfasaría.

**Registro de cambios.** Pantalla estática en Ajustes, arriba de "Acerca de". El contenido vive en resources bilingües desde el primer día. Las versiones se marcan como no traducibles.

### 2026-09-04 — Etapa 2: decisiones de internacionalización

**Español como default de Android.** `res/values/` contiene el español y `res/values-en/` el inglés. Cualquier dispositivo que no esté en inglés ve la app en español. Se eliminó `values-cs` (checo): cubría solo 20 cadenas del proyecto original y habría producido una interfaz mezclada.

**Registro.** Voseo rioplatense consistente ("Tocá", "Elegí", "Podés"), sin lunfardo, con mayúscula solo al inicio de frase como en Material. Glosario fijado: Inicio, Buscar, Biblioteca, Ajustes, Playlist, Cola, Canciones que te gustan, Letras, Respaldo.

**Un archivo de resources por área, con prefijo.** `strings_settings.xml` (`settings_`), `strings_sheets.xml` (`sheet_`), `strings_screens.xml` (`screen_`), `strings_ui.xml` (`ui_`), `strings_system.xml` (`sys_`) más el `strings.xml` base y `strings_changelog.xml`. Permitió extraer en paralelo sin colisiones de claves y facilita ubicar cada texto.

**Nombres de enums.** Los helpers que devolvían texto en inglés (`getDisplayName`, `getName`) se reemplazaron por funciones `labelRes()` que devuelven un id de resource y se resuelven en la interfaz. Los ViewModels que muestran mensajes reciben `@ApplicationContext`.

**Cadenas que quedan en inglés a propósito:** logs, identificadores de canales de notificación, claves de DataStore, URIs de Spotify, y el nombre de la cola automática "Current Queue" que ya está persistido en almacenamiento y no conviene traducir en caliente.

**Números:** 869 entradas de texto en total, con paridad exacta entre español e inglés en los siete archivos.

### 2026-09-04 — 1.1.1 y 1.1.2: el botón de letras no se veía

**Problema.** En el teléfono del mantenedor el reproductor mostraba la tapa, el título, la barra y los controles, y debajo un hueco negro. Faltaban la fila de aleatorio/repetir/favorito y la fila de letras/cola/más.

**Causa.** La hoja del reproductor se ubica con un margen inferior reservado para la barra de navegación, y adentro `PlayerContent` vuelve a aplicar el padding interno del Scaffold, que ya incluye esa barra. El alto se descuenta dos veces. La tapa ocupaba el ancho completo sin mirar el alto disponible, el contenido fijo excedía la columna y las dos últimas filas se dibujaban fuera del área visible.

**Solución.** Primer intento: estimar el alto de lo que va debajo de la tapa y restarlo. Falló en el dispositivo real, la fila de letras seguía afuera porque la estimación de metadatos quedó corta. Solución definitiva: la tapa vive en un contenedor con `weight`, así Compose mide primero todas las filas de alto fijo y la tapa recibe solo el alto que sobra, como el cuadrado más grande que entre. No depende de ninguna estimación ni del padding. Además el layout horizontal ahora dibuja la fila de acciones, que recibía pero nunca renderizaba.

**Lección.** Cuando el problema es "esto no entra", no se resuelve estimando. Se resuelve dejando que el layout mida y ceda por construcción.

**Descartado.** Quitar el doble padding en `MainActivity`. Es la causa de fondo, pero el margen se comparte con la animación del mini reproductor y tocarlo sin probar en varios dispositivos era más riesgo que beneficio. Queda anotado como deuda.

### 2026-09-04 — 1.1.3: la causa real era la barra de navegación

**Qué pasaba de verdad.** Con la 1.1.2 instalada, el mantenedor tocó la zona donde debía estar la fila de letras y las letras se abrieron. La fila existía y recibía toques, pero no se veía. La captura lo mostró: asomaban los 12 dp superiores de la píldora y la punta de los tres íconos. Algo opaco la tapaba de ahí hacia abajo.

**Causa.** En `MainActivity`, en modo vertical con la barra de navegación clásica (`experimentalFloatingNav = false`), `OutifyBottomNav` se compone después de la hoja del reproductor y sin condición de visibilidad. Es un `Surface` opaco, negro con AMOLED, así que pintaba encima de la parte baja del reproductor expandido sin que se notara contra el fondo negro. La variante flotante, que es la predeterminada, sí se oculta cuando el reproductor está expandido. Por eso nadie lo vio antes: hay que haber elegido la barra clásica.

**Solución.** La barra clásica se envuelve en el mismo `AnimatedVisibility` que la flotante: se esconde mientras el reproductor está expandido.

**Sobre 1.1.1 y 1.1.2.** El recorte que corrigieron era real, la fila de aleatorio/repetir/favorito sí estaba fuera de pantalla en la primera captura. Pero no era la única causa, y diagnostiqué la segunda captura con la hipótesis vieja en vez de mirar los píxeles. Dos versiones para llegar a la causa de fondo. La lección queda: cuando un botón responde al toque pero no se ve, no es layout, es algo dibujado encima.

### 2026-09-04 — 1.2.0: tarjeta de letras dentro del reproductor

**Decisión.** El reproductor a pantalla completa pasa a ser una lista desplazable de dos elementos: la vista actual, que ocupa toda la pantalla y no cambia, y debajo una tarjeta "Letras" al estilo de Spotify. La hoja del reproductor ya soportaba contenido desplazable: solo colapsa al arrastrar hacia abajo cuando la lista está en el tope.

**Cómo funciona la tarjeta.** Fondo `primaryContainer`, texto `onPrimaryContainer`, esquinas de 24 dp. Cuerpo de 320 dp con las líneas: la activa en blanco pleno y negrita, las demás al 55 % de opacidad. Auto-scroll centrado en la línea activa con la misma lógica que la hoja completa. El desplazamiento manual dentro de la tarjeta está desactivado para no pelear con el scroll del reproductor; tocar una línea salta a ese momento y el botón de la esquina abre la hoja completa. Se aplica el adelanto y el tamaño de fuente de Ajustes. Con letras sin marcas de tiempo se muestra estática. Sin letras, o con episodios, la tarjeta no aparece.

**Descubierto al implementar.** `PlayerViewModel` declaraba un flujo de letras que nunca se cargaba. Se agregó el cargador: al cambiar la pista, se limpian las letras y se piden al repositorio con un timeout, tragando errores como lista vacía.

**Descartado.** Reutilizar `LyricsViewModel` de la hoja. Está atado al ciclo de vida del popup y a una pista elegida; el reproductor necesita seguir la pista actual.

### 2026-09-04 — 1.3.0: Android Auto

**Aclaración de alcance.** El pedido fue "una versión para pantalla CarPlay". CarPlay es de Apple y solo ejecuta apps de iPhone; una app Android no puede aparecer ahí. El equivalente en Android es Android Auto, y en Android Auto las apps de música no dibujan su propia interfaz: publican un árbol de navegación y el auto lo renderiza con su propio diseño. Por eso no existe una "pantalla" propia que diseñar. Lo que se hizo es que Spoty sea una app de medios válida para Android Auto.

**Lo que había.** El servicio ya publicaba un árbol (Recientes, Canciones que te gustan, Playlists, Artistas), pero el manifest no declaraba compatibilidad con Android Auto, así que el auto nunca lo listaba. Además las playlists aparecían como "Playlist 1, 2, 3", los artistas como "Artista", al abrirlos no había hijos, todo se cargaba bloqueando el hilo del llamador y cada canción se pedía de a una.

**Lo que se hizo.**

- Declaración `com.google.android.gms.car.application` con `automotive_app_desc.xml` de tipo `media`.
- Árbol completo con nombres y portadas reales, hijos para cada playlist y artista, paginación y límites (100 me gusta, 50 recientes, 200 por playlist), carga asíncrona en IO con concurrencia acotada, y pistas de estilo de contenido (grilla para playlists y artistas).
- Reproducción desde el auto: `onSetMediaItems` lee el contexto del ítem elegido y llama a librespot igual que la interfaz. El `Player` propio no anuncia comandos de cambio de ítems, así que la llamada posterior de Media3 es inofensiva.
- Búsqueda por voz con el repositorio de búsqueda existente: pistas reproducibles primero, después artistas y playlists navegables.

**Limitaciones.** No se pudo probar en un auto ni en el emulador Desktop Head Unit desde esta máquina; solo compila. Como la app se instala fuera de Google Play, Android Auto exige activar "Fuentes desconocidas" en sus ajustes de desarrollador. El payload de "recientes" no lo consume ninguna otra parte de la app, así que su forma quedó sin verificar.

### 2026-09-04 — 1.3.1: sonido en una caja iCarPlay y avisos compactos

**El síntoma.** En una caja iCarPlay (Android 12 conectado por USB al auto, con internet compartida desde el teléfono) la canción "reproduce": la barra avanza, los segundos corren, pero no se escucha nada. En el teléfono la misma versión suena. El primer diagnóstico apuntó a Android Auto; no aplica: la caja es un Android completo que manda su pantalla y su audio al estéreo por el protocolo CarPlay, y Spoty corre ahí como en un teléfono.

**Lo que la barra en movimiento demuestra.** librespot decodifica y entrega PCM. El silencio está en la última milla: entre el `AudioTrack` de la app y el hardware de la caja. Dos formas de que eso pase sin que nada falle a la vista:

1. **Foco de audio.** La reproducción que arranca por `spirc.load()` (la interfaz, el auto, el mosaico) nunca pasaba por el camino de Media3 que pide foco; la app sonaba sin tenerlo. El servicio lo pedía una sola vez al crearse y, si otra app se lo quitaba, nunca lo recuperaba (los manejadores estaban comentados). Spotify y YouTube lo piden al momento de dar play, y esas cajas usan justamente esa señal para abrir el canal de audio hacia el estéreo. Ahora el `Player` sincroniza el foco con lo que librespot reporta: al empezar a sonar lo pide; si se lo niegan, pausa; si se lo devuelven, reanuda. Se quitó el pedido manual del servicio para que haya un solo dueño del foco: dos pedidos desde la misma app se pisan entre sí, y al reiniciarse el servicio el segundo pedido le quitaba el foco al primero.
2. **AudioTrack que no se crea.** Si la caja rechaza el formato, cada cuadro se descarta con un log y la barra sigue. Ahora la app lo dice en pantalla una vez por racha, con el motivo, y registra el dispositivo de salida al que quedó enrutado el track.

También se declara explícitamente `allowAudioPlaybackCapture`, por las cajas que espejan el audio capturándolo en vez de enrutarlo.

**Lo que no se pudo verificar.** No hay forma de sacar logs de la caja desde acá. El foco es la causa más probable por diferencia con las apps que sí suenan; la segunda hipótesis queda cubierta por el aviso. Si 1.3.1 sigue en silencio y no aparece ningún aviso, el problema está en el enrutado de la propia caja y hay que mirar sus ajustes de salida de audio.

**Avisos compactos.** Las hojas de "Permitir notificaciones" y "Optimización de batería" tenían un ícono de 112 dp, título grande y dos botones de 58 dp apilados: en una pantalla apaisada y baja cubrían casi todo. Se unificaron en un componente compacto de ancho máximo 400 dp: ícono de 36 dp junto al título, cuerpo corto y los dos botones en una fila.

### 2026-09-04 — 1.3.2: diagnóstico de audio dentro de la app

**Por qué.** 1.3.1 no cambió nada en la caja iCarPlay: play, barra que avanza, silencio. La hipótesis del foco de audio era la más probable desde el código, pero sin logs del dispositivo se está adivinando, y la caja no tiene adb a mano. La app tiene que poder contar sola qué pasó.

**Qué se agregó.**

- `diagnostics/AudioDiagnostics`: un registro en memoria (400 eventos) que además escribe a logcat, y un informe que junta versión y modelo, estado de reproducción, `AudioManager` (volumen de música, modo, dispositivos de salida con tipo y nombre), el estado del `AudioTrack` (estado, cuadros recibidos, bytes escritos, errores, posición de reproducción, underruns, ruta de salida) y las últimas 800 líneas de logcat del propio proceso, que no requieren permiso.
- Instrumentación del motor: creación del `AudioTrack`, cada 500 cuadros PCM un resumen, errores de escritura, fallos de salida, cambios de pista, estado de reproducción de librespot y cada decisión del foco de audio.
- Pantalla Ajustes › Diagnóstico de audio con Compartir (archivo por `FileProvider` a través del selector del sistema), Copiar y Actualizar.
- Dos tonos de prueba que no pasan por librespot: uno con un `AudioTrack` idéntico al del motor y otro con `ToneGenerator` sobre `STREAM_MUSIC`. Son la bifurcación del diagnóstico: si suena el primero, la ruta está bien y el problema es el PCM que entrega librespot; si solo suena el segundo, la caja ignora los `AudioTrack` con atributos de medios de esta app; si no suena ninguno, la caja no enruta el audio de la app.

**Descartado.** Subir el registro a un servidor propio. No hay infraestructura y el selector de compartir alcanza para que el usuario lo mande por mensajería.

### 2026-09-05 — 1.4.0: un solo inicio de sesión, permisos en Ajustes y letras sin controles encima

**Un solo inicio de sesión.** La app tenía dos botones de login: uno para librespot (reproducción) y otro para la Web API (biblioteca, perfil), cada uno con su flujo OAuth, su callback y su archivo (`credentials.json` y `account.json`). El usuario los veía como dos cuentas. Al mirar el código nativo, las 26 scopes que pide librespot incluyen las 10 que pide la Web API y ambos flujos usan el mismo client id. Entonces el flujo de librespot alcanza para los dos: al completar el intercambio del código, Rust guarda las credenciales de librespot y además adopta el mismo token como token de la Web API (`SpotifyClient::adopt_token`). Se usa el token ya refrescado, porque Spotify rota los refresh tokens y el original queda inválido. Un solo botón "Conectar cuenta de Spotify", un solo "Cerrar sesión" que limpia ambos. Si por una instalación vieja quedó una sola mitad conectada, la pantalla pide volver a conectar. El flujo de la Web API (`/account/login`) queda en el código pero la interfaz ya no lo usa.

**Permisos en Ajustes.** Los avisos de notificaciones y batería aparecían en cada arranque mientras no estuvieran concedidos, sin memoria de que el usuario los había rechazado, y en la pantalla del auto tapaban el reproductor. Se quitaron del arranque. Ahora son dos filas en Ajustes › Permisos que lanzan el pedido del sistema y muestran "Ya está concedido" cuando corresponde. Descartado: guardar un "no volver a preguntar"; sin aviso automático no hace falta.

**Letras: modo y controles.** La hoja de letras tenía dos pastillas "Sincronizadas / Estáticas" como estado local que se perdía al cerrar, y los botones de anterior, pausa y siguiente flotaban sobre el texto (96×72 dp el central), con la barra de progreso en una segunda fila. En apaisado o en el auto, los controles se dibujaban encima de las líneas que se estaban cantando. Decisiones: el modo pasa a ser un ajuste persistente (Ajustes › Reproducción › Letras, "Letras sincronizadas", activado por defecto) y desaparece de la hoja; los controles pasan a una única fila al pie, después de la lista y no encima, con iconos de 40 y 44 dp y la barra de progreso en la misma fila. La lista ya no necesita reservar 160 dp de padding inferior.

### 2026-09-05 — 1.5.0: vista apaisada de letras a pantalla completa

**Pedido.** La vista apaisada de 1.4.0 (reproductor a la izquierda, lista a la derecha, controles compactos en la hoja de letras) gustó y se mantiene como opción. Pero en el auto lo que se quiere mirar es la letra, grande, sin nada encima, y solo tres botones: anterior, pausa, siguiente.

**Decisión.** Un ajuste nuevo en Interfaz, "Vista apaisada", con dos valores: "Letras a pantalla completa" (por defecto) y "Reproductor y lista". Con el primero, al estar apaisado y con una canción sonando, la app muestra una pantalla propia (`LandscapeLyricsScreen`) que reutiliza la misma lista de letras de la hoja (auto-centrado, toque para saltar, anticipación y tamaño de texto de Ajustes) y sigue sola el cambio de canción. Abajo, tres botones y nada más: ni barra de progreso ni tiempos, porque en el auto no se busca dentro de la canción.

**Cómo se sale y se vuelve.** La flecha de arriba a la izquierda (o el botón atrás) muestra la vista de reproductor y lista para elegir otra música; un botón flotante de letras vuelve a la pantalla completa, y al empezar a sonar algo después de estar sin nada, la pantalla completa vuelve sola. Se descartó tapar la navegación de forma permanente: en la caja del auto no hay otra forma de elegir canciones.

**Registro de audio.** El registro recibido (spoty-audio-20260905-215335) es del Samsung, no de la caja: AudioTrack creado, escrituras sin errores, posición avanzando, salida por parlante, foco de audio concedido en cada play. Sirve como línea base sana para comparar contra el que salga de la caja.

### 2026-09-05 — 1.5.1: la hoja de letras a todo el ancho

`ModalBottomSheet` de Material 3 limita el ancho de la hoja a 640 dp. En vertical no se nota; en apaisado la hoja de letras quedaba centrada con el reproductor asomando a ambos lados. Se pasa `sheetMaxWidth = Dp.Unspecified` para que cubra la pantalla completa. La pantalla apaisada nueva de 1.5.0 no tenía este límite porque no es una hoja.

### 2026-09-06 — 1.6.0: letras de LRCLIB cuando Spotify no las tiene

**Problema.** Para canciones sin letra en Spotify (por ejemplo "Duel - Stephen Lipson's Digital Variation" de Propaganda) la hoja de letras quedaba completamente negra: solo los episodios tenían un mensaje de "sin letra", y el repositorio devolvía una lista vacía tanto para "no existe" como para "se venció el tiempo" o "no se pudo leer la respuesta".

**Decisión.** Un solo punto de entrada para letras (`LyricsRepository`) que consulta Spotify primero y, si no hay resultado, pide la letra a lrclib.net. El resultado deja de ser una lista y pasa a ser un tipo con tres salidas: encontrada (con origen y si está sincronizada), no encontrada, y error transitorio. La pantalla muestra "Buscando la letra…" mientras corre la búsqueda y "No encontramos la letra de esta canción" si ninguna fuente la tiene. Cuando la letra viene de LRCLIB, aparece una leyenda chica "Letra provista por LRCLIB" debajo del artista, en la hoja, en la vista apaisada y en la tarjeta del reproductor. Una letra en texto plano se muestra fija y sin controles de salto, porque no tiene tiempos.

**Por qué LRCLIB y solo LRCLIB.** Es gratuito, no pide clave, devuelve letra sincronizada en formato LRC y permite pedir coincidencia exacta con título, artista, álbum y duración, que ya tenemos de Spotify. Primero se pide `/api/get` (coincidencia exacta); si responde 404, `/api/search` por título y artista y se elige el candidato con duración a ±5 segundos, prefiriendo el que tenga tiempos. Se descartaron por ahora los otros proveedores del proyecto hermano (KuGou, BetterLyrics, Paxsenix): sumarían latencia y superficie sin evidencia de que hagan falta.

**Privacidad.** El fallback manda título, artista, álbum y duración a un tercero, así que es un interruptor en Ajustes › Reproducción › Letras ("Buscar letras en LRCLIB"), activado por defecto, y la descripción dice qué se envía. Apagado, no sale ninguna petición.

**Caché.** Se recuerdan por canción tanto las letras encontradas como las que no existen (hasta 64 entradas), compartidas entre la tarjeta del reproductor, la hoja y el detalle de pista. Un "no encontrada" solo vale para la combinación de proveedores con la que se calculó: si se enciende el interruptor después, se vuelve a buscar. Los errores transitorios no se guardan, para reintentar la próxima vez.

**Supuesto documentado.** La capa nativa devuelve `null` tanto cuando Spotify responde 404 como cuando la petición falla; no se tocó Rust para distinguirlos (obligaría a recompilar la librería nativa). Se toma `null` como "no existe": el costo de equivocarse es recordar un "sin letra" durante una caída, y el fallback se consulta de todas formas.

**Se aprovechó para** proveer un `OkHttpClient` único por Hilt (antes `Recommendations` creaba el suyo) y agregar los primeros tests unitarios del proyecto: parser LRC y selección de candidato por duración.

### 2026-09-06 — 1.7.0: Me gusta desde la pantalla de letras

**Pedido.** Un corazón en la pantalla de letras para marcar la canción como Me gusta sin volver al reproductor.

**Decisión.** El corazón va en la cabecera, a la derecha, en la hoja vertical y en la vista apaisada, con el mismo círculo que la flecha de volver. Refleja la canción mostrada (`displayedTrack`), no la que suena: si la hoja se abrió desde el detalle de una pista, el corazón habla de esa pista. Se oculta para episodios de podcast, que van a otra lista.

**Un solo mecanismo.** El reproductor ya tenía la lógica de alternar: actualización local optimista, llamada a Spotify y vuelta atrás si falla. Estaba escrita dentro del ViewModel del reproductor. Se movió a `LikedRepository.toggleTrackLiked`, y tanto el reproductor como la pantalla de letras la llaman. Se descartó copiar el bloque en el ViewModel de letras: ya hay copias parecidas en varios ViewModels de detalle y en `MainViewModel`, y sumar otra era seguir cavando. Unificar esas otras copias queda pendiente; no se tocaron para no mezclar cambios.

**Qué no se hizo.** No hay aviso de error cuando Spotify rechaza el cambio: el corazón vuelve solo a su estado anterior, igual que en el reproductor. Sumar el aviso implica decidir dónde mostrarlo en una hoja modal y en la vista del auto, y eso merece su propia entrada.

### 2026-09-06 — 1.7.1: el diagnóstico explica los cuelgues

**Causa.** Apareció un "Spoty no responde" en la pantalla de letras. El informe de diagnóstico que llegó después no servía: la sección de logcat solo cubre el proceso vivo, y el cuelgue había ocurrido en un proceso anterior que Android ya había matado. No había forma de saber en qué estaba trabado el hilo principal.

**Decisión.** El informe suma la sección "Process exits": las últimas diez salidas del proceso según `ActivityManager.getHistoricalProcessExitReasons`, con fecha, motivo, estado, importancia y memoria. Para los ANR y los crashes nativos se adjunta el volcado que Android guarda: las primeras 300 líneas y, si el bloque del hilo `main` queda fuera de esa ventana, ese bloque completo, con un tope de unos 40 KB por traza. Además, al arrancar se escribe una línea `ProcessExit` en logcat con el motivo del cierre anterior.

**Qué se descartó.** Leer `/data/anr` directamente: no es accesible sin root. Adjuntar la traza entera: un volcado de ANR pasa fácil de 200 KB y ahoga el informe que se comparte por WhatsApp.

**Lección.** Un informe de diagnóstico tiene que sobrevivir al reinicio del proceso. Todo lo que se pierda con la muerte del proceso hay que pedírselo al sistema en el arranque siguiente.

### 2026-09-06 — 1.7.2: la app respeta los límites de Spotify

**Causa.** Tras varias reinstalaciones y logins seguidos, Spotify devolvió 429 ("API rate limit exceeded") en el perfil, los artistas top y los guardados. La app no lo distinguía de un error cualquiera: la sincronización de Me gusta reintentaba tres veces con espera creciente ante cualquier excepción (`isTransient = true` fijo), varias pantallas la disparaban a la vez, y la cuenta aparecía como no conectada aunque el token OAuth estaba bien. El mismo informe mostró `get_current_user` corriendo en el hilo principal con "Skipped 44 frames": el origen del "Spoty no responde".

**Decisión.** Cuatro capas. (1) En Rust, un único `ensure_success` para todas las llamadas a api.spotify.com: un 429 lee `Retry-After` (30 s si falta), arma una ventana global (`RATE_LIMIT_UNTIL_MS`, expuesta por JNI como `getRateLimitUntilMs`) y devuelve `SpotifyApiError::RateLimited`; el JSON de error lleva `retry_after_seconds`. (2) En Kotlin, `RateLimitGate` combina esa ventana con la que arma `NativeErrorHandler`; todo pedido de cuenta pregunta antes. (3) La sincronización clasifica el error (`SyncErrorClassifier`): 429 corta en seco, red reintenta, el resto falla rápido; y pasa por `LikedSyncCoordinator`, un solo punto con mutex, coalescencia y anti-rebote de 60 s. (4) La pantalla de Cuentas avisa con cuenta regresiva y deshabilita el botón de conectar mientras dure. Además, todas las llamadas JNI de red que vivían en `viewModelScope` sin `Dispatchers.IO` pasaron a segundo plano.

**Qué se descartó.** Un reintento con espera exponencial ante el 429: Spotify ya dice cuánto esperar y reintentar solo alarga el castigo. Guardar la ventana en disco: un 429 dura segundos o minutos, no vale la pena sobrevivir al reinicio. Tocar el sync de playlists (`syncLikedPlaylists`), que va por otra vía.

**Lección.** Un 429 no es un error, es una instrucción. El cliente que la ignora se convierte en el problema que Spotify está intentando frenar.

### 2026-09-06 — 1.7.3: correcciones de la auditoría de código

**Causa.** Una auditoría estática independiente sobre 1.7.2 (commit 6058d40) señaló siete hallazgos y dos mejoras de uso. Cada uno se revalidó contra el código antes de tocarlo; los siete se confirmaron. Se trabajó en bloques chicos, uno por hallazgo, cada uno con prueba de regresión, corrección y commit propio.

**Me gusta y hilo principal (164ff0e).** `MainViewModel.favorite` llamaba a `removeLikedEpisode` también para canciones: el remoto quitaba el Me gusta pero la tabla local de canciones no cambiaba. Se extrajo un único procedimiento optimista (`OptimisticLikeToggle`: leer, invertir en local, llamar al remoto, revertir si falla) y todos los corazones pasan por `LikedRepository.toggleTrackLiked`/`toggleEpisodeLiked`, serializados por ítem. La radio y el Me gusta desde notificación y Android Auto corrían JNI de red sobre `Dispatchers.Main`; ahora van por repositorio en IO con tope de tiempo, y el comando de la sesión de medios devuelve un futuro que se completa recién con el resultado confirmado. Se anota en código que mover a IO no cancela la llamada nativa.

**Búsqueda (441fae7).** Las secciones se lanzaban sobre `viewModelScope` y sobrevivían al cambio de consulta; el texto se leía de un flujo mutable y los resultados se publicaban por encabezado, sin identidad. `SearchOrchestrator` ata cada búsqueda a un texto inmutable, corre las secciones como hijas de `collectLatest` y aplica cada publicación solo si la consulta vigente coincide. Borrar el campo vuelve a `Idle` y rechaza lo tardío. La cancelación no se traga; "sin coincidencias" es un resultado vacío, no un error, y el error distingue red, límite de Spotify y otros.

**Caché de letras (61372ee).** El atajo `cached(track)` del ViewModel saltaba la validación por configuración que sí hacía el repositorio. La validez vive ahora solo en `LyricsRepository`: las entradas guardan con qué configuración se calcularon y cuándo; un "no encontrada" vale seis horas y solo con la misma configuración; los errores no se guardan; las consultas simultáneas de la misma canción comparten un único pedido. La UI separa "no encontrada" de "falló, reintentá".

**Repetición y aleatorio en Media3 (4a21add).** El reproductor anunciaba `COMMAND_SET_REPEAT_MODE` pero `handleSetRepeatMode` devolvía éxito sin hacer nada, y el estado publicado siempre era "apagado". `PlaybackModeController` es el único dueño del cambio: aplica en Spirc, y solo si acepta persiste en ajustes y actualiza el estado; los comandos estándar, los botones personalizados y los de la app lo comparten. El Player publica el modo real y Media3 emite los cambios a los controladores. Se descartó dejar de anunciar los comandos: Spirc ya soportaba todo. Pendiente fuera de alcance: escuchar `RepeatChanged`/`ShuffleChanged` desde Rust para reflejar cambios hechos desde otro dispositivo Connect.

**Reproducir a continuación (0f0ab13).** `playNext` pasaba el tema insertado como `playingTrack` a `setQueue`; librespot resolvía el índice 0 contra el contexto anterior, reemplazaba la canción actual, borraba el historial y reposicionaba. Se separó el contrato: `insertNext` (JNI `Spirc_insertNext`) nunca toca lo que suena; `setQueue` queda documentado como reemplazo. Un plan puro (`queue_plan.rs`) elige `add_to_queue` cuando no hay encolados al frente. **Subcaso cerrado con el submódulo (autorizado):** cuando ya hay temas encolados al frente, la única vía sin tocar librespot era `set_queue(..., None)`, que borraba el historial previo. Con autorización explícita se agregó a `rust/deps/librespot` (rama `spoty-play-next`, commit 2543678) `ConnectState::add_to_queue_front` y `SpircCommand::PlayNext`, y el plan ahora emite `play_next` por URI en orden inverso para conservar el orden pedido. Se retiró el código de resultado "insertado, historial borrado" de Rust, JNI y Kotlin. El puntero del submódulo apunta a un commit que por ahora existe solo en esta máquina: hasta publicarlo en un fork (y apuntar `.gitmodules` ahí) o subirlo a iTomKo/librespot, un clon limpio no puede resolverlo. Sin nada sonando no se inicia reproducción a escondidas: se avisa.

**Recuperación de audio (d62cf6e).** Las escrituras cortas solo contaban; el búfer se reiniciaba tras cada cuadro y el resto se perdía; un `AudioTrack` muerto se reutilizaba porque seguía reportándose inicializado. `PcmWriter` es una política pura bajo un solo candado: conserva el resto pendiente (tope de 256 KB), espera acotada ante escrituras de cero y declara estancamiento tras veinte seguidas, reconstruye la salida ante `ERROR_DEAD_OBJECT` con máximo de tres por ventana de 30 s y luego informa una sola vez, y nunca escribe sobre una salida liberada. El informe de diagnóstico suma `recovery:`. **No se afirma** que esto explique el silencio en el auto: solo lo hace recuperable y observable; falta comprobarlo en el dispositivo.

**Mejoras de uso (4be2343).** `PlaylistMetadataHelper` esperaba la red aunque tuviera copia; ahora `CacheFirstLoader` emite la copia al instante, refresca en segundo plano con vigencia de 15 minutos y, si falla, conserva lo que había e informa el tipo de falla. Inicio, Me gusta, playlists y búsqueda muestran un aviso con reintento en vez de vaciar la pantalla; el ticker del límite de Spotify se unificó en `RateLimitGate.remainingSecondsFlow`.

**Qué se descartó.** Refactorizaciones fuera del objetivo, frameworks nuevos y rediseños. Optimizaciones sin medición. Modificar el submódulo de librespot sin autorización (se hizo solo tras autorización explícita, y con un cambio mínimo).

**Lección.** Una auditoría estática señala dónde mirar, no qué pasó en el dispositivo. Cada hallazgo se revalidó con el código en la mano y se cubrió con una prueba que falla antes y pasa después; lo que solo se puede comprobar con el teléfono quedó escrito en `docs/manual-tests-1.7.3.md` como pendiente, no como resuelto.

### 2026-09-08 — 1.7.4: auditoría de consumo de la Web API tras 429 persistentes

**Causa.** Con 1.7.3 en el teléfono, los 429 seguían apareciendo en cada arranque aunque la app "respetaba" la ventana. Una auditoría independiente del consumo de la Web API (Rust y Kotlin) encontró que la ventana se re-armaba sola: `smartTransfer` pedía `/me/player/devices` en cada inicio de Spirc sin consultar el gate y sin manejar el error (un JSON de 429 tiraba una excepción dentro del scope del wrapper); cada toque en la barra inferior apilaba una entrada nueva de navegación, con un ViewModel nuevo y sus colectores vivos, así que Inicio, Búsqueda, Me gusta y Biblioteca repetían sus pedidos por cada toque; el historial de búsqueda se volvía a resolver contra la red en cada escritura del DataStore, no solo cuando cambiaba el historial; el token de acceso no tenía caché ni single-flight, de modo que llamadas concurrentes lo renovaban varias veces; y los helpers de metadatos reintentaban cinco veces en Kotlin un 429 que el lado nativo ya reintentaba, apilando pedidos dentro de la ventana. Sumaban: la Biblioteca pedía el rootlist dos veces por entrada, los detalles de episodios de un podcast salían uno por episodio sin gate, y el perfil de Inicio se pedía también en el camino que sirve caché.

**Decisión.** En Rust, el cliente de la Web API se niega a enviar mientras la ventana de 429 está abierta y devuelve el mismo JSON de límite con los segundos restantes; solo dos natives de sonda (`probeCurrentUserProfile`, `probeUserTop`), usados por el botón de diagnóstico, la saltan. El token se cachea en memoria, se renueva una sola vez a la vez y 60 s antes de vencer, y una renovación fallida no se repite por dos minutos. En Kotlin: la barra inferior vuelve a la entrada existente de la pestaña (se descarta lo que hay por encima) en vez de apilar; el historial de búsqueda pasa por `distinctUntilChanged` y el ViewModel reutiliza lo ya resuelto; `smartTransfer` consulta el gate y trata cualquier falla como "no transferir"; los detalles de episodios, los guardados de la Biblioteca, la lista de dispositivos y la navegación de Android Auto consultan el gate antes de salir a la red y cortan al primer 429; se quitó `retryOnRateLimit`; la Biblioteca hace un solo rootlist por ViewModel; un álbum con pistas en caché se verifica contra el remoto una vez por proceso; el perfil de Inicio se carga una vez por instancia salvo refresco forzado.

**Qué se descartó.** Agrupar los pedidos de metadatos por lotes y unificar las secciones de búsqueda en una sola llamada `/v1/search` combinada: reducen más todavía el volumen, pero cambian contratos nativos y quedan como pendientes con su propia medición.

**Lección.** Respetar la ventana no alcanza si algún camino la re-arma en cada arranque: hay que auditar quién llama, cuántas veces y desde qué ciclo de vida. Un ViewModel apilado no es visible en pantalla pero sigue pidiendo; y un reintento encima de otro reintento no es resiliencia, es carga duplicada.

### 2026-09-08 — 1.7.5: los controles no desaparecen cuando falta la letra

**Causa.** En la hoja de letra vertical (`LyricsBottomSheet`), la barra de transporte (anterior, reproducir/pausar, siguiente y la barra de progreso) se mostraba solo si `hasSyncedContent && isCurrentTrack`. Cuando la canción no tenía letra sincronizada (o no tenía letra), `hasSyncedContent` era falso y desaparecían todos los controles, aun con el tema sonando. La condición mezclaba dos cosas distintas: si hay letra con tiempos y si el tema mostrado es el que suena. La pantalla horizontal no tenía el problema porque mostraba los controles siempre.

**Decisión.** Se separó el concepto: los controles se muestran cuando el tema mostrado es el que suena (`isCurrentTrack`), tenga letra o no; el resaltado de líneas y el salto al tocar una línea siguen dependiendo de que haya tiempos (`canSeekLines = hasSyncedContent && isCurrentTrack`). El resto del comportamiento queda igual: un tema que no es el actual no muestra controles de transporte, como antes.

**Qué se descartó.** Corregir la pantalla horizontal (ya estaba bien) y tocar el string de "no hay letra" (era correcto; el grep lo mostraba raro por los saltos de línea).

**Lección.** Una sola bandera que gobierna dos comportamientos distintos termina ocultando uno cuando falla el otro. Si un control depende de "el tema suena" y otro de "hay letra con tiempos", son dos condiciones, no una.

### 2026-09-08 — 1.7.6: la reproducción fallaba en algunos equipos por la carpeta temporal

**Causa.** En un equipo de prueba (Doro, Android 12) la reproducción fallaba en cada tema con `PermissionDenied` sobre `/data/local/tmp/.tmpXXXX`, reintentando una vez por segundo. librespot escribe cada descarga en un `NamedTempFile` dentro de `SessionConfig.tmp_dir`, y nuestro `SessionConfig` no lo fijaba, así que quedaba el valor por defecto: `std::env::temp_dir()`. En Android eso resuelve a `/data/local/tmp` en algunos fabricantes, una carpeta que una app normal no puede escribir. En el Galaxy S24 el sistema apuntaba esa variable a algo escribible y por eso no se notaba; el bug estaba en todos los equipos donde no era así.

**Decisión.** Fijar `tmp_dir` a la carpeta de caché propia de la app (`getCacheDir`), que siempre es escribible, en lugar de depender de lo que devuelva `std::env::temp_dir()` según el fabricante.

**Qué se descartó.** Pedir permisos de almacenamiento o usar almacenamiento externo: innecesario, el caché interno alcanza y no requiere permisos.

**Lección.** Un valor por defecto que depende del entorno (`temp_dir()`) es una bomba de tiempo entre fabricantes: lo que anda en un equipo no prueba que ande en todos. Las rutas de escritura de una app Android tienen que ser explícitas y propias.

### 2026-09-08 — 1.7.7: controles de tipografía propios para la letra

**Causa.** El tamaño de fuente general de la app (Apariencia) no afectaba la letra: la letra tiene su propio ajuste de tamaño, que estaba escondido en Ajustes de reproducción. El usuario agrandó la fuente desde Apariencia esperando que la letra creciera y no cambiaba nada, porque el control que la gobierna vivía en otra pantalla.

**Decisión.** Mover el tamaño de la letra a Apariencia, junto al tamaño general y bajo un grupo propio, y sumar dos controles nuevos que aplican solo a la pantalla de letra (nunca a toda la app): negrita y selector de tipografía con familias del sistema (Sans/Serif/Monoespaciada). Los tres ajustes van a DataStore y se reflejan en vivo en la hoja de letra y en la pantalla horizontal; una única línea de vista previa refleja tamaño, negrita y tipografía juntos.

**Qué se descartó.** Aplicar la negrita y la tipografía a toda la app (es un cambio de theming global y riesgoso, ajeno al pedido), y empaquetar fuentes propias como Atkinson Hyperlegible (suma assets y peso; queda como posible mejora futura si se pide una tipografía específica). Las familias genéricas de Compose están siempre presentes y no agregan riesgo.

**Lección.** Un ajuste que el usuario espera global no puede vivir escondido en otra pantalla: si el usuario agranda la fuente desde Apariencia, la letra tiene que estar donde la busca. Y las familias genéricas de Compose dan variedad legible sin sumar assets ni riesgo de theming.

### 2026-09-08 — 1.7.8: marca "Spoty by Darius" en todas las pantallas y controles más grandes en el auto

**Causa.** El usuario pidió que la app lleve su identidad visible: la palabra "Spoty" y, al lado, "by Darius", en la esquina superior derecha de todas las pantallas.

**Decisión.** Un único componente `SpotyBrand` (Spoty en negrita + "by Darius" en pequeño, una sola línea) integrado por familia de cabecera: en las `actions` de cada `TopAppBar` de ajustes; dentro de `CollapsingHeader` (a la izquierda del botón de acción cuando existe), lo que cubre Biblioteca, Favoritos, Playlist, Perfil, Álbum, Artista, Podcast y Pista de una sola vez; en la fila de iconos del encabezado de Inicio; en el encabezado de Búsqueda (una fila propia sobre la barra, para no achicar el buscador); superpuesto en la zona superior del reproductor expandido en vertical y horizontal, atenuándose junto con la hoja para que el mini reproductor no lo muestre; y en la fila superior de la letra horizontal.

**Qué se descartó.** Una única superposición global en `MainActivity`: habría chocado con las acciones que cada pantalla ya tiene arriba a la derecha (los iconos de ajustes y cuenta en Inicio, los botones de acción de las cabeceras colapsables, el botón de filtros en Búsqueda). También meter la marca en la barra de estado: esa franja es del sistema.

**Lección.** Un elemento "en todas las pantallas" no es un overlay: cada familia de cabecera tiene su propio dueño del rincón superior derecho y la marca tiene que convivir con él. Un componente, muchos puntos de anclaje, cero colisiones.

**Ampliación de la misma versión: controles del auto.** El usuario pidió que la letra a pantalla completa en horizontal, la que usa en el auto, tenga botones más grandes y un botón de aleatorio en el extremo izquierdo, porque al volante cuesta acertarles. Se agrandaron los tres botones de transporte (anterior y siguiente a 64 dp, reproducir a 80 dp, iconos en proporción) y los dos de la cabecera (48 dp), y se añadió el aleatorio anclado al borde izquierdo con fondo relleno cuando está activo, igual que en el reproductor. Se descartó abrir una 1.7.9 aparte: la 1.7.8 todavía no había salido, así que ambos cambios viajan juntos. Lección: esa pantalla tiene su propio `ViewModel`, así que las acciones de modo de reproducción se exponen ahí a través de `PlaybackModeController`, el mismo singleton que usa el reproductor; nunca un segundo camino para conmutar el aleatorio.

**Ampliación de la misma versión: botón "siguiente" del volante.** El usuario reportó que en el auto los botones físicos del volante funcionan con la app oficial de Spotify y no con Spoty. La lectura del código, contrastada con el bytecode de Media3 1.11.0, confirmó una causa para "siguiente": `Player.getState()` publicaba una línea de tiempo de un solo elemento, y `BasePlayer.seekToNext()` ignora la orden cuando no hay elemento siguiente, antes de llegar siquiera a `handleSeek()`. La cola real vive en librespot, y el botón de la app llama a `spirc.playerNext()` directo, sin pasar por la sesión, por eso nadie lo notó. Decisión: publicar un segundo elemento en la línea de tiempo (la pista siguiente cuando la cola local la conoce, o un marcador "Siguiente" que librespot reemplaza al reportar el cambio de pista) y mapear los saltos por índice a anterior/siguiente. Se descartó sobreescribir `seekToNext()`, porque `BasePlayer` lo marca `final`, y un interceptor global de teclas en la Activity, porque las teclas del auto no pasan por la Activity. Reproducir/pausar y volumen se ven correctos en el código: quedaron instrumentados en el informe de diagnóstico (teclas recibidas por la sesión, por la Activity y órdenes que llegan al reproductor) a la espera de un informe desde el box. Lección: toda orden de transporte debe probarse por el camino de la MediaSession, no solo con el botón de la app.

### 2026-09-09 — 1.7.9: ocultar canciones y discos para siempre, botón de inicio

**Decisión.** Un botón de menos (`RemoveCircleOutline`/`RemoveCircle`) junto a cada corazón de "me gusta" (reproductor, letra vertical y horizontal, hoja de información de pista, encabezado de álbum, encabezado de pista) oculta una canción o un disco para siempre: la tabla `hidden_items` (uri, tipo, fecha) guarda el estado, `HiddenItemsRepository` expone el conjunto de uris ocultas como `StateFlow` caliente, y cada lista de la app (Inicio, Búsqueda, Artista, Álbum, Playlist, Favoritos, el árbol de Android Auto) se filtra contra ese conjunto. `Player.onTrackChange` salta automáticamente cuando el tema entrante está oculto (por sí mismo o por su disco), con un tope de 5 saltos seguidos para no girar en falso si toda la cola está oculta. Ocultar un disco pide confirmación con `AlertDialog`, porque es una acción grande y fácil de tocar por error manejando; ocultar una canción es inmediato. Una pantalla nueva en Ajustes ("Ocultos") lista lo oculto por tipo y permite restaurar uno por uno o todo junto.

Además, un botón de inicio (`Icons.Rounded.Home`) se agregó a la izquierda de la marca `SpotyBrand` en todas las pantallas: cierra cualquier popup abierto, colapsa el reproductor si está expandido y selecciona la pestaña de Inicio, a través de un `CompositionLocalProvider(LocalGoHome ...)` instalado una sola vez en `MainActivity`. La fila completa de la marca es clickeable con la misma acción, no solo el ícono, para que sea fácil de tocar en el auto.

**Causa.** El usuario pidió una forma de dejar de ver o escuchar contenido que no le interesa (temas que no le gustan, discos que no quiere en su biblioteca) sin tener que desenrolarlo del "me gusta", y una forma rápida de volver al inicio desde cualquier pantalla sin usar la barra inferior, útil manejando.

**Qué se descartó.** Ocultar en el servidor: la API Web de Spotify no tiene un concepto de "ocultar" para ítems arbitrarios (solo "guardado"/"no guardado", que ya usa el corazón), así que la única opción real es local. Filtrar en una única capa de metadatos (`Metadata`/`TrackMetadataHelper`) en lugar de en cada ViewModel: se descartó porque esas mismas llamadas tienen que seguir resolviendo los ítems ocultos para la pantalla de restauración y para saber si el tema que está sonando está oculto; filtrar en el origen los habría hecho invisibles también ahí.

**Lección.** Un filtro que se aplica en el borde equivocado (la fuente de datos compartida) rompe al primer consumidor que necesita ver lo filtrado (la pantalla de restaurar). El filtro va donde se decide qué se muestra, no donde se decide qué existe.

**Ampliación de la misma versión: botones del volante, segunda vuelta.** El primer informe de diagnóstico desde la caja del auto (Doro, Android 12) mostró que cada pulsación de "siguiente" y "anterior" del volante llegaba a `onMediaButtonEvent` de la sesión (keyCode 87 y 88, `from=android`) y que nunca aparecía la línea `handleSeek` del reproductor: Media3 recibía la tecla y la descartaba en su manejo por defecto, con la línea de tiempo de dos elementos ya publicada. Se leyó la fuente de Media3 1.11.0 (`MediaSessionImpl.onMediaButtonEvent`, `applyMediaButtonKeyEvent`, `MediaSessionLegacyStub.onSkipToNext`, `ConnectedControllersManager.isPlayerCommandAvailable`) sin encontrar una condición que explique el descarte desde el código; el eslabón exacto sigue sin identificarse. Decisión: no depender de ese camino. La sesión aplica ella misma las teclas de transporte: siguiente y anterior van directo a `spirc` (la cola real vive en librespot), reproducir/pausar pasan por el `Player` de Media3 para conservar foco de audio y notificación, y se consume también el evento de soltar la tecla. Se pierde el doble toque de auriculares como "siguiente"; se aceptó. Cada pulsación deja en el informe el estado del reproductor justo antes (ventanas de la línea de tiempo, `hasNext`, comando disponible) para poder cerrar el diagnóstico con el próximo informe. Lección: cuando la evidencia de campo contradice la lectura del código, primero se hace funcionar el botón y después se sigue investigando; el usuario maneja mientras tanto.

**Ampliación de la misma versión: la búsqueda que fallaba en silencio.** Ese mismo informe mostró seis `NullPointerException: Attempt to get length of null array` por cada búsqueda. La función nativa `search` devolvía `null` ante cualquier error de la API (token, límite de peticiones, respuesta inesperada) y Kotlin la declaraba como arreglo no nulo, así que el motivo real se perdía y la pantalla mostraba el error genérico. Decisión: la JNI lanza una `RuntimeException` con el mensaje de Spotify, Kotlin declara el retorno como anulable y trata el `null` como fallo explícito, y el clasificador de errores ya reconoce los mensajes de límite de peticiones. Además, el informe de diagnóstico filtra el ruido del sistema del logcat (One UI lo inunda con `setRequestedFrameRate`) y anota cada fallo de búsqueda con su excepción y causa en la sección de eventos, que sobrevive a esas inundaciones. Lección: un `null` que cruza la frontera nativa sin mensaje es un error que nadie va a poder depurar desde un informe.

**Ampliación de la misma versión: el motivo real de la búsqueda.** Con el error ya visible, el siguiente informe desde la caja lo dijo en una línea: `refresh_token failed with status 400: invalid_client`. La caja del auto guardaba un token de la app de desarrollo anterior de Spotify; el build actual renueva con el id de la app nueva, y Spotify rechaza el cruce. Ninguna reintento iba a arreglarlo, y la app no lo decía: cada búsqueda, favorito y lista fallaba con un mensaje genérico. Decisión: cuando la renovación devuelve `invalid_client` o `invalid_grant`, el nativo borra el token guardado (`account.json`) y la caché, así `isOAuthAuthenticated` pasa a falso y la app vuelve a ofrecer "Conectar" en Inicio; la búsqueda además muestra un mensaje propio ("Spotify rechazó la sesión de la cuenta"). Se descartó reintentar con el id anterior: la app no lo conoce y no debería. Lección: un token que el servidor rechaza de forma definitiva no es un error transitorio; conservarlo solo convierte un login de treinta segundos en días de "la búsqueda falló".

**Ampliación de la misma versión: el informe en dos archivos y trazas de letras.** El informe de una sola pieza superaba lo que se puede pegar en un mensaje y la parte útil quedaba truncada. Ahora se comparte como dos archivos: `1-summary` (dispositivo, estado, eventos, cierres del proceso) y `2-logcat`. El resumen entra entero. Y como en la caja la letra decía "no encontrada" sin que se supiera si LRCLIB llegó a consultarse, `LyricsRepository` anota en los eventos qué respondió Spotify, si el respaldo estaba habilitado y qué respondió LRCLIB.

### 2026-09-10 — 1.7.10: admisión nativa de solicitudes durante una pausa de Spotify

**Decisión.** Todos los envíos del cliente Web API pasan por una admisión nativa compartida: consulta el plazo después de obtener el token y antes de cada envío, incluidos los reintentos por 401. Serializa hasta recibir los encabezados, registra el 429 una sola vez y libera el turno antes de leer el cuerpo. La renovación del token participa porque ya comparte ese plazo; una renovación en espera no vuelve a enviar durante la pausa. Las sondas manuales conservan la excepción de admisión pública, pero no omiten la serialización ni el registro del 429. Si necesitan renovar el token, esa renovación respeta la pausa.

**Contexto.** El control anterior ocurría antes de esperar el token y no se repetía en algunos reintentos. Además, registraba el 429 después de consumir el cuerpo. Esas ventanas permitían nuevos envíos cuando ya se conocía una pausa. La prueba nativa usa el código de producción con respuestas HTTP locales controladas; el permiso Unix del archivo de sesión sigue siendo 0600 en Android, y se condiciona por plataforma para compilar las pruebas en Windows.

**Alternativas y límites.** Se descartó agregar controles dispersos: no ordenan la admisión con la recepción de otro 429. La serialización puede aumentar la latencia hasta recibir encabezados; no retiene el turno durante la lectura del cuerpo ni la espera del token. Esto corrige la política local durante una pausa conocida, no garantiza la cuota global de Spotify. Persistencia entre procesos, reloj y política de sondas quedan fuera de esta corrección.

**Protección ante esperas de red.** La renovación usa el mismo tiempo máximo de cinco segundos que los demás envíos. Así, un servidor que no devuelve encabezados no retiene indefinidamente la admisión compartida. Una prueba con conexión local abierta y encabezados retenidos verifica el vencimiento y la liberación del turno.

### 2026-09-10 — 1.7.11: persistencia y restauración de la pausa compartida

**Decisión.** El cliente nativo notifica cada extensión del plazo absoluto a Kotlin mediante una referencia JNI global. La notificación no depende del resultado público de la operación, por lo que también cubre los errores booleanos. Un único recolector de estado guarda los cambios en DataStore en orden; los valores intermedios obsoletos pueden confluir en el más reciente.

**Inicio.** Primero se lee el plazo guardado y después se restaura la autoridad nativa, antes de publicar el cliente Web API. Un fallo de lectura interrumpe esa inicialización y queda registrado como error; no se sustituye por un plazo vacío. Las excepciones del callback se registran y limpian sin eliminar la pausa nativa.

**Límite.** La notificación no espera al disco: una terminación del proceso antes de completar el guardado puede perder la última extensión. No se garantiza atomicidad entre recibir un 429 y persistirlo. No cambia la política de sondas, cierre de sesión ni reloj.

### 2026-09-10 — 1.7.12: búsqueda sincronizada con la cuenta guardada

**Decisión.** Buscar comprueba la presencia local de la cuenta al entrar o reanudar la pantalla, fuera del hilo principal. Mientras la comprobación está pendiente o no hay cuenta, cancela la consulta activa y no inicia otra. Al completar una comprobación válida vuelve a evaluar la consulta conservada una vez, respetando la pausa compartida. No inicia sesión automáticamente ni depende del bus de eventos de autenticación.

**Errores.** La interfaz distingue ausencia de cuenta, autorización 401, renovación rechazada, acceso 403, solicitud 400, servidor 5xx, conexión y decodificación. Utiliza mensajes fijos con una acción concreta, sin mostrar cuerpos de respuesta ni atribuir un 403 a una causa no demostrada. Inicio y Cuentas aclaran que una pausa no requiere volver a autenticarse.

**Límites y pruebas.** Las pruebas ejecutan el coordinador de acceso y el orquestador reales con una comprobación de cuenta controlada; la conexión con el ciclo de vida Android se revisa estáticamente, sin prueba de dispositivo. No cambia el comportamiento nativo ante un 401 ni la política de renovación o duración de las pausas.

### 2026-09-10 — 1.7.13: ajustes avanzados en una ventana independiente

**Decisión.** Los campos avanzados dejan de expandirse dentro del último elemento de la lista de Reproducción. Se abren en un diálogo de ventana completa, por encima de la navegación y del reproductor, con lista desplazable y espacios para las barras del sistema y el teclado. La flecha superior y Atrás cierran la ventana.

**Semántica.** Se conservan ambos campos, sus valores vacíos como selección predeterminada y el guardado automático tras 500 ms. Las credenciales no se copian a estado guardable ni a registros. No cambian la autenticación, la búsqueda ni el cliente nativo.

**Verificación.** Se incorpora una prueba Compose de acceso a ambos campos con texto grande, teclado y desplazamiento, además del cierre por flecha y Atrás. La compilación de esa prueba no equivale a ejecutarla en un dispositivo; queda pendiente la validación visual real.

## Problemas conocidos heredados

- **Doble padding inferior en la hoja del reproductor.** Ver la entrada 1.1.1 y 1.1.2. Mitigado por el dimensionado de la tapa, no corregido en su origen.

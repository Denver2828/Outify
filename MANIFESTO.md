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

## Problemas conocidos heredados

- **Doble padding inferior en la hoja del reproductor.** Ver la entrada 1.1.1 y 1.1.2. Mitigado por el dimensionado de la tapa, no corregido en su origen.

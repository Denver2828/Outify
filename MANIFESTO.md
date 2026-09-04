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

## Problemas conocidos heredados

- **Fila de acciones ausente en horizontal.** En `PlayerContent.kt` el layout apaisado recibe la fila con los botones de letras, cola y más, pero nunca la dibuja.
- **Fila de acciones recortada en vertical.** La columna del reproductor tiene altura fija y la tapa del álbum ocupa el ancho completo. En pantallas 16:9 o con escalado grande, la fila de acciones queda fuera del borde inferior y el botón de letras no se ve.

Ambos quedan fuera de las siete etapas iniciales hasta que se decida abordarlos.
